package sh.arek.gloaming.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The device's own idea of where it is, from `LocationManager`.
 *
 * **Never Play Services, and approximate only.** The coarse permission is the
 * whole ask; Android hands a coarse-only app a fix blurred to a few
 * kilometres, whichever provider produced it. The only fused provider used is
 * the platform's own (API 31+), a constraint from Sec. 7 rather than an
 * oversight.
 *
 * **[current] asks for a fresh fix when the last-known one is stale** (see
 * [FixPolicy]): inside ten minutes a position another app already caused the
 * system to record is enough and costs nothing; past that the phone may have
 * moved, so a single fix is requested with a twenty-second timeout. The phone
 * may switch the GPS on to answer. It can still come back with nothing, which
 * is reported honestly, and the typed-in position remains the answer that
 * always works.
 *
 * **[fresh] is the same ask with the caller's timeout**, used by the
 * background path to notice that the phone has moved. Providers are tried in
 * [FixPolicy.providers] order and the first to answer wins; they share one
 * timeout. A provider that refuses a coarse-only app (older Android does so
 * for GPS) is skipped. No updates are subscribed to.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    override suspend fun current(): DeviceFix? {
        if (!hasPermission(context)) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val recorded = LocationChoice.best(lastKnown(manager), now)
        if (!FixPolicy.needsFresh(recorded, now)) return recorded

        return fresh(FixPolicy.FOREGROUND_TIMEOUT_MILLIS)
    }

    override suspend fun fresh(timeoutMillis: Long): DeviceFix? {
        if (!hasPermission(context)) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val asked = runCatching {
            withTimeoutOrNull(timeoutMillis) { askInOrder(manager) }
        }.onFailure { Log.w(TAG, "could not ask for a fresh position", it) }
            .getOrNull()

        val fixes = lastKnown(manager) + listOfNotNull(asked?.toFix())
        return LocationChoice.best(fixes, System.currentTimeMillis())
    }

    private fun lastKnown(manager: LocationManager): List<DeviceFix> = runCatching {
        manager.getProviders(true).mapNotNull { provider ->
            @Suppress("MissingPermission")
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
    }.onFailure { Log.w(TAG, "could not read a last-known position", it) }
        .getOrDefault(emptyList())
        .map { it.toFix() }

    /** The first provider to answer; one that refuses or says nothing hands on to the next. */
    private suspend fun askInOrder(manager: LocationManager): Location? {
        val enabled = runCatching { manager.getProviders(true).toSet() }
            .onFailure { Log.w(TAG, "could not list the enabled providers", it) }
            .getOrDefault(emptySet())

        for (provider in FixPolicy.providers(Build.VERSION.SDK_INT, enabled)) {
            val location = try {
                requestOnce(manager, provider)
            } catch (e: SecurityException) {
                // Older Android refuses GPS to a coarse-only app.
                Log.w(TAG, "$provider refused a coarse-only ask", e)
                null
            }
            if (location != null) return location
        }
        return null
    }

    /** One fix or null; cancelling the coroutine cancels the system's request. */
    private suspend fun requestOnce(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            @Suppress("MissingPermission")
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                ContextCompat.getMainExecutor(context),
                Consumer<Location?> { location -> continuation.resume(location) },
            )
        }

    private fun Location.toFix() = DeviceFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = if (hasAccuracy()) accuracy else 0f,
        atMillis = time,
    )

    companion object {
        private const val TAG = "AndroidLocationSource"

        /**
         * Coarse only, and that is the whole ask.
         *
         * The aurora grid is one-degree cells, Open-Meteo is given a position
         * rounded to about a kilometre, and a few kilometres of error moves a
         * satellite pass by a second or so. Fine location would buy the app
         * nothing it uses and would be exactly the "permission the app does not
         * need" that Sec. 7 says weakens the whole posture.
         */
        const val PERMISSION: String = Manifest.permission.ACCESS_COARSE_LOCATION

        /** The coarse grant, extended in time for a cadence the user chose (ADR-0016). */
        const val BACKGROUND_PERMISSION: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION

        /**
         * Whether a receiver may ask for a position with no screen open. Below
         * API 29 the coarse grant already covers it.
         */
        fun hasBackgroundPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

        /**
         * What to ask for so a background cadence can run, or nothing if it
         * already can.
         *
         * Below API 29 the coarse grant covers the background too. On 29 both
         * go in one request. From 30 Android refuses a background request
         * made alongside the coarse one, so coarse is asked for first and
         * background on its own afterwards - which Android answers by sending
         * the user to its settings page rather than with a dialog.
         */
        fun backgroundRequest(
            sdk: Int,
            coarseGranted: Boolean,
            backgroundGranted: Boolean,
        ): List<String> = when {
            sdk < Build.VERSION_CODES.Q -> if (coarseGranted) emptyList() else listOf(PERMISSION)
            coarseGranted && backgroundGranted -> emptyList()
            sdk == Build.VERSION_CODES.Q -> listOf(PERMISSION, BACKGROUND_PERMISSION)
            !coarseGranted -> listOf(PERMISSION)
            else -> listOf(BACKGROUND_PERMISSION)
        }

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
