package sh.arek.gloaming.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll runs inside a broadcast, and a broadcast that overruns is killed.
 *
 * Measured on a Pixel 8a: the process starts, the aurora poll is allowed 45
 * seconds on its own, the pass and calendar checks that follow it had no limit
 * at all, and the whole thing was killed at 61 seconds with nothing recorded.
 * These are the sums that stop that happening.
 */
class RefreshBudgetTest {

    @Test
    fun `a stage gets what it asked for when the budget is untouched`() {
        assertEquals(12_000L, Refresh.stageTimeout(remainingMillis = 40_000L, preferredMillis = 12_000L))
    }

    @Test
    fun `a stage gets only what is left when an earlier one ran long`() {
        assertEquals(3_000L, Refresh.stageTimeout(remainingMillis = 3_000L, preferredMillis = 12_000L))
    }

    @Test
    fun `a spent budget leaves a stage nothing rather than a negative timeout`() {
        assertEquals(0L, Refresh.stageTimeout(remainingMillis = -5_000L, preferredMillis = 12_000L))
    }

    /**
     * The point of the whole exercise: every stage's share has to fit inside
     * the broadcast limit together, or the last one is the one that never runs.
     */
    @Test
    fun `the three stages together fit inside the poll budget`() {
        val stages = Refresh.AURORA_MILLIS + Refresh.PASSES_MILLIS + Refresh.EVENTS_MILLIS
        assertTrue(
            "stages total ${stages}ms against a ${Refresh.POLL_BUDGET_MILLIS}ms budget",
            stages <= Refresh.POLL_BUDGET_MILLIS,
        )
    }

    /** And the budget itself has to fit the limit, with room to start the process. */
    @Test
    fun `the poll budget leaves headroom inside the broadcast limit`() {
        assertTrue(
            "budget ${Refresh.POLL_BUDGET_MILLIS}ms against a 60s broadcast limit",
            Refresh.POLL_BUDGET_MILLIS <= 45_000L,
        )
    }

    /**
     * A slow aurora fetch must not be able to eat the pass check's turn. Missing
     * a satellite pass is worse than carrying a reading a few minutes old.
     */
    @Test
    fun `the aurora stage cannot spend the whole budget`() {
        assertTrue(
            "aurora ${Refresh.AURORA_MILLIS}ms of a ${Refresh.POLL_BUDGET_MILLIS}ms budget",
            Refresh.AURORA_MILLIS < Refresh.POLL_BUDGET_MILLIS,
        )
    }
}
