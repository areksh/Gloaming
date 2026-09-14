package sh.arek.gloaming.work

import android.content.Context
import kotlinx.coroutines.withTimeout
import sh.arek.gloaming.Graph
import sh.arek.gloaming.Notes
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.domain.PollResult
import sh.arek.gloaming.widget.AuroraWidget

/**
 * What one full refresh found, and when the soonest of the three wants waking.
 *
 * The three pollers are deliberately separate things (ADR-0009) and this is not
 * an attempt to unify them. It is the one piece they genuinely share: a wake
 * runs all three, and the next alarm belongs to whichever asks first.
 */
data class RefreshOutcome(
    val aurora: PollResult?,
    /** Minutes until the pass planner wants waking, or null if it asked for nothing. */
    val passWakeMinutes: Int?,
    val eventWakeMinutes: Int?,
) {
    /**
     * The soonest of the three, in minutes.
     *
     * This is the arithmetic that was wrong. A scheduled wake took the soonest
     * of the three; "check now" took the aurora interval alone - so on a quiet
     * overcast night, where the aurora schedule is content to sleep for an
     * hour, pressing the button could push the next wake out past a pass alert
     * due in half an hour and deliver it after the pass. Having one function
     * both callers use is the point of it existing.
     *
     * @param fallbackMinutes used when the aurora poll did not get far enough
     *   to return a plan of its own.
     */
    fun nextWakeMinutes(fallbackMinutes: Int): Int = listOfNotNull(
        aurora?.next?.delayMinutes ?: fallbackMinutes,
        passWakeMinutes,
        eventWakeMinutes,
    ).min()
}

/**
 * Everything the app looks at, in one go.
 *
 * Shared by the scheduled wake and by the button, so that pressing "check now"
 * does exactly what the alarm would have done a few minutes later - which is
 * what somebody pressing it expects, and is also the only way the two paths
 * cannot drift apart again.
 *
 * Nothing here throws. Each poller is guarded on its own, because one of them
 * failing must not cost the others their turn or the schedule its next wake.
 */
object Refresh {

    /**
     * What the whole refresh may spend, when it runs inside a broadcast.
     *
     * A `goAsync` broadcast is killed at sixty seconds, and the kill is not
     * graceful: the coroutine's `finally` never runs, so nothing is recorded
     * and - because the provisional alarm is still the one armed at the top of
     * PollReceiver - the next wake comes almost immediately and repeats the
     * same work. Measured on a Pixel 8a, that loop ran for hours at 95% CPU
     * and completed not one pass check in eighteen polls.
     *
     * The old arithmetic could not fit. The aurora poll alone was allowed
     * forty-five seconds and the two checks after it were allowed everything
     * they liked, which on a slow network came to seventy-plus.
     *
     * Forty-five seconds, against a sixty second limit, leaves room for the
     * process start that comes out of the same allowance - six seconds of it,
     * measured.
     */
    const val POLL_BUDGET_MILLIS: Long = 45_000L

    /**
     * What each stage may spend, and they are separate numbers on purpose.
     *
     * Splitting the budget rather than letting each stage take what is left
     * guarantees the later ones a turn. A slow aurora fetch must not be able to
     * spend the pass check's share: a stale aurora reading costs a few minutes
     * of latency on something that changes over hours, where a missed pass
     * check costs the whole pass.
     */
    /**
     * What these numbers do and do not bind, because the first guess at them
     * was wrong in a way only a measurement showed.
     *
     * `withTimeout` cancels at suspension points. The network inside each stage
     * has plenty, so a slow fetch really is cut off - which is the failure that
     * caused the overrun and the one these exist for. The astronomy is a tight
     * loop with none, so a stage that is computing cannot be interrupted and
     * simply runs to the end of its work.
     *
     * Measured on a Pixel 8a, one complete poll: the pass check took 7.45s
     * against its twelve, and the calendar check took 11.64s against an earlier
     * budget of eight - over it, uncancelled, and completing anyway. The eight
     * was a guess, and an unenforceable guess reads in the source as a
     * guarantee. Fifteen is what the work actually costs.
     *
     * So the compute is bounded by the work rather than by these, at around
     * twenty seconds for both checks together. What keeps that from becoming
     * another overrun on slower hardware is not this: it is the provisional
     * wake in PollAlarm, which makes a killed poll cost one cycle instead of
     * looping.
     */
    const val AURORA_MILLIS: Long = 18_000L
    const val PASSES_MILLIS: Long = 12_000L
    const val EVENTS_MILLIS: Long = 15_000L

    /**
     * What a stage actually gets: its share, or whatever is left of the budget,
     * whichever is smaller, and never a negative number.
     *
     * Zero means the budget is gone and the stage is skipped rather than run.
     * `withTimeout(0)` would throw instantly and read in the log as a failure,
     * which is the wrong word for a turn that never came.
     */
    fun stageTimeout(remainingMillis: Long, preferredMillis: Long): Long =
        minOf(preferredMillis, remainingMillis).coerceAtLeast(0L)

    /**
     * The stage budgets apply to the button as much as to the alarm, and that
     * is a deliberate small loss: pressing "check now" on a slow network now
     * gives the aurora fetch twenty seconds where it used to give forty-five.
     * One set of numbers for both callers is what stops them drifting - the
     * same reason `everything` exists at all - and the constrained caller is
     * the one that has to set the bar.
     *
     * @param budgetMillis a backstop on the whole refresh, over and above the
     *   per-stage shares. It only bites when a stage overruns its own.
     */
    suspend fun everything(
        context: Context,
        tag: String,
        budgetMillis: Long = POLL_BUDGET_MILLIS,
    ): RefreshOutcome {
        val application = context.applicationContext
        val deadline = System.currentTimeMillis() + budgetMillis
        val remaining = { deadline - System.currentTimeMillis() }

        val aurora = runCatching {
            val budget = stageTimeout(remaining(), AURORA_MILLIS)
            if (budget <= 0) {
                Notes.i(application, tag, "aurora poll skipped: no time left in the poll budget")
                null
            } else {
                withTimeout(budget) { Graph.poller(application).poll() }
            }
        }.onFailure { Notes.w(application, tag, "aurora poll failed", it) }.getOrNull()

        if (aurora != null) {
            val unlocked = BootAware.isUnlocked(application)
            val storm = if (aurora.storm) ", storm" else ""
            Notes.i(
                application,
                tag,
                "poll outcome: ${aurora.outcome} (unlocked=$unlocked, " +
                    "next in ${aurora.next.delayMinutes}m: ${aurora.next.reason}$storm)",
            )
            // Only once unlocked: a foreground service cannot be started before
            // then, and the attempt would throw.
            if (unlocked) StormService.apply(application, aurora.storm)
        }

        val passWake = runCatching {
            val budget = stageTimeout(remaining(), PASSES_MILLIS)
            if (budget <= 0) {
                Notes.w(application, tag, "pass check skipped: no time left in the poll budget")
                null
            } else {
                val passes = withTimeout(budget) { Graph.passPoller(application).poll() }
                val wake = minutesUntil(passes.nextWakeAtMillis)
                Notes.i(application, tag, "pass check: ${passes.decision} (next in ${wake}m)")
                wake
            }
        }.onFailure { Notes.w(application, tag, "pass check failed", it) }.getOrNull()

        val eventWake = runCatching {
            val budget = stageTimeout(remaining(), EVENTS_MILLIS)
            if (budget <= 0) {
                Notes.w(application, tag, "calendar check skipped: no time left in the poll budget")
                null
            } else {
                val events = withTimeout(budget) { Graph.eventPoller(application).poll() }
                val wake = minutesUntil(events.nextWakeAtMillis)
                Notes.i(application, tag, "calendar check: ${events.decision} (next in ${wake}m)")
                wake
            }
        }.onFailure { Notes.w(application, tag, "calendar check failed", it) }.getOrNull()

        // Redrawn by the refresh rather than on a schedule of its own, so it is
        // exactly as fresh as the data behind it and costs no wakes.
        runCatching { AuroraWidget.refresh(application) }

        return RefreshOutcome(aurora, passWake, eventWake)
    }

    /** At least a minute: an alarm asked for "now" would spin. */
    fun minutesUntil(millis: Long): Int {
        val delay = millis - System.currentTimeMillis()
        return (delay / 60_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
}
