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
import sh.arek.gloaming.R
import sh.arek.gloaming.domain.PassPlanner
import sh.arek.gloaming.satellites.LivePass
import sh.arek.gloaming.satellites.LiveTracker
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.satellites.TrainFinder

/**
 * Follows one pass, second by second, while it happens.
 *
 * The case for a foreground service here is the one Sec. 9.4 makes: started ten
 * minutes ahead, it **owns its own clock**, so the moment that actually matters
 * - the satellite clearing the horizon - never depends on the system waking
 * anything precisely, and the exact-alarm permission is never needed.
 *
 * Two deliberate differences from [StormService], which this otherwise copies.
 * **It touches no network at all**: the elements are already on the device, so
 * this works in a field with no signal, which is where it will be used. And it
 * is never automatic across the bright catalogue - the station goes over most
 * nights, everything else goes over constantly, and a foreground service per
 * pass would be a persistent-notification generator. [PassPlanner.shouldFollow]
 * makes that structural rather than a default.
 *
 * The pass is re-derived here rather than carried in the intent, because by now
 * the elements may be newer than they were when the user was told about it -
 * and a countdown ought to be the best answer the device can give, not the one
 * it gave half an hour ago.
 */
class PassService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var following: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var latestStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val catalogue = intent?.getIntExtra(PassAlarm.EXTRA_CATALOGUE, 0) ?: 0
        val peaksAt = intent?.getLongExtra(PassAlarm.EXTRA_PEAK_AT, 0L) ?: 0L

        ensureChannel()
        // Before anything else. The system gives a service a few seconds to
        // show its notification and kills it otherwise, and working out a pass
        // costs real arithmetic.
        startInForeground()

        if (following == null && catalogue > 0 && peaksAt > 0L) {
            running = true
            wakeLock = acquireWakeLock()
            following = scope.launch { follow(catalogue, peaksAt) }
        } else if (following == null) {
            stopSelf(startId)
        }

        // Not sticky: restarting with a null intent would leave a service with
        // no pass to follow, and the pass it was started for would be over.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        clearRequest()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Works out which pass this is, then ticks until it is over.
     *
     * Everything needed is read once, up front: after that the loop is pure
     * arithmetic against the device clock, which is what lets it run with no
     * signal and no further wakes.
     */
    private suspend fun follow(catalogue: Int, expectedPeakAt: Long) {
        val user = Graph.settings(applicationContext).read()
        val latitude = user.latitude
        val longitude = user.longitude
        val document = Graph.satellites(applicationContext).cached()

        val elements = document?.satellites?.firstOrNull { it.catalogue == catalogue }
        if (elements == null || latitude == null || longitude == null) {
            Log.w(TAG, "nothing to follow for $catalogue")
            stopSelf(latestStartId)
            return
        }

        val at = System.currentTimeMillis()
        // Back a little, so a pass whose rise has just slipped past is still
        // found rather than missed by a few seconds.
        val from = at - SEARCH_BACK_MILLIS
        val until = at + SEARCH_AHEAD_MILLIS

        // A train is followed as a train. Rebuilt from the lead alone it would
        // announce itself gone the moment the front of it set, with the rest
        // still coming - which is the one thing somebody watching a train is
        // waiting for.
        val candidates = if (document.trains.any { it.lead == catalogue }) {
            TrainFinder.passes(document, latitude, longitude, from, until, FOLLOWING_BAR)
                .filter { it.catalogue == catalogue }
        } else {
            PassFinder.passes(
                elements = elements,
                latitude = latitude,
                longitude = longitude,
                fromMillis = from,
                untilMillis = until,
                minimumPeakElevation = FOLLOWING_BAR,
            )
        }

        val pass = LivePass.chosen(
            passes = candidates,
            expectedPeakAt = expectedPeakAt,
            tolerance = PassPlanner.SAME_PASS_TOLERANCE_MILLIS,
        )

        if (pass == null || !LivePass.isWorthFollowing(pass, at)) {
            Log.i(TAG, "no pass of $catalogue left to follow")
            stopSelf(latestStartId)
            return
        }

        Log.i(TAG, "following ${pass.name}, rises ${pass.risesAt}, sets ${pass.setsAt}")
        val tracker = LiveTracker(elements, latitude, longitude)
        tick(tracker, pass)
        stopSelf(latestStartId)
    }

    private suspend fun tick(tracker: LiveTracker, pass: SatellitePass) {
        val startedAt = System.currentTimeMillis()

        while (scope.isActive) {
            val now = System.currentTimeMillis()
            update(LivePass.describe(tracker.fixAt(now), pass, now), pass.name)

            if (now > pass.setsAt + LINGER_MILLIS) return
            if (now - startedAt > MAX_RUN_MILLIS) {
                Log.i(TAG, "live pass hit its time limit; stopping")
                return
            }
            delay(TICK_MILLIS)
        }
    }

    private fun startInForeground() {
        val notification = notification("Getting ready", "Satellite pass")
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

    private fun update(text: String, name: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification(text, name))
        }
    }

    private fun notification(text: String, name: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PassService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            .setContentTitle(name)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            // Shown at once rather than after the system's ten-second deferral.
            // A tracker that appears eight minutes into a ten-minute lead has
            // missed most of what it was for.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Every second, so the system must not animate or re-sort it.
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * A low-importance channel of its own, as storm mode has.
     *
     * Somebody who wants to be told a pass is coming does not necessarily want
     * a tracker running through it, and the two should be silenceable apart.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_LIVE,
            "Live satellite pass",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Gloaming is following a pass across the sky."
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * The request is spent once the service stops, however it stopped.
     *
     * Left behind, it would survive a reboot and have the tracker start for a
     * pass that finished hours ago.
     */
    private fun clearRequest() {
        runCatching {
            val store = Graph.alertState(applicationContext)
            val held = store.read()
            if (held.followingCatalogue != null || held.followingPeakAt != null) {
                store.write(
                    held.copy(
                        followingCatalogue = null,
                        followingPeakAt = null,
                        followingRisesAt = null,
                    ),
                )
            }
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire(MAX_RUN_MILLIS) }
    }.onFailure { Log.w(TAG, "could not hold a wake lock; following anyway", it) }.getOrNull()

    companion object {
        const val CHANNEL_LIVE = "passes-live"
        private const val NOTIFICATION_ID = 3
        private const val TAG = "PassService"
        private const val WAKE_LOCK_TAG = "gloaming:pass"
        private const val ACTION_STOP = "sh.arek.gloaming.STOP_PASS"

        /**
         * No bar at all once a pass is being followed.
         *
         * Whatever the user was told about, they are now outside looking for
         * it. Re-applying the "worth mentioning" test at this point could only
         * leave them there with nothing.
         */
        private const val FOLLOWING_BAR = 0.0

        /** How often the line is rewritten. A countdown that jumps is not a countdown. */
        private const val TICK_MILLIS = 1000L

        /** Kept up briefly past the set, so "Gone." is actually read. */
        private const val LINGER_MILLIS = 20_000L

        /** Far enough back to catch a rise that has just slipped by. */
        private const val SEARCH_BACK_MILLIS = 5 * 60_000L

        /** A lead of ten minutes plus the longest pass, with room to spare. */
        private const val SEARCH_AHEAD_MILLIS = 45 * 60_000L

        /**
         * A safety net rather than a feature. A low-orbit pass runs under ten
         * minutes, so anything approaching this is a bug holding a wake lock.
         */
        private const val MAX_RUN_MILLIS = 30 * 60 * 1000L

        /** Whether a pass is being followed now, so a second cannot start over it. */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Starts the tracker, or gives up quietly.
         *
         * From Android 12 a background start can be refused outright, and there
         * is nothing useful to do about it here: the alternative to a tracker
         * is not a crash, it is the notification the user already has. So the
         * refusal is logged and swallowed. Whether the refusal actually happens
         * with this app's battery-optimisation exemption in place is a question
         * only a real device answers.
         */
        fun follow(context: Context, catalogue: Int, peaksAt: Long) {
            if (running) return

            val intent = Intent(context, PassService::class.java)
                .putExtra(PassAlarm.EXTRA_CATALOGUE, catalogue)
                .putExtra(PassAlarm.EXTRA_PEAK_AT, peaksAt)

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start the live tracker", it) }
        }

        /** Ends a watch from outside, used when the user turns passes off. */
        fun stop(context: Context) {
            if (!running) return
            runCatching {
                context.startService(
                    Intent(context, PassService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
