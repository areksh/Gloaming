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
import sh.arek.gloaming.domain.PollOutcome
import sh.arek.gloaming.domain.StormMode

/**
 * Watches the sky minute by minute.
 *
 * The one thing in the app that costs a persistent notification and a service
 * that keeps running, and it is justified by the situation it exists for: the
 * user is standing in a field looking north, and wants to know the moment the
 * display strengthens or fades.
 *
 * Entered two ways. Automatically, when [StormMode] says a display is large
 * enough to stand outside for - in which case a poll that finds the display
 * over ends it. Or because the user asked, from the "watch now" switch, in
 * which case only they or the cap end it: the schedule is the thing they are
 * working around, so nothing on the schedule may cancel it.
 *
 * It drives its own loop under a wake lock rather than leaning on alarms.
 * Alarms are inexact by design and a two-minute request lands nearer four, which
 * is most of what storm mode is meant to fix - and without the
 * battery-optimisation exemption it would be far worse. A wake lock for the
 * duration of a display is a real cost, which is why the exit conditions are
 * generous and why there is a hard cap on how long it can run.
 *
 * Not Direct Boot aware, deliberately. A foreground service cannot be started
 * before first unlock, and nobody is standing outside watching an aurora on a
 * phone they have not unlocked since it rebooted.
 */
class StormService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * The most recent start, so the watch loop can stand down without taking a
     * newer request with it.
     *
     * Volatile because the loop runs on the IO dispatcher and this is written
     * from the main thread.
     */
    @Volatile
    private var latestStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId

        if (intent?.action == ACTION_STOP) {
            // Unconditional: the user asked for it to stop, so it stops even if
            // something else started it a moment ago.
            stopSelf()
            return START_NOT_STICKY
        }

        // Sticky for the life of the service: a watch the user started stays
        // theirs even when a later automatic poll would have ended it.
        if (intent?.getBooleanExtra(EXTRA_MANUAL, false) == true) manual = true

        ensureChannel()
        startInForeground()

        if (watching == null) {
            running = true
            wakeLock = acquireWakeLock()
            watching = scope.launch { watch() }
        }

        // Not sticky: if the system kills this under pressure, the ordinary
        // alarm schedule is still running and will restart storm mode on the
        // next poll if the display is still going. Restarting with a null
        // intent would be a worse guess than that.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        manual = false
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Polls until the display fades, the sky closes in, it gets light, or the
     * cap is reached.
     *
     * The cap is a safety net rather than a feature: a bug that left this
     * running would hold a wake lock all night, which is the worst thing this
     * app could do to a phone.
     */
    private suspend fun watch() {
        val startedAt = System.currentTimeMillis()

        while (scope.isActive) {
            // Waits before polling rather than after. Storm mode is entered as
            // the result of a poll that has only just finished, so an immediate
            // one would re-fetch what is already known.
            delay(StormMode.POLL_MINUTES * 60_000L)

            val result = runCatching { Graph.poller(applicationContext).poll() }
                .onFailure { Log.w(TAG, "storm poll failed", it) }
                .getOrNull()

            if (result != null) {
                Log.i(TAG, "storm poll: ${result.outcome} (storm=${result.storm})")
                updateNotification(result.outcome)
                // A watch the user started runs until they end it or the cap
                // does. They are outside looking at a sky the model has not
                // caught up with, which is the whole reason to ask for it.
                if (!result.storm && !manual) {
                    Log.i(TAG, "display is over; leaving storm mode")
                    break
                }
            }

            if (System.currentTimeMillis() - startedAt > MAX_RUN_MILLIS) {
                Log.i(TAG, "storm mode hit its time limit; stopping")
                break
            }
        }

        // With the start id rather than without: an automatic watch ending at
        // the moment the user taps "watch now" would otherwise take their
        // request down with it, and the switch would flick back to off for no
        // reason they could see. Passing the id makes the stop a no-op if a
        // newer start has arrived since this loop began.
        stopSelf(latestStartId)
    }

    private fun startInForeground() {
        val notification = notification("Watching for changes")
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

    private fun updateNotification(outcome: PollOutcome) {
        val text = when (outcome) {
            is PollOutcome.Notified -> "${outcome.probability}% overhead now"
            is PollOutcome.Quiet -> "${outcome.probability}% overhead now"
            else -> "Watching for changes"
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, StormService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_STORM)
            .setSmallIcon(R.drawable.ic_notification_aurora)
            // "Aurora overhead" would be a claim the app has not made when the
            // user started this themselves on a quiet reading.
            .setContentTitle(if (manual) "Watching the sky" else "Aurora overhead")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop watching", stop)
            .setOngoing(true)
            .setSilent(true)
            // Shown at once. The system defers a low-importance foreground
            // notification by about ten seconds, and this one is the only sign
            // the app has started following a display - by which point the user
            // has put the phone back in their pocket.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * A separate, low-importance channel from the alert one. Someone who wants
     * to be woken by an alert does not necessarily want a live tracker, and the
     * two should be silenceable independently.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_STORM,
            "Live aurora",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Gloaming is following an active display minute by minute."
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire(MAX_RUN_MILLIS) }
    }.onFailure { Log.w(TAG, "could not hold a wake lock; watching anyway", it) }.getOrNull()

    companion object {
        const val CHANNEL_STORM = "aurora-live"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "StormService"
        private const val WAKE_LOCK_TAG = "gloaming:storm"
        private const val ACTION_STOP = "sh.arek.gloaming.STOP_STORM"
        private const val EXTRA_MANUAL = "sh.arek.gloaming.MANUAL_WATCH"

        /** A safety net, not a feature. Long enough to outlast any real display. */
        private const val MAX_RUN_MILLIS = 4 * 60 * 60 * 1000L

        /**
         * Whether the service is running, for the hysteresis in
         * [StormMode.shouldRun].
         *
         * A flag rather than a query to the activity manager, which has no
         * reliable answer for a service in one's own process, and which would
         * cost more than the question is worth on every poll.
         */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Whether this run was asked for by the user rather than entered
         * automatically, in which case only they end it.
         */
        @Volatile
        var manual: Boolean = false
            private set

        /**
         * Starts or stops storm mode to match [wanted].
         *
         * Never throws. Background foreground-service starts are restricted from
         * Android 12, and although the battery-optimisation exemption the app
         * already needs lifts that, a device that refuses must not take the
         * ordinary schedule down with it.
         */
        fun apply(context: Context, wanted: Boolean) {
            // A quiet poll must not cancel a watch the user is standing
            // outside using. Only they, or the cap, end that one.
            if (!wanted && manual) return
            start(context, wanted, manual = false)
        }

        /**
         * Starts watching because the user asked, whatever the sky says.
         *
         * The reliable path when the schedule is not: a foreground service runs
         * its own clock, so nothing the system does to alarms - Doze, app
         * standby, battery saver, or an OEM's own process killer - can defer
         * it. That costs a wake lock and a persistent notification, which is
         * why it is a button and not the default.
         */
        fun watchNow(context: Context) = start(context, wanted = true, manual = true)

        /** Ends a watch, whoever started it. */
        fun stop(context: Context) = start(context, wanted = false, manual = false)

        private fun start(context: Context, wanted: Boolean, manual: Boolean) {
            val intent = Intent(context, StormService::class.java)
                .putExtra(EXTRA_MANUAL, manual)
            runCatching {
                when {
                    // Started again when already running so a manual request
                    // can upgrade a run that began automatically.
                    wanted ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }

                    running -> context.stopService(intent)
                }
            }.onFailure { Log.w(TAG, "could not change storm mode", it) }
        }
    }
}
