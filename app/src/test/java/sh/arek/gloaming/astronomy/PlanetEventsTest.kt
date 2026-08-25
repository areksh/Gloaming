package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The events that make one night of the year the night to look at a planet.
 *
 * Dates cross-checked against the cadences these follow: Saturn's oppositions
 * drift about thirteen days later each year, Jupiter's about a month, and Mars
 * manages one roughly every twenty-six months.
 */
class PlanetEventsTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun events(from: String, days: Int, kind: EventKind) =
        SkyCalendar.events(latitude, longitude, at(from), days).filter { it.kind == kind }

    private fun day(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()

    // --- oppositions ---

    /**
     * Saturn is opposite the sun on 4 October 2026: up all night, closest for
     * the year, and the one night to point anything at it.
     */
    @Test
    fun `finds saturn at opposition in october 2026`() {
        val opposition = events("2026-08-25T00:00:00Z", 90, EventKind.Opposition)
            .firstOrNull { it.title.contains("Saturn") }

        assertNotNull(opposition)
        assertEquals("2026-10-04", day(opposition!!.at).toString())
        assertTrue(opposition.detail, opposition.detail.contains("all night"))
    }

    /** Jupiter and Mars both come to opposition in February 2027. */
    @Test
    fun `finds jupiter and mars at opposition in february 2027`() {
        val oppositions = events("2027-01-01T00:00:00Z", 90, EventKind.Opposition)

        val jupiter = oppositions.first { it.title.contains("Jupiter") }
        val mars = oppositions.first { it.title.contains("Mars") }

        assertEquals("2027-02-11", day(jupiter.at).toString())
        assertEquals("2027-02-19", day(mars.at).toString())
    }

    /** Each planet comes to opposition once per cycle, not repeatedly. */
    @Test
    fun `reports each opposition once`() {
        val oppositions = events("2026-08-25T00:00:00Z", 500, EventKind.Opposition)
        val keys = oppositions.map { it.title to it.at / 86_400_000 }

        assertTrue(oppositions.isNotEmpty())
        assertEquals(keys.size, keys.toSet().size)
    }

    /** Nothing beyond Saturn: it needs a telescope, so it is out of scope. */
    @Test
    fun `never mentions a planet that needs a telescope`() {
        val oppositions = events("2026-08-25T00:00:00Z", 800, EventKind.Opposition)

        assertTrue(oppositions.none { it.title.contains("Uranus") || it.title.contains("Neptune") })
    }

    // --- greatest brilliancy ---

    /** Venus at mag -4.9 is bright enough to cast a shadow from a dark site. */
    @Test
    fun `finds venus at its brightest`() {
        val brightest = events("2026-08-25T00:00:00Z", 120, EventKind.GreatestBrightness)

        assertTrue(brightest.isNotEmpty())
        assertTrue(brightest.all { it.title.contains("Venus") })
        assertEquals("2026-09-22", day(brightest.first().at).toString())
    }

    // --- the supermoon, folded into the full moon it is ---

    /**
     * A supermoon is a full moon, so it is that entry saying more rather than a
     * second entry beside it - the same rule that stops an eclipse and the full
     * moon it is being printed twice.
     */
    @Test
    fun `calls out the full moon that falls at perigee`() {
        val phases = SkyCalendar.events(latitude, longitude, at("2026-12-01T00:00:00Z"), 40)
            .filter { it.kind == EventKind.MoonPhase }

        val christmasEve = phases.first { day(it.at).toString() == "2026-12-24" }

        assertTrue(christmasEve.title, christmasEve.title.contains("Full moon"))
        assertTrue(christmasEve.detail, christmasEve.detail.contains("closest"))
        assertEquals(1, phases.count { day(it.at).toString() == "2026-12-24" })
    }

    /** An ordinary full moon says nothing about being close. */
    @Test
    fun `says nothing about distance for an ordinary full moon`() {
        val phases = SkyCalendar.events(latitude, longitude, at("2026-08-25T00:00:00Z"), 120)
            .filter { it.kind == EventKind.MoonPhase && it.title.contains("Full moon") }

        assertTrue(phases.any { !it.detail.contains("closest") })
    }

    // --- seasons ---

    /** The turn of the year, which at this latitude is the turn of the season. */
    @Test
    fun `marks the equinoxes and solstices`() {
        // Four a year, and 400 days reaches into a fifth.
        val seasons = events("2026-08-25T00:00:00Z", 400, EventKind.Season)

        assertEquals(5, seasons.size)
        assertEquals(
            listOf("2026-09-23", "2026-12-21", "2027-03-20", "2027-06-21", "2027-09-23"),
            seasons.map { day(it.at).toString() },
        )
    }

    /** Exactly four in a year, with none doubled by the year boundary. */
    @Test
    fun `marks four turnings in a year and no more`() {
        val seasons = events("2027-01-01T00:00:00Z", 365, EventKind.Season)

        assertEquals(seasons.map { day(it.at).toString() }.toString(), 4, seasons.size)
    }

    /**
     * An equinox is not a sight. It is listed because it marks the observing
     * year turning, and it never interrupts - the same treatment moon phases
     * get, and for the same reason.
     */
    @Test
    fun `never interrupts about a season`() {
        assertEquals(null, EventChannel.of(EventKind.Season))
    }

    /** Plain ASCII, like everything else. */
    @Test
    fun `writes plain ascii`() {
        for (event in SkyCalendar.events(latitude, longitude, at("2026-08-25T00:00:00Z"), 400)) {
            assertTrue(event.title, event.title.all { it.code in 32..126 })
            assertTrue(event.detail, event.detail.all { it.code in 32..126 })
        }
    }
}
