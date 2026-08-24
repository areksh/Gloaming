package sh.arek.gloaming.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Wakes the device to start following a pass.
 *
 * Separate from [PollAlarm] because it is a different kind of thing. A poll is
 * "some time in the next twenty minutes"; this is "shortly before 22:14", and
 * the two must not share a slot - queueing one would cancel the other.
 *
 * **Wall-clock rather than elapsed time**, which is the opposite of the poll
 * alarm's choice and for the same underlying reason. A poll wants an interval
 * and must not have it stretched by a clock correction; a pass happens at a
 * fixed instant in the universe, so if the device's clock is corrected the
 * alarm should move with it, not stay where the old clock thought it was.
 *
 * Still inexact. The design's whole claim for live mode is that a service
 * started ten minutes early owns its own clock, so nothing here needs the
 * exact-alarm permission that Android reserves for alarm clocks and calendars:
 * a wake that lands at eight minutes out, or twelve, is equally fine.
 */
object PassAlarm {

    private const val REQUEST_CODE = 2

    const val EXTRA_CATALOGUE = "sh.arek.gloaming.FOLLOW_CATALOGUE"
    const val EXTRA_PEAK_AT = "sh.arek.gloaming.FOLLOW_PEAK_AT"

    /** Queues the tracker to start at [startAtMillis]. */
    fun follow(context: Context, catalogue: Int, peaksAt: Long, startAtMillis: Long) {
        alarmManager(context)?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            startAtMillis,
            pendingIntent(context, catalogue, peaksAt),
        )
    }

    /** Drops a queued follow, for a pass that is no longer wanted. */
    fun cancel(context: Context) {
        alarmManager(context)?.cancel(pendingIntent(context, 0, 0L))
    }

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /**
     * `FLAG_UPDATE_CURRENT` so that following a second pass replaces the first
     * rather than leaving two alarms racing to start the same service. Only one
     * pass can be followed at a time; the sky offers them one after another.
     */
    private fun pendingIntent(context: Context, catalogue: Int, peaksAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            Intent(context.applicationContext, PassReceiver::class.java)
                .putExtra(EXTRA_CATALOGUE, catalogue)
                .putExtra(EXTRA_PEAK_AT, peaksAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
