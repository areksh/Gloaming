package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.QuietHours
import sh.arek.gloaming.data.UserSettings

class AlertDecisionTest {

    private val hour = 3_600_000L
    private val now = 10 * 24 * hour

    /** Two in the morning: dark, and outside any quiet hours unless one is set. */
    private val smallHours = 2 * 60

    private fun decide(
        probability: Int,
        threshold: Int = 30,
        isDark: Boolean = true,
        localMinuteOfDay: Int = smallHours,
        quietHours: QuietHours? = null,
        quietHoursOverride: Boolean = false,
        lastAlertAt: Long? = null,
        lastAlertProbability: Int? = null,
        armed: Boolean = true,
        minimumInterval: Long = 6 * hour,
    ) = AlertDecision.evaluate(
        probability = probability,
        isDark = isDark,
        localMinuteOfDay = localMinuteOfDay,
        settings = UserSettings(
            threshold = threshold,
            quietHours = quietHours,
            quietHoursOverride = quietHoursOverride,
        ),
        state = AlertState(
            lastAlertAt = lastAlertAt,
            lastAlertProbability = lastAlertProbability,
            armed = armed,
        ),
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

    /**
     * The darkness gate: free, offline, and it eliminates most false alerts on
     * its own. A 90% probability at noon is not something to wake anyone for.
     */
    @Test
    fun `stays quiet while the sun is up, whatever the probability`() {
        assertEquals(AlertDecision.TooLight, decide(probability = 95, isDark = false))
    }

    @Test
    fun `checks the threshold before the sky`() {
        // Below threshold in daylight reports the threshold, which is the more
        // useful of the two true answers - the sky will be dark again tonight.
        assertEquals(
            AlertDecision.BelowThreshold,
            decide(probability = 5, isDark = false),
        )
    }

    @Test
    fun `stays quiet during quiet hours`() {
        val decision = decide(
            probability = 60,
            localMinuteOfDay = 2 * 60,
            quietHours = QuietHours(23 * 60, 7 * 60),
        )

        assertEquals(AlertDecision.InQuietHours, decision)
    }

    @Test
    fun `alerts outside quiet hours`() {
        val decision = decide(
            probability = 60,
            localMinuteOfDay = 22 * 60,
            quietHours = QuietHours(23 * 60, 7 * 60),
        )

        assertEquals(AlertDecision.Notify, decision)
    }

    /**
     * The override is opt-in and off by default. The user decides what is worth
     * being woken for; the app does not decide on their behalf.
     */
    @Test
    fun `stays quiet through an exceptional event unless asked not to`() {
        val decision = decide(
            probability = 95,
            localMinuteOfDay = 2 * 60,
            quietHours = QuietHours(23 * 60, 7 * 60),
            quietHoursOverride = false,
        )

        assertEquals(AlertDecision.InQuietHours, decision)
    }

    @Test
    fun `breaks quiet hours for an exceptional event when asked to`() {
        val decision = decide(
            probability = AlertDecision.EXCEPTIONAL_PROBABILITY,
            localMinuteOfDay = 2 * 60,
            quietHours = QuietHours(23 * 60, 7 * 60),
            quietHoursOverride = true,
        )

        assertEquals(AlertDecision.Notify, decision)
    }

    @Test
    fun `keeps quiet hours for an ordinary event even with the override on`() {
        val decision = decide(
            probability = AlertDecision.EXCEPTIONAL_PROBABILITY - 1,
            localMinuteOfDay = 2 * 60,
            quietHours = QuietHours(23 * 60, 7 * 60),
            quietHoursOverride = true,
        )

        assertEquals(AlertDecision.InQuietHours, decision)
    }

    private fun cloudGate(
        cover: CloudCover?,
        tolerance: Int = UserSettings.DEFAULT_CLOUD_TOLERANCE,
        enabled: Boolean = true,
    ) = AlertDecision.cloudGate(
        cover,
        UserSettings(cloudTolerance = tolerance, cloudGateEnabled = enabled),
    )

    private fun overcast(total: Int) = CloudCover(total, low = total, mid = 0, high = 0)

    @Test
    fun `stays quiet under cloud thicker than the user will tolerate`() {
        assertEquals(AlertDecision.TooCloudy, cloudGate(overcast(95), tolerance = 80))
    }

    @Test
    fun `alerts under cloud the user will tolerate`() {
        assertEquals(AlertDecision.Notify, cloudGate(overcast(40), tolerance = 80))
    }

    @Test
    fun `treats the tolerance itself as acceptable`() {
        assertEquals(AlertDecision.Notify, cloudGate(overcast(80), tolerance = 80))
    }

    /**
     * The stated rule: if the forecast is unreachable, notify anyway and say
     * cloud cover is unknown, rather than suppressing silently. An app that
     * goes quiet when a third party is down is indistinguishable from one that
     * is broken.
     */
    @Test
    fun `alerts when cloud cover is unknown`() {
        assertEquals(AlertDecision.Notify, cloudGate(cover = null))
    }

    @Test
    fun `alerts under any cloud when the gate is switched off`() {
        assertEquals(AlertDecision.Notify, cloudGate(overcast(100), enabled = false))
    }
}
