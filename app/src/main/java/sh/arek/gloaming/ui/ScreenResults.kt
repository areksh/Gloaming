package sh.arek.gloaming.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.GridCell
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitesDocument

/** Tonight as worked out off the main thread: the list, its outlook and its timeline. */
class WorkedNight(
    val list: TonightList,
    val elements: SatellitesDocument?,
    val report: OutlookReport?,
    val forecast: List<HourlyCover>,
    val timeline: Timeline?,
)

/**
 * What the year ahead is worked out from. The calendar is deterministic, so
 * the same key is the same answer and is never worked out twice.
 *
 * The day is there because "today" - the outlined night, the stubs for nights
 * gone, the events still ahead - is fixed when it is worked out, and a screen
 * left open past midnight would otherwise go on a day behind.
 */
data class CalendarKey(
    val latitude: Double?,
    val longitude: Double?,
    /** [localDay] of the screen's clock. */
    val day: Long,
    /** [ScreenResults.recalculation] when the key was made. */
    val recalculation: Int,
)

/**
 * How often tonight is worked out again while the screen is open.
 *
 * Fifteen minutes. Passes move on and the darkness window turns over, but
 * recomputing on every minute tick would run the propagator over the whole
 * catalogue sixty times an hour for an answer that changes slowly.
 */
const val TONIGHT_STALE_MILLIS = 15 * 60_000L

/**
 * What tonight is worked out from, beyond the documents on the device.
 *
 * The calendar it is worked out against is a key rather than the list, so
 * Tonight waits for that calendar instead of being worked out first against an
 * empty one and then again. The settings are the ones the night depends on
 * that nothing else here keys on: without them the card went on quoting a new
 * cloud tolerance against a gap worked out from the old one.
 */
data class TonightKey(
    val calendar: CalendarKey,
    val latitude: Double?,
    val longitude: Double?,
    val cloudTolerance: Int,
    val minimumPassElevationDegrees: Int,
    val threshold: Int,
    /** [ScreenResults.forecastVersion]: a fetched forecast that changed what is held. */
    val forecastVersion: Int,
    /** A check brings a new reading and possibly new elements, both of which the outlook reads. */
    val lastPollAt: Long?,
    /** The screen's clock in [TONIGHT_STALE_MILLIS] stretches, because "tonight" moves as the evening does. */
    val staleSlot: Long,
    /** [ScreenResults.recalculation] when the key was made; the same as [calendar]'s. */
    val recalculation: Int,
) {
    /** The place the night is about, at the grid's resolution. */
    val cell: GridCell?
        get() = if (latitude != null && longitude != null) GridCell.of(latitude, longitude) else null
}

/**
 * The year ahead and tonight, held for the life of the process rather than
 * of a screen.
 *
 * Worked out in [scope], not in a composition. Held in the composition they
 * went with every rotation and were started again from nothing, and a night
 * takes the better part of ten seconds to work out on a phone, so a turn of
 * the phone blanked Tonight for that long. Here a recreated screen finds the
 * result, or the run still going, where it left it.
 *
 * Each `ensure` call is cheap to repeat: work starts only for a key that
 * differs from both the one shown and the one in flight, so a recomposition,
 * a rotation or another open with the same inputs starts nothing. A new key
 * cancels the run in flight and replaces it. The SGP4 sweep has no suspension
 * point and so goes on to the end regardless, which is why every result is
 * published only if its run is still the newest: a late answer for old inputs
 * is dropped rather than shown over the new one.
 *
 * The properties are Compose state, so the screen follows them. They are
 * written from the caller's thread and from [scope]'s, always under one lock
 * and each run's results in one snapshot, so a frame never shows a list from
 * one run beside an outlook from another.
 */
class ScreenResults(private val scope: CoroutineScope) {

    var calendar by mutableStateOf(emptyList<SkyEvent>())
        private set

    /** The month strips for [calendar], which land after it. */
    var months by mutableStateOf(emptyList<MonthStrip>())
        private set

    /** True while the events of the year ahead are being worked out. */
    var calendarLoading by mutableStateOf(false)
        private set

    var tonight by mutableStateOf<TonightList?>(null)
        private set
    var elements by mutableStateOf<SatellitesDocument?>(null)
        private set
    var outlook by mutableStateOf<OutlookReport?>(null)
        private set
    var hourly by mutableStateOf(emptyList<HourlyCover>())
        private set
    var timeline by mutableStateOf<Timeline?>(null)
        private set

    /**
     * True from the moment tonight is asked for under a new key until it is
     * published, including the wait for its calendar.
     */
    var tonightUpdating by mutableStateOf(false)
        private set

    /** Bumped by [recalculate]; both keys carry it. */
    var recalculation by mutableIntStateOf(0)
        private set

    /** Bumped by [forecastChanged]; [TonightKey] carries it. */
    var forecastVersion by mutableIntStateOf(0)
        private set

    private val tonightCount = AtomicInteger()
    private val calendarCount = AtomicInteger()

    /** How many times tonight's work has been started, for tests. */
    val tonightRuns: Int get() = tonightCount.get()

    /** How many times the calendar's work has been started, for tests. */
    val calendarRuns: Int get() = calendarCount.get()

    private val lock = Any()

    /** The calendar key whose events and months are both published. */
    private var calendarShown: CalendarKey? = null
    private var calendarInFlight: CalendarKey? = null
    private var calendarJob: Job? = null
    private var calendarGeneration = 0

    /** Which key's events [calendar] holds, for a tonight run waiting on them. */
    private val landed = MutableStateFlow<Landed?>(null)

    private class Landed(val key: CalendarKey, val events: List<SkyEvent>)

    private var tonightShown: TonightKey? = null
    private var tonightInFlight: TonightKey? = null
    private var tonightJob: Job? = null
    private var tonightGeneration = 0

    /** The grid cell the night on show was worked out for. */
    private var nightCell: GridCell? = null

    /**
     * Works both out again from scratch, even with nothing they depend on
     * changed, by giving the next keys a new [recalculation]. What Check now
     * does once its check has landed.
     */
    fun recalculate() {
        recalculation++
    }

    /** A fetched forecast changed what is held, so tonight is worked out again. */
    fun forecastChanged() {
        forecastVersion++
    }

    /**
     * Works out the year ahead for [key] unless it is already shown or under
     * way. The [events] are published as soon as they are ready and the
     * [months] after them: the screen is usable without the strips, which take
     * as long again. The old strips go at once, because they may be for
     * another place.
     */
    fun ensureCalendar(
        key: CalendarKey,
        events: suspend () -> List<SkyEvent>,
        months: suspend (List<SkyEvent>) -> List<MonthStrip>,
    ) {
        synchronized(lock) {
            if (key == calendarInFlight || key == calendarShown) return
            calendarJob?.cancel()
            val generation = ++calendarGeneration
            calendarInFlight = key
            calendarShown = null
            Snapshot.withMutableSnapshot {
                calendarLoading = true
                this.months = emptyList()
            }
            calendarJob = scope.launch {
                calendarCount.incrementAndGet()
                val worked = events()
                synchronized(lock) {
                    if (generation != calendarGeneration) return@launch
                    Snapshot.withMutableSnapshot {
                        calendar = worked
                        calendarLoading = false
                    }
                    landed.value = Landed(key, worked)
                }
                val strips = months(worked)
                synchronized(lock) {
                    if (generation != calendarGeneration) return@launch
                    this@ScreenResults.months = strips
                    calendarShown = key
                    calendarInFlight = null
                    calendarJob = null
                }
            }
        }
    }

    /**
     * Works out tonight for [key] unless it is already shown or under way.
     *
     * The [work] is not started until the calendar for [TonightKey.calendar]
     * has landed, and is handed those events, so the night and its opening
     * sentence are never worked out against a year that is not there yet. A
     * key for another grid cell takes the old place's night down at once
     * rather than leaving "clear from 22:00" up about somewhere else; a
     * position wandering inside the cell keeps it up until the new one lands.
     * A null night takes every result down.
     */
    fun ensureTonight(key: TonightKey, work: suspend (List<SkyEvent>) -> WorkedNight?) {
        synchronized(lock) {
            if (key == tonightInFlight) return
            if (key == tonightShown) {
                // Back to what is on show, so whatever was under way is moot.
                if (tonightInFlight != null) {
                    tonightJob?.cancel()
                    tonightGeneration++
                    tonightInFlight = null
                    tonightJob = null
                    tonightUpdating = false
                }
                return
            }
            tonightJob?.cancel()
            val generation = ++tonightGeneration
            tonightInFlight = key
            val moved = nightCell != null && key.cell != nightCell
            if (moved) {
                nightCell = null
                tonightShown = null
            }
            Snapshot.withMutableSnapshot {
                if (moved) {
                    tonight = null
                    outlook = null
                    hourly = emptyList()
                    timeline = null
                }
                tonightUpdating = true
            }
            tonightJob = scope.launch {
                val events = landed.filterNotNull().first { it.key == key.calendar }.events
                tonightCount.incrementAndGet()
                val worked = work(events)
                synchronized(lock) {
                    if (generation != tonightGeneration) return@launch
                    Snapshot.withMutableSnapshot {
                        tonight = worked?.list
                        elements = worked?.elements
                        outlook = worked?.report
                        hourly = worked?.forecast.orEmpty()
                        timeline = worked?.timeline
                        tonightUpdating = false
                    }
                    nightCell = key.cell
                    tonightShown = key
                    tonightInFlight = null
                    tonightJob = null
                }
            }
        }
    }
}
