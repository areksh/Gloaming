package sh.arek.gloaming.domain

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz

class PointingTest {

    /**
     * Row-major 3x3 as SensorManager.getRotationMatrixFromVector gives it:
     * world X east, Y north, Z up; device X right, Y top edge, Z out of the
     * screen. A phone flat, screen up, top edge north is the identity. It is
     * pitched up by [pitch] about its own X axis, then turned clockwise by
     * [heading] about the world's vertical.
     */
    private fun matrix(heading: Double, pitch: Double): FloatArray {
        val h = Math.toRadians(heading)
        val p = Math.toRadians(pitch)
        val rz = doubleArrayOf(cos(h), sin(h), 0.0, -sin(h), cos(h), 0.0, 0.0, 0.0, 1.0)
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(p), -sin(p), 0.0, sin(p), cos(p))
        return FloatArray(9) { i ->
            val row = i / 3
            val col = i % 3
            (0 until 3).sumOf { k -> rz[row * 3 + k] * rx[k * 3 + col] }.toFloat()
        }
    }

    private fun direction(
        rotation: FloatArray,
        declination: Double = 0.0,
        offset: Double = 0.0,
    ) = Pointing.direction(rotation, declination, offset)

    private fun turn(fromAz: Double, toAz: Double) =
        Guide.of(AltAz(10.0, fromAz), AltAz(10.0, toAz)).turnDegrees

    private fun words(turn: Double = 0.0, tilt: Double = 0.0, aligned: Boolean = false) =
        GuideWords.instruction(Guidance(turn, tilt, aligned))

    @Test
    fun `phone flat facing north points north at the horizon`() {
        val d = direction(matrix(0.0, 0.0))
        assertEquals(0.0, d.azimuth, 1e-4)
        assertEquals(0.0, d.altitude, 1e-4)
    }

    @Test
    fun `phone upright facing east has its top edge at the zenith`() {
        val d = direction(matrix(90.0, 90.0))
        assertEquals(90.0, d.altitude, 1e-3)
    }

    @Test
    fun `phone tilted forty five degrees up facing south points south at forty five`() {
        val d = direction(matrix(180.0, 45.0))
        assertEquals(180.0, d.azimuth, 1e-3)
        assertEquals(45.0, d.altitude, 1e-3)
    }

    @Test
    fun `phone flat with its top edge east points east at the horizon`() {
        val d = direction(matrix(90.0, 0.0))
        assertEquals(90.0, d.azimuth, 1e-3)
        assertEquals(0.0, d.altitude, 1e-3)
    }

    @Test
    fun `adds the declination and the offset`() {
        val d = direction(matrix(90.0, 30.0), declination = 3.0, offset = -1.5)
        assertEquals(91.5, d.azimuth, 1e-3)
        assertEquals(30.0, d.altitude, 1e-3)
    }

    @Test
    fun `wraps the azimuth past north back to the small side`() {
        val d = direction(matrix(350.0, 10.0), declination = 15.0)
        assertEquals(5.0, d.azimuth, 1e-3)
    }

    @Test
    fun `wraps a negative correction up from the small side`() {
        val d = direction(matrix(5.0, 10.0), offset = -15.0)
        assertEquals(350.0, d.azimuth, 1e-3)
    }

    @Test
    fun `from 359 to 1 is two degrees right`() {
        assertEquals(2.0, turn(359.0, 1.0), 1e-9)
    }

    @Test
    fun `from 1 to 359 is two degrees left`() {
        assertEquals(-2.0, turn(1.0, 359.0), 1e-9)
    }

    @Test
    fun `tilt is the target altitude less the pointing altitude`() {
        val g = Guide.of(AltAz(20.0, 100.0), AltAz(5.0, 100.0))
        assertEquals(-15.0, g.tiltDegrees, 1e-9)
    }

    @Test
    fun `near the zenith only tilt is asked`() {
        val g = Guide.of(AltAz(86.0, 10.0), AltAz(40.0, 200.0))
        assertEquals(0.0, g.turnDegrees, 0.0)
        assertEquals(-46.0, g.tiltDegrees, 1e-9)
    }

    @Test
    fun `a target near the zenith asks for no turn either`() {
        val g = Guide.of(AltAz(30.0, 10.0), AltAz(85.0, 200.0))
        assertEquals(0.0, g.turnDegrees, 0.0)
    }

    @Test
    fun `just under the zenith limit the turn is still asked`() {
        val g = Guide.of(AltAz(84.9, 10.0), AltAz(84.9, 100.0))
        assertEquals(90.0, g.turnDegrees, 1e-9)
    }

    @Test
    fun `within five degrees is lined up`() {
        assertTrue(Guide.of(AltAz(30.0, 100.0), AltAz(33.0, 100.0)).aligned)
        assertTrue(Guide.of(AltAz(30.0, 100.0), AltAz(35.0, 100.0)).aligned)
    }

    @Test
    fun `beyond five degrees is not lined up`() {
        assertFalse(Guide.of(AltAz(30.0, 100.0), AltAz(35.5, 100.0)).aligned)
    }

    @Test
    fun `a big azimuth gap on the horizon is not lined up`() {
        assertFalse(Guide.of(AltAz(0.0, 100.0), AltAz(0.0, 110.0)).aligned)
    }

    @Test
    fun `high in the sky twenty degrees of azimuth is a small angle`() {
        val g = Guide.of(AltAz(80.0, 0.0), AltAz(80.0, 20.0))
        assertTrue(g.aligned)
    }

    @Test
    fun `lined up says there`() {
        assertEquals("There.", words(aligned = true))
    }

    @Test
    fun `turn bands are read at their edges`() {
        assertEquals("Turn right a little.", words(turn = 14.9))
        assertEquals("Turn right.", words(turn = 15.0))
        assertEquals("Turn right.", words(turn = 59.9))
        assertEquals("Turn right a long way.", words(turn = 60.0))
        assertEquals("Turn right a long way.", words(turn = 134.9))
        assertEquals("Turn round.", words(turn = 135.0))
    }

    @Test
    fun `a left turn is worded the same way`() {
        assertEquals("Turn left a little.", words(turn = -14.9))
        assertEquals("Turn left.", words(turn = -30.0))
        assertEquals("Turn left a long way.", words(turn = -100.0))
        assertEquals("Turn round.", words(turn = -170.0))
    }

    @Test
    fun `tilt bands are read at their edges`() {
        assertEquals("Nearly there.", words(tilt = 4.9))
        assertEquals("Tilt up a little.", words(tilt = 5.0))
        assertEquals("Tilt up a little.", words(tilt = 14.9))
        assertEquals("Tilt up.", words(tilt = 15.0))
        assertEquals("Tilt down a little.", words(tilt = -5.0))
        assertEquals("Tilt down.", words(tilt = -15.0))
        assertEquals("Nearly there.", words(tilt = -4.9))
    }

    @Test
    fun `a turn and a tilt are joined`() {
        assertEquals("Turn right a little, then tilt up.", words(turn = 10.0, tilt = 20.0))
        assertEquals("Turn left, then tilt down a little.", words(turn = -40.0, tilt = -10.0))
        assertEquals("Turn round, then tilt up.", words(turn = 170.0, tilt = 30.0))
    }

    @Test
    fun `a tilt alone is capitalised`() {
        assertEquals("Tilt up.", words(tilt = 30.0))
    }

    @Test
    fun `near the zenith and not yet lined up there is still something to say`() {
        // Six degrees apart across the zenith: no turn is asked up there and
        // the tilt is under the smallest band, yet the two are not in line.
        val guidance = Guide.of(AltAz(89.0, 0.0), AltAz(85.0, 180.0))

        assertFalse(guidance.aligned)
        assertEquals("Nearly there.", GuideWords.instruction(guidance))
    }
}
