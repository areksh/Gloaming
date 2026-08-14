package sh.arek.gloaming.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.R
import sh.arek.gloaming.aurora.Likelihood
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.domain.AuroraNotifier

/**
 * Posts the aurora notification.
 *
 * Local, always. The device decides and notifies itself; nothing is delivered
 * from outside and there is no push dependency of any kind.
 *
 * One channel per category so a user can silence comets later and keep aurora.
 * Only the aurora channel exists at this phase; the rest arrive with the
 * features they belong to.
 */
class AndroidAuroraNotifier(private val context: Context) : AuroraNotifier {

    override fun notifyAurora(likelihood: Likelihood, status: SkyStatus, cloud: CloudCover?) {
        ensureChannel()

        val detail = buildString {
            append("Local probability ${likelihood.probability}%")
            // Attributed, both because AuroraWatch's terms require it and
            // because a real UK magnetometer reading carries more weight than a
            // global model and the user should be able to tell which they have.
            if (likelihood.source == Likelihood.Source.AURORAWATCH) {
                append(" (AuroraWatch UK)")
            }
            append(", ").append(describe(cloud))
            status.kp?.let { append(", Kp ${it.value}") }
            status.solarWind?.let { append(", Bz ${it.bz} nT") }
            status.aurorawatch?.let { append(", AuroraWatch ${it.status}") }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_AURORA)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle("Aurora possible now")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

        // Posting without the runtime permission on API 33+ throws; the user
        // may simply have declined, which is their right and not an error.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Where the alert goes when it is tapped.
     *
     * Without this the tap does nothing whatever - the notification does not
     * even dismiss, because `setAutoCancel` only applies once there is
     * somewhere to go. Someone woken at two in the morning taps the alert
     * first, and the app is the only place that can tell them whether it is
     * worth getting dressed.
     *
     * `FLAG_IMMUTABLE` because nothing is passed and the system must not be
     * able to rewrite the target; required outright from Android 12.
     */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * What the sky is doing, in words.
     *
     * An unreachable forecast says so rather than being left out. Omitting it
     * would read as a clear sky, and the whole reason the gate notifies on an
     * unknown reading is that the user should decide whether to go and look.
     *
     * Where the cloud is matters as much as how much: high cirrus at 60% still
     * shows a strong aurora, low stratus at 60% does not.
     */
    private fun describe(cloud: CloudCover?): String {
        if (cloud == null) return "cloud cover unknown"
        val blocking = cloud.low + cloud.mid
        return when {
            cloud.total <= 15 -> "clear"
            cloud.high >= cloud.total && blocking <= 25 -> "${cloud.total}% cloud, high and thin"
            else -> "${cloud.total}% cloud"
        }
    }

    /**
     * Channels exist from API 26. Below that the app runs unchanged and the
     * notification carries its own priority - the single branch that Android 7
     * support costs.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_AURORA,
            "Aurora",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when the aurora may be visible from your location."
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_AURORA = "aurora"
        private const val NOTIFICATION_ID = 1

        /** Distinct from storm mode's, so the two do not overwrite each other. */
        private const val REQUEST_OPEN = 2
    }
}
