package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.QuietHours
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * When to speak about a pass, and when to come back.
 *
 * Passes are known in advance, so none of this is a poll cadence - it is a list
 * of moments. Getting it wrong is the difference between being told at 22:14
 * that something is overhead now and being told at 22:44 that it was.
 */
class PassPlannerTest {

    private val hour = 3_600_000L
    private val peak = 100L * hour

    private fun pass(
        peaksAt: Long = peak,
        catalogue: Int = 25544,
        sunlitAtPeak: Boolean = true,
        entersShadowAt: Long? = null,
        leavesShadowAt: Long? = null,
        elevation: Double = 60.0,
        rangeKm: Double = 450.0,
        channel: PassChannel = PassChannel.of(catalogue),
    ) = SatellitePass(
        catalogue = catalogue,
        name = "ISS (ZARYA)",
        channel = channel,
        risesAt = peaksAt - 4 * 60_000L,
        riseAzimuth = 225.0,
        peaksAt = peaksAt,
        peakElevation = elevation,
        peakAzimuth = 180.0,
        setsAt = peaksAt + 4 * 60_000L,
        setAzimuth = 45.0,
        rangeAtPeakKm = rangeKm,
        sunlitAtPeak = sunlitAtPeak,
        entersShadowAt = entersShadowAt,
        leavesShadowAt = leavesShadowAt,
    )

    private val settings = UserSettings(latitude = 55.95, longitude = -3.19)
    private val clear = CloudCover(total = 5, low = 0, mid = 0, high = 5)

    @Test
    fun `sends the station and everything else to their own channels`() {
        assertEquals(PassChannel.Iss, PassChannel.of(25544))
        assertEquals(PassChannel.Bright, PassChannel.of(20580))
    }

    // --- freshening -----------------------------------------------------

    @Test
    fun `fetches elements when it holds none`() {
        assertTrue(PassPlanner.shouldFreshen(ageMillis = null, millisToNextPass = null))
    }

    /**
     * Reboosts and debris avoidance move a pass by tens of seconds to minutes.
     * Having told somebody 22:14, the prediction is worth re-deriving from
     * fresh elements rather than from a two-hourly cron that landed badly.
     */
    @Test
    fun `refreshes elements in the last few hours before a pass`() {
        assertTrue(PassPlanner.shouldFreshen(ageMillis = 2 * hour, millisToNextPass = 2 * hour))
    }

    /** But not on every wake through those hours, which would be a fetch each. */
    @Test
    fun `leaves recently fetched elements alone`() {
        assertFalse(PassPlanner.shouldFreshen(ageMillis = 10 * 60_000L, millisToNextPass = hour))
    }

    /** Nothing coming means no reason to spend a radio wake. */
    @Test
    fun `does not fetch for a night with no passes`() {
        assertFalse(PassPlanner.shouldFreshen(ageMillis = 5 * hour, millisToNextPass = null))
    }

    @Test
    fun `replaces elements that have gone properly stale`() {
        assertTrue(PassPlanner.shouldFreshen(ageMillis = 30 * hour, millisToNextPass = null))
    }

    // --- what is worth seeing -------------------------------------------

    /** A satellite in shadow reflects nothing, however good the geometry. */
    @Test
    fun `leaves out a pass that never catches the sun`() {
        val dark = pass(sunlitAtPeak = false)

        assertTrue(PassPlanner.visible(listOf(dark)) { true }.isEmpty())
    }

    /** The pre-dawn case: dark on the way up, lit before it sets. */
    @Test
    fun `keeps a pass that lights up part-way across`() {
        val morning = pass(sunlitAtPeak = false, leavesShadowAt = peak + 60_000L)

        assertEquals(1, PassPlanner.visible(listOf(morning)) { true }.size)
    }

    /** Daylight is the other free gate, and it ends most passes. */
    @Test
    fun `leaves out a pass through a light sky`() {
        assertTrue(PassPlanner.visible(listOf(pass())) { false }.isEmpty())
    }

    @Test
    fun `takes the soonest pass still ahead`() {
        val passes = listOf(pass(peaksAt = peak + hour), pass(peaksAt = peak), pass(peaksAt = peak - hour))

        assertEquals(peak, PassPlanner.next(passes, now = peak - 60_000L)?.peaksAt)
    }

    // --- speaking --------------------------------------------------------

    @Test
    fun `announces a pass half an hour before it is highest`() {
        val decision = PassPlanner.decide(listOf(pass()), peak - 29 * 60_000L, settings, AlertState(), 0, clear)

        assertTrue(decision is PassPlanner.Decision.Announce)
    }

    @Test
    fun `holds its tongue until the pass is close`() {
        val decision = PassPlanner.decide(listOf(pass()), peak - 3 * hour, settings, AlertState(), 0, clear)

        assertTrue(decision is PassPlanner.Decision.TooSoon)
    }

    /** A pass is one event and gets one notification. */
    @Test
    fun `says nothing about a pass it has already announced`() {
        val told = AlertState(lastPassPeakAt = peak, lastPassCatalogue = 25544)

        val decision = PassPlanner.decide(listOf(pass()), peak - 10 * 60_000L, settings, told, 0, clear)

        assertTrue(decision is PassPlanner.Decision.AlreadyTold)
    }

    /**
     * Freshened elements move a pass by up to minutes. That is the same pass,
     * not a new one, and re-announcing it is exactly what the record is for.
     */
    @Test
    fun `recognises an already announced pass that has shifted`() {
        val told = AlertState(lastPassPeakAt = peak, lastPassCatalogue = 25544)
        val shifted = pass(peaksAt = peak + 90_000L)

        val decision = PassPlanner.decide(listOf(shifted), peak - 60_000L, settings, told, 0, clear)

        assertTrue(decision is PassPlanner.Decision.AlreadyTold)
    }

    /** A different object peaking at a similar time is a different event. */
    @Test
    fun `announces another satellite passing at a similar time`() {
        val told = AlertState(lastPassPeakAt = peak, lastPassCatalogue = 25544)
        val other = pass(peaksAt = peak + 60_000L, catalogue = 20580)

        val decision = PassPlanner.decide(listOf(other), peak - 60_000L, settings, told, 0, clear)

        assertTrue(decision is PassPlanner.Decision.Announce)
    }

    @Test
    fun `stays silent during quiet hours`() {
        val quiet = settings.copy(quietHours = QuietHours(23 * 60, 7 * 60))

        val decision = PassPlanner.decide(
            listOf(pass()), peak - 10 * 60_000L, quiet, AlertState(), localMinuteOfDay = 2 * 60, cloud = clear,
        )

        assertTrue(decision is PassPlanner.Decision.InQuietHours)
    }

    @Test
    fun `holds back under more cloud than the user will go out in`() {
        val overcast = CloudCover(total = 95, low = 90, mid = 10, high = 5)

        val decision = PassPlanner.decide(
            listOf(pass()), peak - 10 * 60_000L, settings, AlertState(), 0, overcast,
        )

        assertTrue(decision is PassPlanner.Decision.TooCloudy)
    }

    /**
     * An unreachable forecast notifies and says so. An app that goes quiet
     * whenever a third party is down is indistinguishable from a broken one.
     */
    @Test
    fun `announces anyway when the forecast could not be reached`() {
        val decision = PassPlanner.decide(
            listOf(pass()), peak - 10 * 60_000L, settings, AlertState(), 0, cloud = null,
        )

        assertTrue(decision is PassPlanner.Decision.Announce)
    }

    @Test
    fun `ignores cloud entirely for a user who turned the gate off`() {
        val overcast = CloudCover(total = 99, low = 99, mid = 0, high = 0)
        val ungated = settings.copy(cloudGateEnabled = false)

        val decision = PassPlanner.decide(
            listOf(pass()), peak - 10 * 60_000L, ungated, AlertState(), 0, overcast,
        )

        assertTrue(decision is PassPlanner.Decision.Announce)
    }

    // --- how often, and which one --------------------------------------

    /**
     * The reason any of this exists: a clear night over Edinburgh has upwards
     * of forty passes worth looking at, and an app that mentioned each of them
     * would be muted within the week.
     */
    @Test
    fun `says nothing more on a channel that has just spoken`() {
        val spoken = AlertState(lastIssAlertAt = peak - 60 * 60_000L)

        val decision = PassPlanner.decide(
            listOf(pass()), peak - 10 * 60_000L, settings, spoken, 0, clear,
        )

        assertTrue(decision is PassPlanner.Decision.RateLimited)
    }

    /** And says when it will speak again, rather than merely going quiet. */
    @Test
    fun `comes back exactly when the channel is free`() {
        val lastAlert = peak - 60 * 60_000L
        val spoken = AlertState(lastIssAlertAt = lastAlert)
        val now = peak - 10 * 60_000L

        val decision = PassPlanner.decide(listOf(pass()), now, settings, spoken, 0, clear)

        val interval = settings.issPassAlertIntervalMinutes * 60_000L
        assertEquals(
            lastAlert + interval,
            PassPlanner.nextWake(decision, now, emptyList(), settings, spoken),
        )
    }

    /**
     * The interval must not cost quality. Announcing whichever pass came first
     * and then falling silent through a far better one twenty minutes later
     * would be a worse app than one that said nothing, so the interval is the
     * window the best pass is chosen from.
     */
    @Test
    fun `picks the best pass in the window, not the first`() {
        val mediocre = pass(peaksAt = peak, elevation = 12.0, rangeKm = 1900.0)
        val brilliant = pass(peaksAt = peak + 20 * 60_000L, elevation = 82.0, rangeKm = 430.0)

        val chosen = PassPlanner.chooseNext(
            listOf(mediocre, brilliant), peak - 2 * hour, settings, AlertState(),
        )

        assertEquals(brilliant.peaksAt, chosen?.peaksAt)
    }

    /** But it does not hold out for something better beyond the window. */
    @Test
    fun `takes what is in the window over something better outside it`() {
        val soon = pass(peaksAt = peak, elevation = 30.0, rangeKm = 800.0)
        val laterAndBetter = pass(peaksAt = peak + 9 * hour, elevation = 85.0, rangeKm = 430.0)

        val chosen = PassPlanner.chooseNext(
            listOf(soon, laterAndBetter), peak - 2 * hour, settings, AlertState(),
        )

        assertEquals(soon.peaksAt, chosen?.peaksAt)
    }

    /**
     * The channels hold separate budgets, which is the whole point of their
     * being separate: somebody may want the station often and the rocket bodies
     * rarely.
     */
    @Test
    fun `one channel speaking does not silence the other`() {
        val issSpoken = AlertState(lastIssAlertAt = peak - 60 * 60_000L)
        val rocketBody = pass(catalogue = 20580)

        val chosen = PassPlanner.chooseNext(
            listOf(pass(), rocketBody), peak - 10 * 60_000L, settings, issSpoken,
        )

        assertEquals(20580, chosen?.catalogue)
    }

    /** A pass after the limit lifts is still worth planning for. */
    @Test
    fun `keeps a pass that falls due once the interval has run out`() {
        val spoken = AlertState(lastIssAlertAt = peak - 7 * hour)

        val chosen = PassPlanner.chooseNext(listOf(pass()), peak - hour, settings, spoken)

        assertEquals(peak, chosen?.peaksAt)
    }

    /** The user's own number, not a constant. */
    @Test
    fun `honours the interval the user chose`() {
        val impatient = settings.copy(issPassAlertIntervalMinutes = 60)
        val spoken = AlertState(lastIssAlertAt = peak - 90 * 60_000L)

        val decision = PassPlanner.decide(
            listOf(pass()), peak - 10 * 60_000L, impatient, spoken, 0, clear,
        )

        assertTrue(decision is PassPlanner.Decision.Announce)
    }

    // --- when to come back ------------------------------------------------

    @Test
    fun `comes back when the alert is due`() {
        val now = peak - 2 * hour

        val wake = PassPlanner.nextWake(PassPlanner.Decision.TooSoon(pass()), now, emptyList(), settings, AlertState())

        assertEquals(peak - PassPlanner.LEAD_MILLIS, wake)
    }

    /** Far enough out, the elements want freshening before the alert is due. */
    @Test
    fun `comes back to freshen elements before a distant pass`() {
        val subject = pass()
        val now = peak - 8 * hour

        val wake = PassPlanner.nextWake(PassPlanner.Decision.TooSoon(subject), now, emptyList(), settings, AlertState())

        assertEquals(subject.risesAt - PassPlanner.FRESHEN_BEFORE_MILLIS, wake)
    }

    /** Once a pass is dealt with, nothing about it changes: wait it out. */
    @Test
    fun `waits until a pass is over before considering the next`() {
        val subject = pass()

        val wake = PassPlanner.nextWake(PassPlanner.Decision.Announce(subject), peak - 60_000L, emptyList(), settings, AlertState())

        assertTrue(wake > subject.setsAt)
    }

    @Test
    fun `looks again in a few hours when there is nothing coming`() {
        val now = peak

        val wake = PassPlanner.nextWake(PassPlanner.Decision.Nothing, now, emptyList(), settings, AlertState())

        assertEquals(now + PassPlanner.DORMANT_INTERVAL_MILLIS, wake)
    }

    /**
     * The failure this was written for, observed on real hardware overnight on
     * 2026-08-25. A rocket body was announced at 03:00:53 for a peak at
     * 03:28:23, and the planner armed its next wake for that pass's *set* at
     * about 03:34 - sleeping straight through 03:18:24, which was when the
     * station's own pass on the other channel fell due. The station pass was
     * never announced.
     *
     * "The pass has been dealt with, so wait until it is over" is sound about
     * the pass that was announced, and says nothing about the other channel.
     * Channels have separate budgets precisely so one does not crowd out the
     * other, and sleeping past another channel's alert is exactly that.
     */
    @Test
    fun `does not sleep through another channel's alert after announcing`() {
        val announced = pass(peaksAt = peak, catalogue = 20580)
        // The station peaks later but its alert falls due first, because the
        // announced pass is not over until after that moment.
        val station = pass(peaksAt = peak + 31 * 60_000L, catalogue = 25544)
        val alertDue = station.peaksAt - PassPlanner.LEAD_MILLIS
        // Due after this moment, but before the announced pass has finished.
        assertTrue(alertDue > peak - 60_000L)
        assertTrue(alertDue < announced.setsAt)

        val wake = PassPlanner.nextWake(
            PassPlanner.Decision.Announce(announced),
            peak - 60_000L,
            listOf(announced, station),
            settings,
            AlertState(),
        )

        assertEquals(alertDue, wake)
    }

    /** With nothing else pending it still waits out the pass it announced. */
    @Test
    fun `waits out the announced pass when no other channel is due`() {
        val announced = pass(peaksAt = peak, catalogue = 20580)

        val wake = PassPlanner.nextWake(
            PassPlanner.Decision.Announce(announced),
            peak - 60_000L,
            listOf(announced),
            settings,
            AlertState(),
        )

        assertEquals(announced.setsAt + 1, wake)
    }

    /** And it does not wake for a channel that has already spoken. */
    @Test
    fun `ignores a channel that is still rate limited`() {
        val announced = pass(peaksAt = peak, catalogue = 20580)
        val station = pass(peaksAt = peak + 31 * 60_000L, catalogue = 25544)

        val wake = PassPlanner.nextWake(
            PassPlanner.Decision.Announce(announced),
            peak - 60_000L,
            listOf(announced, station),
            settings,
            AlertState(lastIssAlertAt = peak),
        )

        assertEquals(announced.setsAt + 1, wake)
    }

    @Test
    fun `does not follow anything live unless asked`() {
        val settings = UserSettings()

        assertTrue(!PassPlanner.shouldFollow(PassChannel.Iss, settings))
        assertTrue(!PassPlanner.shouldFollow(PassChannel.Bright, settings))
    }

    @Test
    fun `follows the station live once asked`() {
        val settings = UserSettings(followStationPassesAutomatically = true)

        assertTrue(PassPlanner.shouldFollow(PassChannel.Iss, settings))
    }

    /**
     * The one setting cannot be made to cover the bright catalogue, whatever
     * else is switched on. The station goes over most nights; the bright
     * catalogue goes over constantly, and a foreground service per pass would
     * be one running for most of every clear night.
     */
    @Test
    fun `never follows a bright satellite live, whatever is switched on`() {
        val settings = UserSettings(
            followStationPassesAutomatically = true,
            passAlertsEnabled = true,
            alertsEnabled = true,
        )

        assertTrue(!PassPlanner.shouldFollow(PassChannel.Bright, settings))
    }
}
