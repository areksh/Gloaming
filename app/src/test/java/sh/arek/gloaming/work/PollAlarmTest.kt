package sh.arek.gloaming.work

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delay the scheduler actually asks for.
 *
 * Jitter exists to stop every install waking on the same boundary and arriving
 * at the CDN together. It must not double as a delay: the system already adds a
 * batching window of its own to an inexact alarm, and a jitter that only ever
 * pushes the wake later stacks on top of that. Spread around the requested time
 * de-aligns installs just as well and leaves the average where it was asked to
 * be.
 */
class PollAlarmTest {

    private val minute = 60_000L

    @Test
    fun `spreads either side of the requested interval rather than only later`() {
        val delays = (0 until 400).map { PollAlarm.delayMillis(30, Random(it)) }

        assertTrue("nothing landed early: the spread is one-sided", delays.any { it < 30 * minute })
        assertTrue("nothing landed late", delays.any { it > 30 * minute })
    }

    /** The spread is a de-aliasing measure, not a rescheduling one. */
    @Test
    fun `stays within an eighth of the interval`() {
        val spread = 30 * minute / 8

        for (seed in 0 until 400) {
            val delay = PollAlarm.delayMillis(30, Random(seed))
            assertTrue(
                "$delay is further than $spread from the requested interval",
                delay in (30 * minute - spread)..(30 * minute + spread),
            )
        }
    }

    /**
     * Storm mode aside, the shortest interval the policy asks for is five
     * minutes; a caller asking for less, or for nothing, must still produce a
     * wake in the future rather than one in the past.
     */
    @Test
    fun `never asks for a wake that has already passed`() {
        for (interval in listOf(0, -5, 1)) {
            for (seed in 0 until 50) {
                val delay = PollAlarm.delayMillis(interval, Random(seed))
                assertTrue("interval $interval produced $delay", delay > 0)
            }
        }
    }

    /** Same seed, same answer: the spread is random, not arbitrary. */
    @Test
    fun `is deterministic for a given source of randomness`() {
        assertEquals(PollAlarm.delayMillis(15, Random(7)), PollAlarm.delayMillis(15, Random(7)))
    }
}
