package sh.arek.gloaming.ui

import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.Recommendations
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitesDocument

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
     * What the app has downloaded, and how old what it holds is.
     *
     * Resolved here like everything else on the screen. Both halves matter and
     * for different reasons: a fortnight went by in which the satellite
     * elements were never republished, every conditional GET answered 304, and
     * the app went on predicting passes from a two-week-old catalogue with
     * nothing anywhere on a screen to say so.
     */
    val downloads: List<DownloadRow> = emptyList(),
    /** What has a request open right now, for the line beside the progress bar. */
    val downloading: List<String> = emptyList(),
    /**
     * Why the last attempt at the satellite elements failed, where that is known.
     *
     * Resolved here so Tonight can tell a fresh install apart from a broken
     * download. In memory for one process, so a background poll that
     * failed overnight leaves nothing to quote - which is a third case again,
     * and one [describeMissingElements] has words for.
     */
    val elementsFailure: String? = null,
    /**
     * How dark it is where the observer is, or null when no location is set.
     * Drives the sentence explaining why the app may not have checked recently.
     */
    val darkness: Darkness? = null,
    /** Whether the system is in battery saver right now. */
    val powerSaveMode: Boolean = false,
    /** Whether the foreground watch is running. */
    val watching: Boolean = false,
    /** When the running watch stops on its own, where that is known. */
    val watchingUntil: Long? = null,
    /** How much of the app's own log is on disk, for the log card. */
    val logSizeBytes: Long = 0L,
    /**
     * The year ahead, already computed. Held rather than derived on the fly
     * because a year takes several seconds to work out on a phone, which is
     * far too long to spend in a composition.
     */
    val calendar: List<SkyEvent> = emptyList(),
    /**
     * A strip of shaded nights for each month of that year, worked out from
     * the same events so the two never disagree about which days have one,
     * and landing after them.
     */
    val months: List<MonthStrip> = emptyList(),
    /** True while that computation is in flight, so the tab can say so. */
    val calendarLoading: Boolean = false,
    /** What there is to see between now and dawn, once it has been worked out. */
    val tonight: TonightList? = null,
    /**
     * True while tonight is being worked out, which takes several seconds on
     * a phone, so Tonight can say so rather than sit empty or stale.
     */
    val tonightUpdating: Boolean = false,
    /**
     * The satellite elements [tonight] was worked out from, so Find can follow
     * a pass across the sky from the same copy rather than reading the cache
     * again on the main thread.
     */
    val elements: SatellitesDocument? = null,
    /**
     * Tonight in one sentence, decided from [tonight] and the same held
     * documents, or null until it has been worked out.
     */
    val outlook: OutlookReport? = null,
    /** The held hourly cloud forecast [outlook] was decided against. */
    val hourly: List<HourlyCover> = emptyList(),
    /**
     * Tonight from sunset to sunrise, laid out from [tonight] and [outlook],
     * or null until both are worked out or where the sun does not set.
     */
    val timeline: Timeline? = null,
    /** True while a position is being read from the device. */
    val detectingLocation: Boolean = false,
    /**
     * What happened last time a position was asked for, when it is worth
     * saying: a refused permission, or no fix the phone was willing to give.
     */
    val locationNote: String? = null,
    /**
     * Whether Android already lets the position be read in the background, so
     * the card can warn about the "all the time" prompt only while there is
     * still one to come.
     */
    val backgroundLocationAllowed: Boolean = false,
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
