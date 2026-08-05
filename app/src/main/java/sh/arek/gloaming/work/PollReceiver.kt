package sh.arek.gloaming.work

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

        val wakeLock = application.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (BootAware.canPollNow(application)) {
                    val outcome = withTimeout(POLL_TIMEOUT_MILLIS) {
                        Graph.poller(application).poll()
                    }
                    Log.i(TAG, "poll outcome: $outcome (unlocked=${BootAware.isUnlocked(application)})")
                } else {
                    // Before first unlock without the option enabled there is
                    // nothing readable to act on. Sleep until the next wake.
                    Log.i(TAG, "locked and before-unlock alerting is off; skipping")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "poll failed", error)
            } finally {
                // Always queue the next wake, whatever happened. A poll that
                // throws must not end the schedule and leave the app asleep.
                runCatching {
                    PollAlarm.schedule(
                        application,
                        BootAware.pollSettings(application).read().pollIntervalMinutes,
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
        const val POLL_TIMEOUT_MILLIS = 45_000L
    }
}
