package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking early enough that an inexact alarm still arrives in time.
 *
 * Measured over thirteen consecutive overnight wakes on a Pixel 8a, exempt and
 * in the active bucket: every one landed between 1.25 and 1.99 times the
 * interval asked for, median 1.78. That is not noise, it is the shape of
 * `setAndAllowWhileIdle` - the system attaches a batching window of about three
 * quarters of the interval and fires near the end of it.
 *
 * It cost a real alert. A pass wake asked for 28 minutes at 02:17 arrived at
 * 03:11, so an alert meant to give half an hour's notice of ALOS (DAICHI)
 * arrived two minutes before it rose.
 */
class DeliveryTest {

    private val minute = 60_000L
    private val now = 1_000_000L

    @Test
    fun `asks for half the time left, so the slack still fits inside it`() {
        assertEquals(now + 15 * minute, Delivery.askAt(now + 30 * minute, now))
    }

    /**
     * The property that matters, stated as the measurement rather than as the
     * arithmetic: even at the worst ratio ever seen, the wake lands in time.
     */
    @Test
    fun `even the worst measured slack arrives before the deadline`() {
        val deadline = now + 30 * minute
        val asked = Delivery.askAt(deadline, now) - now
        val worstDelivery = now + (asked * 199) / 100

        assertTrue(
            "asked ${asked / minute}m, delivered at ${(worstDelivery - now) / minute}m, " +
                "deadline ${(deadline - now) / minute}m",
            worstDelivery < deadline,
        )
    }

    /** Repeated approach converges on the deadline instead of stopping short of it. */
    @Test
    fun `successive asks close on the deadline`() {
        val deadline = now + 30 * minute
        var at = now
        repeat(4) { at = Delivery.askAt(deadline, at) + (Delivery.askAt(deadline, at) - at) * 8 / 10 }

        assertTrue("stalled at ${(at - now) / minute}m short of the deadline", at > now + 25 * minute)
    }

    @Test
    fun `a deadline already past asks for the soonest wake there is`() {
        assertEquals(now, Delivery.askAt(now - 5 * minute, now))
        assertEquals(now, Delivery.askAt(now, now))
    }

    /**
     * Never returns something the caller has to guard: a wake in the past would
     * arm an alarm for a moment that has gone.
     */
    @Test
    fun `never asks for a moment that has passed`() {
        for (offset in -10..60) {
            assertTrue(Delivery.askAt(now + offset * minute, now) >= now)
        }
    }
}
