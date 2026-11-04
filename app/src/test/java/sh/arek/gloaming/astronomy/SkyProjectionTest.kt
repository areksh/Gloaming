package sh.arek.gloaming.astronomy

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The flat drawing of a patch of sky: up is towards the zenith, right is
 * clockwise in azimuth, and near the centre one unit is one degree.
 */
class SkyProjectionTest {

    private val DEGREES = 180.0 / Math.PI

    @Test
    fun `puts the centre at the origin`() {
        val point = SkyProjection.around(AltAz(30.0, 200.0), AltAz(30.0, 200.0))!!

        assertEquals(0.0, point.x, 1e-9)
        assertEquals(0.0, point.y, 1e-9)
    }

    @Test
    fun `puts a point straight above the centre straight up`() {
        val point = SkyProjection.around(AltAz(20.0, 180.0), AltAz(30.0, 180.0))!!

        assertEquals(0.0, point.x, 1e-9)
        assertEquals(10.0, point.y, 0.05)
    }

    @Test
    fun `puts a point clockwise in azimuth to the right`() {
        val point = SkyProjection.around(AltAz(0.0, 90.0), AltAz(0.0, 100.0))!!

        assertEquals(10.0, point.x, 0.05)
        assertEquals(0.0, point.y, 1e-9)
    }

    /** Five degrees away in eight directions, from a centre low in the south-west. */
    @Test
    fun `keeps small distances in degrees`() {
        val centre = AltAz(35.0, 225.0)
        for (step in 0 until 8) {
            val bearing = Math.toRadians(step * 45.0)
            val point = offset(centre, 5.0, bearing)
            val flat = SkyProjection.around(centre, point)!!
            assertEquals("bearing ${step * 45}", 5.0, hypot(flat.x, flat.y), 0.01)
        }
    }

    @Test
    fun `leaves out a point behind the viewer`() {
        assertNull(SkyProjection.around(AltAz(10.0, 0.0), AltAz(10.0, 180.0)))
    }

    @Test
    fun `stays finite at the zenith`() {
        val point = SkyProjection.around(AltAz(90.0, 0.0), AltAz(80.0, 0.0))

        assertNotNull(point)
        assertEquals(10.0, hypot(point!!.x, point.y), 0.05)
    }

    /** Just inside the cutoff a point is placed, finite; just past it, it is not. */
    @Test
    fun `places a point up to ninety degrees away and no further`() {
        val inside = SkyProjection.around(AltAz(0.0, 0.0), AltAz(0.0, 89.9))
        assertNotNull(inside)
        assertEquals(2 * DEGREES * Math.tan(Math.toRadians(89.9 / 2)), inside!!.x, 1e-6)
        assertNull(SkyProjection.around(AltAz(0.0, 0.0), AltAz(0.0, 90.1)))
    }

    // --- which way a point lies ---

    @Test
    fun `points the way the drawing would put a point in front`() {
        val centre = AltAz(25.0, 140.0)
        for (target in listOf(AltAz(50.0, 170.0), AltAz(5.0, 100.0), AltAz(70.0, 300.0))) {
            val flat = SkyProjection.around(centre, target)!!
            val way = SkyProjection.towards(centre, target)!!
            val length = hypot(flat.x, flat.y)
            assertEquals("$target", flat.x / length, way.x, 1e-9)
            assertEquals("$target", flat.y / length, way.y, 1e-9)
        }
    }

    /** Looking nearly straight up, a star low on the far side is over the top, not down. */
    @Test
    fun `points over the zenith to a star on the far side`() {
        val way = SkyProjection.towards(AltAz(88.0, 0.0), AltAz(40.0, 180.0))!!

        assertEquals(0.0, way.x, 1e-9)
        assertEquals(1.0, way.y, 1e-9)
    }

    @Test
    fun `points behind the viewer too`() {
        val way = SkyProjection.towards(AltAz(10.0, 0.0), AltAz(10.0, 180.0))!!

        assertEquals(1.0, hypot(way.x, way.y), 1e-9)
        assertEquals(1.0, way.y, 1e-9)
    }

    @Test
    fun `has no way to the centre itself`() {
        assertNull(SkyProjection.towards(AltAz(30.0, 30.0), AltAz(30.0, 30.0)))
    }

    // --- the horizon ---

    /** Every point of the horizon that is drawn at all is drawn on the one circle. */
    @Test
    fun `draws the horizon as one circle`() {
        for (altitude in listOf(-60.0, -20.0, -3.0, 5.0, 20.0, 45.0, 80.0, 90.0)) {
            val circle = SkyProjection.horizon(altitude)!!
            for (step in -8..8) {
                val point = SkyProjection.around(AltAz(altitude, 200.0), AltAz(0.0, 200.0 + step * 11.0)) ?: continue
                val off = hypot(point.x - circle.centre.x, point.y - circle.centre.y)
                assertEquals("altitude $altitude, step $step", circle.radius, off, 1e-6 * circle.radius)
            }
        }
    }

    /** A point ten degrees up, straight ahead: inside the circle looking up, outside it looking down. */
    @Test
    fun `keeps the sky inside the circle looking up and outside it looking down`() {
        for (altitude in listOf(-40.0, 30.0)) {
            val circle = SkyProjection.horizon(altitude)!!
            val sky = SkyProjection.around(AltAz(altitude, 0.0), AltAz(10.0, 0.0))!!
            val inside = hypot(sky.x - circle.centre.x, sky.y - circle.centre.y) < circle.radius
            assertEquals("altitude $altitude", altitude > 0, inside)
        }
    }

    /** Level, the horizon is the straight line through the centre, and there is no circle to give. */
    @Test
    fun `gives no circle looking level`() {
        assertNull(SkyProjection.horizon(0.2))
        assertNull(SkyProjection.horizon(-0.2))
    }

    /** The point [distance] degrees from [from] along [bearing] (0 towards the zenith, clockwise). */
    private fun offset(from: AltAz, distance: Double, bearing: Double): AltAz {
        val alt0 = Math.toRadians(from.altitude)
        val d = Math.toRadians(distance)
        val sinAlt = sin(alt0) * cos(d) + cos(alt0) * sin(d) * cos(bearing)
        val alt = Math.asin(sinAlt)
        val dAz = Math.atan2(sin(bearing) * sin(d) * cos(alt0), cos(d) - sin(alt0) * sinAlt)
        return AltAz(Math.toDegrees(alt), from.azimuth + Math.toDegrees(dAz))
    }
}
