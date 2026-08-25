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
import sh.arek.gloaming.Graph
import sh.arek.gloaming.data.BootAware

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
            var outcome: RefreshOutcome? = null
            try {
                if (BootAware.canPollNow(application)) {
                    // Exactly what the button does, by construction: the two
                    // paths share one function so they cannot drift apart.
                    outcome = Refresh.everything(application, TAG)
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
                    val flat = BootAware.pollSettings(application).read().pollIntervalMinutes
                    PollAlarm.schedule(
                        application,
                        outcome?.nextWakeMinutes(flat) ?: flat,
                    )
                }
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PollReceiver"
        const val WAKE_LOCK_TAG = "gloaming:poll"
        const val WAKE_LOCK_TIMEOUT_MILLIS = 60_000L
    }
}
