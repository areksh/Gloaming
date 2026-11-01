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

    @Test
    fun `tonight's events match what the year scan lists for tonight`() {
        val misses = mutableListOf<String>()
        var checked = 0
        var withEvents = 0

        fun check(label: String, settings: UserSettings, now: Long, comets: CometsDocument?) {
            val latitude = settings.latitude!!
            val longitude = settings.longitude!!
            val year = SkyCalendar.events(latitude, longitude, now) +
                Comets.calendar(comets, latitude, longitude, settings, now)
            val expected = eventsOf(settings, now, year.sortedBy { it.at }, comets)
            val actual = eventsOf(
                settings,
                now,
                TonightEvents.of(latitude, longitude, now, comets, settings),
                comets,
            )
            checked++
            if (expected.isNotEmpty()) withEvents++
            if (describe(expected) != describe(actual)) {
                misses += "$label: expected ${describe(expected)} but got ${describe(actual)}"
            }
        }

        for (scenario in Scenarios.all) {
            if (scenario.latitude == null) continue
            check(scenario.name, scenario.settings, scenario.nowMillis, scenario.comets)
        }
        for (latitude in listOf(60.0, 0.0, -45.0)) {
            val settings = UserSettings(latitude = latitude, longitude = -3.19)
            var offset = 0L
            while (offset < 365) {
                check("lat $latitude day $offset", settings, yearStart + offset * day + 21 * 3_600_000L, null)
                offset += 7
            }
        }

        assertTrue("the sample found no events at all", withEvents > 0)
        assertEquals("nights checked: $checked", emptyList<String>(), misses)
    }

    /**
     * The weekly sample can step over the nights that decide the horizon: an
     * opposition opens three weeks before its date, so the night it first
     * shows is a little over twenty-one days ahead of it, which a sample on a
     * fixed beat lands on once in seven times. These nights are put on the
     * edge on purpose, with a sixty-day scan as the reference.
     */
    @Test
    fun `an opposition is listed from the night its window opens`() {
        val misses = mutableListOf<String>()
        var checked = 0

        for (latitude in listOf(60.0, 0.0, -45.0)) {
            val settings = UserSettings(latitude = latitude, longitude = -3.19)
            val oppositions = SkyCalendar.events(latitude, -3.19, yearStart)
                .filter { it.kind == EventKind.Opposition }
            for (opposition in oppositions) {
                for (hoursBefore in listOf(1L, 12L, 20L, 28L)) {
                    val now = opposition.from - hoursBefore * 3_600_000L
                    val reference = SkyCalendar.events(latitude, -3.19, now, 60)
                    val expected = eventsOf(settings, now, reference)
                    val actual = eventsOf(
                        settings,
                        now,
                        TonightEvents.of(latitude, -3.19, now, null, settings),
                    )
                    checked++
                    if (describe(expected) != describe(actual)) {
                        misses += "lat $latitude, ${hoursBefore}h before ${opposition.title}: " +
                            "expected ${describe(expected)} but got ${describe(actual)}"
                    }
                }
            }
        }

        assertTrue("no oppositions were sampled", checked > 0)
        assertEquals("nights checked: $checked", emptyList<String>(), misses)
    }
}
