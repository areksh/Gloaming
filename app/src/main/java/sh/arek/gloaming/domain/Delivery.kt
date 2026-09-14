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
 * **The window is capped, and that is why this stops where it does.** Read off
 * `dumpsys alarm`, the window an alarm carries is three quarters of the time
 * until it fires *or one hour, whichever is smaller*. This app's own alarms
 * show both halves: 4m02s of window on a 5 minute wake, 23m18s on 31 minutes,
 * 24m02s on 32 - and then exactly 1h0m0s0ms on a wake 4h57m out, where three
 * quarters would have been 3h43m. Seven unrelated system alarms, from half an
 * hour out to twelve and a half hours, sit at exactly one hour too. The
 * crossover is around eighty minutes.
 *
 * So the proportion this corrects for only exists below the cap, which is
 * exactly where the alert leads live - half an hour, an hour. Above it the
 * lateness stops growing: a nine hour sleep until dusk arrives at most an hour
 * late, eleven per cent, not eighty.
 *
 * **Not applied to the sleep through daylight**, for that reason. There is
 * little left to correct, halving it would turn the design's one cheap wake
 * into six because the approach is geometric, and dusk is a soft deadline where
 * a pass is a hard one: late to dusk costs part of an aurora that lasts hours,
 * late to a pass costs all six minutes of it.
 *
 * Halving is kept rather than subtracting the hour directly, even though that
 * would save a wake or two on a deadline half a day out. It is correct whether
 * or not the cap holds on the next handset, and the cap is a measurement from
 * one phone rather than a documented promise.
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
