package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz

class CompassCheckTest {

    private val day = 24 * 60 * 60 * 1000L
    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private fun offered(
        accuracy: CompassAccuracy = CompassAccuracy.Unreliable,
        offeredAt: Long? = null,
    ) = CompassCheck.shouldOffer(accuracy, offeredAt, now)

    private fun offset(
        pointing: AltAz = AltAz(30.0, 100.0),
        moon: AltAz = AltAz(30.0, 104.0),
        current: Double = 1.0,
    ): Double? = CompassCheck.offsetFromMoon(pointing, moon, current)

    @Test
    fun `maps the platform's sensor statuses`() {
        assertEquals(CompassAccuracy.Unreliable, CompassAccuracy.fromSensorStatus(0))
        assertEquals(CompassAccuracy.Low, CompassAccuracy.fromSensorStatus(1))
        assertEquals(CompassAccuracy.Medium, CompassAccuracy.fromSensorStatus(2))
        assertEquals(CompassAccuracy.High, CompassAccuracy.fromSensorStatus(3))
    }

    @Test
    fun `reads an out-of-range sensor status as unreliable`() {
        assertEquals(CompassAccuracy.Unreliable, CompassAccuracy.fromSensorStatus(-1))
        assertEquals(CompassAccuracy.Unreliable, CompassAccuracy.fromSensorStatus(4))
    }

    @Test
    fun `offers when the compass is unreliable`() {
        assertTrue(offered(CompassAccuracy.Unreliable))
    }

    @Test
    fun `offers when the compass is low`() {
        assertTrue(offered(CompassAccuracy.Low))
    }

    @Test
    fun `does not offer at medium or high`() {
        assertFalse(offered(CompassAccuracy.Medium))
        assertFalse(offered(CompassAccuracy.High))
    }

    @Test
    fun `offers at most once a week`() {
        assertFalse(offered(offeredAt = now - (6 * day + 23 * hour)))
        assertTrue(offered(offeredAt = now - 7 * day))
    }

    @Test
    fun `holds the weekly boundary to the millisecond`() {
        assertFalse(offered(offeredAt = now - (7 * day - 1)))
        assertTrue(offered(offeredAt = now - 7 * day))
    }

    @Test
    fun `corrects by the moon's bearing`() {
        assertEquals(5.0, offset()!!, 1e-9)
    }

    @Test
    fun `refuses a moon too low`() {
        assertNull(offset(pointing = AltAz(4.0, 100.0), moon = AltAz(4.0, 104.0)))
        assertNotNull(offset(pointing = AltAz(5.0, 100.0), moon = AltAz(5.0, 104.0)))
    }

    @Test
    fun `refuses when not pointed at the moon`() {
        assertNull(offset(pointing = AltAz(20.0, 100.0), moon = AltAz(30.0, 104.0)))
    }

    @Test
    fun `allows a tilt mismatch up to the limit`() {
        assertNotNull(offset(pointing = AltAz(25.0, 100.0), moon = AltAz(30.0, 104.0)))
        assertNull(offset(pointing = AltAz(24.9, 100.0), moon = AltAz(30.0, 104.0)))
    }

    @Test
    fun `refuses a correction too large to be a compass error`() {
        assertNull(offset(moon = AltAz(30.0, 131.0)))
        assertNotNull(offset(moon = AltAz(30.0, 130.0)))
    }

    @Test
    fun `judges the size of the correction rather than the new total`() {
        // The change is 20 degrees; only the total passes 30.
        assertEquals(35.0, offset(moon = AltAz(30.0, 120.0), current = 15.0)!!, 1e-9)
    }

    @Test
    fun `wraps the correction across north`() {
        assertEquals(4.0, offset(AltAz(30.0, 358.0), AltAz(30.0, 2.0), 0.0)!!, 1e-9)
    }

    @Test
    fun `wraps a negative correction across north`() {
        assertEquals(-4.0, offset(AltAz(30.0, 2.0), AltAz(30.0, 358.0), 0.0)!!, 1e-9)
    }

    @Test
    fun `normalises the new total into minus 180 to 180`() {
        assertEquals(-175.0, offset(moon = AltAz(30.0, 110.0), current = 175.0)!!, 1e-9)
    }
}
