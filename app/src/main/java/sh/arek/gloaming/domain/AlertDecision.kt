package sh.arek.gloaming.domain

/**
 * Whether an aurora reading is worth interrupting someone for.
 *
 * Delivery is necessary but not sufficient: an app that delivers reliably and
 * decides badly gets uninstalled at 2 a.m. These rules are the difference
 * between an app people keep and one they mute.
 *
 * This is the Phase 1 subset - threshold, hysteresis and rate limiting. The
 * darkness and cloud gates arrive in Phase 2 once astronomy-engine and
 * Open-Meteo are wired in, and they sit in front of this rather than replacing
 * it.
 */
object AlertDecision {

    sealed interface Result

    /** Worth telling the user about. */
    data object Notify : Result

    /** Below the user's threshold. */
    data object BelowThreshold : Result

    /** Above threshold, but too soon after the last alert. */
    data object RateLimited : Result

    /** Above threshold, but not re-armed since the last alert. */
    data object NotReArmed : Result

    /**
     * The re-arm threshold as a percentage of the alert threshold.
     *
     * Deliberately well below it: a value hovering at the boundary must not
     * flip the app in and out of armed state, which is exactly how an app
     * notifies eleven times before dawn.
     */
    const val OFF_THRESHOLD_RATIO = 60

    /**
     * A jump this far above the previous alert is treated as a new, larger
     * event and escapes the quiet window. Rate limiting should keep a storm to
     * one notification, not hide it turning into a much bigger one.
     */
    const val ESCALATION_MARGIN = 25

    fun offThreshold(threshold: Int): Int = threshold * OFF_THRESHOLD_RATIO / 100

    /** True when activity has fallen far enough to allow a future alert. */
    fun shouldReArm(probability: Int, threshold: Int): Boolean =
        probability < offThreshold(threshold)

    fun evaluate(
        probability: Int,
        threshold: Int,
        lastAlertAt: Long?,
        lastAlertProbability: Int?,
        armed: Boolean,
        now: Long,
        minimumIntervalMillis: Long,
    ): Result {
        if (probability < threshold) return BelowThreshold
        if (!armed) return NotReArmed

        if (lastAlertAt != null && now - lastAlertAt < minimumIntervalMillis) {
            val escalated = lastAlertProbability != null &&
                probability >= lastAlertProbability + ESCALATION_MARGIN
            if (!escalated) return RateLimited
        }

        return Notify
    }
}
