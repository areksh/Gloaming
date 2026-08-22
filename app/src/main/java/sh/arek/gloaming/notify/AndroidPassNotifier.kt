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
import sh.arek.gloaming.domain.PassChannel
import sh.arek.gloaming.domain.PassNotifier
import sh.arek.gloaming.satellites.PassDescription
import sh.arek.gloaming.satellites.SatellitePass

/**
 * Posts a satellite pass notification.
 *
 * Two channels rather than one, because the station goes over most nights and
 * everything else goes over constantly: somebody who wants to be told about the
 * ISS and nothing else can silence the other from the system settings without
 * coming back into the app.
 *
 * The text says what a person outside actually needs - when, which way to look,
 * how high, and whether it will go out part-way across - and never a magnitude.
 */
class AndroidPassNotifier(private val context: Context) : PassNotifier {

    override fun notifyPass(
        pass: SatellitePass,
        description: PassDescription,
        channel: PassChannel,
    ) {
        ensureChannel(channel)

        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val detail = buildString {
            append("Rises ${description.risesTowards.label} at ${clock.format(Date(pass.risesAt))}, ")
            append("highest at ${clock.format(Date(pass.peaksAt))} ")
            append("(${pass.peakElevation.toInt()} degrees, ${description.peaksTowards.label}). ")
            append(description.headline)
        }

        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle("${pass.name} passes at ${clock.format(Date(pass.peaksAt))}")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            // Keyed on the pass rather than the channel, so an alert for one
            // object never quietly replaces another still worth reading.
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(pass.catalogue, notification)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Passes are routine, so the channels are deliberately quieter than the
     * aurora one: this is worth a look out of the window, not worth waking for.
     */
    private fun ensureChannel(channel: PassChannel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val created = NotificationChannel(
            channel.id,
            when (channel) {
                PassChannel.Iss -> "Space station passes"
                PassChannel.Bright -> "Bright satellite passes"
            },
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = when (channel) {
                PassChannel.Iss ->
                    "When the International Space Station crosses your sky in sunlight."
                PassChannel.Bright ->
                    "When another bright satellite crosses your sky in sunlight."
            }
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(created)
    }

    private companion object {
        /** Distinct from the aurora notifier's and storm mode's. */
        const val REQUEST_OPEN = 3
    }
}
