package sh.arek.gloaming.data

import android.content.SharedPreferences

/**
 * Tonight's opening sentence, kept for the places that cannot compute it.
 *
 * The widget redraws from a poll receiver, sometimes before first unlock, and
 * the outlook needs the whole of tonight's list to decide. Rather than redo
 * that there, the screen leaves its sentence behind when it works it out and
 * the widget quotes it.
 *
 * @property validUntil the end of the night it describes. Past that the
 *   sentence is about last night and is not shown.
 * @property place the cell it describes, so a move to somewhere else does not
 *   leave the old place's sentence on the widget.
 * @property pollAt the check (`AlertState.lastPollAt`) whose reading the
 *   sentence was decided from, or null where there had been none. Once a newer
 *   check has landed the sentence may contradict the reading shown above it,
 *   so it is no longer quoted.
 */
data class HeldOutlook(
    val headline: String,
    val writtenAt: Long,
    val validUntil: Long,
    /** The coarse place it was worked out for; see [placeOf]. */
    val place: String,
    val pollAt: Long?,
) {
    companion object {
        /**
         * The grid cell as a key, never the precise position: this store is
         * readable before first unlock. Null where there is no location.
         */
        fun placeOf(settings: UserSettings): String? =
            settings.cell?.let { "${it.latitude},${it.longitude}" }
    }
}

interface OutlookStore {
    fun read(): HeldOutlook?
    fun write(held: HeldOutlook)
    fun clear()
}

/**
 * Backed by device-encrypted storage, like the alert state beside it, because
 * the widget draws before first unlock. It holds a sentence and two times and
 * no position, so nothing about it needs the credential-encrypted store.
 */
class PreferencesOutlookStore(private val prefs: SharedPreferences) : OutlookStore {

    /**
     * The held outlook, or null where none is held - including one written by
     * a build that did not record the check it was decided against, which
     * cannot be told from one decided against an old check.
     */
    override fun read(): HeldOutlook? {
        val headline = prefs.getString(KEY_HEADLINE, null) ?: return null
        val writtenAt = prefs.getLong(KEY_WRITTEN_AT, UNSET).takeIf { it != UNSET } ?: return null
        val validUntil = prefs.getLong(KEY_VALID_UNTIL, UNSET).takeIf { it != UNSET } ?: return null
        val place = prefs.getString(KEY_PLACE, null) ?: return null
        val pollAt = prefs.getLong(KEY_POLL_AT, UNSET).takeIf { it != UNSET } ?: return null
        return HeldOutlook(headline, writtenAt, validUntil, place, pollAt.takeIf { it != NO_POLL })
    }

    override fun write(held: HeldOutlook) {
        prefs.edit()
            .putString(KEY_HEADLINE, held.headline)
            .putLong(KEY_WRITTEN_AT, held.writtenAt)
            .putLong(KEY_VALID_UNTIL, held.validUntil)
            .putString(KEY_PLACE, held.place)
            .putLong(KEY_POLL_AT, held.pollAt ?: NO_POLL)
            .apply()
    }

    /** Removes the outlook's own keys only: the file is shared with other stores. */
    override fun clear() {
        prefs.edit().apply { KEYS.forEach(::remove) }.apply()
    }

    private companion object {
        const val KEY_HEADLINE = "outlook_headline"
        const val KEY_WRITTEN_AT = "outlook_written_at"
        const val KEY_PLACE = "outlook_place"
        const val KEY_VALID_UNTIL = "outlook_valid_until"
        const val KEY_POLL_AT = "outlook_poll_at"
        val KEYS = listOf(KEY_HEADLINE, KEY_WRITTEN_AT, KEY_PLACE, KEY_VALID_UNTIL, KEY_POLL_AT)
        const val UNSET = Long.MIN_VALUE

        /** Written for "no check yet"; a check's time is never negative. */
        const val NO_POLL = -1L
    }
}
