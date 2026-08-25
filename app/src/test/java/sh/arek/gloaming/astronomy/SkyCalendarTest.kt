package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar against dates whose answers are published.
 *
 * Every instant here was taken from the vendored ephemeris and cross-checked
 * against the observing literature, so a failure means the wiring moved rather
 * than the sky. Edinburgh throughout, because the whole point of the visibility
 * verdicts is that they depend on where the observer stands.
 */
class SkyCalendarTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /**
     * The published times these fixtures come from are given to the second, so
     * that is the resolution the claim is made at. Comparing the milliseconds
     * too would pin an ephemeris detail nobody asserted and nothing depends on.
     */
    private fun assertAtSecond(iso: String, actual: Long) =
        assertEquals(iso, at(iso) / 1000, actual / 1000)

    private fun events(from: String, days: Int) =
        SkyCalendar.events(latitude, longitude, at(from), days)

    private fun onlyKind(from: String, days: Int, kind: EventKind) =
        events(from, days).filter { it.kind == kind }

    // --- solar eclipses ---

    /**
     * Edinburgh's next solar eclipse: 2 August 2027, 27 per cent covered with
     * the sun well up. The one unambiguously worth telling someone about in the
     * next several years.
     */
    @Test
    fun `finds the partial solar eclipse of august 2027 with the sun well up`() {
        val eclipse = onlyKind("2026-08-25T00:00:00Z", 800, EventKind.SolarEclipse)
            .firstOrNull { it.at > at("2027-01-01T00:00:00Z") && it.at < at("2027-12-31T00:00:00Z") }

        assertNotNull("expected an eclipse in 2027", eclipse)
        assertAtSecond("2027-08-02T09:00:23Z", eclipse!!.at)
        assertEquals(Visibility.Good, eclipse.visibility)
        assertTrue(eclipse.detail, eclipse.detail.contains("27 per cent"))
    }

    /**
     * 26 January 2028 is the awkward case, and the one worth getting right: the
     * sun is 5.6 degrees up at first contact and has set by maximum. Calling
     * that invisible would be as wrong as calling it good - the first hour is
     * genuinely worth going out for, and the best part is not.
     */
    @Test
    fun `warns when the sun sets part-way through an eclipse`() {
        val eclipse = onlyKind("2027-09-01T00:00:00Z", 200, EventKind.SolarEclipse).single()

        assertAtSecond("2028-01-26T16:43:11Z", eclipse.at)
        val reason = (eclipse.visibility as Visibility.Marginal).reason
        assertTrue(reason, reason.contains("sets"))
    }

    // --- lunar eclipses ---

    /**
     * The near-total lunar eclipse of 28 August 2026: 97 per cent of the disc in
     * the umbra, but the moon only 7 degrees up in a brightening sky. Worth
     * knowing about and worth being warned about, which is what marginal is for.
     */
    @Test
    fun `finds the partial lunar eclipse of august 2026 and calls it marginal`() {
        val eclipse = onlyKind("2026-08-25T00:00:00Z", 30, EventKind.LunarEclipse).single()

        assertAtSecond("2026-08-28T04:12:49Z", eclipse.at)
        assertTrue(eclipse.visibility.toString(), eclipse.visibility is Visibility.Marginal)
        assertTrue(eclipse.detail, eclipse.detail.contains("97 per cent"))
    }

    /**
     * Penumbral eclipses are not dim, they are invisible: the moon passes
     * through the Earth's outer shadow and no part of it enters the umbra. Three
     * of the next six lunar eclipses are penumbral, and listing them would train
     * somebody to ignore the channel.
     */
    @Test
    fun `omits penumbral lunar eclipses entirely`() {
        val eclipses = onlyKind("2026-12-01T00:00:00Z", 400, EventKind.LunarEclipse)

        assertTrue(
            eclipses.map { Instant.ofEpochMilli(it.at).toString() }.toString(),
            eclipses.none { it.at == at("2027-02-20T23:12:44Z") },
        )
        assertTrue(eclipses.none { it.at == at("2027-07-18T16:02:54Z") })
    }

    /**
     * 6 July 2028 is a real partial lunar eclipse that Edinburgh cannot see at
     * all: the moon is below the horizon at every umbral contact. This is the
     * verdict that stops the calendar promising something the ground is in the
     * way of.
     */
    @Test
    fun `marks a lunar eclipse below the horizon as not visible from here`() {
        val eclipse = onlyKind("2028-06-01T00:00:00Z", 60, EventKind.LunarEclipse).single()

        assertAtSecond("2028-07-06T18:19:38Z", eclipse.at)
        val reason = (eclipse.visibility as Visibility.NotFromHere).reason
        assertTrue(reason, reason.contains("below the horizon"))
    }

    // --- elongations ---

    /**
     * Mercury is the planet people never see, and greatest elongation is the
     * night to try. The instant itself is in daylight, so the verdict has to
     * come from the twilight either side of it rather than from the instant.
     */
    @Test
    fun `finds mercury at greatest evening elongation in october 2026`() {
        val elongation = onlyKind("2026-08-25T00:00:00Z", 90, EventKind.Elongation)
            .firstOrNull { it.title.contains("Mercury") }

        assertNotNull("expected a Mercury elongation", elongation)
        assertAtSecond("2026-10-12T10:06:17Z", elongation!!.at)
        assertTrue(elongation.detail, elongation.detail.contains("evening"))
        assertTrue(elongation.detail, elongation.detail.contains("25 degrees"))
    }

    // --- moon phases ---

    /** New moon is the dark-sky marker everything else is planned around. */
    @Test
    fun `lists moon phases and names the new moon`() {
        val phases = onlyKind("2026-08-25T00:00:00Z", 30, EventKind.MoonPhase)

        assertTrue(phases.size.toString(), phases.size >= 3)
        assertTrue(phases.any { it.title.contains("New moon") })
        assertTrue(phases.any { it.title.contains("Full moon") })
    }

    // --- the list as a whole ---

    /** A calendar out of order is not a calendar. */
    @Test
    fun `returns events in time order and inside the horizon`() {
        val from = at("2026-08-25T00:00:00Z")
        val horizon = from + 200L * 24 * 60 * 60 * 1000
        val all = SkyCalendar.events(latitude, longitude, from, 200)

        assertEquals(all.map { it.at }.sorted(), all.map { it.at })
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.at in from..horizon })
    }

    /** British English, no decorative characters, the same rule as everywhere else. */
    @Test
    fun `writes plain ascii`() {
        for (event in SkyCalendar.events(latitude, longitude, at("2026-08-25T00:00:00Z"), 400)) {
            assertTrue(event.title, event.title.all { it.code in 32..126 })
            assertTrue(event.detail, event.detail.all { it.code in 32..126 })
        }
    }

    /**
     * The screen computes this on demand, so it has to be quick enough not to
     * need a spinner. Measured rather than assumed - the eclipse searches are
     * iterative and there is no other signal that one has gone quadratic.
     */
    @Test
    fun `computes a year of events quickly`() {
        val started = System.nanoTime()
        SkyCalendar.events(latitude, longitude, at("2026-08-25T00:00:00Z"), 365)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue("a year took ${millis}ms", millis < 4_000)
    }

    // --- the shared darkness helper ---

    /**
     * Elongations, conjunctions and shower peaks all ask the same question: how
     * high does this thing get on the night in question. The instant of the
     * event is usually the wrong moment to ask, because it is often daylight.
     */
    @Test
    fun `finds how high a planet gets in the darkness nearest an event`() {
        val best = SkyCalendar.bestInDarkness(
            body = SkyBody.Jupiter,
            latitude = latitude,
            longitude = longitude,
            aroundMillis = at("2026-11-03T00:00:00Z"),
        )

        assertNotNull(best)
        assertTrue(best!!.altitude.toString(), best.altitude > 0.0)
        assertTrue(best.azimuth.toString(), best.azimuth in 0.0..360.0)
    }

    /** In the polar day there is no darkness to find, and that is an answer. */
    @Test
    fun `finds nothing in the darkness at midsummer inside the arctic circle`() {
        assertNull(
            SkyCalendar.bestInDarkness(
                body = SkyBody.Jupiter,
                latitude = 78.2,
                longitude = 15.6,
                aroundMillis = at("2027-06-21T12:00:00Z"),
            ),
        )
    }
}
