package sh.arek.gloaming.ui

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitesDocument

class ScreenResultsTest {

    // --- fixtures ---

    private val edinburgh = 55.95 to -3.19

    /** A few hundred metres from [edinburgh], inside the same grid cell. */
    private val edinburghNearby = 55.951 to -3.191

    private val london = 51.5 to -0.12

    private fun calendarKey(
        place: Pair<Double, Double>? = edinburgh,
        day: Long = 20_000L,
        recalculation: Int = 0,
    ) = CalendarKey(place?.first, place?.second, day, recalculation)

    private fun tonightKey(
        place: Pair<Double, Double>? = edinburgh,
        calendar: CalendarKey = calendarKey(place),
        threshold: Int = 30,
        recalculation: Int = calendar.recalculation,
    ) = TonightKey(
        calendar = calendar,
        latitude = place?.first,
        longitude = place?.second,
        cloudTolerance = 50,
        minimumPassElevationDegrees = 20,
        threshold = threshold,
        forecastVersion = 0,
        lastPollAt = null,
        staleSlot = 0L,
        recalculation = recalculation,
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
    private fun TestScope.results() =
        ScreenResults(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

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
            } catch (e: kotlinx.coroutines.CancellationException) {
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
            return calendar to tonightKey(calendar = calendar)
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

    // --- tonight waits for its calendar ---

    @Test
    fun `tonight is not started until its calendar has landed`() = runTest {
        val results = results()
        val gate = CompletableDeferred<Unit>()
        val events = listOf(event("Saturn at opposition"))
        var given: List<SkyEvent>? = null

        results.ensureCalendar(calendarKey(), events = { gate.await(); events }, months = { emptyList() })
        results.ensureTonight(tonightKey()) { calendar -> given = calendar; night(1) }
        runCurrent()

        assertEquals(0, results.tonightRuns)
        assertTrue(results.tonightUpdating)
        assertTrue(results.calendarLoading)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, results.tonightRuns)
        assertEquals(events, given)
    }

    @Test
    fun `tonight is not worked out against another key's calendar`() = runTest {
        val results = results()
        land(results, calendarKey(recalculation = 0))
        val gate = CompletableDeferred<Unit>()
        val fresh = listOf(event("Fresh"))
        var given: List<SkyEvent>? = null

        results.ensureCalendar(calendarKey(recalculation = 1), events = { gate.await(); fresh }, months = { emptyList() })
        results.ensureTonight(tonightKey(calendar = calendarKey(recalculation = 1))) { calendar ->
            given = calendar
            night(1)
        }
        runCurrent()
        assertEquals(0, results.tonightRuns)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(fresh, given)
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
}
