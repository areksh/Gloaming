package sh.arek.gloaming.work

import sh.arek.gloaming.Notes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import sh.arek.gloaming.Graph
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.domain.PollPlan
import sh.arek.gloaming.widget.AuroraWidget

/**
 * Runs one poll when the alarm fires.
 *
 * Declared `directBootAware` in the manifest, so it is delivered even before
 * the device has been unlocked after a reboot.
 *
 * A receiver has only a few seconds on the main thread, so the work goes to
 * `goAsync` with a wake lock held: a poll is a couple of small conditional GETs
 * that usually transfer nothing, but a slow network must not lose the wake.
 */
class PollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val application = context.applicationContext

        // A wake lock is an optimisation, not a precondition: without it the
        // device may sleep mid-fetch and the poll fails, which the next wake
        // retries. Crashing the receiver instead would take the app down on
        // every alarm - the worst possible failure for something whose job is
        // to be awake overnight - so acquisition failure is tolerated.
        val wakeLock = runCatching {
            application.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        }.onFailure { Log.w(TAG, "could not hold a wake lock; polling anyway", it) }
            .getOrNull()

        CoroutineScope(Dispatchers.IO).launch {
            // Set by a poll that got far enough to work one out. Anything else
            // falls back to the flat interval below.
            var next: PollPlan? = null
            // When the pass planner wants to be woken, in minutes from now.
            // Null when it has nothing to ask for.
            var passWake: Int? = null
            try {
                if (BootAware.canPollNow(application)) {
                    val result = withTimeout(POLL_TIMEOUT_MILLIS) {
                        Graph.poller(application).poll()
                    }
                    next = result.next
                    val unlocked = BootAware.isUnlocked(application)
                    val storm = if (result.storm) ", storm" else ""
                    Notes.i(
                        application,
                        TAG,
                        "poll outcome: ${result.outcome} (unlocked=$unlocked, " +
                            "next in ${result.next.delayMinutes}m: " +
                            "${result.next.reason}$storm)",
                    )
                    // Only once unlocked: a foreground service cannot be
                    // started before then, and the attempt would throw.
                    if (unlocked) StormService.apply(application, result.storm)

                    // Passes share this wake rather than owning an alarm.
                    // Most of the time the planner has nothing to do and says
                    // so immediately; the work only happens at the moments it
                    // asked for. Its failure must not cost the aurora poll its
                    // next wake, which is already queued below.
                    runCatching {
                        val passes = Graph.passPoller(application).poll()
                        passWake = minutesUntil(passes.nextWakeAtMillis)
                        Notes.i(application, TAG, "pass check: ${passes.decision} (next in ${passWake}m)")
                    }.onFailure { Notes.w(application, TAG, "pass check failed", it) }
                    // The widget is redrawn by the poll rather than on a
                    // schedule of its own, so it is exactly as fresh as the
                    // data behind it and costs no wakes.
                    AuroraWidget.refresh(application)
                } else {
                    // Before first unlock without the option enabled there is
                    // nothing readable to act on. Sleep until the next wake.
                    Notes.i(application, TAG, "locked and before-unlock alerting is off; skipping")
                }
            } catch (error: Throwable) {
                Notes.w(application, TAG, "poll failed", error)
            } finally {
                // Always queue the next wake, whatever happened. A poll that
                // throws must not end the schedule and leave the app asleep.
                // Once per wake, which is often enough for a log measured in
                // days and costs nothing on a wake that is already happening.
                runCatching {
                    val user = BootAware.pollSettings(application).read()
                    if (user.keepsLog) {
                        Graph.journal(application)
                            .prune(System.currentTimeMillis(), user.logRetentionMillis)
                    } else {
                        Graph.journal(application).clear()
                    }
                }

                runCatching {
                    val aurora = next?.delayMinutes
                        ?: BootAware.pollSettings(application).read().pollIntervalMinutes
                    // The sooner of the two. A pass alert goes out half an hour
                    // before the satellite is highest, and the aurora schedule
                    // is happy to sleep for an hour on a quiet overcast night -
                    // so taking only the aurora interval would deliver a pass
                    // alert after the pass.
                    PollAlarm.schedule(application, minOf(aurora, passWake ?: aurora))
                }
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                pending.finish()
            }
        }
    }

    /**
     * Whole minutes until [millis], never less than one.
     *
     * Rounded down, so a wake lands at or before the moment asked for rather
     * than after it - the alarm is inexact and already runs late.
     */
    private fun minutesUntil(millis: Long): Int {
        val delay = millis - System.currentTimeMillis()
        return (delay / 60_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val TAG = "PollReceiver"
        const val WAKE_LOCK_TAG = "gloaming:poll"
        const val WAKE_LOCK_TIMEOUT_MILLIS = 60_000L
        const val POLL_TIMEOUT_MILLIS = 45_000L
    }
}
