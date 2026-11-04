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
