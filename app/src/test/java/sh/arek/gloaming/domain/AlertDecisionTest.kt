package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDecisionTest {

    private val hour = 3_600_000L
    private val now = 10 * 24 * hour

    private fun decide(
        probability: Int,
        threshold: Int = 30,
        lastAlertAt: Long? = null,
        lastAlertProbability: Int? = null,
        armed: Boolean = true,
        minimumInterval: Long = 6 * hour,
    ) = AlertDecision.evaluate(
        probability = probability,
        threshold = threshold,
        lastAlertAt = lastAlertAt,
        lastAlertProbability = lastAlertProbability,
        armed = armed,
        now = now,
        minimumIntervalMillis = minimumInterval,
    )

    @Test
    fun `alerts when probability crosses the threshold`() {
        assertEquals(AlertDecision.Notify, decide(probability = 45, threshold = 30))
    }

    @Test
    fun `stays quiet below the threshold`() {
        assertEquals(AlertDecision.BelowThreshold, decide(probability = 20, threshold = 30))
    }

    @Test
    fun `treats the threshold itself as a crossing`() {
        assertEquals(AlertDecision.Notify, decide(probability = 30, threshold = 30))
    }

    // A storm is one event, not forty. Kp oscillating around a single threshold
    // is how an app notifies eleven times before dawn.
    @Test
    fun `does not re-alert within the minimum interval`() {
        val decision = decide(
            probability = 45,
            lastAlertAt = now - 2 * hour,
            lastAlertProbability = 40,
        )

        assertEquals(AlertDecision.RateLimited, decision)
    }

    @Test
    fun `alerts again once the minimum interval has passed`() {
        val decision = decide(
            probability = 45,
            lastAlertAt = now - 7 * hour,
            lastAlertProbability = 40,
        )

        assertEquals(AlertDecision.Notify, decision)
    }

    /**
     * Hysteresis. Once alerted, the app does not re-arm until activity drops
     * clearly below the threshold, so a value hovering at the boundary produces
     * one alert rather than a stream of them.
     */
    @Test
    fun `stays disarmed while activity remains high`() {
        assertEquals(AlertDecision.NotReArmed, decide(probability = 45, armed = false))
    }

    @Test
    fun `re-arms once activity falls below the off threshold`() {
        // Off threshold is deliberately lower than on, so the gap has to be
        // crossed properly rather than grazed.
        assertEquals(30 * AlertDecision.OFF_THRESHOLD_RATIO / 100, AlertDecision.offThreshold(30))
        assertEquals(true, AlertDecision.shouldReArm(probability = 10, threshold = 30))
        assertEquals(false, AlertDecision.shouldReArm(probability = 25, threshold = 30))
    }

    @Test
    fun `a first ever alert is not rate limited`() {
        assertEquals(AlertDecision.Notify, decide(probability = 45, lastAlertAt = null))
    }

    // Rate limiting must not hide a genuinely bigger event. A jump well above
    // the last alert is worth interrupting for even inside the quiet window.
    @Test
    fun `alerts inside the interval when activity climbs substantially`() {
        val decision = decide(
            probability = 80,
            lastAlertAt = now - 1 * hour,
            lastAlertProbability = 35,
        )

        assertEquals(AlertDecision.Notify, decision)
    }

    @Test
    fun `does not alert inside the interval for a marginal increase`() {
        val decision = decide(
            probability = 42,
            lastAlertAt = now - 1 * hour,
            lastAlertProbability = 35,
        )

        assertEquals(AlertDecision.RateLimited, decision)
    }
}
