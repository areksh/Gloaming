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

    /** Long enough for a slow network, short enough not to hold a wake lock past it. */
    const val TIMEOUT_MILLIS: Long = 45_000L

    suspend fun everything(context: Context, tag: String): RefreshOutcome {
        val application = context.applicationContext

        val aurora = runCatching {
            withTimeout(TIMEOUT_MILLIS) { Graph.poller(application).poll() }
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
            val passes = Graph.passPoller(application).poll()
            val wake = minutesUntil(passes.nextWakeAtMillis)
            Notes.i(application, tag, "pass check: ${passes.decision} (next in ${wake}m)")
            wake
        }.onFailure { Notes.w(application, tag, "pass check failed", it) }.getOrNull()

        val eventWake = runCatching {
            val events = Graph.eventPoller(application).poll()
            val wake = minutesUntil(events.nextWakeAtMillis)
            Notes.i(application, tag, "calendar check: ${events.decision} (next in ${wake}m)")
            wake
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
