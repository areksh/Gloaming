package sh.arek.gloaming.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlin.random.Random
import sh.arek.gloaming.data.BootAware

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
     * Queues the next poll.
     *
     * Uses elapsed-realtime rather than wall-clock so a timezone change or an
     * NTP correction cannot push the next wake hours away.
     */
    fun schedule(context: Context, intervalMinutes: Int) {
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis(intervalMinutes)

        alarmManager(context)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent(context),
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
