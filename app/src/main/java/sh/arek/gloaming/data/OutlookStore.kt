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
 */
data class HeldOutlook(val headline: String, val writtenAt: Long, val validUntil: Long)

interface OutlookStore {
    fun read(): HeldOutlook?
    fun write(held: HeldOutlook)
}

/**
 * Backed by device-encrypted storage, like the alert state beside it, because
 * the widget draws before first unlock. It holds a sentence and two times and
 * no position, so nothing about it needs the credential-encrypted store.
 */
class PreferencesOutlookStore(private val prefs: SharedPreferences) : OutlookStore {

    override fun read(): HeldOutlook? {
        val headline = prefs.getString(KEY_HEADLINE, null) ?: return null
        val writtenAt = prefs.getLong(KEY_WRITTEN_AT, UNSET).takeIf { it != UNSET } ?: return null
        val validUntil = prefs.getLong(KEY_VALID_UNTIL, UNSET).takeIf { it != UNSET } ?: return null
        return HeldOutlook(headline, writtenAt, validUntil)
    }

    override fun write(held: HeldOutlook) {
        prefs.edit()
            .putString(KEY_HEADLINE, held.headline)
            .putLong(KEY_WRITTEN_AT, held.writtenAt)
            .putLong(KEY_VALID_UNTIL, held.validUntil)
            .apply()
    }

    private companion object {
        const val KEY_HEADLINE = "outlook_headline"
        const val KEY_WRITTEN_AT = "outlook_written_at"
        const val KEY_VALID_UNTIL = "outlook_valid_until"
        const val UNSET = Long.MIN_VALUE
    }
}
