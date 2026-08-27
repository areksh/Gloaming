package sh.arek.gloaming.share

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility

/** The message that goes into a chat window rather than into a calendar. */
class ShareTest {

    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    @Before
    fun fixTheMachine() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreTheMachine() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun event(
        title: String = "Total lunar eclipse",
        detail: String = "Totality from 02:11 to 03:24.",
        from: String = "2028-12-31T01:00:00Z",
        until: String = "2028-12-31T04:30:00Z",
        visibility: Visibility = Visibility.Good,
    ) = SkyEvent(
        kind = EventKind.LunarEclipse,
        at = at(from),
        from = at(from),
        until = at(until),
        title = title,
        detail = detail,
        visibility = visibility,
    )

    @Test
    fun `leads with the title, the date and the span`() {
        val text = shareText(event())

        assertEquals(
            """
            Total lunar eclipse
            Sunday 31 December 2028, 01:00 to 04:30

            Totality from 02:11 to 03:24.
            """.trimIndent(),
            text,
        )
    }

    /** A shower runs past midnight, and one date would be a lie about half of it. */
    @Test
    fun `names both dates when the event crosses midnight`() {
        val text = shareText(
            event(from = "2028-12-13T20:00:00Z", until = "2028-12-14T06:00:00Z"),
        )

        assertTrue(text, text.contains("Wednesday 13 December 2028 20:00"))
        assertTrue(text, text.contains("Thursday 14 December 2028 06:00"))
    }

    /**
     * The caveat travels with the event. "It only reaches 4 degrees" is the
     * difference between a wasted evening and a good one, and sending the
     * headline without it is sending a half-truth.
     */
    @Test
    fun `carries the reason an event is only marginal`() {
        val text = shareText(
            event(visibility = Visibility.Marginal("It only reaches 4 degrees, low in the south.")),
        )

        assertTrue(text, text.endsWith("It only reaches 4 degrees, low in the south."))
    }

    @Test
    fun `says nothing extra about an event that is simply worth seeing`() {
        val text = shareText(event())

        assertTrue(text.endsWith("Totality from 02:11 to 03:24."))
        assertFalse(text.endsWith("\n"))
    }
}
