package sh.arek.gloaming.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.GridCell
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.TonightSnapshot
import sh.arek.gloaming.data.TonightSnapshots
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.usable
import sh.arek.gloaming.domain.AnnouncedPass
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
 * How finely the position is keyed, in degrees: about a kilometre.
 *
 * The work itself uses the exact position. An approximate fix wanders a few
 * hundred metres between one ask and the next, and keying on it exactly made
 * every wander - a rotation in automatic mode asks again - work the year and
 * the night out from scratch, some twenty seconds, for passes and rise times
 * that move by seconds over that distance. A wander across a step boundary
 * still counts, as any rounding must; across a step it is rare.
 */
const val KEY_PRECISION_DEGREES = 0.01

/** [degrees] to the nearest [KEY_PRECISION_DEGREES]. */
private fun keyed(degrees: Double?): Double? =
    degrees?.let { Math.round(it / KEY_PRECISION_DEGREES) * KEY_PRECISION_DEGREES }

/**
 * What the year ahead is worked out from. The calendar is deterministic, so
 * the same key is the same answer and is never worked out twice. Made with
 * [of], which holds every setting the calendar reads.
 *
 * The day is there because "today" - the outlined night, the stubs for nights
 * gone, the events still ahead - is fixed when it is worked out, and a screen
 * left open past midnight would otherwise go on a day behind.
 */
data class CalendarKey(
    /** The position, to [KEY_PRECISION_DEGREES]. */
    val latitude: Double?,
    val longitude: Double?,
    /** [localDay] of the screen's clock. */
    val day: Long,
    /** [ScreenResults.recalculation] when the key was made. */
    val recalculation: Int,
    /** The comet settings, which decide which comets the calendar lists and how. */
    val cometMagnitudeThreshold: Int,
    val minimumCometAltitudeDegrees: Int,
    val cometObservationStaleDays: Int,
    val cometMoonTolerance: Int,
) {
    companion object {
        fun of(settings: UserSettings, day: Long, recalculation: Int) = CalendarKey(
            latitude = keyed(settings.latitude),
            longitude = keyed(settings.longitude),
            day = day,
            recalculation = recalculation,
            cometMagnitudeThreshold = settings.cometMagnitudeThreshold,
            minimumCometAltitudeDegrees = settings.minimumCometAltitudeDegrees,
            cometObservationStaleDays = settings.cometObservationStaleDays,
            cometMoonTolerance = settings.cometMoonTolerance,
        )
    }
}

/**
 * How often tonight is worked out again while the screen is open.
 *
 * Fifteen minutes. Passes move on and the darkness window turns over, but
 * recomputing on every minute tick would run the propagator over the whole
 * catalogue sixty times an hour for an answer that changes slowly.
 */
const val TONIGHT_STALE_MILLIS = 15 * 60_000L

/**
 * What tonight is worked out from, beyond the documents on the device. Made
 * with [of].
 *
 * Tonight works its own events out ([sh.arek.gloaming.astronomy.TonightEvents])
 * and so does not depend on the calendar's key, which means the comet
 * settings that the events and `Tonight.of` read are here themselves. The
 * settings are every one that `TonightEvents`, `Tonight.of`, `Outlooks.of` and
 * `NightTimeline.of` read: one left out leaves the night decided from its old
 * value - the card quoting a new cloud tolerance against a gap worked out from
 * the old one, or the cloud gate turned on and the sentence still decided
 * without it.
 */
data class TonightKey(
    /** The position, to [KEY_PRECISION_DEGREES]. */
    val latitude: Double?,
    val longitude: Double?,
    val cloudGateEnabled: Boolean,
    val cloudTolerance: Int,
    val minimumPassElevationDegrees: Int,
    val threshold: Int,
    val showEveryConstellation: Boolean,
    /** The comet settings, which decide which comets the night lists and how. */
    val cometMagnitudeThreshold: Int,
    val minimumCometAltitudeDegrees: Int,
    val cometObservationStaleDays: Int,
    val cometMoonTolerance: Int,
    /** [ScreenResults.forecastVersion]: a fetched forecast that changed what is held. */
    val forecastVersion: Int,
    /** A check brings a new reading and possibly new elements, both of which the outlook reads. */
    val lastPollAt: Long?,
    /** The screen's clock in [TONIGHT_STALE_MILLIS] stretches, because "tonight" moves as the evening does. */
    val staleSlot: Long,
    /** [ScreenResults.recalculation] when the key was made. */
    val recalculation: Int,
    /** The pass an alert last spoke about, which tonight's best always includes. */
    val announced: AnnouncedPass? = null,
) {
    /** The place the night is about, at the grid's resolution. */
    val cell: GridCell?
        get() = if (latitude != null && longitude != null) GridCell.of(latitude, longitude) else null

    companion object {
        fun of(
            settings: UserSettings,
            forecastVersion: Int,
            lastPollAt: Long?,
            staleSlot: Long,
            recalculation: Int,
            announced: AnnouncedPass? = null,
        ) = TonightKey(
            latitude = keyed(settings.latitude),
            longitude = keyed(settings.longitude),
            cloudGateEnabled = settings.cloudGateEnabled,
            cloudTolerance = settings.cloudTolerance,
            minimumPassElevationDegrees = settings.minimumPassElevationDegrees,
            threshold = settings.threshold,
            showEveryConstellation = settings.showEveryConstellation,
            cometMagnitudeThreshold = settings.cometMagnitudeThreshold,
            minimumCometAltitudeDegrees = settings.minimumCometAltitudeDegrees,
            cometObservationStaleDays = settings.cometObservationStaleDays,
            cometMoonTolerance = settings.cometMoonTolerance,
            forecastVersion = forecastVersion,
            lastPollAt = lastPollAt,
            staleSlot = staleSlot,
            recalculation = recalculation,
            announced = announced,
        )
    }
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
 * Work that throws ends its run as though it had answered nothing, and the
 * exception goes on to [scope]'s handler; it never leaves a run marked in
 * flight for ever.
 *
 * A cold process has nothing to show for the twenty seconds the first night
 * takes, so [prefetchSnapshot] reads [snapshots], the last night worked out,
 * in [scope] - never on the caller's thread, and not under the lock - and the
 * night is shown, as updating, once it has been read and an [ensureTonight]
 * has said which cell it is for: when the snapshot is for that cell and not
 * yet over. It is shown only into an empty holder, so a snapshot never
 * replaces a night worked out in this process, however late it is read. Each
 * run that lands writes the next snapshot after its results are published,
 * outside the lock; a replaced run writes nothing, and writes never go out of
 * order. A run with no night clears it.
 */
class ScreenResults(
    private val scope: CoroutineScope,
    private val snapshots: TonightSnapshots? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

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
     * published.
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

    private var tonightShown: TonightKey? = null
    private var tonightInFlight: TonightKey? = null
    private var tonightJob: Job? = null
    private var tonightGeneration = 0

    /** The grid cell the night on show was worked out for. */
    private var nightCell: GridCell? = null

    /** The snapshot read by [prefetchSnapshot], waiting for a cell to be asked for. */
    private var kept: TonightSnapshot? = null
    private var prefetched = false

    /** The cell of the latest [ensureTonight]; null before the first, or for one with no position. */
    private var askedCell: GridCell? = null

    /** Stamps each snapshot write so that an older one, overtaken, is dropped. */
    private val writeStamp = AtomicInteger()
    private val writing = Any()

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
                var published = false
                try {
                    calendarCount.incrementAndGet()
                    val worked = events()
                    synchronized(lock) {
                        if (generation != calendarGeneration) return@launch
                        publishEvents(key, worked)
                        published = true
                    }
                    val strips = months(worked)
                    synchronized(lock) {
                        if (generation != calendarGeneration) return@launch
                        this@ScreenResults.months = strips
                        calendarShown = key
                        calendarInFlight = null
                        calendarJob = null
                    }
                } finally {
                    // Work that threw: the exception goes on to the scope's
                    // handler, but this run is over, and the events are
                    // published empty rather than left reading "loading".
                    synchronized(lock) {
                        if (generation == calendarGeneration && calendarInFlight == key) {
                            if (!published) publishEvents(key, emptyList())
                            calendarInFlight = null
                            calendarJob = null
                        }
                    }
                }
            }
        }
    }

    /** Under [lock]. */
    private fun publishEvents(key: CalendarKey, events: List<SkyEvent>) {
        Snapshot.withMutableSnapshot {
            calendar = events
            calendarLoading = false
        }
    }

    /**
     * Works out tonight for [key] unless it is already shown or under way.
     *
     * The [work] starts at once and gathers its own events over a short
     * horizon, so Tonight does not wait for the year ahead. A
     * key for another grid cell takes the old place's night down at once
     * rather than leaving "clear from 22:00" up about somewhere else; a
     * position wandering inside the cell keeps it up until the new one lands.
     * A null night takes every result down.
     */
    fun ensureTonight(key: TonightKey, work: suspend () -> WorkedNight?) {
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
            askedCell = key.cell
            seedFromSnapshot()
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
                try {
                    tonightCount.incrementAndGet()
                    val worked = work()
                    val persist = synchronized(lock) {
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
                        kept = null
                        prepareKeep(worked, key.cell)
                    }
                    persist?.invoke()
                } finally {
                    // Work that threw: the night on show stays, but it is no
                    // longer updating, and the same key may be asked for again.
                    synchronized(lock) {
                        if (generation == tonightGeneration && tonightInFlight == key) {
                            tonightUpdating = false
                            tonightInFlight = null
                            tonightJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads the last night worked out, once, in [scope]. Called when the
     * holder is made, so the read is under way before anything asks for a
     * night. The decode takes no lock: it is the last stage that is locked.
     */
    fun prefetchSnapshot() {
        val store = snapshots ?: return
        synchronized(lock) {
            if (prefetched) return
            prefetched = true
        }
        scope.launch {
            val read = runCatching { store.read() }.getOrNull()
            synchronized(lock) {
                kept = read
                seedFromSnapshot()
            }
        }
    }

    /**
     * Under [lock]. Shows the snapshot read, once, when nothing has been
     * shown or worked out yet and it is for the cell last asked for and a
     * night not yet over. Tried again by each ask until a run lands, because
     * the read can finish before or after the first ask, and the first ask can
     * be for no position.
     */
    private fun seedFromSnapshot() {
        val snapshot = kept ?: return
        val cell = askedCell ?: return
        if (tonight != null || tonightShown != null) return
        if (!snapshot.usable(cell, clock())) return
        kept = null
        Snapshot.withMutableSnapshot {
            tonight = snapshot.list
            outlook = snapshot.outlook
            hourly = snapshot.hourly
            timeline = snapshot.timeline
            tonightUpdating = true
        }
        nightCell = cell
    }

    /**
     * Under [lock]. What to write for [worked] - or to clear, when there is no
     * night - as work to do after the lock is let go. Stamped, so a write
     * overtaken by a later run's is dropped rather than landing after it.
     */
    private fun prepareKeep(worked: WorkedNight?, cell: GridCell?): (() -> Unit)? {
        val store = snapshots ?: return null
        val stamp = writeStamp.incrementAndGet()
        val snapshot = if (worked == null || cell == null) {
            null
        } else {
            TonightSnapshot.of(
                cell = cell,
                writtenAt = clock(),
                list = worked.list,
                outlook = worked.report,
                hourly = worked.forecast,
                timeline = worked.timeline,
            )
        }
        return {
            synchronized(writing) {
                if (stamp == writeStamp.get()) {
                    if (snapshot == null) store.clear() else store.write(snapshot)
                }
            }
        }
    }
}
