package sh.arek.gloaming.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.arek.gloaming.data.BootAware

/**
 * Hands a due pass to the tracker.
 *
 * Not `directBootAware`, unlike [PollReceiver]. A foreground service cannot be
 * started before the device has been unlocked once, so being delivered early
 * would only produce a crash where there is currently nothing at all - and
 * nobody is standing in a field watching a satellite on a phone they have not
 * touched since it rebooted.
 */
class PassReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val catalogue = intent.getIntExtra(PassAlarm.EXTRA_CATALOGUE, 0)
        val peaksAt = intent.getLongExtra(PassAlarm.EXTRA_PEAK_AT, 0L)
        if (catalogue <= 0 || peaksAt <= 0L) return

        if (!BootAware.isUnlocked(context)) {
            Log.i(TAG, "pass $catalogue is due but the device is locked since reboot")
            return
        }

        PassService.follow(context.applicationContext, catalogue, peaksAt)
    }

    private companion object {
        const val TAG = "PassReceiver"
    }
}
