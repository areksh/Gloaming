package sh.arek.gloaming.domain

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.PlanePoint
import sh.arek.gloaming.astronomy.SkyProjection
import sh.arek.gloaming.data.FindAim

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
        return times(rz, rx)
    }

    /**
     * Held up and looked through: its back towards [heading] at [altitude],
     * screen towards the holder, and rolled clockwise by [roll] as the holder
     * sees it.
     */
    private fun held(heading: Double, altitude: Double, roll: Double = 0.0): FloatArray {
        val r = Math.toRadians(roll)
        // Clockwise in the screen's own plane, seen from in front of it.
        val rz = doubleArrayOf(cos(r), sin(r), 0.0, -sin(r), cos(r), 0.0, 0.0, 0.0, 1.0)
        return times(matrix(heading, 90.0 + altitude).map { it.toDouble() }.toDoubleArray(), rz)
    }

    private fun times(a: DoubleArray, b: DoubleArray): FloatArray = FloatArray(9) { i ->
        val row = i / 3
        val col = i % 3
        (0 until 3).sumOf { k -> a[row * 3 + k] * b[k * 3 + col] }.toFloat()
    }

    private fun direction(
        rotation: FloatArray,
        declination: Double = 0.0,
        offset: Double = 0.0,
        aim: FindAim = FindAim.TopEdge,
    ) = Pointing.direction(rotation, aim, declination, offset)

    private fun through(rotation: FloatArray) = direction(rotation, aim = FindAim.LookThrough)

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

    // --- looked through, out of the back

    /**
     * Looked through like a window the phone points out of its back: held up
     * to be read, it looks at the horizon, where top-edge aiming had it at
     * the zenith.
     */
    @Test
    fun `looked through upright with its back to the north it looks north at the horizon`() {
        val d = through(matrix(0.0, 90.0))
        assertEquals(0.0, d.azimuth, 1e-3)
        assertEquals(0.0, d.altitude, 1e-3)
    }

    @Test
    fun `looked through flat on a table screen up it looks at the ground beneath`() {
        assertEquals(-90.0, through(matrix(0.0, 0.0)).altitude, 1e-3)
    }

    @Test
    fun `looked through tipped back forty five degrees facing south it looks south at forty five`() {
        val d = through(matrix(180.0, 135.0))
        assertEquals(180.0, d.azimuth, 1e-3)
        assertEquals(45.0, d.altitude, 1e-3)
    }

    @Test
    fun `looked through rolling the phone does not move where it looks`() {
        val d = through(held(heading = 120.0, altitude = 30.0, roll = 35.0))
        assertEquals(120.0, d.azimuth, 1e-3)
        assertEquals(30.0, d.altitude, 1e-3)
    }

    @Test
    fun `looked through adds the declination and the offset`() {
        val d = direction(held(90.0, 30.0), declination = 3.0, offset = -1.5, aim = FindAim.LookThrough)
        assertEquals(91.5, d.azimuth, 1e-3)
        assertEquals(30.0, d.altitude, 1e-3)
    }

    // --- which way is up in the drawing

    @Test
    fun `looked through level the sky is drawn the right way up`() {
        assertEquals(0.0, Pointing.roll(held(heading = 200.0, altitude = 20.0), FindAim.LookThrough), 1e-3)
    }

    /**
     * Turned clockwise, the real horizon tilts the other way across the
     * screen, so the drawing turns back by as much and stays level with it.
     */
    @Test
    fun `looked through rolled clockwise the sky is turned back anticlockwise`() {
        assertEquals(-35.0, Pointing.roll(held(200.0, 20.0, roll = 35.0), FindAim.LookThrough), 1e-3)
        assertEquals(50.0, Pointing.roll(held(10.0, 60.0, roll = -50.0), FindAim.LookThrough), 1e-3)
        assertEquals(-90.0, Pointing.roll(held(0.0, 0.0, roll = 90.0), FindAim.LookThrough), 1e-3)
    }

    @Test
    fun `looked through straight up or down there is no up to turn to`() {
        assertEquals(0.0, Pointing.roll(matrix(40.0, 0.0), FindAim.LookThrough), 0.0)
        assertEquals(0.0, Pointing.roll(matrix(40.0, 180.0), FindAim.LookThrough), 0.0)
    }

    /** Aimed by the top edge the screen is not a window, and below the zenith the drawing is not turned. */
    @Test
    fun `aimed by the top edge low down the drawing is not turned`() {
        assertEquals(0.0, Pointing.roll(held(200.0, 20.0, roll = 35.0), FindAim.TopEdge), 0.0)
        assertEquals(0.0, Pointing.roll(matrix(200.0, 55.0), FindAim.TopEdge), 0.0)
    }

    /**
     * Where [target] is drawn, aimed by the top edge: projected around where
     * the phone points, then turned by the roll as the view turns it -
     * clockwise on the screen, which with y up is this.
     */
    private fun drawnTopEdge(rotation: FloatArray, target: AltAz): PlanePoint {
        val flat = SkyProjection.around(direction(rotation), target)!!
        val r = Math.toRadians(Pointing.roll(rotation, FindAim.TopEdge))
        return PlanePoint(flat.x * cos(r) + flat.y * sin(r), -flat.x * sin(r) + flat.y * cos(r))
    }

    /**
     * Tipping the top edge a fraction of a degree past straight up swings the
     * azimuth round by half a turn. The Plough, 85 degrees up, must stay
     * where it was on the screen rather than jump to the other side of it.
     */
    @Test
    fun `aimed by the top edge the sky does not flip as the edge passes straight up`() {
        val plough = AltAz(85.0, 0.0)
        val before = drawnTopEdge(matrix(0.0, 89.8), plough)
        val after = drawnTopEdge(matrix(0.0, 90.2), plough)

        assertEquals(before.x, after.x, 0.5)
        assertEquals(before.y, after.y, 0.5)
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

    // --- when to buzz ---

    /** Feeds [separations] through the buzz from armed, and counts the buzzes. */
    private fun buzzes(vararg separations: Double?): Int {
        var armed = true
        var fired = 0
        for (separation in separations) {
            val step = AlignBuzz.next(armed, separation)
            armed = step.armed
            if (step.fire) fired++
        }
        return fired
    }

    @Test
    fun `buzzes once on coming within the aligned distance`() {
        assertEquals(1, buzzes(20.0, Guide.ALIGNED_DEGREES))
    }

    @Test
    fun `does not buzz short of the aligned distance`() {
        assertEquals(0, buzzes(20.0, Guide.ALIGNED_DEGREES + 0.1))
    }

    @Test
    fun `jitter between four and seven degrees buzzes nothing more`() {
        assertEquals(1, buzzes(20.0, 4.0, 7.0, 4.5, 6.9, 4.0, 7.0, 3.0))
    }

    @Test
    fun `going out past the re-arm distance and back in buzzes again`() {
        assertEquals(2, buzzes(20.0, 4.0, AlignBuzz.REARM_DEGREES + 0.1, 4.0))
    }

    @Test
    fun `reaching the re-arm distance exactly does not re-arm`() {
        assertEquals(1, buzzes(20.0, 4.0, AlignBuzz.REARM_DEGREES, 4.0))
    }

    // --- the buzz among many ---

    private val jupiter = FindTarget.Planet(sh.arek.gloaming.astronomy.SkyBody.Jupiter)
    private val saturn = FindTarget.Planet(sh.arek.gloaming.astronomy.SkyBody.Saturn)

    /** Feeds (what is under the reticle, how far the last-buzzed target is) pairs from the start, counting buzzes. */
    private fun buzzesAmong(vararg steps: Pair<FindTarget?, Double?>): Int {
        var state = AlignBuzz.AMONG_START
        var fired = 0
        for ((under, fromLast) in steps) {
            val (next, fire) = AlignBuzz.nextAmong(state, under, fromLast)
            state = next
            if (fire) fired++
        }
        return fired
    }

    @Test
    fun `buzzes on coming onto a target among many`() {
        assertEquals(1, buzzesAmong(null to null, jupiter to 3.0))
    }

    @Test
    fun `wavering on one target buzzes once`() {
        assertEquals(1, buzzesAmong(jupiter to 3.0, null to 6.0, jupiter to 4.0, null to 7.9, jupiter to 2.0))
    }

    @Test
    fun `moving off past re-arm and back buzzes again`() {
        assertEquals(2, buzzesAmong(jupiter to 3.0, null to 8.1, jupiter to 3.0))
    }

    @Test
    fun `moving straight onto another target buzzes at once`() {
        assertEquals(2, buzzesAmong(jupiter to 3.0, saturn to 6.0))
    }

    @Test
    fun `a last target no longer up re-arms`() {
        assertEquals(2, buzzesAmong(jupiter to 3.0, null to null, jupiter to 3.0))
    }

    @Test
    fun `no reading changes nothing`() {
        assertEquals(1, buzzes(20.0, null, 4.0, null, 4.0))
        assertEquals(2, buzzes(4.0, 9.0, null, 4.0))
    }

    @Test
    fun `measures the separation on the sky, not by adding the differences`() {
        // Ninety degrees of azimuth apart, both nearly overhead: a few degrees.
        assertEquals(1.41, Guide.separation(AltAz(89.0, 0.0), AltAz(89.0, 90.0)), 0.01)
        assertEquals(10.0, Guide.separation(AltAz(10.0, 0.0), AltAz(20.0, 0.0)), 1e-9)
    }
}
