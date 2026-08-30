package sh.arek.gloaming.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings

/**
 * What there is to see between now and the end of tonight.
 *
 * The list the calendar structurally cannot hold: a year of passes would run to
 * tens of thousands of entries, but "what is crossing while I am standing here"
 * is the question the app exists to answer.
 */
class TonightTest {

    private val settings = UserSettings(latitude = 55.95, longitude = -3.19)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun event(
        kind: EventKind = EventKind.MeteorShower,
        from: String,
        until: String,
        visibility: Visibility = Visibility.Good,
    ) = SkyEvent(
        kind = kind,
        at = at(from),
        from = at(from),
        until = at(until),
        title = "Geminids at their best",
        detail = "Perhaps 130 an hour.",
        visibility = visibility,
    )

    private fun tonight(
        now: String,
        calendar: List<SkyEvent> = emptyList(),
        user: UserSettings = settings,
        comets: CometsDocument? = null,
        cloud: CloudCover? = null,
        hourly: List<HourlyCover> = emptyList(),
    ) = Tonight.of(
        satellites = null,
        comets = comets,
        calendar = calendar,
        settings = user,
        nowMillis = at(now),
        cloud = cloud,
        hourly = hourly,
    )

    /** One entry an hour from [from], reading the percentages in order. */
    private fun hours(from: String, vararg total: Int): List<HourlyCover> =
        total.mapIndexed { index, cover ->
            HourlyCover(at(from) + index * 3_600_000L, cover)
        }

    /** Without a location there is nothing that could be about tonight. */
    @Test
    fun `offers nothing without a location`() {
        val list = tonight("2026-12-14T20:00:00Z", user = UserSettings())

        assertTrue(list.isEmpty)
        assertEquals(null, list.darkFrom)
    }

    /** A December night at Edinburgh is long, and the window says so. */
    @Test
    fun `finds tonight's darkness`() {
        val list = tonight("2026-12-14T18:00:00Z")

        assertNotNull(list.darkFrom)
        assertNotNull(list.darkUntil)
        assertTrue((list.darkUntil!! - list.darkFrom!!) > 10 * 3_600_000L)
    }

    /** An event running through tonight belongs to tonight. */
    @Test
    fun `includes an event whose window overlaps tonight`() {
        val shower = event(from = "2026-12-14T22:00:00Z", until = "2026-12-15T06:00:00Z")

        val list = tonight("2026-12-14T20:00:00Z", listOf(shower))

        assertEquals(listOf(shower), list.events)
    }

    /**
     * Compared as a span, not as a moment. A shower peak runs the whole night;
     * matching on the instant alone would drop the one somebody is outside for.
     */
    @Test
    fun `includes an event already under way`() {
        val shower = event(from = "2026-12-14T20:00:00Z", until = "2026-12-15T05:00:00Z")

        val list = tonight("2026-12-14T23:30:00Z", listOf(shower))

        assertEquals(listOf(shower), list.events)
    }

    /** Next week is not tonight. */
    @Test
    fun `leaves out an event on another night`() {
        val later = event(from = "2026-12-20T22:00:00Z", until = "2026-12-21T04:00:00Z")

        assertTrue(tonight("2026-12-14T20:00:00Z", listOf(later)).events.isEmpty())
    }

    /** What cannot be seen from here is not on tonight's list. */
    @Test
    fun `leaves out what cannot be seen from here`() {
        val hidden = event(
            from = "2026-12-14T22:00:00Z",
            until = "2026-12-15T06:00:00Z",
            visibility = Visibility.NotFromHere("The radiant never rises here"),
        )

        assertTrue(tonight("2026-12-14T20:00:00Z", listOf(hidden)).events.isEmpty())
    }

    /**
     * Marginal stays. Somebody reading a list of tonight has already decided to
     * go outside, and "the moon is low at maximum" is what they plan around
     * rather than a reason to be told nothing at all.
     */
    @Test
    fun `keeps a marginal event on tonight's list`() {
        val marginal = event(
            from = "2026-12-14T22:00:00Z",
            until = "2026-12-15T06:00:00Z",
            visibility = Visibility.Marginal("The moon is 80 per cent lit"),
        )

        assertEquals(listOf(marginal), tonight("2026-12-14T20:00:00Z", listOf(marginal)).events)
    }

    /** No elements cached means no passes, not a crash. */
    @Test
    fun `copes with no satellite elements`() {
        assertTrue(tonight("2026-12-14T20:00:00Z").passes.isEmpty())
    }

    /** In season it prompts, and the prompt is tonight's rather than a date. */
    @Test
    fun `carries the noctilucent prompt in season`() {
        val list = Tonight.of(
            satellites = null,
            comets = null,
            calendar = emptyList(),
            settings = settings,
            nowMillis = at("2027-06-21T18:00:00Z"),
        )

        assertNotNull(list.noctilucent)
        assertEquals(EventKind.Noctilucent, list.noctilucent!!.kind)
    }

    /** Out of season there is no prompt. */
    @Test
    fun `carries no noctilucent prompt out of season`() {
        assertEquals(null, tonight("2026-12-14T20:00:00Z").noctilucent)
    }

    /**
     * Midsummer inside the arctic circle has no darkness to describe, and the
     * list says so by having no window rather than by failing.
     */
    @Test
    fun `has no window where there is no night`() {
        val list = Tonight.of(
            satellites = null,
            comets = null,
            calendar = emptyList(),
            settings = UserSettings(latitude = 78.2, longitude = 15.6),
            nowMillis = at("2027-06-21T12:00:00Z"),
        )

        assertEquals(null, list.darkFrom)
    }

    // --- the other two gates on a clear night ---

    /**
     * Proper astronomical darkness, which is a different night from the civil
     * one the app has always shown. December at Edinburgh has plenty of it.
     */
    @Test
    fun `finds the darker window as well as the civil one`() {
        val list = tonight("2026-12-14T18:00:00Z")

        assertNotNull(list.darkestFrom)
        assertNotNull(list.darkestUntil)
        assertTrue(list.darkestFrom!! > list.darkFrom!!)
        assertTrue(list.darkestUntil!! < list.darkUntil!!)
    }

    /**
     * The one that matters. Edinburgh in late June is dark enough to see an
     * aurora and never dark enough for anything faint, and until now the app
     * could not tell the two apart - which is why a summer meteor shower
     * disappoints.
     */
    @Test
    fun `finds no darker window in a northern midsummer`() {
        val list = tonight("2027-06-21T22:00:00Z")

        assertNotNull(list.darkFrom)
        assertEquals(null, list.darkestFrom)
    }

    @Test
    fun `weighs the moon against tonight's darkness`() {
        val list = tonight("2026-12-14T18:00:00Z")

        val moon = assertNotNull(list.moon).let { list.moon!! }
        assertTrue(moon.illumination in 0.0..1.0)
        assertTrue(moon.upFraction in 0.0..1.0)
        assertEquals(moon.illumination * moon.upFraction, moon.interference, 1e-9)
    }

    /** No night to weigh it against, so there is nothing to say about it. */
    @Test
    fun `says nothing about the moon where there is no night`() {
        val list = Tonight.of(
            satellites = null,
            comets = null,
            calendar = emptyList(),
            settings = UserSettings(latitude = 78.2, longitude = 15.6),
            nowMillis = at("2027-06-21T12:00:00Z"),
        )

        assertEquals(null, list.moon)
    }

    /** A moon that crosses the horizon during the night is timed to the minute. */
    @Test
    fun `times the moon's crossings inside the night`() {
        val list = tonight("2026-12-14T18:00:00Z")

        val moon = list.moon!!
        listOfNotNull(moon.risesAt, moon.setsAt).forEach {
            assertTrue(it in list.darkFrom!!..list.darkUntil!!)
        }
    }

    // --- the cloud, and the gap in it ---

    @Test
    fun `carries the cloud reading it was handed`() {
        val list = tonight("2026-12-14T18:00:00Z", cloud = CloudCover(80, 60, 20, 10))

        assertEquals(80, list.cloud!!.total)
    }

    /**
     * The gap is looked for inside tonight's darkness and against the user's
     * own tolerance, not against a constant: somebody who will go out under
     * broken cloud and somebody who wants a clear sky are asking different
     * questions of the same forecast.
     */
    @Test
    fun `finds the gap in the cloud inside tonight's darkness`() {
        val list = tonight(
            "2026-12-14T18:00:00Z",
            hourly = hours("2026-12-14T17:00:00Z", 100, 100, 100, 100, 20, 20, 100),
        )

        val spell = assertNotNull(list.clearSpell).let { list.clearSpell!! }
        assertEquals(at("2026-12-14T21:00:00Z"), spell.from)
        assertEquals(at("2026-12-14T23:00:00Z"), spell.until)
    }

    /**
     * The tolerance the caller passed, not a constant. Two people looking at
     * the same forecast are asking different questions of it: somebody who will
     * go out under broken cloud and somebody who wants a clear sky.
     */
    @Test
    fun `finds the whole night clear when the tolerance allows anything`() {
        val list = tonight(
            "2026-12-14T18:00:00Z",
            user = settings.copy(cloudTolerance = 100),
            hourly = hours("2026-12-14T15:00:00Z", *IntArray(24) { 100 }),
        )

        val spell = assertNotNull(list.clearSpell).let { list.clearSpell!! }
        assertTrue(spell.toString(), spell.allNight)
    }

    /**
     * A forecast that stops before the night does must not claim the rest of
     * it. Two days of hours start at midnight of the day they are asked from,
     * so a December night at this latitude runs past the end of them - and
     * "clear for the rest of the night" would then be a promise about hours
     * nobody has a forecast for.
     */
    @Test
    fun `does not claim the whole night when the forecast runs out first`() {
        val list = tonight(
            "2026-12-14T18:00:00Z",
            user = settings.copy(cloudTolerance = 100),
            hourly = hours("2026-12-14T17:00:00Z", *IntArray(8) { 100 }),
        )

        val spell = assertNotNull(list.clearSpell).let { list.clearSpell!! }
        assertFalse(spell.toString(), spell.allNight)
        assertTrue(spell.toString(), spell.until < list.darkUntil!!)
    }

    @Test
    fun `finds no gap where the forecast has none`() {
        val list = tonight(
            "2026-12-14T18:00:00Z",
            hourly = hours("2026-12-14T17:00:00Z", 100, 100, 100, 100, 100),
        )

        assertEquals(null, list.clearSpell)
    }

    @Test
    fun `finds no gap without a forecast`() {
        assertEquals(null, tonight("2026-12-14T18:00:00Z").clearSpell)
    }

    // --- which few to lead with ---

    // --- the constellations ---

    /**
     * The season half of the feature. A January night from Edinburgh has the
     * winter patterns up, and Orion is the one anybody would name.
     */
    @Test
    fun `lists the constellations up tonight`() {
        val list = tonight("2027-01-15T22:00:00Z")

        assertTrue(list.constellations.isNotEmpty())
        assertTrue(
            list.constellations.map { it.constellation.name }.toString(),
            list.constellations.any { it.constellation.name == "Orion" },
        )
    }

    /**
     * The card names the pattern and marks the night it is at its best, so the
     * calendar's own entry is dropped rather than listed beside it - the same
     * rule that keeps a comet off the events list.
     */
    @Test
    fun `keeps the constellation calendar entry off tonight's list`() {
        val calendar = SkyCalendar.events(55.95, -3.19, at("2027-12-16T18:00:00Z"), 3)
        val list = tonight("2027-12-16T18:00:00Z", calendar = calendar)

        assertTrue(
            calendar.map { it.kind }.toString(),
            calendar.any { it.kind == EventKind.Constellation },
        )
        assertTrue(
            list.events.map { it.title }.toString(),
            list.events.none { it.kind == EventKind.Constellation },
        )
    }

    /** And says so on the card instead, on the one night it is true. */
    @Test
    fun `marks the constellation that is at its best tonight`() {
        val list = tonight("2027-12-16T22:00:00Z")

        assertTrue(
            list.constellations.map { it.constellation.name to it.isAtItsBest }.toString(),
            list.constellations.any { it.isAtItsBest },
        )
    }

    private fun pass(
        name: String,
        rises: String,
        elevation: Double,
        channel: sh.arek.gloaming.satellites.PassChannel =
            sh.arek.gloaming.satellites.PassChannel.Bright,
        catalogue: Int = 10000,
    ) = sh.arek.gloaming.satellites.SatellitePass(
        catalogue = catalogue,
        name = name,
        channel = channel,
        risesAt = at(rises),
        riseAzimuth = 200.0,
        peaksAt = at(rises) + 300_000L,
        peakElevation = elevation,
        peakAzimuth = 180.0,
        setsAt = at(rises) + 600_000L,
        setAzimuth = 100.0,
        rangeAtPeakKm = 500.0,
        sunlitAtPeak = true,
        entersShadowAt = null,
        leavesShadowAt = null,
    )

    /**
     * The first few by time answer "what happens next", which is the wrong
     * question: at dusk that is whatever rocket body is up, and the pass worth
     * going out for may be four hours later.
     */
    @Test
    fun `leads with the highest passes rather than the earliest`() {
        val low = pass("SL-16 R/B", "2026-12-14T17:00:00Z", 12.0)
        val high = pass("COSMOS 2221", "2026-12-14T23:00:00Z", 82.0)

        val best = Tonight.best(listOf(low, high), limit = 1)

        assertEquals(listOf(high), best)
    }

    /** The station comes first whatever its elevation: it is the one people mean. */
    @Test
    fun `always finds room for the space station`() {
        val station = pass(
            "ISS (ZARYA)", "2026-12-14T18:00:00Z", 18.0,
            channel = sh.arek.gloaming.satellites.PassChannel.Iss, catalogue = 25544,
        )
        val higher = (1..10).map {
            pass("R/B $it", "2026-12-14T19:00:00Z", 70.0 + it, catalogue = 20000 + it)
        }

        val best = Tonight.best(listOf(station) + higher)

        assertTrue(best.toString(), best.contains(station))
    }

    /** Once chosen they are listed in time order, because that is how an evening runs. */
    @Test
    fun `lists the chosen few in time order`() {
        val later = pass("A", "2026-12-14T23:00:00Z", 80.0)
        val earlier = pass("B", "2026-12-14T19:00:00Z", 60.0)

        val best = Tonight.best(listOf(later, earlier))

        assertEquals(listOf(earlier, later), best)
    }

    /** A quiet night is not padded out to the limit. */
    @Test
    fun `returns only what there is on a quiet night`() {
        val one = pass("A", "2026-12-14T23:00:00Z", 40.0)

        assertEquals(listOf(one), Tonight.best(listOf(one)))
    }
}
