package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.CloudCover

/**
 * When the app should watch minute by minute rather than every few.
 *
 * This is the one thing that costs a persistent notification and a service that
 * keeps running, so the bar is high and the exit is generous. It is justified
 * only in the situation the app exists to produce: something remarkable
 * overhead, a dark sky, no cloud, and somebody standing outside looking north.
 *
 * It costs nothing the rest of the time, because the rest of the time it does
 * not run.
 */
object StormMode {

    /**
     * How big a display has to be before minute-by-minute watching is worth a
     * persistent notification. Well above any sensible alert threshold - this
     * is not "worth telling you about" but "worth standing outside for".
     */
    const val ENTER_PROBABILITY = 60

    /**
     * And how far it has to fall before stopping. Hysteresis, for the same
     * reason the alert has it: a probability hovering at one threshold would
     * start and stop the service, and its notification, every couple of
     * minutes.
     */
    const val LEAVE_PROBABILITY = 40

    /** Cloud enough to give up on. Broken cloud is still worth standing out in. */
    const val CLOUD_LIMIT = 70

    /** How often to poll while it runs. */
    const val POLL_MINUTES = 2

    /**
     * Whether storm mode should be running after this poll.
     *
     * @param running whether it is running now. The bar to start is higher than
     *   the bar to keep going, in both activity and certainty.
     * @param cloud the last known cover, or null for unknown. Unknown does not
     *   start it - unlike the alert gate, which notifies on unknown, because
     *   the costs are not comparable: a notification the user can dismiss
     *   against a service running all night on a sky nobody has confirmed. Once
     *   running, though, a lost forecast does not end an event that is
     *   otherwise still going.
     */
    fun shouldRun(
        running: Boolean,
        probability: Int,
        darkness: Darkness,
        cloud: CloudCover?,
    ): Boolean {
        // Nothing to stand outside for. Ends it immediately, whatever else is
        // true, because the whole justification is somebody being out there.
        if (darkness !is Darkness.Now) return false

        if (cloud != null && cloud.total > CLOUD_LIMIT) return false
        if (!running && cloud == null) return false

        return if (running) {
            probability >= LEAVE_PROBABILITY
        } else {
            probability >= ENTER_PROBABILITY
        }
    }
}
