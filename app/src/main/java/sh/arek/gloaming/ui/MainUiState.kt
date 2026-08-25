package sh.arek.gloaming.ui

import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings

/**
 * The watch switch, which is an action rather than a setting and so is the one
 * control worth reaching by tag: every other switch is identifiable by the
 * setting it sits beside.
 */
const val WATCH_SWITCH_TAG = "watch-switch"

/**
 * The live-following switch, reached by tag for the same reason: it sits beside
 * a sentence rather than a value, so there is no "Space station: 6 hours" style
 * label to find it by.
 */
const val FOLLOW_STATION_SWITCH_TAG = "follow-station-switch"

/** The precise-before-unlock switch, which sits beside a sentence rather than a value. */
const val PRECISE_BEFORE_UNLOCK_TAG = "precise-before-unlock-switch"

/** The log buttons, which are actions rather than settings. */
const val EXPORT_LOG_TAG = "export-log"

const val CLEAR_LOG_TAG = "clear-log"

/** Everything the screen needs, already resolved. */
data class MainUiState(
    val settings: UserSettings,
    val alertState: AlertState,
    val latitudeInput: String,
    val longitudeInput: String,
    val quietFromInput: String,
    val quietUntilInput: String,
    val batteryExempt: Boolean,
    val notificationsAllowed: Boolean,
    val nowMillis: Long,
    /** True while a check the user asked for is in flight. */
    val checking: Boolean = false,
    /**
     * How dark it is where the observer is, or null when no location is set.
     * Drives the sentence explaining why the app may not have checked recently.
     */
    val darkness: Darkness? = null,
    /** Whether the system is in battery saver right now. */
    val powerSaveMode: Boolean = false,
    /** Whether the foreground watch is running. */
    val watching: Boolean = false,
    /** How much of the app's own log is on disk, for the log card. */
    val logSizeBytes: Long = 0L,
)
