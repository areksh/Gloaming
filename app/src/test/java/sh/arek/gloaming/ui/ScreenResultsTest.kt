package sh.arek.gloaming.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.GridCell
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.TonightSnapshot
import sh.arek.gloaming.data.TonightSnapshots
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.AnnouncedPass
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitesDocument

class ScreenResultsTest {

    // --- fixtures ---

    private val edinburgh = 55.95 to -3.19

    /** A few kilometres from [edinburgh]: another key, but the same grid cell. */
    private val edinburghNearby = 55.98 to -3.25

    private val london = 51.5 to -0.12

    private fun settings(place: Pair<Double, Double>? = edinburgh) =
        UserSettings(latitude = place?.first, longitude = place?.second)

    private fun calendarKey(
        place: Pair<Double, Double>? = edinburgh,
        day: Long = 20_000L,
        recalculation: Int = 0,
        user: UserSettings = settings(place),
    ) = CalendarKey.of(user, day, recalculation)

    private fun tonightKey(
        place: Pair<Double, Double>? = edinburgh,
        threshold: Int = 30,
        user: UserSettings = settings(place).copy(threshold = threshold),
        recalculation: Int = 0,
        announced: AnnouncedPass? = null,
    ) = TonightKey.of(
        settings = user,
        forecastVersion = 0,
        lastPollAt = null,
        staleSlot = 0L,
        recalculation = recalculation,
        announced = announced,
    )

    private fun event(title: String) = SkyEvent(
        kind = EventKind.Opposition,
        at = 1_800_000_000_000L,
        from = 1_800_000_000_000L,
        until = 1_800_003_600_000L,
        title = title,
        detail = "",
        visibility = Visibility.Good,
    )

    private fun strip(name: String) = MonthStrip(
        year = 2027,
        month = 0,
        name = name,
        nights = emptyList(),
        darkFrom = null,
        darkUntil = null,
    )

    /** A night told apart from any other by [marker], carried as its dark start. */
    private fun night(marker: Long, elements: SatellitesDocument? = null) = WorkedNight(
        list = TonightList(marker, null, emptyList(), emptyList(), emptyList(), emptyList(), null),
        elements = elements,
        report = null,
        forecast = listOf(HourlyCover(marker, 10)),
        timeline = null,
    )

    private val document = SatellitesDocument(schema = 1, updated = "2026-11-01T00:00:00Z")

    /**
     * On the test's scheduler but not in its `backgroundScope`, whose tasks
     * `advanceUntilIdle` leaves alone. A run left waiting at the end of a
     * test is simply abandoned with the scope.
     */
    private fun TestScope.results(snapshots: TonightSnapshots? = null, prefetch: Boolean = true) = ScreenResults(
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + escaped),
        snapshots = snapshots,
        clock = { NOW },
    ).also { if (prefetch) it.prefetchSnapshot() }

    /** Keeps the snapshot in memory, and counts what was asked of it. */
    private class MemorySnapshots(var held: TonightSnapshot? = null) : TonightSnapshots {
        var reads = 0
        var clears = 0
        override fun read(): TonightSnapshot? { reads++; return held }
        override fun write(snapshot: TonightSnapshot) { held = snapshot }
        override fun clear() { clears++; held = null }
    }

    /** A snapshot of [marker]'s night for [cell], ending an hour after [NOW] unless told otherwise. */
    private fun snapshot(
        marker: Long,
        cell: GridCell = GridCell.of(edinburgh.first, edinburgh.second),
        endsAt: Long = NOW + 3_600_000L,
    ) = TonightSnapshot.of(
        cell = cell,
        writtenAt = NOW - 3_600_000L,
        list = night(marker).list.copy(darkUntil = endsAt),
        outlook = null,
        hourly = listOf(HourlyCover(marker, 10)),
        timeline = null,
    )

    /** What escaped the work, as the app's own handler would log it. */
    private val escapes = mutableListOf<Throwable>()
    private val escaped = CoroutineExceptionHandler { _, e -> escapes += e }

    /** Lands the calendar for [key] at once, with [events]. */
    private fun TestScope.land(
        results: ScreenResults,
        key: CalendarKey = calendarKey(),
        events: List<SkyEvent> = listOf(event("Saturn at opposition")),
    ) {
        results.ensureCalendar(key, events = { events }, months = { emptyList() })
        advanceUntilIdle()
    }

    // --- running once per key ---

    @Test
    fun `the same tonight key twice runs the work once`() = runTest {
        val results = results()
        land(results)
        var calls = 0

        results.ensureTonight(tonightKey()) { calls++; night(1) }
        results.ensureTonight(tonightKey()) { calls++; night(2) }
        advanceUntilIdle()
        results.ensureTonight(tonightKey()) { calls++; night(3) }
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(1, results.tonightRuns)
        assertEquals(1L, results.tonight?.darkFrom)
    }

    @Test
    fun `the same calendar key twice runs the work once`() = runTest {
        val results = results()
        var calls = 0

        results.ensureCalendar(calendarKey(), events = { calls++; emptyList() }, months = { emptyList() })
        results.ensureCalendar(calendarKey(), events = { calls++; emptyList() }, months = { emptyList() })
        advanceUntilIdle()
        results.ensureCalendar(calendarKey(), events = { calls++; emptyList() }, months = { emptyList() })
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(1, results.calendarRuns)
    }

    @Test
    fun `a new key cancels the run in flight and runs again`() = runTest {
        val results = results()
        land(results)
        val gate = CompletableDeferred<Unit>()
        var firstCancelled = false

        results.ensureTonight(tonightKey(threshold = 30)) {
            try {
                gate.await()
            } catch (e: CancellationException) {
                firstCancelled = true
                throw e
            }
            night(1)
        }
        runCurrent()
        results.ensureTonight(tonightKey(threshold = 50)) { night(2) }
        advanceUntilIdle()

        assertTrue(firstCancelled)
        assertEquals(2, results.tonightRuns)
        assertEquals(2L, results.tonight?.darkFrom)
    }

    /**
     * The SGP4 sweep has no suspension point, so a cancelled run goes on to
     * the end and hands back a night for inputs nobody is looking at any more.
     */
    @Test
    fun `a cancelled run that finishes late never replaces the newer night`() = runTest {
        val results = results()
        land(results)
        val gate = CompletableDeferred<Unit>()

        results.ensureTonight(tonightKey(threshold = 30)) {
            withContext(NonCancellable) { gate.await() }
            night(1)
        }
        runCurrent()
        results.ensureTonight(tonightKey(threshold = 50)) { night(2) }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, results.tonight?.darkFrom)
        assertFalse(results.tonightUpdating)
    }

    @Test
    fun `a cancelled calendar run that finishes late never replaces the newer events`() = runTest {
        val results = results()
        val gate = CompletableDeferred<Unit>()
        val old = listOf(event("Old"))
        val new = listOf(event("New"))

        results.ensureCalendar(
            calendarKey(recalculation = 0),
            events = { withContext(NonCancellable) { gate.await() }; old },
            months = { listOf(strip("old")) },
        )
        runCurrent()
        results.ensureCalendar(
            calendarKey(recalculation = 1),
            events = { new },
            months = { listOf(strip("new")) },
        )
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(new, results.calendar)
        assertEquals(listOf(strip("new")), results.months)
    }

    // --- publishing, and saying it is working ---

    @Test
    fun `the results are published and updating is false afterwards`() = runTest {
        val results = results()
        land(results)
        val worked = night(7, elements = document)

        results.ensureTonight(tonightKey()) { worked }
        advanceUntilIdle()

        assertSame(worked.list, results.tonight)
        assertSame(document, results.elements)
        assertEquals(worked.forecast, results.hourly)
        assertFalse(results.tonightUpdating)
    }

    @Test
    fun `updating is true while the work is in flight`() = runTest {
        val results = results()
        land(results)
        val gate = CompletableDeferred<Unit>()

        results.ensureTonight(tonightKey()) { gate.await(); night(1) }
        runCurrent()

        assertTrue(results.tonightUpdating)
        assertEquals(1, results.tonightRuns)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(results.tonightUpdating)
    }

    @Test
    fun `nothing worked out takes every result down`() = runTest {
        val results = results()
        land(results)
        results.ensureTonight(tonightKey()) { night(1, elements = document) }
        advanceUntilIdle()
        assertSame(document, results.elements)

        results.ensureTonight(tonightKey(threshold = 50)) { null }
        advanceUntilIdle()

        assertNull(results.tonight)
        assertNull(results.elements)
        assertEquals(emptyList<HourlyCover>(), results.hourly)
        assertFalse(results.tonightUpdating)
    }

    // --- recalculating ---

    @Test
    fun `recalculating with nothing else changed works the calendar and tonight out once more`() = runTest {
        val results = results()
        fun keys(): Pair<CalendarKey, TonightKey> {
            val calendar = calendarKey(recalculation = results.recalculation)
            return calendar to tonightKey(recalculation = results.recalculation)
        }
        fun ensure() {
            val (calendar, tonight) = keys()
            results.ensureCalendar(calendar, events = { emptyList() }, months = { emptyList() })
            results.ensureTonight(tonight) { night(1) }
        }
        ensure()
        advanceUntilIdle()
        ensure()
        advanceUntilIdle()
        assertEquals(1, results.calendarRuns)
        assertEquals(1, results.tonightRuns)

        results.recalculate()
        ensure()
        advanceUntilIdle()

        assertEquals(2, results.calendarRuns)
        assertEquals(2, results.tonightRuns)
    }

    // --- tonight does not wait for its calendar ---

    @Test
    fun `tonight is worked out while the calendar is still in flight`() = runTest {
        val results = results()
        val gate = CompletableDeferred<Unit>()

        results.ensureCalendar(calendarKey(), events = { gate.await(); emptyList() }, months = { emptyList() })
        results.ensureTonight(tonightKey()) { night(1) }
        runCurrent()

        assertEquals(1, results.tonightRuns)
        assertEquals(1L, results.tonight?.darkFrom)
        assertFalse(results.tonightUpdating)
        assertTrue(results.calendarLoading)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(results.calendarLoading)
    }

    @Test
    fun `the events are published before the months`() = runTest {
        val results = results()
        val gate = CompletableDeferred<Unit>()
        val events = listOf(event("Saturn at opposition"))

        results.ensureCalendar(calendarKey(), events = { events }, months = { gate.await(); listOf(strip("May")) })
        runCurrent()

        assertEquals(events, results.calendar)
        assertFalse(results.calendarLoading)
        assertEquals(emptyList<MonthStrip>(), results.months)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(strip("May")), results.months)
    }

    @Test
    fun `another place's months are taken down as soon as its calendar is asked for again`() = runTest {
        val results = results()
        results.ensureCalendar(calendarKey(edinburgh), events = { emptyList() }, months = { listOf(strip("May")) })
        advanceUntilIdle()

        results.ensureCalendar(calendarKey(london), events = { CompletableDeferred<Unit>().await(); emptyList() }, months = { emptyList() })

        assertEquals(emptyList<MonthStrip>(), results.months)
        assertTrue(results.calendarLoading)
    }

    // --- the night belongs to a place ---

    @Test
    fun `a move to another cell takes the old place's night down at once`() = runTest {
        val results = results()
        land(results, calendarKey(edinburgh))
        results.ensureTonight(tonightKey(edinburgh)) { night(1, elements = document) }
        advanceUntilIdle()

        results.ensureTonight(tonightKey(london)) { night(2) }

        assertNull(results.tonight)
        assertEquals(emptyList<HourlyCover>(), results.hourly)
        // The elements are not about a place, so Find keeps them until the new night lands.
        assertSame(document, results.elements)
        assertTrue(results.tonightUpdating)
    }

    @Test
    fun `a position wandering inside the cell keeps the night up while it is worked out again`() = runTest {
        val results = results()
        land(results, calendarKey(edinburgh))
        results.ensureTonight(tonightKey(edinburgh)) { night(1) }
        advanceUntilIdle()

        results.ensureTonight(tonightKey(edinburghNearby)) { night(2) }

        assertEquals(1L, results.tonight?.darkFrom)
        assertTrue(results.tonightUpdating)
    }

    @Test
    fun `going back to the key already shown stops the run in flight and keeps the night`() = runTest {
        val results = results()
        land(results)
        results.ensureTonight(tonightKey(threshold = 30)) { night(1) }
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()

        results.ensureTonight(tonightKey(threshold = 50)) {
            withContext(NonCancellable) { gate.await() }
            night(2)
        }
        runCurrent()
        results.ensureTonight(tonightKey(threshold = 30)) { night(3) }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1L, results.tonight?.darkFrom)
        assertFalse(results.tonightUpdating)
        assertEquals(2, results.tonightRuns)
    }

    // --- the keys hold what the work reads ---

    /**
     * Every setting `Tonight.of`, `Outlooks.of` and `NightTimeline.of` read,
     * each changed on its own. The cloud gate went unnoticed once the forecast
     * stopped bumping the key on every open.
     */
    @Test
    fun `a change to any setting the night reads gives tonight a new key`() {
        val base = settings()
        val changes = mapOf<String, (UserSettings) -> UserSettings>(
            "cloud gate" to { it.copy(cloudGateEnabled = !it.cloudGateEnabled) },
            "cloud tolerance" to { it.copy(cloudTolerance = it.cloudTolerance + 10) },
            "minimum pass elevation" to { it.copy(minimumPassElevationDegrees = it.minimumPassElevationDegrees + 5) },
            "threshold" to { it.copy(threshold = it.threshold + 10) },
            "every constellation" to { it.copy(showEveryConstellation = !it.showEveryConstellation) },
            "comet magnitude" to { it.copy(cometMagnitudeThreshold = it.cometMagnitudeThreshold + 1) },
            "comet altitude" to { it.copy(minimumCometAltitudeDegrees = it.minimumCometAltitudeDegrees + 5) },
            "comet staleness" to { it.copy(cometObservationStaleDays = it.cometObservationStaleDays + 1) },
            "comet moon" to { it.copy(cometMoonTolerance = it.cometMoonTolerance + 10) },
        )
        for ((name, change) in changes) {
            assertNotEquals(name, tonightKey(user = base), tonightKey(user = change(base)))
        }
    }

    @Test
    fun `a new recalculation gives tonight a new key`() {
        assertNotEquals(tonightKey(recalculation = 0), tonightKey(recalculation = 1))
    }

    /** Tonight lists the pass just announced, so an announcement has to reach it. */
    @Test
    fun `a newly announced pass gives tonight a new key`() {
        assertNotEquals(tonightKey(), tonightKey(announced = AnnouncedPass(28499, 1_793_644_495_840L)))
    }

    @Test
    fun `a change to any comet setting gives the calendar a new key`() {
        val base = settings()
        val changes = listOf<(UserSettings) -> UserSettings>(
            { it.copy(cometMagnitudeThreshold = it.cometMagnitudeThreshold + 1) },
            { it.copy(minimumCometAltitudeDegrees = it.minimumCometAltitudeDegrees + 5) },
            { it.copy(cometObservationStaleDays = it.cometObservationStaleDays + 1) },
            { it.copy(cometMoonTolerance = it.cometMoonTolerance + 10) },
        )
        for (change in changes) {
            assertNotEquals(calendarKey(user = base), calendarKey(user = change(base)))
        }
    }

    @Test
    fun `turning the cloud gate on works tonight out again`() = runTest {
        val results = results()
        land(results)
        val gateOff = settings().copy(cloudGateEnabled = false)

        results.ensureTonight(tonightKey(user = gateOff)) { night(1) }
        advanceUntilIdle()
        results.ensureTonight(tonightKey(user = gateOff.copy(cloudGateEnabled = true))) { night(2) }
        advanceUntilIdle()

        assertEquals(2, results.tonightRuns)
        assertEquals(2L, results.tonight?.darkFrom)
    }

    /**
     * An approximate fix wanders a few hundred metres between asks, and a
     * rotation in automatic mode used to ask again; each wander re-ran both.
     */
    @Test
    fun `two positions inside one key step give equal keys`() {
        val a = 55.9512 to -3.1912
        val b = 55.9538 to -3.1888

        assertEquals(calendarKey(a), calendarKey(b))
        assertEquals(tonightKey(a), tonightKey(b))
    }

    @Test
    fun `positions a key step apart give different keys`() {
        val a = 55.95 to -3.19
        val north = 55.95 + KEY_PRECISION_DEGREES to -3.19
        val east = 55.95 to -3.19 + KEY_PRECISION_DEGREES

        assertNotEquals(calendarKey(a), calendarKey(north))
        assertNotEquals(calendarKey(a), calendarKey(east))
        assertNotEquals(tonightKey(a), tonightKey(north))
        assertNotEquals(tonightKey(a), tonightKey(east))
    }

    // --- late months, and work that throws ---

    @Test
    fun `a replaced run's months that finish late touch nothing of the newer run`() = runTest {
        val results = results()
        val gate = CompletableDeferred<Unit>()
        val first = calendarKey(recalculation = 0)
        val second = calendarKey(recalculation = 1)
        val fresh = listOf(event("Fresh"))

        results.ensureCalendar(
            first,
            events = { listOf(event("Old")) },
            months = { withContext(NonCancellable) { gate.await() }; listOf(strip("old")) },
        )
        runCurrent()
        results.ensureCalendar(second, events = { fresh }, months = { listOf(strip("new")) })
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(fresh, results.calendar)
        assertEquals(listOf(strip("new")), results.months)
        // The second key is still the one shown: asking for it again runs nothing.
        results.ensureCalendar(second, events = { emptyList() }, months = { emptyList() })
        advanceUntilIdle()
        assertEquals(2, results.calendarRuns)
        assertFalse(results.calendarLoading)
    }

    @Test
    fun `tonight work that throws stops updating and a later key still runs`() = runTest {
        val results = results()
        land(results)

        results.ensureTonight(tonightKey(threshold = 30)) { throw IllegalStateException("broken") }
        advanceUntilIdle()

        assertFalse(results.tonightUpdating)
        assertEquals(listOf("broken"), escapes.map { it.message })

        results.ensureTonight(tonightKey(threshold = 50)) { night(2) }
        advanceUntilIdle()
        assertEquals(2L, results.tonight?.darkFrom)
        assertFalse(results.tonightUpdating)
    }

    @Test
    fun `calendar work that throws leaves tonight running`() = runTest {
        val results = results()

        results.ensureCalendar(calendarKey(), events = { throw IllegalStateException("broken") }, months = { emptyList() })
        results.ensureTonight(tonightKey()) { night(1) }
        advanceUntilIdle()

        assertFalse(results.calendarLoading)
        assertEquals(1L, results.tonight?.darkFrom)
        assertFalse(results.tonightUpdating)
        assertEquals(listOf("broken"), escapes.map { it.message })
    }

    // --- the last night, kept across a cold start ---

    @Test
    fun `an empty holder opens on a usable snapshot, marked as updating`() = runTest {
        val results = results(MemorySnapshots(snapshot(7)))
        land(results)
        val gate = CompletableDeferred<Unit>()

        results.ensureTonight(tonightKey()) { gate.await(); night(8) }
        runCurrent()

        assertEquals(7L, results.tonight?.darkFrom)
        assertEquals(listOf(HourlyCover(7L, 10)), results.hourly)
        assertTrue(results.tonightUpdating)
        // The elements are not kept; the run brings them.
        assertNull(results.elements)
    }

    @Test
    fun `the run that lands replaces the snapshot's night`() = runTest {
        val results = results(MemorySnapshots(snapshot(7)))
        land(results)

        results.ensureTonight(tonightKey()) { night(8, elements = document) }
        advanceUntilIdle()

        assertEquals(8L, results.tonight?.darkFrom)
        assertSame(document, results.elements)
        assertFalse(results.tonightUpdating)
    }

    @Test
    fun `a snapshot for another cell is not shown`() = runTest {
        val results = results(MemorySnapshots(snapshot(7, cell = GridCell.of(london.first, london.second))))
        land(results)
        val gate = CompletableDeferred<Unit>()

        results.ensureTonight(tonightKey()) { gate.await(); night(8) }
        runCurrent()

        assertNull(results.tonight)
    }

    @Test
    fun `a snapshot of a night that is over is not shown`() = runTest {
        val results = results(MemorySnapshots(snapshot(7, endsAt = NOW)))
        land(results)
        val gate = CompletableDeferred<Unit>()

        results.ensureTonight(tonightKey()) { gate.await(); night(8) }
        runCurrent()

        assertNull(results.tonight)
    }

    @Test
    fun `a snapshot read after a run has landed never overwrites it`() = runTest {
        val results = results(MemorySnapshots(snapshot(7)), prefetch = false)
        land(results)
        results.ensureTonight(tonightKey()) { night(1) }
        advanceUntilIdle()

        results.prefetchSnapshot()
        advanceUntilIdle()

        assertEquals(1L, results.tonight?.darkFrom)
        assertFalse(results.tonightUpdating)
    }

    @Test
    fun `a snapshot read while the first run is under way is shown, and the run then replaces it`() = runTest {
        val results = results(MemorySnapshots(snapshot(7)), prefetch = false)
        land(results)
        val gate = CompletableDeferred<Unit>()
        results.ensureTonight(tonightKey()) { gate.await(); night(1) }
        runCurrent()
        assertNull(results.tonight)

        results.prefetchSnapshot()
        runCurrent()
        assertEquals(7L, results.tonight?.darkFrom)
        assertTrue(results.tonightUpdating)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1L, results.tonight?.darkFrom)
    }

    @Test
    fun `a first ask with no position does not stop a later one being seeded`() = runTest {
        val results = results(MemorySnapshots(snapshot(7)))
        land(results, calendarKey(place = null))
        val gate = CompletableDeferred<Unit>()
        results.ensureTonight(tonightKey(place = null)) { gate.await(); null }
        runCurrent()
        assertNull(results.tonight)

        land(results)
        results.ensureTonight(tonightKey()) { gate.await(); night(8) }
        runCurrent()

        assertEquals(7L, results.tonight?.darkFrom)
    }

    @Test
    fun `a snapshot is shown for whichever cell it matches, not only the usual one`() = runTest {
        val results = results(MemorySnapshots(snapshot(7, cell = GridCell.of(london.first, london.second))))
        land(results, calendarKey(london))
        val gate = CompletableDeferred<Unit>()

        results.ensureTonight(tonightKey(london)) { gate.await(); night(8) }
        runCurrent()

        assertEquals(7L, results.tonight?.darkFrom)
    }

    @Test
    fun `the snapshot is read once, however many nights are asked for`() = runTest {
        val snapshots = MemorySnapshots()
        val results = results(snapshots)
        land(results)

        results.ensureTonight(tonightKey(threshold = 30)) { night(1) }
        advanceUntilIdle()
        results.ensureTonight(tonightKey(threshold = 50)) { night(2) }
        advanceUntilIdle()

        assertEquals(1, snapshots.reads)
    }

    @Test
    fun `a move to another cell takes a seeded night down`() = runTest {
        val results = results(MemorySnapshots(snapshot(7)))
        land(results, calendarKey(edinburgh))
        results.ensureTonight(tonightKey(edinburgh)) { night(8) }
        runCurrent()
        land(results, calendarKey(london))

        results.ensureTonight(tonightKey(london)) { CompletableDeferred<Unit>().await(); night(9) }

        assertNull(results.tonight)
    }

    @Test
    fun `each finished run writes a snapshot of its night`() = runTest {
        val snapshots = MemorySnapshots()
        val results = results(snapshots)
        land(results)

        results.ensureTonight(tonightKey()) { night(8) }
        advanceUntilIdle()

        val written = snapshots.held!!
        assertEquals(8L, written.list.darkFrom)
        assertEquals(listOf(HourlyCover(8L, 10)), written.hourly)
        assertEquals(GridCell.of(edinburgh.first, edinburgh.second), written.cell)
        assertEquals(NOW, written.writtenAt)
    }

    @Test
    fun `no night worked out clears the snapshot`() = runTest {
        val snapshots = MemorySnapshots(snapshot(7))
        val results = results(snapshots)
        land(results)

        results.ensureTonight(tonightKey()) { null }
        advanceUntilIdle()

        assertNull(snapshots.held)
    }

    @Test
    fun `a run replaced while it worked writes nothing`() = runTest {
        val snapshots = MemorySnapshots()
        val results = results(snapshots)
        land(results)
        val gate = CompletableDeferred<Unit>()
        results.ensureTonight(tonightKey(threshold = 30)) {
            withContext(NonCancellable) { gate.await() }
            night(1)
        }
        runCurrent()
        results.ensureTonight(tonightKey(threshold = 50)) { night(2) }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, snapshots.held!!.list.darkFrom)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
