package sh.arek.gloaming.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.arek.gloaming.data.BootAware

/**
 * Turns a press on the eclipse notification into a running countdown.
 *
 * A broadcast rather than starting the service straight from the notification,
 * matching [FollowPassReceiver]: the press is one of the exemptions that lets a
 * foreground service start from the background, and routing it the same way as
 * the pass action keeps one pattern rather than two.
 */
class FollowEclipseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peaksAt = intent.getLongExtra(EclipseService.EXTRA_PEAK_AT, 0L)
        if (peaksAt <= 0L) return

        if (!BootAware.isUnlocked(context)) {
            Log.i(TAG, "eclipse follow asked for but the device is locked since reboot")
            return
        }

        EclipseService.follow(context.applicationContext, peaksAt)
    }

    private companion object {
        const val TAG = "FollowEclipseReceiver"
    }
}
