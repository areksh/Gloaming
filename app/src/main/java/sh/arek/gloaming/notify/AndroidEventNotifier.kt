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
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.VerdictChannel
import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.domain.SubjectRef
import sh.arek.gloaming.ui.findTarget
import sh.arek.gloaming.work.EclipseService
import sh.arek.gloaming.work.FollowEclipseReceiver

/**
 * What the noctilucent channel says about itself. A constant so a JVM test can
 * read it: the channel is created only on a device.
 */
internal const val NOCTILUCENT_CHANNEL_DESCRIPTION =
    "On summer nights when the sun sits at the right depth for high ice " +
        "clouds to still be lit. A prompt to look towards the pole, not a forecast."

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
            .setContentIntent(
                ShowIt.pendingIntent(context, SubjectRef.Event(EventPlanner.starKey(event)), notificationId(event, phase)),
            )
            .apply { followEclipse(event)?.let { addAction(0, "Follow it", it) } }
            .apply {
                findTarget(event)?.let { addFindIt(context, it, notificationId(event, phase)) }
            }
            // Only on the notice for the night itself. Asking whether an
            // eclipse two days away was worth it is asking about something
            // that has not happened.
            .apply {
                if (phase == EventPlanner.Phase.Tonight) {
                    addWorthItActions(context, conditions(event), notificationId(event, phase))
                }
            }
            .setAutoCancel(true)
            .build()

        // Keyed on the event and the phase, so the notice on the day does not
        // silently replace an advance one the user has not read yet, and two
        // events pending at once do not collapse into one.
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(event, phase), notification)
        }
    }

    /**
     * One quiet notice that a comet is over.
     *
     * Deliberately low priority: this is the app closing a loop it opened
     * rather than asking anybody to go outside, and it should not buzz a phone
     * at four in the morning to report that nothing is happening. It goes on
     * the comets channel all the same, so somebody who wants none of this can
     * silence the lot in one place.
     */
    override fun notifyCometFaded(name: String) {
        ensureChannel(EventChannel.Comets)

        val text = "It has dropped below the brightness you asked about, so this is the " +
            "last you will hear of it unless it brightens again."

        val notification = NotificationCompat.Builder(context, EventChannel.Comets.id)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle("$name has faded")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(name.hashCode(), notification)
        }
    }

    /**
     * Keyed on the event and the phase, so the notice on the day does not
     * silently replace an advance one the user has not read yet, and two events
     * pending at once do not collapse into one.
     */
    private fun notificationId(event: SkyEvent, phase: EventPlanner.Phase): Int =
        EventPlanner.key(event, phase).hashCode()

    /**
     * What an event verdict is about.
     *
     * Thinner than the aurora one, because an eclipse is judged on nothing the
     * user set - and it is recorded anyway, because "the app told me about six
     * things this month and two were worth it" is itself the answer to whether
     * every event should interrupt or only the starred ones.
     */
    private fun conditions(event: SkyEvent) = Conditions(
        atMillis = System.currentTimeMillis(),
        channel = VerdictChannel.Event,
        subject = event.title,
    )

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

    /**
     * Offered on an eclipse and on nothing else.
     *
     * Everything else in the calendar is either an instant with nothing to
     * count to, or a whole night where a clock adds nothing. An eclipse has
     * contacts that matter to the minute, which is exactly what a countdown is
     * for - and it is offered per eclipse rather than turned on by a setting,
     * because a foreground service should never start on its own.
     */
    private fun followEclipse(event: SkyEvent): PendingIntent? {
        if (event.kind != EventKind.SolarEclipse && event.kind != EventKind.LunarEclipse) {
            return null
        }
        return PendingIntent.getBroadcast(
            context,
            event.at.hashCode(),
            Intent(context, FollowEclipseReceiver::class.java)
                .putExtra(EclipseService.EXTRA_PEAK_AT, event.at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
                EventChannel.Comets -> "Comets"
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
                EventChannel.Noctilucent -> NOCTILUCENT_CHANNEL_DESCRIPTION
                EventChannel.Comets ->
                    "When a comet bright enough for your threshold is up in a dark sky. " +
                        "A handful of times a decade for a good one, and quiet in between."
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
