package sh.arek.gloaming.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.arek.gloaming.Graph
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.Notes
import sh.arek.gloaming.R
import sh.arek.gloaming.astronomy.EclipseTrack
import sh.arek.gloaming.astronomy.EclipseTracks
import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.data.UserSettings

/**
 * Counts an eclipse down, contact by contact, while it happens.
 *
 * The same shape as [PassService], which is the same shape as [StormService]:
 * a `specialUse` foreground service owning its own clock, an ongoing silent
 * notification, a stop action, a capped wake lock, and never sticky. It touches
 * no network either - an eclipse is arithmetic the device can already do, which
 * is what lets this run from a hillside with no signal.
 *
 * **It counts to the next contact rather than through the whole event.** That
 * is what makes it usable for a lunar eclipse, which runs for hours: the thing
 * worth knowing is never "how long until it is over", it is "how long until the
 * shadow reaches the edge". The cap is its own, and longer than the pass one,
 * because the events genuinely are.
 *
 * Started only when somebody presses the action on the notification. There is
 * no setting that makes it automatic, on the same reasoning that keeps the
 * bright catalogue out of live pass mode.
 */
class EclipseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var following: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var latestStartId: Int = 0

    /** The user's cap, read once the settings are in hand. */
    @Volatile
    private var capMillis: Long = UserSettings.DEFAULT_ECLIPSE_FOLLOW_MAX_MINUTES * 60_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val peaksAt = intent?.getLongExtra(EXTRA_PEAK_AT, 0L) ?: 0L

        ensureChannel()
        // Before anything else: the system gives a service seconds to show its
        // notification, and re-deriving an eclipse costs real arithmetic.
        startInForeground()

        if (following == null && peaksAt > 0L) {
            running = true
            wakeLock = acquireWakeLock()
            following = scope.launch { follow(peaksAt) }
        } else if (following == null) {
            stopSelf(startId)
        }

        // Not sticky: restarted with a null intent there would be no eclipse to
        // follow, and the one it was started for would be over.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Works out which eclipse this is, then ticks until the last contact.
     *
     * Re-derived rather than carried in the intent, the same as a pass: the
     * countdown should be the best answer the device can give now.
     */
    private suspend fun follow(peakAt: Long) {
        val user = Graph.settings(applicationContext).read()
        val latitude = user.latitude
        val longitude = user.longitude

        if (latitude == null || longitude == null) {
            Notes.w(applicationContext, TAG, "no location, so nothing to follow")
            stopSelf(latestStartId)
            return
        }

        capMillis = user.eclipseFollowMaxMillis
        val track = EclipseTracks.forPeak(latitude, longitude, peakAt)

        if (track == null || EclipseTracks.isOver(track, System.currentTimeMillis())) {
            Notes.i(applicationContext, TAG, "no eclipse left to follow near $peakAt")
            stopSelf(latestStartId)
            return
        }

        Notes.i(applicationContext, TAG, "following ${track.title}, ends ${track.endsAt}")
        tick(track, latitude, longitude)
        stopSelf(latestStartId)
    }

    private suspend fun tick(track: EclipseTrack, latitude: Double, longitude: Double) {
        val startedAt = System.currentTimeMillis()

        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val where = runCatching {
                SkyCalendar.positionOf(track.body, latitude, longitude, now)
            }.getOrNull()

            update(EclipseTracks.describe(track, where, now), track.title)

            if (now > track.endsAt + LINGER_MILLIS) return
            if (now - startedAt > capMillis) {
                Notes.i(applicationContext, TAG, "eclipse follow hit its time limit; stopping")
                return
            }
            delay(TICK_MILLIS)
        }
    }

    private fun startInForeground() {
        val notification = notification("Getting ready", "Eclipse")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun update(text: String, title: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification(text, title))
        }
    }

    private fun notification(text: String, title: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, EclipseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Its own low-importance channel, matching the `aurora-live` and
     * `passes-live` precedent: being told an eclipse is coming is not the same
     * as wanting a clock running through it.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_LIVE,
            "Live eclipse countdown",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Gloaming is counting an eclipse down contact by contact."
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire(MAX_RUN_MILLIS) }
    }.onFailure { Log.w(TAG, "could not hold a wake lock; following anyway", it) }.getOrNull()

    companion object {
        const val CHANNEL_LIVE = "eclipse-live"
        const val EXTRA_PEAK_AT = "sh.arek.gloaming.ECLIPSE_PEAK_AT"

        private const val NOTIFICATION_ID = 5
        private const val TAG = "EclipseService"
        private const val WAKE_LOCK_TAG = "gloaming:eclipse"
        private const val ACTION_STOP = "sh.arek.gloaming.STOP_ECLIPSE"

        /** How often the line is rewritten. A countdown that jumps is not a countdown. */
        private const val TICK_MILLIS = 1000L

        /** Kept up briefly past the end, so "Over." is actually read. */
        private const val LINGER_MILLIS = 30_000L

        /**
         * A safety net rather than a feature, as in the other two services.
         * The longest umbral lunar eclipse runs under four hours, so anything
         * past this is a bug holding a wake lock.
         */
        private const val MAX_RUN_MILLIS = 5 * 60 * 60 * 1000L

        /** Whether a countdown is running, so a second cannot start over it. */
        @Volatile
        var running: Boolean = false
            private set

        /** Starts the countdown, or gives up quietly. */
        fun follow(context: Context, peaksAt: Long) {
            if (running) return
            val intent = Intent(context, EclipseService::class.java)
                .putExtra(EXTRA_PEAK_AT, peaksAt)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start the eclipse countdown", it) }
        }
    }
}
