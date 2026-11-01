package sh.arek.gloaming.ui

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility

/**
 * The strip of nights above each month on Ahead.
 *
 * Time zone and locale are pinned so the days and the month names are the same
 * wherever the suite runs.
 */
class MonthStripTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private lateinit var zone: TimeZone
    private lateinit var locale: Locale

    @Before
    fun pin() {
        zone = TimeZone.getDefault()
        locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    @After
    fun unpin() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, hour, 0, 0)
        return calendar.timeInMillis
    }

    private fun event(atMillis: Long) = SkyEvent(
        kind = EventKind.MeteorShower,
        at = atMillis,
        from = atMillis,
        until = atMillis,
        title = "A shower",
        detail = "Some meteors.",
        visibility = Visibility.Good,
    )

    private fun strips(
        from: Long = at(2026, 12, 1),
        months: Int = 1,
        events: List<SkyEvent> = emptyList(),
    ) = MonthStrips.of(latitude, longitude, from, months, events)

    @Test
    fun `a month has one cell per night`() {
        val february = strips(from = at(2027, 2, 10)).single()

        assertEquals(28, february.nights.size)
        assertEquals((1..28).toList(), february.nights.map { it.day })
        assertEquals(2027, february.year)
        assertEquals(2, february.month)
    }

    @Test
    fun `counts the extra day of a leap year`() {
        assertEquals(29, strips(from = at(2028, 2, 1)).single().nights.size)
    }

    @Test
    fun `starts at the month the instant falls in and runs forward`() {
        val run = strips(from = at(2026, 11, 20), months = 3)

        assertEquals(listOf(2026 to 11, 2026 to 12, 2027 to 1), run.map { it.year to it.month })
        assertEquals("December", run[1].name)
    }

    @Test
    fun `the darkest run surrounds the new moon`() {
        // New moon on 9 December 2026.
        val december = strips().single()

        val from = december.darkFrom
        val until = december.darkUntil
        assertNotNull(from)
        assertNotNull(until)
        assertTrue("the run $from..$until should hold the 9th", 9 in from!!..until!!)
        for (night in december.nights.filter { it.day in from..until }) {
            assertTrue("night ${night.day} is ${night.moonlight}", night.moonlight < MonthStrips.DARK_BELOW)
        }
    }

    @Test
    fun `the run is the longest one, not the first`() {
        val december = strips().single()
        val from = december.darkFrom!!
        val until = december.darkUntil!!

        var longest = 0
        var current = 0
        for (night in december.nights) {
            current = if (night.moonlight < MonthStrips.DARK_BELOW) current + 1 else 0
            longest = maxOf(longest, current)
        }

        assertEquals(longest, until - from + 1)
    }

    @Test
    fun `the full moon is brighter than the new moon`() {
        val december = strips().single()

        val newMoon = december.nights.first { it.day == 9 }.moonlight
        val fullMoon = december.nights.first { it.day == 24 }.moonlight

        assertTrue("$fullMoon should beat $newMoon", fullMoon > newMoon + 0.3)
    }

    @Test
    fun `marks the nights with events`() {
        val december = strips(
            events = listOf(event(at(2026, 12, 14)), event(at(2026, 12, 14, 20)), event(at(2026, 12, 21))),
        ).single()

        assertEquals(listOf(14, 21), december.nights.filter { it.hasEvent }.map { it.day })
    }

    @Test
    fun `ignores an event in another month`() {
        val december = strips(events = listOf(event(at(2027, 1, 14)))).single()

        assertFalse(december.nights.any { it.hasEvent })
    }

    @Test
    fun `no night is dark where the sun never sets`() {
        val june = MonthStrips.of(78.2, 15.6, at(2027, 6, 1), 1, emptyList()).single()

        assertTrue(june.nights.all { it.moonlight == 0.0 })
    }

    @Test
    fun `names the darkest run in words`() {
        val december = strips().single()

        val words = december.darkestWords()

        assertTrue(words, words.startsWith("Darkest: "))
        assertTrue(words, words.endsWith(" December"))
        assertTrue(words, words.contains("${december.darkFrom}-${december.darkUntil}"))
    }

    @Test
    fun `names a run of one night without a range`() {
        val strip = strip(darkFrom = 9, darkUntil = 9)

        assertEquals("Darkest: 9 December", strip.darkestWords())
    }

    @Test
    fun `says so when no night is dark`() {
        assertEquals("No dark nights this month", strip(darkFrom = null, darkUntil = null).darkestWords())
    }

    @Test
    fun `says no dark nights are left once the dark run is behind us`() {
        // Dark early on, bright from the 10th, and today is the 20th.
        val cells = (1..31).map {
            NightCell(it, if (it < 10) 0.05 else 0.8, false, past = it < 20, today = it == 20)
        }
        val strip = MonthStrip(2026, 12, "December", cells, null, null)

        assertEquals("No dark nights left this month", strip.darkestWords())
        assertTrue(strip.description(), strip.description().contains("No dark nights left this month."))
    }

    @Test
    fun `on 20 December 2026 the dark nights ahead are the 30th and 31st`() {
        // The run around the new moon is behind us; the next one starts after
        // the full moon, so the strip must name that and not claim none.
        val december = strips(from = at(2026, 12, 20)).single()

        assertEquals("Darkest: 30-31 December", december.darkestWords())
    }

    @Test
    fun `says no dark nights this month when the whole month had none`() {
        val cells = (1..31).map { NightCell(it, 0.8, false, past = it < 20, today = it == 20) }
        val strip = MonthStrip(2026, 12, "December", cells, null, null)

        assertEquals("No dark nights this month", strip.darkestWords())
    }

    @Test
    fun `says which night is today`() {
        val cells = (1..31).map { NightCell(it, 0.5, false, today = it == 20) }
        val words = MonthStrip(2026, 12, "December", cells, null, null).description()

        assertTrue(words, words.endsWith("Today is 20 December."))
    }

    @Test
    fun `the description gives the darkest run and the event days`() {
        val strip = strip(darkFrom = 5, darkUntil = 13, eventDays = setOf(3, 9))

        assertEquals(
            "December 2026. Darkest: 5-13 December. Events on 3 and 9 December.",
            strip.description(),
        )
    }

    @Test
    fun `the description says when there are no events`() {
        assertEquals(
            "December 2026. Darkest: 5-13 December. No events.",
            strip(darkFrom = 5, darkUntil = 13).description(),
        )
    }

    @Test
    fun `the year ahead takes under a second`() {
        // Warm the astronomy tables, as the calendar's own budget does not.
        strips(months = 1)

        val started = System.nanoTime()
        strips(months = MonthStrips.YEAR_AHEAD)
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("took ${tookMillis}ms", tookMillis < BUDGET_MILLIS)
    }

    @Test
    fun `flags the nights before today and today itself`() {
        val december = strips(from = at(2026, 12, 20)).single()

        assertEquals((1..19).toList(), december.nights.filter { it.past }.map { it.day })
        assertEquals(listOf(20), december.nights.filter { it.today }.map { it.day })
        assertFalse(december.nights.first { it.day == 20 }.past)
    }

    @Test
    fun `flags nothing in a month that has not begun`() {
        val january = strips(from = at(2026, 12, 20), months = 2)[1]

        assertFalse(january.nights.any { it.past || it.today })
    }

    @Test
    fun `a past night keeps its shading`() {
        val december = strips(from = at(2026, 12, 20)).single()

        assertTrue(december.nights.first { it.day == 24 }.moonlight > 0.5)
        assertTrue(december.nights.first { it.day == 1 }.moonlight > 0.0)
    }

    @Test
    fun `the darkest run never includes a past night`() {
        // The new moon is on the 9th and the run around it is behind us.
        val december = strips(from = at(2026, 12, 20)).single()

        val from = december.darkFrom
        if (from != null) {
            assertTrue("run starts on $from", from >= 20)
        }
    }

    @Test
    fun `a month that is wholly dark ahead still names a run`() {
        val december = strips(from = at(2026, 12, 6)).single()

        assertEquals(6, december.darkFrom)
    }

    @Test
    fun `the description does not announce past nights as darkest`() {
        val december = strips(from = at(2026, 12, 20)).single()

        val words = december.description()

        assertFalse(words, words.contains("Darkest: 5"))
        assertTrue(words, words.contains("Today is 20 December."))
    }

    private companion object {
        /** Measured at about 0.13 s for the year on the JVM; this leaves room for a slow machine. */
        const val BUDGET_MILLIS = 1_000
    }

    private fun strip(darkFrom: Int?, darkUntil: Int?, eventDays: Set<Int> = emptySet()) = MonthStrip(
        year = 2026,
        month = 12,
        name = "December",
        nights = (1..31).map { NightCell(it, 0.5, it in eventDays) },
        darkFrom = darkFrom,
        darkUntil = darkUntil,
    )
}
