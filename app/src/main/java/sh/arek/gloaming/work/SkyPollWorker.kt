package sh.arek.gloaming.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import sh.arek.gloaming.Graph
import sh.arek.gloaming.data.UserSettings

/**
 * One scheduled wake.
 *
 * Chained one-time work rather than periodic work: `WorkManager`'s minimum
 * periodic interval is fifteen minutes, which the eventual adaptive schedule
 * violates in both directions. Chaining also survives reboot without a boot
 * receiver, which matters for something meant to run all night.
 */
class SkyPollWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        try {
            val outcome = Graph.poller(applicationContext).poll()
            android.util.Log.i(TAG, "poll outcome: $outcome")
        } finally {
            // Always chain the next wake, even after a failure. A poll that
            // throws must not silently end the schedule and leave the app
            // permanently asleep - the failure is already recorded for the UI.
            schedule(applicationContext, Graph.settings(applicationContext).read())
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SkyPollWorker"
        const val WORK_NAME = "gloaming-poll"

        /**
         * Queues the next poll.
         *
         * Every wake is jittered. A fixed period with no randomisation aligns
         * every install on wall-clock boundaries; the OS batches jobs across
         * apps anyway, but that is a side effect and not something to rely on.
         */
        fun schedule(context: Context, settings: UserSettings) {
            val periodSeconds = settings.pollIntervalMinutes.coerceAtLeast(1) * 60L
            val jitterSeconds = Random.nextLong(0, (periodSeconds / 4).coerceAtLeast(1))

            val request = OneTimeWorkRequestBuilder<SkyPollWorker>()
                .setInitialDelay(periodSeconds + jitterSeconds, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Runs a poll immediately, for the in-app "check now" action. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SkyPollWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
