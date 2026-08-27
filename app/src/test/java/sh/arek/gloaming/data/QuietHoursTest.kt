package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `covers the minutes between start and end`() {
        val quiet = QuietHours(startMinute = at(13), endMinute = at(14))

        assertTrue(quiet.contains(at(13, 30)))
        assertFalse(quiet.contains(at(12, 59)))
        assertFalse(quiet.contains(at(14, 30)))
    }

    // The interesting case, and the only one anybody actually sets: quiet hours
    // run overnight, so they wrap past midnight.
    @Test
    fun `wraps across midnight`() {
        val quiet = QuietHours(startMinute = at(23), endMinute = at(7))

        assertTrue(quiet.contains(at(23, 30)))
        assertTrue(quiet.contains(at(0, 1)))
        assertTrue(quiet.contains(at(6, 59)))
        assertFalse(quiet.contains(at(22, 59)))
        assertFalse(quiet.contains(at(7)))
        assertFalse(quiet.contains(at(12)))
    }

    // Half-open, so an end of 07:00 means alerts resume at 07:00 rather than
    // 07:01, and two adjacent periods do not overlap by a minute.
    @Test
    fun `includes its start minute and excludes its end minute`() {
        val quiet = QuietHours(startMinute = at(23), endMinute = at(7))

        assertTrue(quiet.contains(at(23)))
        assertFalse(quiet.contains(at(7)))
    }

    // The same boundary on the branch that does not wrap. The two branches
    // share no comparison, so the test above proves nothing about this one -
    // found by a mutation that excluded the start minute here and survived
    // the whole suite.
    @Test
    fun `includes the start minute of a same-day period too`() {
        val quiet = QuietHours(startMinute = at(13), endMinute = at(14))

        assertTrue(quiet.contains(at(13)))
        assertFalse(quiet.contains(at(14)))
    }

    /**
     * "Quiet from 23:00 until 23:00" is a contradiction, and reading it as
     * silence for a whole day would mute the app permanently through a
     * plausible off-by-one in a time picker. It reads as no quiet hours at all.
     */
    @Test
    fun `covers nothing when start and end are the same`() {
        val quiet = QuietHours(startMinute = at(23), endMinute = at(23))

        assertFalse(quiet.contains(at(23)))
        assertFalse(quiet.contains(at(3)))
        assertFalse(quiet.contains(at(12)))
    }

    // --- when the silence lifts ---------------------------------------------

    @Test
    fun `counts the minutes remaining until it ends`() {
        val quiet = QuietHours(startMinute = at(23), endMinute = at(7))

        assertEquals(60, quiet.minutesUntilEnd(at(6)))
        assertEquals(1, quiet.minutesUntilEnd(at(6, 59)))
    }

    // The count has to cross midnight with the period. Measured forwards from
    // the moment asked about, so 23:30 is seven and a half hours from a 07:00
    // end rather than sixteen and a half hours back to it.
    @Test
    fun `counts forwards across midnight`() {
        val quiet = QuietHours(startMinute = at(23), endMinute = at(7))

        assertEquals(7 * 60 + 30, quiet.minutesUntilEnd(at(23, 30)))
        assertEquals(6 * 60 + 59, quiet.minutesUntilEnd(at(0, 1)))
    }

    @Test
    fun `counts the minutes remaining in a same-day period`() {
        val quiet = QuietHours(startMinute = at(13), endMinute = at(14))

        assertEquals(30, quiet.minutesUntilEnd(at(13, 30)))
    }

    /**
     * Asked at the end minute itself the answer is none, not a whole day.
     * Nothing calls it there - the period does not contain its own end - but a
     * total function beats one with a hole in it, and a stray day of silence
     * is the expensive way to get this wrong.
     */
    @Test
    fun `is already over at its end minute`() {
        val quiet = QuietHours(startMinute = at(23), endMinute = at(7))

        assertEquals(0, quiet.minutesUntilEnd(at(7)))
    }
}
