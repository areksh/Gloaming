package sh.arek.gloaming.ui

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeOfDayTest {

    @Test
    fun `reads a time of day as minutes since midnight`() {
        assertEquals(0, parseTimeOfDay("00:00"))
        assertEquals(23 * 60, parseTimeOfDay("23:00"))
        assertEquals(7 * 60 + 30, parseTimeOfDay("07:30"))
        assertEquals(24 * 60 - 1, parseTimeOfDay("23:59"))
    }

    @Test
    fun `accepts the forms people actually type`() {
        assertEquals(7 * 60, parseTimeOfDay("7:00"))
        assertEquals(7 * 60 + 5, parseTimeOfDay(" 7:05 "))
        assertEquals(23 * 60, parseTimeOfDay("23.00"))
    }

    // Rejected rather than clamped: a typo silently becoming 23:59 would set
    // quiet hours the user did not ask for and never notice.
    @Test
    fun `rejects anything that is not a time`() {
        assertNull(parseTimeOfDay(""))
        assertNull(parseTimeOfDay("bedtime"))
        assertNull(parseTimeOfDay("24:00"))
        assertNull(parseTimeOfDay("23:60"))
        assertNull(parseTimeOfDay("-1:00"))
        assertNull(parseTimeOfDay("2300"))
    }

    @Test
    fun `writes minutes back as a padded time of day`() {
        assertEquals("00:00", formatTimeOfDay(0))
        assertEquals("07:05", formatTimeOfDay(7 * 60 + 5))
        assertEquals("23:59", formatTimeOfDay(24 * 60 - 1))
    }

    @Test
    fun `round-trips every minute of the day`() {
        for (minute in 0 until 24 * 60) {
            assertEquals(minute, parseTimeOfDay(formatTimeOfDay(minute)))
        }
    }

    /** The date over the outlook: the weekday and the day, and no year. */
    @Test
    fun `writes the date as the weekday, the day and the month`() {
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        Locale.setDefault(Locale.UK)
        try {
            // 2026-10-31T20:05:00Z, a Saturday evening in October.
            assertEquals("Saturday 31 October", describeDate(1_793_477_100_000L))
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
        }
    }
}
