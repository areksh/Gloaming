package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations

/** The parts of a drawn figure that are words and numbers rather than pixels. */
class FigureDrawingTest {

    private val peakAt = 1_799_000_000_000L

    private fun orion(altitude: Double, azimuth: Double) = ConstellationTonight(
        Constellations.WATCHED.first { it.name == "Orion" }, altitude, peakAt, azimuth,
        isCircumpolar = false, isAtItsBest = false,
    )

    @Test
    fun `sizes sirius largest and faint stars smallest`() {
        assertEquals(4.4f, starRadiusDp(-1.46), 0.01f)
        assertEquals(1.5f, starRadiusDp(4.5), 0.001f)
        assertEquals(4.5f, starRadiusDp(-3.0), 0.001f)
    }

    @Test
    fun `says which way to look`() {
        assertEquals(
            "As it stands at ${clock(peakAt)}, looking south.",
            figureCaption(orion(altitude = 40.0, azimuth = 180.0)),
        )
    }

    @Test
    fun `says overhead instead of a direction high up`() {
        assertEquals(
            "As it stands at ${clock(peakAt)}, nearly overhead.",
            figureCaption(orion(altitude = 80.0, azimuth = 180.0)),
        )
    }
}
