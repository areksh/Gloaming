package sh.arek.gloaming.widget

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.HeldOutlook
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.Outlook
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

    /**
     * How long a held outlook is quoted for.
     *
     * The poller does not recompute the outlook, so the sentence ages from the
     * moment the screen wrote it; "clear from 22:00" is not worth repeating
     * hours after the cloud forecast it rested on has moved.
     */
    const val OUTLOOK_FRESH_MILLIS: Long = 3 * 60 * 60 * 1000L

    data class Text(
        val headline: String,
        val detail: String,
        /** Whether the reading has crossed the user's own bar. */
        val worthLooking: Boolean,
    )

    private fun holds(held: HeldOutlook, settings: UserSettings, state: AlertState, now: Long): Boolean =
        now < held.validUntil &&
            now < held.writtenAt + OUTLOOK_FRESH_MILLIS &&
            held.place == HeldOutlook.placeOf(settings) &&
            !newerCheck(held, state)

    /** Whether a check has landed since the one [held] was decided against. */
    private fun newerCheck(held: HeldOutlook, state: AlertState): Boolean {
        val latest = state.lastPollAt ?: return false
        val against = held.pollAt ?: return true
        return latest > against
    }

    /**
     * @param held tonight's outlook as the screen last worked it out. Its
     *   sentence replaces the freshness line while it is still tonight, for at
     *   most [OUTLOOK_FRESH_MILLIS], and only until the next check lands: the
     *   sentence was decided from one reading, and the figure above it - which
     *   is what the widget is for - moves on with each check.
     */
    fun of(settings: UserSettings, state: AlertState, held: HeldOutlook?, now: Long): Text {
        if (!settings.hasLocation) {
            return Text(NO_READING, "Tap to set your location", worthLooking = false)
        }

        val age = state.lastPollAt?.let { now - it }
        val probability = state.lastProbability
            ?.takeIf { age != null && age < STALE_AFTER_MILLIS }

        val detail = when {
            // Ahead of the outlook: a failed check means the reading above is
            // not current, and the sentence beside it would read as if it were.
            state.lastError != null -> "Last check failed"
            held != null && holds(held, settings, state, now) -> held.headline
            else -> describeAge(state.lastPollAt, now)
        }

        return Text(
            headline = probability?.let { "$it%" } ?: NO_READING,
            detail = detail,
            worthLooking = probability != null && probability >= settings.threshold,
        )
    }

    /**
     * What the screen should do with the held outlook, having just worked
     * tonight out.
     *
     * A live aurora reading ([Outlook.AuroraNow]) is never held: the figure
     * the widget shows is that reading, and a copy of it can only go stale
     * under a newer one. Anything held from before is taken away. Otherwise
     * [sentence] is held for the night ending at [nightEndMillis], against the
     * check in [state], and written again only when the words, the night, the
     * place or the check have changed - not on every tick. With no sentence,
     * night or place nothing changes.
     */
    fun update(
        before: HeldOutlook?,
        outlook: Outlook?,
        sentence: String?,
        nightEndMillis: Long?,
        settings: UserSettings,
        state: AlertState,
        now: Long,
    ): HeldUpdate {
        if (outlook is Outlook.AuroraNow) return if (before != null) HeldUpdate.Clear else HeldUpdate.Keep
        val place = HeldOutlook.placeOf(settings)
        if (sentence == null || nightEndMillis == null || place == null) return HeldUpdate.Keep
        val next = HeldOutlook(sentence, now, nightEndMillis, place, state.lastPollAt)
        return if (before != null && before.copy(writtenAt = now) == next) HeldUpdate.Keep else HeldUpdate.Write(next)
    }
}

/** What [WidgetContent.update] decided: leave the held outlook, take it away, or replace it. */
sealed interface HeldUpdate {
    data object Keep : HeldUpdate
    data object Clear : HeldUpdate
    data class Write(val held: HeldOutlook) : HeldUpdate
}
