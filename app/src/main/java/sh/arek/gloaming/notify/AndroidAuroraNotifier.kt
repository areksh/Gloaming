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
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.VerdictChannel
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.domain.AuroraNotifier
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.ui.OutlookWords
import sh.arek.gloaming.ui.describeCloud

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

    /**
     * The sky as it was, kept with the question about it.
     *
     * Kp and Bz are persisted nowhere in this app - they are read off a
     * document the Worker replaces every few minutes - so unless they travel
     * with the notification they are gone by the time anybody answers. The same
     * goes for the threshold: it is the one in force *now*, and the whole point
     * of the record is to work out whether it was right.
     */
    private fun conditions(
        likelihood: Likelihood,
        status: SkyStatus,
        cloud: CloudCover?,
        threshold: Int?,
    ) = Conditions(
        atMillis = System.currentTimeMillis(),
        channel = VerdictChannel.Aurora,
        probability = likelihood.probability,
        source = likelihood.source.name,
        kp = status.kp?.value,
        bz = status.solarWind?.bz,
        cloudTotal = cloud?.total,
        cloudLow = cloud?.low,
        cloudMid = cloud?.mid,
        cloudHigh = cloud?.high,
        threshold = threshold,
        subject = "Aurora",
    )

    override fun notifyAurora(
        likelihood: Likelihood,
        status: SkyStatus,
        cloud: CloudCover?,
        threshold: Int?,
        clearUntil: Long?,
    ) {
        ensureChannel()

        val figures = buildString {
            append("Local probability ${likelihood.probability}%")
            // Attributed, both because AuroraWatch's terms require it and
            // because a real UK magnetometer reading carries more weight than a
            // global model and the user should be able to tell which they have.
            if (likelihood.source == Likelihood.Source.AURORAWATCH) {
                append(" (AuroraWatch UK)")
            }
            append(", ").append(describeCloud(cloud))
            status.kp?.let { append(", Kp ${it.value}") }
            status.solarWind?.let { append(", Bz ${it.bz} nT") }
            status.aurorawatch?.let { append(", AuroraWatch ${it.status}") }
        }

        // The same words the Tonight screen opens with, so the alert and the
        // screen it opens never tell two stories.
        val outlook = OutlookWords.auroraAlert(likelihood.probability, clearUntil)

        val notification = NotificationCompat.Builder(context, CHANNEL_AURORA)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle("Aurora possible now")
            .setContentText(outlook)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$outlook\n$figures"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .addFindIt(context, FindTarget.Aurora, NOTIFICATION_ID)
            // The highest-leverage two buttons in the app: they turn every real
            // night into the calibration data the default thresholds have been
            // waiting on since the first version.
            .addWorthItActions(context, conditions(likelihood, status, cloud, threshold), NOTIFICATION_ID)
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
