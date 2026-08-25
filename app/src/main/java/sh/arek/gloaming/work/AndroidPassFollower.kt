package sh.arek.gloaming.work

import android.content.Context
import sh.arek.gloaming.Graph
import sh.arek.gloaming.domain.PassFollower
import sh.arek.gloaming.satellites.LivePass
import sh.arek.gloaming.satellites.SatellitePass

/**
 * Books the tracker for a pass the poller has just announced.
 *
 * Reached only through [sh.arek.gloaming.domain.PassPlanner.shouldFollow], so
 * only for the station and only when the user has asked for it. The poller
 * records the request in the alert state; all this does is get something to
 * happen at the right moment.
 *
 * Normally that means an alarm, since the alert goes out thirty minutes ahead
 * and the tracker wants ten. The immediate branch is for a poll that ran late -
 * a wake deferred in Doze, say - where the lead has already been eaten and
 * queueing an alarm for a moment in the past would be the wrong answer.
 */
class AndroidPassFollower(context: Context) : PassFollower {

    private val application = context.applicationContext

    override fun follow(pass: SatellitePass) {
        val lead = runCatching { Graph.settings(application).read().liveFollowLeadMillis }
            .getOrDefault(LivePass.LEAD_MILLIS)

        if (LivePass.startsNow(pass.risesAt, System.currentTimeMillis(), lead)) {
            PassService.follow(application, pass.catalogue, pass.peaksAt)
        } else {
            PassAlarm.follow(
                application,
                pass.catalogue,
                pass.peaksAt,
                LivePass.startAt(pass, lead),
            )
        }
    }
}
