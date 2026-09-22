package sh.arek.gloaming.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlin.random.Random
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.wakePreferences

/**
 * Scheduling, on `AlarmManager` rather than `WorkManager`.
 *
 * `WorkManager` keeps its queue in credential-encrypted storage and is not
 * Direct Boot aware, so nothing it schedules runs until the device has been
 * unlocked at least once. On a phone set to reboot every few hours that means
 * no alerts for most of a night. `AlarmManager` with a Direct Boot aware
 * receiver runs regardless.
 *
 * The alarm is inexact. `setAndAllowWhileIdle` fires through Doze without the
 * exact-alarm permission, which is reserved for alarm clocks and calendars; the
 * design never needs a precise moment, only a wake that actually happens.
 */
object PollAlarm {

    private const val REQUEST_CODE = 1

    /** How far either side of the requested interval a wake may land. */
    private const val SPREAD_DIVISOR = 8

    /** However short the request, the wake is still in the future. */
    private const val MINIMUM_DELAY_MILLIS = 30_000L

    /**
     * The soonest a *provisional* wake may come, whatever the poll interval.
     *
     * PollReceiver arms one before it does any work, so that a process death
     * between there and the real reschedule cannot end the schedule. That makes
     * it the alarm a failing phone retries at - and armed at the poll interval,
     * a phone set to two minutes retried a killed poll every two minutes,
     * repeating the work that had just been killed and never getting far enough
     * to record anything. It ran for hours at 95% CPU and finished no pass check
     * at all.
     *
     * Fifteen minutes is the app's own default interval: long enough that a
     * struggling phone stops stampeding, short enough that a recovery still
     * happens within a night.
     */
    private const val PROVISIONAL_FLOOR_MINUTES = 15

    /**
     * What the provisional wake asks for, given the interval in the settings.
     *
     * Never sooner than the floor, never later than the schedule already is: a
     * phone that polls twice a day should not be dragged awake by a safety net.
     */
    fun provisionalMinutes(intervalMinutes: Int): Int =
        maxOf(intervalMinutes, PROVISIONAL_FLOOR_MINUTES)

    /**
     * Queues the next poll.
     *
     * Uses elapsed-realtime rather than wall-clock so a timezone change or an
     * NTP correction cannot push the next wake hours away.
     *
     * Leaves the record [WakeAudit] reads on the next wake. It is written here,
     * by every caller, rather than by the poll alone - the button reschedules
     * after a manual check, the boot receiver reschedules after a reboot, and
     * the settings screen reschedules when alerting is switched on. A record
     * left behind by the poll alone would be measured against an alarm one of
     * those had since replaced, and a wake correctly delivered at twelve hours
     * would be reported as one asked for at thirty minutes and hours late.
     *
     * @param pollInFlight true only for the provisional wake armed at the top
     *   of `PollReceiver`, where a poll is about to run and has not yet
     *   finished. Everything else is scheduling from a standing start with
     *   nothing in flight, so the record it leaves is complete.
     */
    fun schedule(context: Context, intervalMinutes: Int, pollInFlight: Boolean = false) {
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis(intervalMinutes)

        alarmManager(context)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent(context),
        )

        val now = System.currentTimeMillis()
        WakeRecordStore(wakePreferences(context)).write(
            WakeRecord(
                atMillis = now,
                askedMinutes = intervalMinutes,
                complete = !pollInFlight,
                bootInstantMillis = now - SystemClock.elapsedRealtime(),
            ),
        )
    }

    /**
     * How long until the next wake, jittered.
     *
     * Jitter is here to stop every install waking on the same boundary and
     * arriving at the CDN together, and that is all it is for. It used to be
     * added to the interval rather than spread around it, which quietly made
     * every wake up to a quarter later than asked for - on top of the batching
     * window the system already attaches to an inexact alarm, and in an app
     * whose one complaint is that checks arrive late. Spreading either side
     * de-aliases installs just as well and leaves the average where the policy
     * put it.
     *
     * @param random injected so the spread can be exercised without waiting for
     *   a distribution to show itself.
     */
    fun delayMillis(intervalMinutes: Int, random: Random = Random): Long {
        val period = intervalMinutes.coerceAtLeast(1) * 60_000L
        val spread = (period / SPREAD_DIVISOR).coerceAtLeast(1)
        return (period + random.nextLong(-spread, spread + 1)).coerceAtLeast(MINIMUM_DELAY_MILLIS)
    }

    fun cancel(context: Context) {
        alarmManager(context)?.cancel(pendingIntent(context))
    }

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /**
     * The alarm is registered against device-protected storage so it can be
     * rebuilt and fired before the user unlocks.
     */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        BootAware.deviceContext(context),
        REQUEST_CODE,
        Intent(context.applicationContext, PollReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
