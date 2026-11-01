package sh.arek.gloaming.astronomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPositionsTest {

    private val edinburgh = 55.95 to -3.19

    // 2026-11-01 17:00 UTC, the start of a long night in Edinburgh.
    private val evening = 1_793_552_400_000L

    private fun at(millis: Long = evening, place: Pair<Double, Double> = edinburgh, body: SkyBody) =
        SkyPositions.body(body, place.first, place.second, millis)

    @Test
    fun `agrees with the planet's own peak`() {
        val night = evening..(evening + 10L * 3_600_000)
        // Saturn is an evening planet that autumn, so it must be in the list.
        val saturn = Planets.tonight(edinburgh.first, edinburgh.second, night)
            .single { it.body == SkyBody.Saturn }

        val now = at(saturn.peakAt, body = saturn.body)

        assertEquals(saturn.peakAltitude, now.altitude, 0.5)
        assertEquals(saturn.peakAzimuth, now.azimuth, 0.5)
    }

    @Test
    fun `agrees with the moon's altitude`() {
        // Sampled across a day so at least one moment has it well clear of the
        // horizon, where refraction (applied here, not by Twilight) is small.
        val clear = (0 until 24)
            .map { evening + it * 3_600_000L }
            .filter { Twilight.moonAltitude(edinburgh.first, edinburgh.second, it) > 10.0 }
        assertTrue(clear.isNotEmpty())

        for (t in clear) {
            assertEquals(
                Twilight.moonAltitude(edinburgh.first, edinburgh.second, t),
                at(t, body = SkyBody.Moon).altitude,
                0.5,
            )
        }
    }

    @Test
    fun `Ursa Major's centre is circumpolar from Edinburgh`() {
        for (i in 0 until 8) {
            val t = evening + i * 3L * 3_600_000
            val star = SkyPositions.fixed(11.0, 50.7, edinburgh.first, edinburgh.second, t)
            assertTrue("altitude ${star.altitude} at step $i", star.altitude > 0.0)
        }
    }

    // GMST at 2000-01-01 12:00 UTC (J2000.0) is 18.697374558 hours (USNO, and
    // the standard GMST polynomial's constant term), so a star on that right
    // ascension, at the observer's own declination, is overhead on the
    // Greenwich meridian; 90 degrees east, six hours of right ascension on, it
    // is overhead again. Hours read as degrees, or the wrong sign on longitude,
    // puts the star many degrees from the zenith.
    private val j2000 = 946_728_000_000L
    private val gmstJ2000 = 18.697374558

    @Test
    fun `a star at the local sidereal time and the observer's declination is at the zenith`() {
        val star = SkyPositions.fixed(gmstJ2000, 51.48, 51.48, 0.0, j2000)

        assertEquals(90.0, star.altitude, 0.5)
    }

    @Test
    fun `east longitude advances the sidereal time`() {
        val east = SkyPositions.fixed(gmstJ2000 + 6.0, 20.0, 20.0, 90.0, j2000)
        val west = SkyPositions.fixed(gmstJ2000 + 6.0, 20.0, 20.0, -90.0, j2000)

        assertEquals(90.0, east.altitude, 0.5)
        assertTrue(west.altitude < 0.0)
    }

    @Test
    fun `azimuth runs clockwise from north`() {
        // A right ascension the meridian has already passed is west of it.
        val west = SkyPositions.fixed(gmstJ2000 - 3.0, 0.0, 0.0, 0.0, j2000)
        val east = SkyPositions.fixed(gmstJ2000 + 3.0, 0.0, 0.0, 0.0, j2000)

        assertEquals(270.0, west.azimuth, 1.0)
        assertEquals(90.0, east.azimuth, 1.0)
    }
}
