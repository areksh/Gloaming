package sh.arek.gloaming.work

import sh.arek.gloaming.Notes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.arek.gloaming.Graph
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.wakePreferences

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

        // Read on this thread so it is the instant of the wake itself rather
        // than of whenever the coroutine got a turn. Both are wanted by
        // WakeAudit, which is the only thing that can tell the journal whether
        // the poll before this one finished and whether this wake was on time.
        val wokeAt = System.currentTimeMillis()
        val bootInstant = wokeAt - SystemClock.elapsedRealtime()
        // Read before the provisional wake below overwrites it. A memory read
        // of four keys in a file of its own; the settings read a few lines
        // further down already costs more than this does.
        val previous = runCatching { WakeRecordStore(wakePreferences(application)).read() }
            .getOrNull()

        // The next wake is armed before anything else and replaced with the
        // computed one when the poll completes. The alarm that delivered this
        // broadcast is already spent, so between here and the reschedule in
        // `finally` nothing is pending - and a process death or a stall past
        // the wake lock in that window would otherwise end the schedule until
        // the next reboot or app open, on exactly the phone nobody touches
        // overnight. The provisional alarm shares the PendingIntent slot, so
        // the real one simply overwrites it; it only ever fires if the poll
        // never got as far as scheduling its own.
        //
        // Deliberately not at the poll interval. This is what a *failing* phone
        // retries at, and at the interval it turned one killed poll into an
        // endless run of them - see PollAlarm.provisionalMinutes.
        val provisional = runCatching {
            PollAlarm.provisionalMinutes(
                BootAware.pollSettings(application).read().pollIntervalMinutes,
            )
        }.getOrElse { PollAlarm.provisionalMinutes(0) }

        runCatching { PollAlarm.schedule(application, provisional, pollInFlight = true) }
            // A schedule that throws used to do so in silence, which is the one
            // failure that ends the schedule outright: no alarm pending, no
            // line anywhere, and nothing until a reboot or an app open.
            .onFailure { Log.w(TAG, "could not arm the provisional wake", it) }

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
            // Said here rather than on the main thread: the journal is a file,
            // and this is the one part of the receiver allowed to write one.
            runCatching {
                val findings = WakeAudit.findings(previous, wokeAt, bootInstant)
                if (findings.isNotEmpty()) {
                    val power = PowerState.describe(application)
                    for (finding in findings) Notes.w(application, TAG, "$finding ($power)")
                }
            }
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

                // Which also replaces the provisional record with a complete
                // one. A poll killed before it reaches here leaves the
                // incomplete record behind, and that absence is the whole
                // signal - see WakeAudit.
                runCatching {
                    val flat = BootAware.pollSettings(application).read().pollIntervalMinutes
                    PollAlarm.schedule(application, outcome?.nextWakeMinutes(flat) ?: flat)
                }.onFailure { Notes.w(application, TAG, "could not schedule the next wake", it) }
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
