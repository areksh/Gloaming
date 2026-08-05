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

    /**
     * Queues the next poll.
     *
     * Uses elapsed-realtime rather than wall-clock so a timezone change or an
     * NTP correction cannot push the next wake hours away, and jitters every
     * wake so installs do not align on the same boundaries.
     */
    fun schedule(context: Context, intervalMinutes: Int) {
        val period = intervalMinutes.coerceAtLeast(1) * 60_000L
        val jitter = Random.nextLong(0, (period / 4).coerceAtLeast(1))
        val triggerAt = SystemClock.elapsedRealtime() + period + jitter

        alarmManager(context)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pendingIntent(context),
        )
    }

    /** Queues a poll almost immediately, for the in-app "check now" action. */
    fun scheduleNow(context: Context) {
        alarmManager(context)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1_000L,
            pendingIntent(context),
        )
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
