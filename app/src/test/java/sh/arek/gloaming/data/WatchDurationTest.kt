package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long a watch the user started runs before it stops itself.
 *
 * Previously a constant nobody could see or change: four hours, described in
 * the code as "a safety net, not a feature". It is both. How long somebody
 * intends to stand outside is their call - the same reasoning that made the
 * interruption intervals a setting - but a watch polls every two minutes and
 * holds a wake lock, so it must still end on its own if it is forgotten.
 */
class WatchDurationTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `runs for the span the user picked`() {
        assertEquals(60 * 60_000L, WatchDuration.OneHour.capMillis(now, null))
        assertEquals(2 * 60 * 60_000L, WatchDuration.TwoHours.capMillis(now, null))
        assertEquals(4 * 60 * 60_000L, WatchDuration.FourHours.capMillis(now, null))
        assertEquals(8 * 60 * 60_000L, WatchDuration.EightHours.capMillis(now, null))
    }

    /**
     * The option no other app can offer, because this one already knows when
     * astronomical dawn is where the user is standing. It is also the natural
     * end of an aurora watch: there is genuinely nothing to see after it.
     */
    @Test
    fun `runs until it gets light when asked`() {
        val dawn = now + 3 * 60 * 60_000L + 12 * 60_000L

        assertEquals(dawn - now, WatchDuration.UntilLight.capMillis(now, dawn))
    }

    /**
     * Above the Arctic circle in summer the sun never gets far enough down and
     * darkness never ends, so there is no dawn to run to. The type already
     * forces the question by making the moment nullable; this is the answer.
     */
    @Test
    fun `falls back to the ceiling when darkness has no end`() {
        assertEquals(WatchDuration.MAXIMUM_MILLIS, WatchDuration.UntilLight.capMillis(now, null))
    }

    /** A dawn already behind us is not a duration. */
    @Test
    fun `falls back to the ceiling when it is already light`() {
        assertEquals(
            WatchDuration.MAXIMUM_MILLIS,
            WatchDuration.UntilLight.capMillis(now, now - 60_000L),
        )
    }

    /**
     * A long winter night could ask for fourteen hours of wake lock and
     * two-minutely polling. The ceiling is the one thing here that is not the
     * user's to set: an unbounded watch is a flat battery by morning, and it
     * lands on exactly the person who tapped it at eleven and fell asleep.
     */
    @Test
    fun `never runs past the ceiling`() {
        val midwinterDawn = now + 14 * 60 * 60_000L

        assertEquals(
            WatchDuration.MAXIMUM_MILLIS,
            WatchDuration.UntilLight.capMillis(now, midwinterDawn),
        )
    }
}
