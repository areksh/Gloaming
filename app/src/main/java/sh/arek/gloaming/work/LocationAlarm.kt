package sh.arek.gloaming.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.LocationRefresh

/**
 * The wake for the background location refresh (ADR-0016).
 *
 * Its own alarm rather than a stage of the poll. The poll runs before first
 * unlock and reads the device-encrypted mirror, which carries a whole degree at
 * most; refreshing the position needs the credential-encrypted settings and a
 * cadence of hours, not of minutes. Sharing a wake would tie either to the
 * other's schedule.
 *
 * Inexact, like [PollAlarm] and for the same reason: `setAndAllowWhileIdle`
 * gets through Doze without the exact-alarm permission. The system attaches a
 * window of up to an hour to it, so "every hour" means a refresh somewhere
 * within about an hour and three quarters. Nothing here needs better.
 */
object LocationAlarm {

    private const val REQUEST_CODE = 3

    /**
     * Arms the next refresh for [settings], or cancels it when they ask for
     * none - typed in, or a cadence the background does not answer.
     *
     * Idempotent, so every settings save can call it: there is one slot, and a
     * second arm only replaces the first.
     */
    fun arm(context: Context, settings: UserSettings) {
        val now = System.currentTimeMillis()
        val at = LocationRefresh.nextAt(settings, now)
        if (at == null) {
            cancel(context)
            return
        }

        // Converted to elapsed realtime for the reason PollAlarm uses it: a
        // clock correction between now and then must not move the wake.
        alarmManager(context)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + (at - now),
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        alarmManager(context)?.cancel(pendingIntent(context))
    }

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /**
     * Registered against ordinary storage, unlike the poll's: the receiver is
     * not Direct Boot aware, so there is nothing to deliver before unlock.
     */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST_CODE,
        Intent(context.applicationContext, LocationReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
