package sh.arek.gloaming.ui

import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.domain.Recommendations
import sh.arek.gloaming.domain.TonightList

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

/** The button that deletes the record of "was it worth it?" answers. */
const val CLEAR_VERDICTS_TAG = "clear-verdicts"

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
    /**
     * The year ahead, already computed. Held rather than derived on the fly
     * because a year takes a quarter of a second to work out, which is far too
     * long to spend in a composition.
     */
    val calendar: List<SkyEvent> = emptyList(),
    /** True while that computation is in flight, so the tab can say so. */
    val calendarLoading: Boolean = false,
    /** What there is to see between now and dawn, once it has been worked out. */
    val tonight: TonightList? = null,
    /** True while a position is being read from the device. */
    val detectingLocation: Boolean = false,
    /**
     * What happened last time a position was asked for, when it is worth
     * saying: a refused permission, or no fix the phone was willing to give.
     */
    val locationNote: String? = null,
    /**
     * The calendar starred events are being written into, by name.
     *
     * Named because "your default calendar" on a phone with four of them is not
     * an answer. Null when there is none to name - no permission, nothing
     * selected, or a phone with no writable calendar at all, which is a real
     * configuration and one that would otherwise look exactly like a broken
     * feature.
     */
    val calendarName: String? = null,
    /** What went wrong last time calendar sync was asked for, when it did. */
    val calendarNote: String? = null,
    /**
     * An alert nobody has said anything about yet, if there is one.
     *
     * Decoded here rather than in the composition, so the card is a plain
     * function of resolved state like everything else on the screen.
     */
    val pendingVerdict: Conditions? = null,
    /**
     * What the verdicts so far suggest each calibrated setting should be.
     *
     * Shown whether or not automatic mode is on: with it off the app still
     * makes its case underneath each control and the user decides, which is
     * most of the value of calibration at none of its cost.
     */
    val recommendations: Recommendations? = null,
    /**
     * Every "was it worth it?" answer this phone holds, for the card that shows
     * them. The recommendations above are what they add up to; this is the
     * evidence, which somebody who wants to argue with a recommendation has
     * otherwise no way to see.
     */
    val verdicts: List<Verdict> = emptyList(),
)
