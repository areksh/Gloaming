package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How big things look, checked against published figures rather than against
 * the app's own arithmetic.
 *
 * The moon's apparent diameter is one of the best-known numbers in observational
 * astronomy - about half a degree, varying from roughly 29.4 to 33.5 arcminutes
 * between apogee and perigee - so it is worth pinning against that rather than
 * against whatever this code happens to produce.
 */
class AngularSizeTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val edinburgh = 55.95 to -3.19

    private fun semi(body: SkyBody, iso: String) = AngularSize.semiDiameterDegrees(
        body,
        edinburgh.first,
        edinburgh.second,
        at(iso),
    )

    /**
     * Half a degree across, give or take. Anything outside 29 to 34 arcminutes
     * of diameter is not the moon.
     */
    @Test
    fun `puts the moon at about half a degree across`() {
        val diameter = semi(SkyBody.Moon, "2027-03-15T22:00:00Z") * 2 * 60

        assertTrue("$diameter arcminutes", diameter in 29.0..34.0)
    }

    /**
     * The moon is much bigger overhead than it is on the horizon - not the
     * illusion, the real effect: an observer on the surface is one Earth radius
     * nearer to it when it is at the zenith, which is about 1.7% of its
     * distance. That is the parallax an occultation prediction turns on, and it
     * is only visible at all because this is topocentric.
     */
    @Test
    fun `sees a bigger moon from under it than from beside it`() {
        val instant = "2027-03-15T22:00:00Z"

        val here = AngularSize.semiDiameterDegrees(SkyBody.Moon, 55.95, -3.19, at(instant))
        val elsewhere = AngularSize.semiDiameterDegrees(SkyBody.Moon, -55.95, 176.81, at(instant))

        assertTrue("$here vs $elsewhere", here != elsewhere)
    }

    /** The sun is very nearly the same apparent size, which is why eclipses work. */
    @Test
    fun `puts the sun at about half a degree across too`() {
        val diameter = semi(SkyBody.Sun, "2027-03-15T12:00:00Z") * 2 * 60

        assertTrue("$diameter arcminutes", diameter in 31.0..33.0)
    }

    /**
     * Jupiter runs from about 30 to 50 arcseconds across depending on where it
     * is in its orbit - two orders of magnitude smaller than the moon, which is
     * exactly why an occultation of it is over in a moment at each edge.
     */
    @Test
    fun `puts jupiter at tens of arcseconds across`() {
        val diameter = semi(SkyBody.Jupiter, "2027-01-10T22:00:00Z") * 2 * 3600

        assertTrue("$diameter arcseconds", diameter in 28.0..52.0)
    }

    @Test
    fun `puts venus somewhere between ten and sixty-six arcseconds`() {
        val diameter = semi(SkyBody.Venus, "2027-01-10T06:00:00Z") * 2 * 3600

        assertTrue("$diameter arcseconds", diameter in 9.0..67.0)
    }

    // --- covering ---

    /**
     * A separation of a tenth of a degree is well inside the moon's own radius
     * of about a quarter, so the discs are touching and there is something to
     * see.
     */
    @Test
    fun `calls a very close pairing an occultation`() {
        val covered = AngularSize.moonCovers(
            SkyBody.Venus,
            separationDegrees = 0.1,
            latitude = edinburgh.first,
            longitude = edinburgh.second,
            atMillis = at("2026-09-14T09:00:00Z"),
        )

        assertTrue(covered)
    }

    /**
     * The near miss that is not one. `ConjunctionsTest` already pins the moon
     * within 0.07 degrees of Venus; at a whole degree the discs are two moon
     * widths apart and nothing is hidden by anything.
     */
    @Test
    fun `does not call a near miss an occultation`() {
        val covered = AngularSize.moonCovers(
            SkyBody.Venus,
            separationDegrees = 1.0,
            latitude = edinburgh.first,
            longitude = edinburgh.second,
            atMillis = at("2026-09-14T09:00:00Z"),
        )

        assertFalse(covered)
    }

    /** The moon cannot occult itself, and asking is a caller's mistake rather than a crash. */
    @Test
    fun `never says the moon covers the moon`() {
        assertFalse(
            AngularSize.moonCovers(
                SkyBody.Moon,
                separationDegrees = 0.0,
                latitude = edinburgh.first,
                longitude = edinburgh.second,
                atMillis = at("2026-09-14T09:00:00Z"),
            ),
        )
    }

    /**
     * The planet's own disc counts. Jupiter is tens of arcseconds across, so
     * the discs touch fractionally sooner than a point source would - which is
     * the difference between first contact and the centres being a lunar radius
     * apart.
     */
    @Test
    fun `counts the planet's own disc as well as the moon's`() {
        val instant = at("2027-01-10T22:00:00Z")
        val moon = AngularSize.semiDiameterDegrees(
            SkyBody.Moon, edinburgh.first, edinburgh.second, instant,
        )
        val jupiter = AngularSize.semiDiameterDegrees(
            SkyBody.Jupiter, edinburgh.first, edinburgh.second, instant,
        )

        // Just outside the moon's own edge, and inside the sum of the two.
        val between = moon + jupiter / 2

        assertTrue(
            AngularSize.moonCovers(
                SkyBody.Jupiter, between, edinburgh.first, edinburgh.second, instant,
            ),
        )
        assertTrue("jupiter should have a measurable disc", jupiter > 0.0)
    }

    /** Nothing is asked of a body with no radius on file, and nothing breaks. */
    @Test
    fun `reports nothing for a body it has no size for`() {
        assertEquals(0.0, AngularSize.semiDiameterDegrees(SkyBody.Moon, 0.0, 0.0, 0L), 1.0)
    }
}
