package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Data age is shown prominently rather than buried.
 *
 * "Kp 6" from three days ago is worse than showing nothing, and a device that
 * has been dormant through a fortnight of overcast nights is correctly holding
 * old data - the UI has to distinguish "quiet" from "hasn't looked recently".
 */
class StalenessTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 100 * day

    @Test
    fun `says so when nothing has been checked yet`() {
        assertEquals("Not checked yet", describeAge(null, now))
    }

    @Test
    fun `reads as just now within the first minute`() {
        assertEquals("Checked just now", describeAge(now - 30_000, now))
    }

    @Test
    fun `counts minutes, singular and plural`() {
        assertEquals("Checked 1 minute ago", describeAge(now - minute, now))
        assertEquals("Checked 5 minutes ago", describeAge(now - 5 * minute, now))
        assertEquals("Checked 59 minutes ago", describeAge(now - 59 * minute, now))
    }

    @Test
    fun `counts hours once past the hour`() {
        assertEquals("Checked 1 hour ago", describeAge(now - hour, now))
        assertEquals("Checked 3 hours ago", describeAge(now - 3 * hour, now))
    }

    // The wording shifts at a day: past this point the number is the story, and
    // it must not read as though the app is up to date.
    @Test
    fun `changes wording once a day has passed`() {
        assertEquals("Last checked 1 day ago", describeAge(now - day, now))
        assertEquals("Last checked 14 days ago", describeAge(now - 14 * day, now))
    }

    // Clock changes and timezone shifts can put the stored time in the future.
    // Showing a negative age would look broken; "just now" is honest enough.
    @Test
    fun `does not report a negative age when the clock moves backwards`() {
        assertEquals("Checked just now", describeAge(now + hour, now))
    }
}
