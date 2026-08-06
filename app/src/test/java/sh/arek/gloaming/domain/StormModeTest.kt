package sh.arek.gloaming.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.CloudCover

class StormModeTest {

    private val dark = Darkness.Now(endsAt = null)
    private val clear = CloudCover(total = 10, low = 0, mid = 0, high = 10)

    private fun shouldRun(
        running: Boolean = false,
        probability: Int = 90,
        darkness: Darkness = dark,
        cloud: CloudCover? = clear,
    ) = StormMode.shouldRun(running, probability, darkness, cloud)

    @Test
    fun `starts when a big display is overhead, dark and clear`() {
        assertTrue(shouldRun(probability = StormMode.ENTER_PROBABILITY))
    }

    @Test
    fun `stays off for anything short of remarkable`() {
        assertFalse(shouldRun(probability = StormMode.ENTER_PROBABILITY - 1))
    }

    /**
     * Storm mode is a persistent notification and a service polling every
     * couple of minutes. That is only justified while somebody could be
     * standing outside looking north, so daylight ends it immediately.
     */
    @Test
    fun `never starts in daylight`() {
        assertFalse(shouldRun(darkness = Darkness.Later(startsAt = 1L, endsAt = null)))
        assertFalse(shouldRun(darkness = Darkness.None))
    }

    @Test
    fun `stops as soon as it gets light, however big the display`() {
        assertFalse(shouldRun(running = true, probability = 100, darkness = Darkness.None))
    }

    @Test
    fun `never starts under cloud`() {
        assertFalse(shouldRun(cloud = CloudCover(total = 90, low = 90, mid = 0, high = 0)))
    }

    /**
     * Unknown cloud does not start storm mode, unlike the alert gate, which
     * notifies on unknown. The costs are not comparable: a notification the
     * user can dismiss against a foreground service running all night on a sky
     * nobody has confirmed is clear.
     */
    @Test
    fun `does not start on an unknown sky`() {
        assertFalse(shouldRun(cloud = null))
    }

    // Once running, a lost forecast should not end an event that is otherwise
    // still going. Stopping on every network blip would flicker the service.
    @Test
    fun `keeps running through a lost forecast`() {
        assertTrue(shouldRun(running = true, cloud = null))
    }

    /**
     * Hysteresis again, and for the same reason as the alert: a probability
     * hovering at the entry threshold would otherwise start and stop the
     * service - and its notification - every couple of minutes.
     */
    @Test
    fun `keeps running while activity stays above the lower bar`() {
        assertTrue(shouldRun(running = true, probability = StormMode.LEAVE_PROBABILITY))
    }

    @Test
    fun `stops once activity falls below the lower bar`() {
        assertFalse(shouldRun(running = true, probability = StormMode.LEAVE_PROBABILITY - 1))
    }

    @Test
    fun `does not restart on a value that only clears the lower bar`() {
        assertFalse(shouldRun(running = false, probability = StormMode.LEAVE_PROBABILITY))
    }

    @Test
    fun `stops when the sky closes in`() {
        val cloud = CloudCover(total = 100, low = 100, mid = 0, high = 0)

        assertFalse(shouldRun(running = true, cloud = cloud))
    }

    @Test
    fun `the entry bar is above the exit bar`() {
        assertTrue(StormMode.ENTER_PROBABILITY > StormMode.LEAVE_PROBABILITY)
    }
}
