package sh.arek.gloaming.domain

/**
 * Asking early enough that an inexact alarm still arrives in time.
 *
 * Most of what the app schedules is a cadence - come back in half an hour - and
 * arriving late costs a little latency and nothing else. A few wakes are not
 * cadences but deadlines: speak thirty minutes before this pass peaks, come back
 * when quiet hours lift and something is still overhead. Those have to arrive
 * *before* a moment, and asking for the moment itself does not achieve that.
 *
 * `PollAlarm` uses `setAndAllowWhileIdle`, which is inexact by design - it is
 * what gets through Doze without the exact-alarm permission Android reserves
 * for alarm clocks. The system attaches a batching window of roughly three
 * quarters of the interval asked for and fires near the end of it. Measured over
 * thirteen consecutive overnight wakes on a Pixel 8a, exempt and in the active
 * bucket, every one landed between 1.25 and 1.99 times the interval requested,
 * median 1.78:
 *
 *   requested 42m -> delivered 82m      requested 28m -> delivered 54m
 *   requested 26m -> delivered 47m      requested 16m -> delivered 32m
 *
 * Which cost a real alert. A pass wake asked for 28 minutes at 02:17 arrived at
 * 03:11; the alert meant to give half an hour's notice of ALOS (DAICHI) arrived
 * two minutes before it rose.
 *
 * Halving the request is the correction. At the worst ratio ever measured,
 * 1.99, half of the remaining time still lands at 0.995 of it - before the
 * deadline, with nothing to spare but on the right side of it. Anything less
 * conservative would be tuned to the median rather than the tail.
 *
 * The cost is extra wakes on the approach: the first lands around nine tenths
 * of the way there, and the next halving covers most of the rest, so a half-hour
 * approach costs two or three wakes instead of one. That is the price of the
 * lead being real, and it is only paid when a deadline is actually pending.
 *
 * **Not applied to the sleep through daylight**, deliberately. Every interval
 * behind the ratio above was between 13 and 42 minutes, which covers the alert
 * leads this is used for and says nothing about a wait of seven hours - the
 * window may well be capped somewhere above the range measured. Halving a dusk
 * wait would also turn the design's one cheap wake into six, since the approach
 * is geometric, and dusk is a soft deadline where a pass is a hard one: arriving
 * late to dusk costs some of an aurora that lasts hours, where arriving late to
 * a pass costs all of six minutes of it. Measure the long end before extending
 * this to it; guessing at the ratio outside its range is the mistake this whole
 * class exists to correct.
 */
object Delivery {

    /**
     * How much of the remaining time to ask for.
     *
     * Two, not the 1.99 the measurement suggests, because a divisor tuned to the
     * exact worst case observed is a number that will be wrong on the next
     * handset. Halving is the honest reading of "the system may take up to twice
     * what you asked for".
     */
    const val SLACK_DIVISOR = 2

    /**
     * The instant to ask for, so that a wake wanted by [deadline] arrives in
     * time.
     *
     * Never earlier than [now]: a deadline already gone wants the soonest wake
     * there is, not one in the past.
     */
    fun askAt(deadline: Long, now: Long): Long {
        val remaining = deadline - now
        if (remaining <= 0) return now
        return now + remaining / SLACK_DIVISOR
    }
}
