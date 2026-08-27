package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is up tonight, and where to look.
 *
 * The app could position all six naked-eye bodies topocentrically from the day
 * the conjunctions went in, and the calendar dates the one night a year each is
 * at opposition - neither of which answers the question somebody standing in a
 * garden asks. Pinned against published positions rather than against the
 * app's own output, since the engine is the thing being trusted here.
 */
class PlanetsTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun night(from: String, until: String) = at(from)..at(until)

    private val edinburgh = 55.95 to -3.19

    private fun tonight(from: String, until: String, place: Pair<Double, Double> = edinburgh) =
        Planets.tonight(place.first, place.second, night(from, until))

    /**
     * Jupiter on a January night from Edinburgh: high in the south and by some
     * way the brightest thing up. This is the case any planisphere agrees on,
     * and the one somebody would notice being wrong.
     */
    @Test
    fun `finds a bright planet high in the south on a winter night`() {
        val planets = tonight("2027-01-10T17:00:00Z", "2027-01-11T07:00:00Z")

        val jupiter = planets.single { it.body == SkyBody.Jupiter }
        assertTrue(jupiter.toString(), jupiter.peakAltitude > 40.0)
        assertEquals(Compass.South, Compass.of(jupiter.peakAzimuth))
        assertTrue(jupiter.toString(), jupiter.magnitude < -2.0)
    }

    /** Highest first: what somebody will actually be able to find. */
    @Test
    fun `lists the planets highest first`() {
        val planets = tonight("2027-01-10T17:00:00Z", "2027-01-11T07:00:00Z")

        assertEquals(
            planets.map { it.peakAltitude }.sortedDescending(),
            planets.map { it.peakAltitude },
        )
    }

    /** Below the horizon all night is not a planet up tonight. */
    @Test
    fun `leaves out a planet that never clears the horizon`() {
        val planets = tonight("2027-01-10T17:00:00Z", "2027-01-11T07:00:00Z")

        assertTrue(planets.toString(), planets.all { it.peakAltitude >= Planets.VISIBLE_DEGREES })
    }

    /**
     * Mercury's own bar, which is much higher than the rest. It is never far
     * from the sun, so most nights it is a few degrees up in a sky still too
     * bright to see it in - technically above the horizon, in practice not
     * there.
     */
    @Test
    fun `holds mercury to a higher bar than the rest`() {
        val planets = tonight("2027-01-10T17:00:00Z", "2027-01-11T07:00:00Z")

        planets.filter { it.body == SkyBody.Mercury }.forEach {
            assertTrue(it.toString(), it.peakAltitude >= Planets.MERCURY_DEGREES)
        }
        assertTrue(Planets.MERCURY_DEGREES > Planets.VISIBLE_DEGREES)
    }

    /**
     * A planet that comes up during the night is timed, and one that is
     * already up when the darkness starts is not given an invented rise.
     */
    @Test
    fun `times only the crossings that happen inside the night`() {
        val window = night("2027-01-10T17:00:00Z", "2027-01-11T07:00:00Z")
        val planets = Planets.tonight(edinburgh.first, edinburgh.second, window)

        planets.forEach { planet ->
            listOfNotNull(planet.risesAt, planet.setsAt).forEach {
                assertTrue(planet.toString(), it in window)
            }
        }
    }

    /** The peak is inside the window it was asked about, always. */
    @Test
    fun `puts every peak inside the night it was asked about`() {
        val window = night("2027-01-10T17:00:00Z", "2027-01-11T07:00:00Z")
        val planets = Planets.tonight(edinburgh.first, edinburgh.second, window)

        assertTrue(planets.isNotEmpty())
        planets.forEach { assertTrue(it.toString(), it.peakAt in window) }
    }

    /**
     * A planet that comes up part-way through the night is reported with the
     * moment it does, which is the whole difference between this and the
     * calendar - "Venus rises at 05:30" is a plan, "Venus is at greatest
     * western elongation" is not.
     */
    @Test
    fun `times a planet that rises part-way through the night`() {
        val window = night("2027-01-10T17:00:00Z", "2027-01-11T07:00:00Z")
        val planets = Planets.tonight(edinburgh.first, edinburgh.second, window)

        val venus = planets.single { it.body == SkyBody.Venus }
        assertNotNull(venus.risesAt)
        assertTrue(venus.toString(), venus.risesAt!! in window)
        // Nothing in the sky but the moon is anywhere near this bright.
        assertTrue(venus.toString(), venus.magnitude < -3.5)
    }

    /**
     * A planet on the far side of the sun is simply absent, rather than
     * appearing at some altitude below the bar. Early January 2026 has neither
     * Venus nor Mars in the Edinburgh evening sky.
     */
    @Test
    fun `leaves out the planets that are not there`() {
        val planets = tonight("2026-01-05T17:00:00Z", "2026-01-05T21:00:00Z")

        assertEquals(
            setOf(SkyBody.Jupiter, SkyBody.Saturn),
            planets.map { it.body }.toSet(),
        )
    }

    @Test
    fun `finds nothing in a window with no time in it`() {
        val planets = Planets.tonight(edinburgh.first, edinburgh.second, 1L..0L)

        assertEquals(emptyList<PlanetTonight>(), planets)
    }

    /**
     * A planet's altitude is a fact about a place. The same instant from
     * Edinburgh and from the far side of the world puts a body in different
     * parts of the sky, and one of the two will not have it up at all.
     */
    @Test
    fun `answers for where the observer stands`() {
        val window = night("2027-01-10T22:00:00Z", "2027-01-10T23:00:00Z")

        val here = Planets.tonight(55.95, -3.19, window)
        val antipodes = Planets.tonight(-41.0, 175.0, window)

        assertTrue(here.map { it.body }.toSet() != antipodes.map { it.body }.toSet())
    }

    /** The pole is an ordinary place to be asked about, not a failure. */
    @Test
    fun `answers at the pole rather than failing`() {
        val planets = Planets.tonight(89.9, 0.0, night("2027-06-21T00:00:00Z", "2027-06-21T02:00:00Z"))

        assertNull(planets.firstOrNull { it.peakAltitude < Planets.VISIBLE_DEGREES })
    }
}
