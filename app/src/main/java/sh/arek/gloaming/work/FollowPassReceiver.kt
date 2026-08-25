package sh.arek.gloaming.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import sh.arek.gloaming.Graph
import sh.arek.gloaming.satellites.LivePass

/**
 * Takes up the "Follow it" offer on a pass notification.
 *
 * The one surface that exists at the moment somebody is deciding whether to go
 * outside, which is why the per-pass offer is a notification action and not a
 * setting: a setting has to be found in advance by a person who does not yet
 * know a pass is coming.
 *
 * Two routes out, decided by the clock. The alert goes out thirty minutes ahead
 * and the tracker wants ten, so an early tap queues an alarm and a late one
 * starts the service there and then. Starting immediately is not a shortcut
 * around the queue: a notification the user has just pressed is one of the
 * exemptions that lets a foreground service begin at all from the background,
 * so it is the most reliable moment there will be.
 */
class FollowPassReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext
        val catalogue = intent.getIntExtra(PassAlarm.EXTRA_CATALOGUE, 0)
        val peaksAt = intent.getLongExtra(PassAlarm.EXTRA_PEAK_AT, 0L)
        val risesAt = intent.getLongExtra(EXTRA_RISES_AT, 0L)
        if (catalogue <= 0 || peaksAt <= 0L || risesAt <= 0L) return

        remember(application, catalogue, peaksAt, risesAt)

        val lead = runCatching { Graph.settings(application).read().liveFollowLeadMillis }
            .getOrDefault(LivePass.LEAD_MILLIS)

        if (LivePass.startsNow(risesAt, System.currentTimeMillis(), lead)) {
            PassService.follow(application, catalogue, peaksAt)
            // The tracker's own notification says everything this one did and
            // keeps saying it, so leaving both up would be two lines about one
            // pass, one of them frozen.
            runCatching { NotificationManagerCompat.from(application).cancel(catalogue) }
        } else {
            // The alert stays up in this case. There is nothing to replace it
            // with for another twenty minutes, and a notification that vanishes
            // on being pressed reads as having gone wrong.
            PassAlarm.follow(application, catalogue, peaksAt, LivePass.startAt(risesAt, lead))
        }
    }

    /**
     * Written down as well as queued, because an alarm does not survive a
     * reboot and this is the worst request in the app to drop silently: the
     * user has already decided to go outside.
     */
    private fun remember(context: Context, catalogue: Int, peaksAt: Long, risesAt: Long) {
        runCatching {
            val store = Graph.alertState(context)
            store.write(
                store.read().copy(
                    followingCatalogue = catalogue,
                    followingPeakAt = peaksAt,
                    followingRisesAt = risesAt,
                ),
            )
        }.onFailure { Log.w(TAG, "could not record the follow request", it) }
    }

    companion object {
        /**
         * The rise, which the alarm intent does not carry.
         *
         * The tracker is identified by its peak, as every other pass reference
         * in the app is, but *when to start* comes off the rise - so the action
         * needs both and the alarm only ever needs the one.
         */
        const val EXTRA_RISES_AT = "sh.arek.gloaming.FOLLOW_RISES_AT"

        private const val TAG = "FollowPassReceiver"
    }
}
