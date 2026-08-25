package sh.arek.gloaming.astronomy

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassTest {

    /** The compass wraps, and 350 degrees is north rather than north-west. */
    @Test
    fun `rounds an azimuth to the nearest of eight points`() {
        assertEquals(Compass.North, Compass.of(350.0))
        assertEquals(Compass.North, Compass.of(0.0))
        assertEquals(Compass.NorthEast, Compass.of(44.0))
        assertEquals(Compass.West, Compass.of(281.0))
    }

    /** Bearings outside 0-360 arrive from arithmetic elsewhere and must still land. */
    @Test
    fun `wraps a bearing from outside a single turn`() {
        assertEquals(Compass.North, Compass.of(720.0))
        assertEquals(Compass.West, Compass.of(-90.0))
        assertEquals(Compass.South, Compass.of(-180.0))
    }
}
