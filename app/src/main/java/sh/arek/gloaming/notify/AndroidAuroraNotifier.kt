package sh.arek.gloaming.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import sh.arek.gloaming.R
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

    override fun notifyAurora(probability: Int, status: SkyStatus, cloud: CloudCover?) {
        ensureChannel()

        val detail = buildString {
            append("Local probability $probability%")
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
            .setAutoCancel(true)
            .build()

        // Posting without the runtime permission on API 33+ throws; the user
        // may simply have declined, which is their right and not an error.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

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
    }
}
