package sh.arek.gloaming.ui

import androidx.compose.ui.geometry.Offset
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.ConstellationFigures
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.PlanePoint
import sh.arek.gloaming.astronomy.SkyPositions
import sh.arek.gloaming.astronomy.SkyProjection
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.astronomy.placedAt

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

    /** The drawing is still turned for one way of facing, so high up it still says which. */
    @Test
    fun `says overhead and still which way to face high up`() {
        assertEquals(
            "As it stands at ${clock(peakAt)}, nearly overhead, facing south.",
            figureCaption(orion(altitude = 80.0, azimuth = 180.0)),
        )
    }

    // --- where a point of sky lands on Find's view ---

    private val centre = Offset(160f, 160f)

    @Test
    fun `puts the direction the phone points on the reticle`() {
        val at = skyToScreen(AltAz(40.0, 120.0), AltAz(40.0, 120.0), centre, perDegree = 5f)!!

        assertEquals(160f, at.x, 1e-3f)
        assertEquals(160f, at.y, 1e-3f)
    }

    /** Up the sky is up the screen, which is smaller y; clockwise round the horizon is right. */
    @Test
    fun `puts higher above and clockwise to the right`() {
        val higher = skyToScreen(AltAz(30.0, 180.0), AltAz(34.0, 180.0), centre, perDegree = 5f)!!
        val clockwise = skyToScreen(AltAz(0.0, 180.0), AltAz(0.0, 184.0), centre, perDegree = 5f)!!

        assertEquals(160f, higher.x, 1e-3f)
        assertEquals(140f, higher.y, 0.1f)
        assertEquals(180f, clockwise.x, 0.1f)
        assertEquals(160f, clockwise.y, 1e-3f)
    }

    @Test
    fun `places nothing behind the viewer`() {
        assertNull(skyToScreen(AltAz(10.0, 0.0), AltAz(10.0, 180.0), centre, perDegree = 5f))
    }

    // --- fitting a figure into the sheet ---

    private val width = 400f
    private val height = 280f
    private val pad = 16f
    private val label = 40f

    private fun inside(point: Offset) =
        point.x >= pad - 1e-3f && point.x <= width - pad + 1e-3f &&
            point.y >= pad - 1e-3f && point.y <= height - pad + 1e-3f

    /** Two stars on a level line: no height to scale by, and nothing may come out infinite. */
    @Test
    fun `fits two stars on one level without blowing up`() {
        val fitted = fitToBox(listOf(PlanePoint(-2.0, 0.0), PlanePoint(2.0, 0.0)), width, height, pad, 1, label)

        for (point in fitted) {
            assertTrue(point.toString(), point!!.x.isFinite() && point.y.isFinite() && inside(point))
        }
        assertEquals(height / 2, fitted[0]!!.y, 1e-3f)
        assertEquals(width - pad - label - pad, fitted[1]!!.x - fitted[0]!!.x, 0.5f)
    }

    @Test
    fun `keeps up up and right right`() {
        val fitted = fitToBox(listOf(PlanePoint(0.0, 0.0), PlanePoint(1.0, 3.0)), width, height, pad, 0, label)

        assertTrue(fitted[1]!!.y < fitted[0]!!.y)
        assertTrue(fitted[1]!!.x > fitted[0]!!.x)
    }

    @Test
    fun `leaves out a star that is not on the drawing`() {
        val fitted = fitToBox(listOf(PlanePoint(0.0, 0.0), null, PlanePoint(5.0, 5.0)), width, height, pad, 0, label)

        assertNull(fitted[1])
        assertTrue(fitted[0] != null && fitted[2] != null)
    }

    @Test
    fun `fits nothing when nothing is on the drawing`() {
        assertEquals(listOf<Offset?>(null, null), fitToBox(listOf(null, null), width, height, pad, 0, label))
    }

    /** The Plough at its highest over Edinburgh in spring, about 85 degrees up: it must fill the box, not collapse. */
    @Test
    fun `fits the plough drawn near the zenith`() {
        val lat = 55.95
        val lon = -3.19
        val plough = Constellations.WATCHED.first { it.name == "Ursa Major" }
        val night = Twilight.nightNearest(lat, lon, Instant.parse("2027-04-01T22:00:00Z").toEpochMilli())!!
        val seen = Constellations.tonight(lat, lon, night).first { it.constellation.name == "Ursa Major" }
        assertTrue("peaks at ${seen.peakAltitude}", seen.peakAltitude > 80.0)

        val centreThen = SkyPositions.fixed(plough.raHours, plough.dec, lat, lon, seen.peakAt)
        val flat = ConstellationFigures.of(plough)!!.placedAt(lat, lon, seen.peakAt).positions
            .map { SkyProjection.around(centreThen, it) }
        val alioth = ConstellationFigures.of(plough)!!.stars.indexOfFirst { it.name == plough.brightestStar }
        val fitted = fitToBox(flat, width, height, pad, alioth, label).map { it!! }

        assertTrue(fitted.all { inside(it) })
        assertTrue(fitted[alioth].x + label <= width - pad + 1e-3f)
        val spanX = fitted.maxOf { it.x } - fitted.minOf { it.x }
        val spanY = fitted.maxOf { it.y } - fitted.minOf { it.y }
        assertTrue(
            "span $spanX x $spanY",
            spanX >= 0.99f * (width - 2 * pad - label) || spanY >= 0.99f * (height - 2 * pad),
        )
    }

    /** A label that falls inside the figure needs no room of its own, so the figure is centred. */
    @Test
    fun `centres a figure whose label falls inside it`() {
        val fitted = fitToBox(listOf(PlanePoint(0.0, 0.0), PlanePoint(10.0, 0.0)), width, height, pad, 0, label)

        assertEquals(pad, fitted[0]!!.x, 1e-3f)
        assertEquals(width - pad, fitted[1]!!.x, 1e-3f)
    }

    /** A label beside the rightmost star is kept in, and the figure and label are centred together. */
    @Test
    fun `keeps a label at the right edge inside`() {
        val fitted = fitToBox(listOf(PlanePoint(0.0, 0.0), PlanePoint(10.0, 0.0)), width, height, pad, 1, label)

        assertEquals(pad, fitted[0]!!.x, 1e-3f)
        assertEquals(width - pad, fitted[1]!!.x + label, 1e-3f)
    }

    /** With no star to label, there is nothing to make room for. */
    @Test
    fun `makes no room for a label when no star has one`() {
        val fitted = fitToBox(listOf(PlanePoint(0.0, 0.0), PlanePoint(10.0, 0.0)), width, height, pad, -1, label)

        assertEquals(width - pad, fitted[1]!!.x, 1e-3f)
    }
}
