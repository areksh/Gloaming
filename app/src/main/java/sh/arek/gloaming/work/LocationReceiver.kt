package sh.arek.gloaming.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.os.UserManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.arek.gloaming.Graph
import sh.arek.gloaming.Notes
import sh.arek.gloaming.data.AndroidLocationSource
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationChoice
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.domain.LocationRefresh
import sh.arek.gloaming.domain.RefreshSkip
import kotlin.coroutines.coroutineContext

/**
 * Refreshes the phone's approximate position when [LocationAlarm] fires.
 *
 * Not Direct Boot aware, unlike [PollReceiver]. The cadence and the position it
 * replaces are kept in credential-encrypted storage, so a wake before first
 * unlock could read neither, and the precise position must never be written
 * anywhere weaker. The reboot that locks the phone also clears this alarm, and
 * [BootReceiver] arms it again on the boot broadcast that follows the unlock.
 *
 * Says what it did in the journal, and never where the phone was: the lines are
 * "location refreshed" or why not, and nothing more.
 */
class LocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext

        // Before any settings are read: the store this needs throws while the
        // phone is locked. Nothing is re-armed either - see the class comment.
        if (!UserManagerCompat.isUserUnlocked(application)) return

        val pending = goAsync()

        // An optimisation, as in PollReceiver: without it the fix may be lost
        // to sleep and the next wake tries again.
        val wakeLock = runCatching {
            application.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        }.onFailure { Log.w(TAG, "could not hold a wake lock; refreshing anyway", it) }
            .getOrNull()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Well inside the sixty seconds Android allows a goAsync
                // broadcast, which ends it without running this finally.
                withTimeoutOrNull(BUDGET_MILLIS) { refresh(application) }
                    ?: Notes.w(application, TAG, "location refresh ran out of time")
            } catch (error: Throwable) {
                Notes.w(application, TAG, "location refresh failed", error)
            } finally {
                // Whatever happened, from the settings as they now stand: a
                // cadence put back to opening the app cancels the alarm; a
                // refresh that found nothing left the position overdue or
                // never refreshed, so it retries after the quarter-hour floor;
                // and one that stored a fix waits a whole interval.
                runCatching { LocationAlarm.arm(application, Graph.settings(application).read()) }
                    .onFailure {
                        Notes.w(application, TAG, "could not arm the next location refresh", it)
                    }
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                pending.finish()
            }
        }
    }

    /** One refresh, or the reason for skipping it. */
    private suspend fun refresh(context: Context) {
        val store = Graph.settings(context)
        val settings = store.read()
        val now = System.currentTimeMillis()

        when (
            val skip = LocationRefresh.skip(
                settings = settings,
                unlocked = UserManagerCompat.isUserUnlocked(context),
                // Both, because below API 29 the background answer is always
                // yes and only the coarse grant can be taken away. Without it a
                // withdrawn grant there read as "no fix" and retried every
                // quarter of an hour for good.
                backgroundPermission = AndroidLocationSource.hasPermission(context) &&
                    AndroidLocationSource.hasBackgroundPermission(context),
                now = now,
            )
        ) {
            null -> Unit
            RefreshSkip.NoPermission -> {
                // Taken away in the system settings since the cadence was
                // chosen. Falling back to refreshing on open keeps the app
                // honest about what it can do, and the flag lets the screen say
                // why the choice changed rather than leaving it to be noticed.
                BootAware.saveSettings(
                    context,
                    settings.copy(
                        locationCadence = LocationCadence.OnOpen,
                        backgroundLocationRevoked = true,
                    ),
                )
                Notes.w(context, TAG, "background location was taken away; refreshing on open")
                return
            }
            else -> {
                Notes.i(context, TAG, "location refresh skipped: $skip")
                return
            }
        }

        val fix = Graph.locationSource(context).fresh(LocationChoice.FRESH_TIMEOUT_MILLIS)
        // fresh() swallows cancellation along with every other failure, so the
        // budget is checked here rather than trusted to have stopped it.
        coroutineContext.ensureActive()

        if (fix == null) {
            Notes.i(context, TAG, "location refresh found no fix")
            return
        }

        // Read again rather than reusing the copy from before the request,
        // which took up to twenty seconds: anything changed on the screen in
        // that time must survive, and a position typed in meanwhile must not be
        // overwritten by a detected one.
        val latest = store.read()
        if (latest.locationMode != LocationMode.Automatic) return
        BootAware.saveSettings(
            context,
            latest.copy(
                latitude = fix.latitude,
                longitude = fix.longitude,
                locationUpdatedAt = now,
            ),
        )
        Notes.i(context, TAG, "location refreshed")
    }

    private companion object {
        const val TAG = "LocationReceiver"
        const val WAKE_LOCK_TAG = "gloaming:location"
        const val BUDGET_MILLIS = 30_000L
        const val WAKE_LOCK_TIMEOUT_MILLIS = 40_000L
    }
}
