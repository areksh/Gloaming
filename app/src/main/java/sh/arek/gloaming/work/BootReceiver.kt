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
        val settings = BootAware.pollSettings(application).read()

        if (!settings.alertsEnabled) {
            Log.i(TAG, "alerts are off; not rescheduling after boot")
            return
        }

        PollAlarm.schedule(application, settings.pollIntervalMinutes)
        Log.i(TAG, "rescheduled after $action (unlocked=${BootAware.isUnlocked(application)})")
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
