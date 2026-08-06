package sh.arek.gloaming.widget

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.ui.describeAge

/**
 * What the home-screen widget says.
 *
 * Pure, and separate from the widget itself, because the words are the whole
 * feature and `RemoteViews` is not testable without a device. The widget
 * answers "is it worth checking?" without the user opening anything, which is
 * the question notifications are a poor fit for.
 */
object WidgetContent {

    /** Shown when there is no reading worth presenting. */
    const val NO_READING = "--"

    /**
     * Past this, a reading is not shown at all.
     *
     * A phone that has been offline for a week showing "5%" is worse than one
     * showing nothing: the reassurance is exactly what stops someone looking
     * out of the window, and it would be reassurance about last Tuesday.
     */
    const val STALE_AFTER_MILLIS: Long = 12 * 60 * 60 * 1000L

    data class Text(
        val headline: String,
        val detail: String,
        /** Whether the reading has crossed the user's own bar. */
        val worthLooking: Boolean,
    )

    fun of(settings: UserSettings, state: AlertState, now: Long): Text {
        if (!settings.hasLocation) {
            return Text(NO_READING, "Tap to set your location", worthLooking = false)
        }

        val age = state.lastPollAt?.let { now - it }
        val probability = state.lastProbability
            ?.takeIf { age != null && age < STALE_AFTER_MILLIS }

        val detail = when {
            state.lastError != null -> "Last check failed"
            else -> describeAge(state.lastPollAt, now)
        }

        return Text(
            headline = probability?.let { "$it%" } ?: NO_READING,
            detail = detail,
            worthLooking = probability != null && probability >= settings.threshold,
        )
    }
}
