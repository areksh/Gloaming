package sh.arek.gloaming.astronomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.comets.Comets
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.Tonight
import sh.arek.gloaming.scenarios.Scenarios

class TonightEventsTest {

    private val day = 86_400_000L

    /** Midnight UTC on 1 January 2027, the start of the sampled year. */
    private val yearStart = 1_798_761_600_000L

    private fun eventsOf(
        settings: UserSettings,
        nowMillis: Long,
        calendar: List<SkyEvent>,
        comets: CometsDocument? = null,
    ) = Tonight.of(
        satellites = null,
        comets = comets,
        calendar = calendar,
        settings = settings,
        nowMillis = nowMillis,
    ).events

    private fun describe(events: List<SkyEvent>) =
        events.map { "${it.kind} ${it.title} @${it.at}" }

    /** Tonight's events from [TonightEvents] against those from [reference], for one night. */
    private fun mismatch(
        label: String,
        settings: UserSettings,
        now: Long,
        reference: List<SkyEvent>,
        comets: CometsDocument? = null,
    ): String? {
        val expected = eventsOf(settings, now, reference.sortedBy { it.at }, comets)
        val actual = eventsOf(
            settings,
            now,
            TonightEvents.of(settings.latitude!!, settings.longitude!!, now, comets, settings),
            comets,
        )
        return if (describe(expected) == describe(actual)) {
            null
        } else {
            "$label: expected ${describe(expected)} but got ${describe(actual)}"
        }
    }

    /**
     * The reference for a scenario night is a scan of 45 days rather than the
     * year: tonight can only list what opens within a day, which is dated within
     * 22 days (twice that is room to spare), and a year per night costs minutes.
     * The monthly sample below checks the year scan itself, once per place.
     */
    @Test
    fun `tonight's events match the longer scan for every scenario night`() {
        val misses = mutableListOf<String>()
        var withEvents = 0
        for (scenario in Scenarios.all) {
            val latitude = scenario.latitude ?: continue
            val longitude = scenario.longitude ?: continue
            val reference = SkyCalendar.events(latitude, longitude, scenario.nowMillis, 45) +
                Comets.calendar(scenario.comets, latitude, longitude, scenario.settings, scenario.nowMillis)
            if (eventsOf(scenario.settings, scenario.nowMillis, reference, scenario.comets).isNotEmpty()) withEvents++
            mismatch(scenario.name, scenario.settings, scenario.nowMillis, reference, scenario.comets)
                ?.let(misses::add)
        }
        assertTrue("the scenarios found no events at all", withEvents > 0)
        assertEquals(emptyList<String>(), misses)
    }

    /**
     * Monthly nights through a year at three latitudes, against one year scan
     * per place. A scan from the year's start holds everything a later night
     * can list, because a window that is open later was open, or began, after
     * that start.
     */
    @Test
    fun `tonight's events match what the year scan lists for tonight`() {
        val misses = mutableListOf<String>()
        var withEvents = 0
        for (latitude in listOf(60.0, 0.0, -45.0)) {
            val settings = UserSettings(latitude = latitude, longitude = -3.19)
            val year = SkyCalendar.events(latitude, -3.19, yearStart)
            for (month in 0 until 11) {
                val now = yearStart + month * 30 * day + 21 * 3_600_000L
                val reference = year.filter { it.until >= now }
                if (eventsOf(settings, now, reference).isNotEmpty()) withEvents++
                mismatch("lat $latitude month $month", settings, now, reference)?.let(misses::add)
            }
        }
        assertTrue("the sample found no events at all", withEvents > 0)
        assertEquals(emptyList<String>(), misses)
    }

    /**
     * The nights that force the horizon. Every kind whose window opens before
     * its date is put just inside and just outside the opening, at three
     * latitudes: the opposition's three weeks is what makes 22 days rather
     * than 21, and the shorter lead of the others is what makes it more than
     * one. The reference is a 45-day scan.
     */
    @Test
    fun `a long window is listed from the night it opens and not before`() {
        val misses = mutableListOf<String>()
        val seen = mutableSetOf<EventKind>()
        val kinds = setOf(
            EventKind.Opposition,
            EventKind.Elongation,
            EventKind.GreatestBrightness,
            EventKind.MeteorShower,
        )
        for (latitude in listOf(60.0, 0.0, -45.0)) {
            val settings = UserSettings(latitude = latitude, longitude = -3.19)
            val windows = SkyCalendar.events(latitude, -3.19, yearStart - 60 * day)
                .filter { it.kind in kinds && it.from < it.at }
                .groupBy { it.kind }
                .flatMap { (_, events) -> events.take(2) }
            for (window in windows) {
                seen += window.kind
                // Just inside: the window opens within the hour. Just outside:
                // it opens a day and more later, past the end of the night.
                for (hoursBefore in listOf(1L, 30L)) {
                    val now = window.from - hoursBefore * 3_600_000L
                    val reference = SkyCalendar.events(latitude, -3.19, now, 45)
                    mismatch("lat $latitude, ${hoursBefore}h before ${window.title}", settings, now, reference)
                        ?.let(misses::add)
                }
            }
        }
        assertEquals(kinds, seen)
        assertEquals(emptyList<String>(), misses)
    }
}
