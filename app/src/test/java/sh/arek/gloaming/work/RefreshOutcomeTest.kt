package sh.arek.gloaming.work

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.domain.PollOutcome
import sh.arek.gloaming.domain.PollingPolicy
import sh.arek.gloaming.domain.PollPlan
import sh.arek.gloaming.domain.PollResult

/**
 * Which of the three decides the next wake.
 *
 * The whole reason this is a function rather than a line in each caller: the
 * scheduled wake had it right and the button had it wrong, and nothing but a
 * shared answer stops that happening again.
 */
class RefreshOutcomeTest {

    private fun aurora(minutes: Int) = PollResult(
        outcome = PollOutcome.NoGrid,
        next = PollPlan(delayMinutes = minutes, reason = PollingPolicy.Reason.DAYLIGHT),
    )

    /**
     * The bug this exists to prevent. A quiet overcast night lets the aurora
     * schedule sleep an hour; a pass alert is due in half. Taking the aurora
     * interval alone delivers that alert after the pass has gone.
     */
    @Test
    fun `takes the pass wake when it comes before the aurora one`() {
        val outcome = RefreshOutcome(aurora(60), passWakeMinutes = 30, eventWakeMinutes = null)

        assertEquals(30, outcome.nextWakeMinutes(fallbackMinutes = 15))
    }

    @Test
    fun `takes the calendar wake when it is the soonest`() {
        val outcome = RefreshOutcome(aurora(60), passWakeMinutes = 45, eventWakeMinutes = 5)

        assertEquals(5, outcome.nextWakeMinutes(fallbackMinutes = 15))
    }

    @Test
    fun `keeps the aurora wake when it is the soonest`() {
        val outcome = RefreshOutcome(aurora(5), passWakeMinutes = 45, eventWakeMinutes = 360)

        assertEquals(5, outcome.nextWakeMinutes(fallbackMinutes = 15))
    }

    /**
     * An aurora poll that failed outright still leaves a schedule behind. The
     * flat interval is the fallback, and the other two still get their say.
     */
    @Test
    fun `falls back to the flat interval when the aurora poll got nowhere`() {
        val outcome = RefreshOutcome(aurora = null, passWakeMinutes = null, eventWakeMinutes = null)

        assertEquals(15, outcome.nextWakeMinutes(fallbackMinutes = 15))
    }

    @Test
    fun `still hears the other two when the aurora poll got nowhere`() {
        val outcome = RefreshOutcome(aurora = null, passWakeMinutes = 7, eventWakeMinutes = 90)

        assertEquals(7, outcome.nextWakeMinutes(fallbackMinutes = 15))
    }

    /** A poller that asked for nothing does not get to hold the schedule open. */
    @Test
    fun `ignores a poller that asked for nothing`() {
        val outcome = RefreshOutcome(aurora(20), passWakeMinutes = null, eventWakeMinutes = null)

        assertEquals(20, outcome.nextWakeMinutes(fallbackMinutes = 15))
    }
}
