package sh.arek.gloaming.ui

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility

/** What Ahead hands the shared row sheet, and how it counts the kinds shown. */
class AheadRowsTest {

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

    private fun event(
        kind: EventKind = EventKind.LunarEclipse,
        atMillis: Long = Calendar.getInstance().run {
            clear()
            set(2027, Calendar.MARCH, 14, 19, 5, 0)
            timeInMillis
        },
    ) = SkyEvent(
        kind = kind,
        at = atMillis,
        from = atMillis - 3_600_000L,
        until = atMillis + 3_600_000L,
        title = "Total eclipse of the moon",
        detail = "In the Earth's deep shadow.",
        visibility = Visibility.Good,
    )

    @Test
    fun `the sheet row carries the event itself`() {
        val shown = event()

        val row = eventRow(shown)

        assertEquals(RowSubject.Event(shown), row.subject)
        assertEquals(shown.at, row.atMillis)
        assertEquals(shown.title, row.title)
    }

    @Test
    fun `the sheet row says the day as well as the time`() {
        assertEquals("Sun 14 March, 19:05", eventRow(event()).time)
    }

    @Test
    fun `the sheet row leaves the detail to the sheet`() {
        // The sheet prints an event's own detail; a copy here would say it twice.
        assertNull(eventRow(event()).detail)
        assertEquals(RowTone.Normal, eventRow(event()).tone)
    }

    @Test
    fun `counts every kind as shown when none is hidden`() {
        assertEquals(EventKind.entries.size, kindsShowing(emptySet()))
        assertEquals(14, kindsShowing(emptySet()))
    }

    @Test
    fun `counts the kinds left after some are hidden`() {
        assertEquals(9, kindsShowing(setOf("MoonPhase", "MoonQuarter", "Season", "Constellation", "Comet")))
    }

    @Test
    fun `ignores a hidden name that is no longer a kind`() {
        assertEquals(13, kindsShowing(setOf("MoonPhase", "Retired")))
    }
}
