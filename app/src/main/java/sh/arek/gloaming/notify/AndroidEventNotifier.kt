package sh.arek.gloaming.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.R
import sh.arek.gloaming.astronomy.EventChannel
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.domain.EventNotifier
import sh.arek.gloaming.domain.EventPlanner

/**
 * Posts a sky calendar notification.
 *
 * Three channels, matching the design's taxonomy, so somebody can keep eclipses
 * and silence conjunctions from the system settings without coming back into
 * the app.
 *
 * Quieter than the aurora channel and no louder than the pass ones. An eclipse
 * is worth arranging a day around; it is not worth being woken for, and the
 * user's own quiet hours have already had their say by the time this is called.
 */
class AndroidEventNotifier(private val context: Context) : EventNotifier {

    override fun notifyEvent(event: SkyEvent, phase: EventPlanner.Phase) {
        val channel = EventChannel.of(event.kind) ?: return
        ensureChannel(channel)

        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle(title(event, phase))
            .setContentText(event.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

        // Keyed on the event and the phase, so the notice on the day does not
        // silently replace an advance one the user has not read yet, and two
        // events pending at once do not collapse into one.
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(EventPlanner.key(event, phase).hashCode(), notification)
        }
    }

    /**
     * The title carries the timing, because that is what differs between the
     * two notices about the same event - the body is identical.
     */
    private fun title(event: SkyEvent, phase: EventPlanner.Phase): String {
        if (phase == EventPlanner.Phase.Tonight) {
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
            return "${event.title}, from ${clock.format(Date(event.from))}"
        }
        val day = SimpleDateFormat("EEEE", Locale.getDefault())
        return "${event.title}, ${day.format(Date(event.at))}"
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel(channel: EventChannel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val created = NotificationChannel(
            channel.id,
            when (channel) {
                EventChannel.Eclipses -> "Eclipses"
                EventChannel.MeteorShowers -> "Meteor showers"
                EventChannel.Conjunctions -> "Planets and conjunctions"
                EventChannel.Noctilucent -> "Noctilucent clouds"
            },
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = when (channel) {
                EventChannel.Eclipses ->
                    "When the sun or the moon is eclipsed where you are. A few times a year " +
                        "at most, and only when something is actually above your horizon."
                EventChannel.MeteorShowers ->
                    "When a shower peaks on a night the radiant is high and the moon is out " +
                        "of the way."
                EventChannel.Conjunctions ->
                    "When two bright planets come close together, or a planet is at its best " +
                        "for the year."
                EventChannel.Noctilucent ->
                    "On summer nights when the sun sits at the right depth for high ice " +
                        "clouds to still be lit. A prompt to look north, not a forecast."
            }
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(created)
    }

    private companion object {
        /** Distinct from the aurora notifier's, storm mode's and the pass notifier's. */
        const val REQUEST_OPEN = 4
    }
}
