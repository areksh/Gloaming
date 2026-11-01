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
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.PassNotifier
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.PassDescription
import sh.arek.gloaming.satellites.describeTrain
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.VerdictChannel
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.work.FollowPassReceiver
import sh.arek.gloaming.work.PassAlarm

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

    override fun notifyPass(pass: SatellitePass, description: PassDescription) {
        val channel = pass.channel
        ensureChannel(channel)

        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val members = pass.members
        val detail = if (channel == PassChannel.Trains && members != null) {
            // A train is described by its two ends, not by its geometry: the
            // whole of what makes it worth going outside for is that they keep
            // coming. The prominence sentence still applies - it is the same
            // sky and the same shadow - so it is kept.
            describeTrain(
                members = members,
                risesTowards = description.risesTowards,
                risesAt = clock.format(Date(pass.risesAt)),
                lastClearAt = clock.format(Date(pass.setsAt)),
            ) + " " + description.headline
        } else {
            buildString {
                append(
                    "Rises ${description.risesTowards.label} at ${clock.format(Date(pass.risesAt))}, ",
                )
                append("highest at ${clock.format(Date(pass.peaksAt))} ")
                append("(${pass.peakElevation.toInt()} degrees, ${description.peaksLabel}). ")
                append(description.headline)
            }
        }

        val title = if (channel == PassChannel.Trains) {
            "Starlink train at ${clock.format(Date(pass.risesAt))}"
        } else {
            "${pass.name} passes at ${clock.format(Date(pass.peaksAt))}"
        }

        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp())
            .addAction(0, "Follow it", followPass(pass))
            .addFindIt(context, FindTarget.Pass(pass.catalogue, pass.risesAt), pass.catalogue)
            // Alongside "Follow it" rather than instead of it. A pass verdict
            // calibrates a different number from an aurora one - the elevation
            // bar rather than the probability threshold - and there is no way
            // to learn where that bar belongs without asking.
            .addWorthItActions(context, conditions(pass), pass.catalogue)
            .setAutoCancel(true)
            // Keyed on the pass rather than the channel, so an alert for one
            // object never quietly replaces another still worth reading.
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(pass.catalogue, notification)
        }
    }

    /**
     * What a pass verdict is about: how high it got.
     *
     * The elevation is the number a pass alert is judged against, the way the
     * probability is for an aurora - somebody in a city with buildings all
     * round wants a far higher bar than somebody with a clear horizon over the
     * sea, and that is not a fact anybody can be asked in advance.
     */
    private fun conditions(pass: SatellitePass) = Conditions(
        atMillis = System.currentTimeMillis(),
        channel = VerdictChannel.Pass,
        peakElevation = pass.peakElevation,
        subject = pass.name,
    )

    /**
     * Offered on every pass, unlike the setting that does this automatically.
     *
     * The distinction is between reaching for a tracker once and having one
     * start on its own: following a bright satellite because you decided to go
     * outside for it is fine, whereas following every bright satellite would be
     * a foreground service most of every clear night.
     *
     * Keyed on the catalogue number so two alerts up at once each carry their
     * own object. `FLAG_UPDATE_CURRENT` keeps the times fresh when a pass is
     * re-derived from newer elements and the alert is reposted.
     */
    private fun followPass(pass: SatellitePass): PendingIntent = PendingIntent.getBroadcast(
        context,
        pass.catalogue,
        Intent(context, FollowPassReceiver::class.java)
            .putExtra(PassAlarm.EXTRA_CATALOGUE, pass.catalogue)
            .putExtra(PassAlarm.EXTRA_PEAK_AT, pass.peaksAt)
            .putExtra(FollowPassReceiver.EXTRA_RISES_AT, pass.risesAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
                PassChannel.Trains -> "Starlink trains"
            },
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = when (channel) {
                PassChannel.Iss ->
                    "When the International Space Station crosses your sky in sunlight."
                PassChannel.Bright ->
                    "When another bright satellite crosses your sky in sunlight."
                PassChannel.Trains ->
                    "When a freshly launched string of Starlinks crosses your sky. " +
                        "Only for the few nights after a launch, before they spread out."
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
