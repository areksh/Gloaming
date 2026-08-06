package sh.arek.gloaming.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.arek.gloaming.data.BootAware

/**
 * Re-arms the schedule after a reboot.
 *
 * Alarms do not survive a restart, so without this the app stays silent until
 * someone opens it. That is the whole failure this exists to prevent on a phone
 * configured to reboot on a timer.
 *
 * Listens for `LOCKED_BOOT_COMPLETED` as well as `BOOT_COMPLETED`: the former
 * arrives before the device is unlocked and is the only one that will be seen
 * for a while if the phone rebooted overnight and nobody has touched it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        val application = context.applicationContext

        // A throw here means the alarm is never armed and the app stays silent
        // until someone opens it - the exact failure this receiver exists to
        // prevent - so it degrades to a logged error instead.
        runCatching {
            val settings = BootAware.pollSettings(application).read()
            if (!settings.alertsEnabled) {
                Log.i(TAG, "alerts are off; not rescheduling after boot")
                return@runCatching
            }

            // The flat interval, not the adaptive one: working out a schedule
            // needs a poll's worth of state, and getting one wake armed matters
            // more here than getting its timing ideal. The first poll after it
            // computes the real rate.
            PollAlarm.schedule(application, settings.pollIntervalMinutes)
            Log.i(TAG, "rescheduled after $action (unlocked=${BootAware.isUnlocked(application)})")
        }.onFailure { Log.e(TAG, "failed to reschedule after $action", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
