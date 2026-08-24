package sh.arek.gloaming.satellites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a pass into something worth reading at 22:00.
 *
 * The app deliberately prints no magnitudes: the only open datasets are stale,
 * incomplete or in incompatible conventions, and a number that is wrong by a
 * magnitude and a half is worse than no number. What is left is geometry, which
 * is exact - how high, how close, and whether the sun is still on it - so the
 * wording has to be honest about being qualitative.
 */
class PassDescriptionTest {

    private fun pass(
        peakElevation: Double = 60.0,
        rangeKm: Double = 450.0,
        peakAzimuth: Double = 180.0,
        riseAzimuth: Double = 225.0,
        setAzimuth: Double = 45.0,
        sunlitAtPeak: Boolean = true,
        entersShadowAt: Long? = null,
        leavesShadowAt: Long? = null,
    ) = SatellitePass(
        catalogue = 25544,
        name = "ISS (ZARYA)",
        channel = PassChannel.Iss,
        risesAt = 1_000_000L,
        riseAzimuth = riseAzimuth,
        peaksAt = 1_200_000L,
        peakElevation = peakElevation,
        peakAzimuth = peakAzimuth,
        setsAt = 1_400_000L,
        setAzimuth = setAzimuth,
        rangeAtPeakKm = rangeKm,
        sunlitAtPeak = sunlitAtPeak,
        entersShadowAt = entersShadowAt,
        leavesShadowAt = leavesShadowAt,
    )

    @Test
    fun `calls a high close sunlit pass the best it gets`() {
        val described = PassDescription.of(pass(), observerIsDark = true)

        assertEquals(Prominence.Striking, described.prominence)
        assertEquals(Height.High, described.height)
        assertEquals(Compass.South, described.peaksTowards)
        assertTrue(described.headline.contains("high in the south"))
    }

    @Test
    fun `calls a middling sunlit pass a good one`() {
        val described = PassDescription.of(pass(peakElevation = 30.0, rangeKm = 800.0), true)

        assertEquals(Prominence.Good, described.prominence)
        assertEquals(Height.Middling, described.height)
    }

    /** The doc's own example of the other end: low, grazing, probably faint. */
    @Test
    fun `is blunt about a low pass`() {
        val described = PassDescription.of(pass(peakElevation = 12.0, rangeKm = 1900.0), true)

        assertEquals(Prominence.Modest, described.prominence)
        assertEquals(Height.Low, described.height)
        assertTrue(described.headline.lowercase().contains("faint"))
    }

    /**
     * A satellite in the Earth's shadow reflects nothing. Saying so is the
     * point: the pass is happening, and there is still nothing to see.
     */
    @Test
    fun `says a pass in shadow throughout cannot be seen`() {
        val described = PassDescription.of(pass(sunlitAtPeak = false), true)

        assertEquals(Prominence.InShadow, described.prominence)
        assertTrue(described.headline.contains("shadow"))
    }

    /** Geometry is not enough on its own: in daylight there is nothing to see. */
    @Test
    fun `says a pass in daylight cannot be seen`() {
        val described = PassDescription.of(pass(), observerIsDark = false)

        assertEquals(Prominence.TooLight, described.prominence)
    }

    /**
     * The pre-dawn case. It is dark at the highest point but lights up before
     * the end, so it is not a pass with nothing to see.
     */
    @Test
    fun `counts a pass that lights up before it sets`() {
        val described = PassDescription.of(
            pass(sunlitAtPeak = false, leavesShadowAt = 1_300_000L),
            true,
        )

        assertEquals(Prominence.Modest, described.prominence)
    }

    /** Going out mid-sky is the thing people ask about afterwards. */
    @Test
    fun `mentions the satellite going out part-way across`() {
        val described = PassDescription.of(pass(entersShadowAt = 1_300_000L), true)

        assertEquals(Prominence.Striking, described.prominence)
        assertTrue(described.goesIntoShadow)
        assertTrue(described.headline.contains("shadow"))
    }

    @Test
    fun `names the compass point every pass runs through`() {
        val described = PassDescription.of(
            pass(riseAzimuth = 315.0, peakAzimuth = 90.0, setAzimuth = 150.0),
            true,
        )

        assertEquals(Compass.NorthWest, described.risesTowards)
        assertEquals(Compass.East, described.peaksTowards)
        assertEquals(Compass.SouthEast, described.setsTowards)
    }

    /** The compass wraps, and 350 degrees is north rather than north-west. */
    @Test
    fun `rounds an azimuth to the nearest of eight points`() {
        assertEquals(Compass.North, Compass.of(350.0))
        assertEquals(Compass.North, Compass.of(0.0))
        assertEquals(Compass.NorthEast, Compass.of(44.0))
        assertEquals(Compass.West, Compass.of(281.0))
    }

    /** British English throughout, and no decorative characters. */
    @Test
    fun `writes plain ascii`() {
        for (elevation in listOf(5.0, 30.0, 70.0)) {
            for (dark in listOf(true, false)) {
                val headline = PassDescription.of(pass(peakElevation = elevation), dark).headline
                assertTrue(headline, headline.all { it.code in 32..126 })
            }
        }
    }
}
