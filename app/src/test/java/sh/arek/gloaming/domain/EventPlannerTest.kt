package sh.arek.gloaming.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.QuietHours
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.UserSettings

/**
 * When to speak about something in the calendar.
 *
 * Everything here is pure: the same events, settings and state give the same
 * answer with no clock, no network and no Android.
 */
class EventPlannerTest {

    private val settings = UserSettings(latitude = 55.95, longitude = -3.19)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun eclipse(
        from: String = "2027-08-02T08:11:11Z",
        at: String = "2027-08-02T09:00:23Z",
        until: String = "2027-08-02T09:51:31Z",
        visibility: Visibility = Visibility.Good,
    ) = SkyEvent(
        kind = EventKind.SolarEclipse,
        at = at(at),
        from = at(from),
        until = at(until),
        title = "Partial eclipse of the sun",
        detail = "27 per cent of the sun covered at maximum.",
        visibility = visibility,
    )

    private fun conjunction(
        at: String = "2026-11-16T01:50:43Z",
        visibility: Visibility = Visibility.Good,
    ) = SkyEvent(
        kind = EventKind.Conjunction,
        at = at(at),
        from = at(at),
        until = at(at),
        title = "Mars and Jupiter at their closest",
        detail = "1.2 degrees apart.",
        visibility = visibility,
    )

    private fun moonPhase(at: String = "2026-11-09T07:02:00Z") = SkyEvent(
        kind = EventKind.MoonPhase,
        at = at(at),
        from = at(at),
        until = at(at),
        title = "New moon",
        detail = "The darkest skies of the month.",
        visibility = Visibility.Good,
    )

    private fun decide(
        events: List<SkyEvent>,
        now: String,
        state: AlertState = AlertState(),
        user: UserSettings = settings,
        minuteOfDay: Int = 12 * 60,
        cloud: CloudCover? = null,
    ) = EventPlanner.decide(events, at(now), user, state, minuteOfDay, cloud)

    // --- what may interrupt at all ---

    /** Moon phases have no channel, so they can never reach a notification. */
    @Test
    fun `never announces a moon phase`() {
        val decision = decide(listOf(moonPhase()), "2026-11-09T06:00:00Z")

        assertEquals(EventPlanner.Decision.Nothing, decision)
    }

    /** Only what can actually be seen from here interrupts. */
    @Test
    fun `never announces an event that cannot be seen from here`() {
        val hidden = conjunction(visibility = Visibility.NotFromHere("below the horizon"))

        assertEquals(
            EventPlanner.Decision.Nothing,
            decide(listOf(hidden), "2026-11-16T00:50:00Z"),
        )
    }

    /** A marginal event is worth a line in the calendar and not worth waking for. */
    @Test
    fun `never announces a marginal event`() {
        val low = conjunction(visibility = Visibility.Marginal("only 7 degrees up"))

        assertEquals(
            EventPlanner.Decision.Nothing,
            decide(listOf(low), "2026-11-16T00:50:00Z"),
        )
    }

    /** With calendar alerts off, nothing in the calendar speaks. */
    @Test
    fun `says nothing when calendar alerts are switched off`() {
        val decision = decide(
            listOf(eclipse()),
            "2027-08-02T07:11:11Z",
            user = settings.copy(eventAlertsEnabled = false),
        )

        assertEquals(EventPlanner.Decision.Nothing, decision)
    }

    // --- the two leads ---

    /** An eclipse is worth planning around, so it gets days of notice. */
    @Test
    fun `gives an eclipse a heads up days ahead`() {
        val decision = decide(listOf(eclipse()), "2027-07-31T08:11:11Z")

        assertTrue(decision.toString(), decision is EventPlanner.Decision.Announce)
        assertEquals(
            EventPlanner.Phase.HeadsUp,
            (decision as EventPlanner.Decision.Announce).phase,
        )
    }

    /** And then again on the day, close enough to act on. */
    @Test
    fun `speaks again on the day`() {
        val told = AlertState(
            announcedEvents = setOf(EventPlanner.key(eclipse(), EventPlanner.Phase.HeadsUp)),
        )
        val decision = decide(listOf(eclipse()), "2027-08-02T07:30:00Z", state = told)

        assertEquals(
            EventPlanner.Phase.Tonight,
            (decision as EventPlanner.Decision.Announce).phase,
        )
    }

    /**
     * A conjunction is a look out of the window, not a trip. Two alerts for one
     * would be the surest way to have the channel muted.
     */
    @Test
    fun `gives a conjunction one alert and not two`() {
        val early = decide(listOf(conjunction()), "2026-11-14T01:50:00Z")

        assertEquals(EventPlanner.Decision.Nothing, early)

        val onTheNight = decide(listOf(conjunction()), "2026-11-16T01:00:00Z")
        assertEquals(
            EventPlanner.Phase.Tonight,
            (onTheNight as EventPlanner.Decision.Announce).phase,
        )
    }

    /**
     * Installed the day before an eclipse, a heads-up and an on-the-day alert
     * would arrive within hours of each other. Only the second is any use then.
     */
    @Test
    fun `skips the heads up when there is no longer time for it to be one`() {
        val decision = decide(listOf(eclipse()), "2027-08-02T06:00:00Z")

        assertEquals(EventPlanner.Decision.Nothing, decision)
    }

    /** Nothing is said twice. */
    @Test
    fun `does not repeat what it has already announced`() {
        val told = AlertState(
            announcedEvents = setOf(EventPlanner.key(eclipse(), EventPlanner.Phase.Tonight)),
        )
        val decision = decide(listOf(eclipse()), "2027-08-02T07:30:00Z", state = told)

        assertTrue(decision.toString(), decision is EventPlanner.Decision.AlreadyTold)
    }

    /** Once it is over there is nothing to say. */
    @Test
    fun `says nothing about an event that has finished`() {
        assertEquals(
            EventPlanner.Decision.Nothing,
            decide(listOf(eclipse()), "2027-08-02T10:30:00Z"),
        )
    }

    // --- the gates ---

    /**
     * The darkness gate is deliberately not applied here. A solar eclipse
     * happens in daylight by definition, and gating on the sun being down would
     * suppress every one of them. Whether a thing can be seen is already
     * settled by its own visibility verdict, computed per kind.
     */
    @Test
    fun `announces a solar eclipse in broad daylight`() {
        val decision = decide(listOf(eclipse()), "2027-08-02T07:30:00Z", minuteOfDay = 8 * 60)

        assertTrue(decision.toString(), decision is EventPlanner.Decision.Announce)
    }

    /** Cloud stops an on-the-day alert, because cloud stops the event being seen. */
    @Test
    fun `holds back an alert on the day when it is too cloudy`() {
        val decision = decide(
            listOf(eclipse()),
            "2027-08-02T07:30:00Z",
            cloud = CloudCover(total = 95, low = 90, mid = 40, high = 20),
        )

        assertTrue(decision.toString(), decision is EventPlanner.Decision.TooCloudy)
    }

    /**
     * Cloud does not stop a heads-up. A forecast two days out says nothing
     * useful about the sky, and the point of the notice is to let somebody
     * arrange to be somewhere.
     */
    @Test
    fun `gives the heads up regardless of tonight's cloud`() {
        val decision = decide(
            listOf(eclipse()),
            "2027-07-31T08:11:11Z",
            cloud = CloudCover(total = 95, low = 90, mid = 40, high = 20),
        )

        assertTrue(decision.toString(), decision is EventPlanner.Decision.Announce)
    }

    /** Quiet hours are honoured, the same as everywhere else. */
    @Test
    fun `stays quiet during quiet hours`() {
        val decision = decide(
            listOf(eclipse()),
            "2027-08-02T07:30:00Z",
            user = settings.copy(quietHours = QuietHours(23 * 60, 8 * 60)),
            minuteOfDay = 2 * 60,
        )

        assertTrue(decision.toString(), decision is EventPlanner.Decision.InQuietHours)
    }

    // --- coming back ---

    /** The next wake is the next thing due, not a fixed cadence. */
    @Test
    fun `comes back when the next alert is due`() {
        val now = at("2027-07-31T04:00:00Z")
        val wake = EventPlanner.nextWake(listOf(eclipse()), now, settings, AlertState())

        assertEquals(at("2027-07-31T08:11:11Z"), wake)
    }

    /**
     * Capped even so. A notice a month away is no reason to sleep for a month:
     * the calendar is recomputed when the location changes, and a wake costs
     * almost nothing next to being silent through one.
     */
    @Test
    fun `does not sleep past the cap waiting for something far off`() {
        val now = at("2027-07-01T00:00:00Z")
        val wake = EventPlanner.nextWake(listOf(eclipse()), now, settings, AlertState())

        assertEquals(now + EventPlanner.DORMANT_INTERVAL_MILLIS, wake)
    }

    /** With nothing ahead it still comes back, rather than going silent forever. */
    @Test
    fun `comes back eventually with an empty calendar`() {
        val now = at("2027-01-01T00:00:00Z")
        val wake = EventPlanner.nextWake(emptyList(), now, settings, AlertState())

        assertEquals(now + EventPlanner.DORMANT_INTERVAL_MILLIS, wake)
    }

    // --- keeping the record small ---

    /** The announced-keys record cannot grow without bound. */
    @Test
    fun `forgets what is long over`() {
        val old = EventPlanner.key(eclipse(), EventPlanner.Phase.Tonight)
        val kept = EventPlanner.prune(setOf(old), at("2027-09-30T00:00:00Z"))

        assertTrue(kept.toString(), kept.isEmpty())
    }

    /** But not what has only just happened, or the alert would repeat. */
    @Test
    fun `remembers what has just been announced`() {
        val recent = EventPlanner.key(eclipse(), EventPlanner.Phase.Tonight)
        val kept = EventPlanner.prune(setOf(recent), at("2027-08-02T12:00:00Z"))

        assertEquals(setOf(recent), kept)
    }

    /** A key that cannot be read is dropped rather than kept forever. */
    @Test
    fun `drops a key it cannot parse`() {
        assertTrue(EventPlanner.prune(setOf("nonsense"), at("2027-08-02T12:00:00Z")).isEmpty())
    }

    /** The key names the event and the phase, so the two alerts are distinct. */
    @Test
    fun `keys the two phases apart`() {
        assertTrue(
            EventPlanner.key(eclipse(), EventPlanner.Phase.HeadsUp) !=
                EventPlanner.key(eclipse(), EventPlanner.Phase.Tonight),
        )
    }

    /** Nothing due means nothing due, not the first thing in the list. */
    @Test
    fun `finds nothing due when nothing is`() {
        assertNull(EventPlanner.due(listOf(eclipse()), at("2027-01-01T00:00:00Z"), settings, AlertState()))
    }

    // --- starring ---

    /** On by default, so the calendar is useful before anybody has curated it. */
    @Test
    fun `announces everything visible by default`() {
        val decision = decide(listOf(conjunction()), "2026-11-16T01:00:00Z")

        assertTrue(decision.toString(), decision is EventPlanner.Decision.Announce)
    }

    /** Turned off, an unstarred event is a line in the calendar and nothing more. */
    @Test
    fun `says nothing about an unstarred event when only starred ones may interrupt`() {
        val decision = decide(
            listOf(conjunction()),
            "2026-11-16T01:00:00Z",
            user = settings.copy(notifyAboutEveryEvent = false),
        )

        assertEquals(EventPlanner.Decision.Nothing, decision)
    }

    /** And a starred one still speaks. */
    @Test
    fun `announces a starred event when only starred ones may interrupt`() {
        val event = conjunction()
        val decision = decide(
            listOf(event),
            "2026-11-16T01:00:00Z",
            user = settings.copy(
                notifyAboutEveryEvent = false,
                starredEvents = setOf(EventPlanner.starKey(event)),
            ),
        )

        assertTrue(decision.toString(), decision is EventPlanner.Decision.Announce)
    }

    /** A star cannot rescue something that cannot be seen from here. */
    @Test
    fun `a star does not override the visibility verdict`() {
        val hidden = conjunction(visibility = Visibility.NotFromHere("below the horizon"))
        val decision = decide(
            listOf(hidden),
            "2026-11-16T01:00:00Z",
            user = settings.copy(starredEvents = setOf(EventPlanner.starKey(hidden))),
        )

        assertEquals(EventPlanner.Decision.Nothing, decision)
    }

    /**
     * Noctilucent conditions never reach the calendar, so there is nothing to
     * put a star on. Making them obey the filter would silence them for good
     * the moment somebody turned it on, for a reason they could never see.
     */
    @Test
    fun `still announces what could never have been starred`() {
        val nlc = SkyEvent(
            kind = EventKind.Noctilucent,
            at = at("2027-06-21T21:30:00Z"),
            from = at("2027-06-21T21:30:00Z"),
            until = at("2027-06-21T23:30:00Z"),
            title = "Good conditions for noctilucent clouds",
            detail = "Look low in the north.",
            visibility = Visibility.Good,
        )

        val decision = decide(
            listOf(nlc),
            "2027-06-21T21:00:00Z",
            user = settings.copy(notifyAboutEveryEvent = false),
        )

        assertTrue(decision.toString(), decision is EventPlanner.Decision.Announce)
    }

    /** The star key names the event and not the phase, or one star would be two. */
    @Test
    fun `keys a star to the event rather than to a notice about it`() {
        val event = eclipse()

        assertTrue(
            EventPlanner.key(event, EventPlanner.Phase.HeadsUp)
                .startsWith(EventPlanner.starKey(event)),
        )
        assertTrue(
            EventPlanner.key(event, EventPlanner.Phase.Tonight)
                .startsWith(EventPlanner.starKey(event)),
        )
    }

    /** A star on something long past is a preference about nothing. */
    @Test
    fun `forgets stars on events long gone`() {
        val old = EventPlanner.starKey(eclipse())

        assertTrue(EventPlanner.pruneStars(setOf(old), at("2027-09-30T00:00:00Z")).isEmpty())
        assertEquals(
            setOf(old),
            EventPlanner.pruneStars(setOf(old), at("2027-08-02T12:00:00Z")),
        )
    }
}
