package sh.arek.gloaming.astronomy

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar is the poll's other long computation, and the last one that
 * could run a broadcast past the sixty seconds Android allows it.
 *
 * A year of eclipses, elongations, conjunctions, showers, oppositions,
 * brightness peaks, constellations, seasons and moon phases measures 11.6
 * seconds on a woken Pixel 8a and 12.5 in Doze. That fits today. It is not
 * bounded by anything, though, and the satellite sweep taught what an unbounded
 * computation inside a broadcast costs: the kill takes the reschedule with it,
 * nothing is recorded, and the next wake repeats the work.
 *
 * So the caller may ask it to stop. The predicate keeps the clock on the
 * caller's side - this package stays clock-free, as the sweep's does.
 */
class CalendarBudgetTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt() - 1,
            iso.substring(8, 10).toInt(), 0, 0, 0)
        return calendar.timeInMillis
    }

    @Test
    fun `runs to the end when nothing asks it to stop`() {
        val events = SkyCalendar.events(latitude, longitude, at("2027-01-01"), 90)

        assertTrue("a quarter of a year should hold something", events.isNotEmpty())
    }

    /**
     * Refusing rather than returning what it had. A truncated calendar is not a
     * quiet sky: it would announce whatever it happened to reach and leave an
     * eclipse unmentioned because the budget ran out before eclipses.
     */
    @Test
    fun `refuses to hand back a half-built calendar`() {
        assertThrows(SkyCalendar.Interrupted::class.java) {
            SkyCalendar.events(latitude, longitude, at("2027-01-01"), 365) { true }
        }
    }

    /** Asked at every seam, so the stop takes effect without finishing the year. */
    @Test
    fun `asks more than once on the way through`() {
        var asked = 0
        runCatching {
            SkyCalendar.events(latitude, longitude, at("2027-01-01"), 365) {
                asked++
                false
            }
        }

        assertTrue("only asked $asked time(s); a stop could not land between them", asked >= 5)
    }

    /** The default keeps every existing caller exactly as it was. */
    @Test
    fun `the same calendar either way`() {
        val plain = SkyCalendar.events(latitude, longitude, at("2027-03-01"), 60)
        val asked = SkyCalendar.events(latitude, longitude, at("2027-03-01"), 60) { false }

        assertEquals(plain.map { it.kind to it.at }, asked.map { it.kind to it.at })
    }
}
