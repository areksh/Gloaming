package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.SkyStatus

/**
 * How long until the next poll, and why.
 *
 * The reason is carried so a log line, and later the UI, can say what the app
 * is doing instead of leaving a varying interval looking like a bug.
 */
data class PollPlan(val delayMinutes: Int, val reason: PollingPolicy.Reason)

/**
 * How often to look, given what the sky is doing.
 *
 * A flat interval is both slower than wanted when it matters and more expensive
 * than needed the rest of the time. Almost every input here is offline and
 * free: darkness comes from the ephemeris, and the cloud reading is whatever a
 * recent poll already fetched. Only the solar wind costs anything, and that is
 * in a document the app was fetching regardless.
 *
 * The escalation is the point. Because Bz is measured at L1, about an hour
 * upstream, a southward turn escalates the app's own attention half an hour to
 * an hour *before* a substorm - better latency than a flat fifteen minutes
 * exactly when it matters, and far less battery than one the rest of the time.
 */
object PollingPolicy {

    /** Why the next poll is when it is. */
    enum class Reason {
        /** The sun is up. Sleeping until it goes down. */
        DAYLIGHT,

        /** It does not get dark here at this time of year at all. */
        DORMANT,

        /** Dark, but nothing would be visible through the cloud. */
        OVERCAST,

        /** Dark and clear, with nothing much happening. */
        QUIET,

        /** Dark, clear, and the wind or Kp says something may be starting. */
        ELEVATED,
    }

    /**
     * Delivered slower than requested, and it is worth knowing by how much.
     *
     * Measured on a Pixel 8a: a two-minute interval landed at about four, and
     * on a Pixel 10a a five-minute one at nine to ten.
     *
     * This used to read those as a roughly fixed cost of a few minutes rather
     * than a multiplier, and drew from that the conclusion that nothing should
     * compensate for it. Both halves were wrong, and the numbers above were
     * always the counter-example: four on two is twice, nine and a half on five
     * is very nearly twice, and the "fixed cost" grew from two minutes to four
     * and a half between them.
     *
     * It is a proportion. `setAndAllowWhileIdle` is inexact and the window the
     * system attaches is about three quarters of the interval asked for - 24
     * minutes on a 32 minute request, 4 minutes on a 5 minute one - and it
     * fires near the end of it. Thirteen consecutive overnight wakes landed
     * between 1.25 and 1.99 times what was asked, median 1.78, never once on
     * time.
     *
     * Nothing here compensates, and that is still right for *this* number: a
     * poll interval is a cadence, and arriving late to it costs a little
     * latency and nothing else. A wake with a deadline is a different matter
     * and does compensate - see [sh.arek.gloaming.domain.Delivery], which
     * exists because reading this comment the other way cost a real alert.
     */
    const val ELEVATED_MINUTES = 5

    const val QUIET_MINUTES = 30

    const val OVERCAST_MINUTES = 60

    /**
     * How long to sleep when there is nothing to watch for at all.
     *
     * Twice a day, so the app finds out within half a day that the season has
     * turned, without polling through a polar summer to discover it.
     */
    const val DORMANT_MINUTES = 12 * 60

    /**
     * Cloud thick enough that nothing would be visible through it.
     *
     * Deliberately higher than the alert gate's tolerance: "not worth
     * interrupting someone for" and "not worth watching the sky for" are
     * different bars, and the aurora is bright enough to show through gaps.
     */
    const val HEAVY_CLOUD = 90

    /** Southward and meaningful. A degree or two either side of zero is noise. */
    const val SOUTHWARD_BZ = -5.0

    /** Active, on the way to storm. */
    const val ELEVATED_KP = 4.0

    /**
     * When to poll next.
     *
     * @param cloud the last known cover, or null if none is known. Unknown is
     *   treated as worth watching: the app does not spend a request purely to
     *   decide how often to poll, so cloud is only known when a recent poll
     *   fetched it for the alert gate.
     * @param status the last status document, or null when it could not be
     *   read. Without it the quiet rate applies, which is the safe reading of
     *   no information.
     */
    fun next(
        darkness: Darkness,
        now: Long,
        cloud: CloudCover?,
        status: SkyStatus?,
    ): PollPlan = when (darkness) {
        // Nothing gets dark enough here for weeks. Check back twice a day to
        // find out when that changes.
        Darkness.None -> PollPlan(DORMANT_MINUTES, Reason.DORMANT)

        // The single largest saving in the design: while the sun is up no
        // amount of polling could discover anything, and the moment it comes
        // down is known days ahead without touching the network.
        is Darkness.Later -> PollPlan(minutesUntil(darkness.startsAt, now), Reason.DAYLIGHT)

        is Darkness.Now -> whileDark(cloud, status)
    }

    private fun whileDark(cloud: CloudCover?, status: SkyStatus?): PollPlan = when {
        // Ahead of the escalation deliberately, following the Sec. 9.3 table: a
        // sky nothing is visible through is not worth polling fast, whatever
        // the wind is doing. The cost is up to an hour's delay in noticing that
        // heavy cloud has broken during a storm, which wants a season of real
        // data before it is traded away.
        cloud != null && cloud.total >= HEAVY_CLOUD ->
            PollPlan(OVERCAST_MINUTES, Reason.OVERCAST)

        elevated(status) -> PollPlan(ELEVATED_MINUTES, Reason.ELEVATED)

        else -> PollPlan(QUIET_MINUTES, Reason.QUIET)
    }

    private fun elevated(status: SkyStatus?): Boolean {
        if (status == null) return false
        val southward = status.solarWind?.bz?.let { it <= SOUTHWARD_BZ } ?: false
        val active = status.kp?.value?.let { it >= ELEVATED_KP } ?: false
        return southward || active
    }

    /**
     * Never zero, never negative, and never longer than a dormant season.
     *
     * A dusk already past would otherwise arm an alarm for the past, and a dusk
     * months away - which the polar winter produces - would sleep through the
     * season turning.
     */
    private fun minutesUntil(instant: Long, now: Long): Int {
        val minutes = (instant - now) / 60_000L
        return minutes.coerceIn(1L, DORMANT_MINUTES.toLong()).toInt()
    }
}
