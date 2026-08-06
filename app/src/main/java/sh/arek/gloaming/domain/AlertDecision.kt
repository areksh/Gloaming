package sh.arek.gloaming.domain

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.UserSettings

/**
 * Whether an aurora reading is worth interrupting someone for.
 *
 * Delivery is necessary but not sufficient: an app that delivers reliably and
 * decides badly gets uninstalled at 2 a.m. These rules are the difference
 * between an app people keep and one they mute.
 *
 * The checks are split in two because one of them costs a radio wake. Everything
 * decidable for free - the threshold, darkness, hysteresis, quiet hours and rate
 * limiting - happens in [evaluate]; [cloudGate] runs afterwards, and only if
 * everything else already said yes. Most polls stop before the network is
 * touched at all, which is the same reason the darkness gate exists.
 *
 * Both take [UserSettings] rather than a dozen loose parameters. The settings
 * object is what the user actually chose, and passing it whole means a new
 * preference does not churn every call site into a positional-argument hazard.
 */
object AlertDecision {

    sealed interface Result

    /** Worth telling the user about. */
    data object Notify : Result

    /** Below the user's threshold. */
    data object BelowThreshold : Result

    /** The sun is not far enough down for anything to be visible. */
    data object TooLight : Result

    /** Thicker cloud than the user is willing to go outside for. */
    data object TooCloudy : Result

    /** Inside the user's quiet hours, and not exceptional enough to break them. */
    data object InQuietHours : Result

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

    /**
     * The local probability at which an event may break quiet hours, for a user
     * who has opted into that.
     */
    const val EXCEPTIONAL_PROBABILITY = 80

    fun offThreshold(threshold: Int): Int = threshold * OFF_THRESHOLD_RATIO / 100

    /** True when activity has fallen far enough to allow a future alert. */
    fun shouldReArm(probability: Int, threshold: Int): Boolean =
        probability < offThreshold(threshold)

    /**
     * Every rule that can be applied without asking anyone anything.
     *
     * @param isDark whether the sun is far enough below the horizon, from
     *   [sh.arek.gloaming.astronomy.Twilight]. Offline, and it rules out most of
     *   the day on its own.
     * @param localMinuteOfDay minutes since local midnight, for quiet hours.
     *   Passed in rather than read here so this stays free of clocks and time
     *   zones and can be tested at any instant.
     */
    fun evaluate(
        probability: Int,
        isDark: Boolean,
        localMinuteOfDay: Int,
        settings: UserSettings,
        state: AlertState,
        now: Long,
        minimumIntervalMillis: Long,
    ): Result {
        // The user's own bar comes first: below it there is nothing to discuss,
        // and it is the answer most worth reporting back.
        if (probability < settings.threshold) return BelowThreshold

        if (!isDark) return TooLight
        if (!state.armed) return NotReArmed

        val quietHours = settings.quietHours
        if (quietHours != null && quietHours.contains(localMinuteOfDay)) {
            val exceptional = settings.quietHoursOverride &&
                probability >= EXCEPTIONAL_PROBABILITY
            if (!exceptional) return InQuietHours
        }

        val lastAlertAt = state.lastAlertAt
        if (lastAlertAt != null && now - lastAlertAt < minimumIntervalMillis) {
            val lastProbability = state.lastAlertProbability
            val escalated = lastProbability != null &&
                probability >= lastProbability + ESCALATION_MARGIN
            if (!escalated) return RateLimited
        }

        return Notify
    }

    /**
     * The one gate that needs a network round trip, so it runs last and only
     * when everything else has already said yes.
     *
     * A null [cover] means the forecast could not be reached, and that notifies:
     * the alert says cloud cover is unknown rather than being suppressed
     * silently. An app that goes quiet whenever a third party is down is
     * indistinguishable from one that is broken.
     *
     * Judged on total cover. The layers are carried for the notification text -
     * high cirrus at 60% still shows a strong aurora and low stratus does not -
     * but weighting them is guesswork until there is a season of real data to
     * calibrate against.
     */
    fun cloudGate(cover: CloudCover?, settings: UserSettings): Result {
        if (!settings.cloudGateEnabled) return Notify
        if (cover == null) return Notify
        return if (cover.total > settings.cloudTolerance) TooCloudy else Notify
    }
}
