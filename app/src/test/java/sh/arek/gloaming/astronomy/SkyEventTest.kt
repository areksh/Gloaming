package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether something is happening now, in the sense somebody standing outside
 * would mean by it.
 *
 * The calendar keeps an entry while any part of it is still ahead, so the list
 * now holds things already under way alongside things weeks off. Telling them
 * apart is what the screen needs, and "now is inside the window" is not enough
 * on its own: an opposition's window is six weeks, and a card that said
 * "happening now" for six weeks would mean nothing by the end of the first.
 */
class SkyEventTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun eclipse() = SkyEvent(
        kind = EventKind.LunarEclipse,
        at = at("2029-12-20T22:41:54Z"),
        from = at("2029-12-20T21:10:00Z"),
        until = at("2029-12-21T00:15:00Z"),
        title = "Total eclipse of the moon",
        detail = "100 per cent of the moon's disc in the Earth's deep shadow.",
        visibility = Visibility.Good,
    )

    /** A shower runs the whole night, which is a window and not a moment. */
    private fun shower() = SkyEvent(
        kind = EventKind.MeteorShower,
        at = at("2026-12-14T02:15:00Z"),
        from = at("2026-12-13T16:25:00Z"),
        until = at("2026-12-14T07:49:36Z"),
        title = "Geminids at their best",
        detail = "Perhaps 137 an hour from here at its best.",
        visibility = Visibility.Good,
    )

    /** Three weeks either side of its date, and it says so in its own text. */
    private fun opposition() = SkyEvent(
        kind = EventKind.Opposition,
        at = at("2026-01-10T08:29:31Z"),
        from = at("2025-12-20T08:29:31Z"),
        until = at("2026-01-31T08:29:31Z"),
        title = "Jupiter at its best for the year",
        detail = "Opposite the sun, so it rises at sunset and is up all night.",
        visibility = Visibility.Good,
    )

    private fun moonPhase() = SkyEvent(
        kind = EventKind.MoonPhase,
        at = at("2026-11-09T07:02:00Z"),
        from = at("2026-11-09T07:02:00Z"),
        until = at("2026-11-09T07:02:00Z"),
        title = "Full moon",
        detail = "The brightest sky of the month.",
        visibility = Visibility.Good,
    )

    @Test
    fun `an eclipse between its contacts is under way`() {
        assertTrue(eclipse().isUnderWay(at("2029-12-20T22:00:00Z")))
    }

    @Test
    fun `an eclipse an hour before first contact is not`() {
        assertFalse(eclipse().isUnderWay(at("2029-12-20T20:10:00Z")))
    }

    @Test
    fun `an eclipse after last contact is not`() {
        assertFalse(eclipse().isUnderWay(at("2029-12-21T01:00:00Z")))
    }

    /** The edges belong to it: an eclipse at first contact has begun. */
    @Test
    fun `an eclipse at either contact is under way`() {
        assertTrue(eclipse().isUnderWay(at("2029-12-20T21:10:00Z")))
        assertTrue(eclipse().isUnderWay(at("2029-12-21T00:15:00Z")))
    }

    @Test
    fun `a shower during its night is under way`() {
        assertTrue(shower().isUnderWay(at("2026-12-14T01:00:00Z")))
    }

    @Test
    fun `a shower the following afternoon is not`() {
        assertFalse(shower().isUnderWay(at("2026-12-14T15:00:00Z")))
    }

    /**
     * The case the rule exists for. Jupiter really is at its best for six
     * weeks, and it is on the list for all of them - but "happening now" is a
     * claim about a moment, and stretching it over six weeks makes it a label
     * on half the calendar and a signal about nothing.
     */
    @Test
    fun `a planet in the middle of its season is not under way`() {
        assertFalse(opposition().isUnderWay(at("2026-01-10T20:00:00Z")))
    }

    /** And an instant is not something anybody can be in the middle of. */
    @Test
    fun `a moon phase is never under way, not even at its own moment`() {
        assertFalse(moonPhase().isUnderWay(at("2026-11-09T07:02:00Z")))
    }
}
