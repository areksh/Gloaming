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
 * **Never Play Services, and never GPS.** `GPS_PROVIDER` is not used anywhere,
 * and the coarse permission could not reach it. The only fused provider asked
 * for is the platform's own (API 31+), as a fallback when the network
 * provider is off - a constraint from Sec. 7 rather than an oversight.
 *
 * **[current] reads last-known fixes only.** That is enough for the question
 * the foreground asks, which is "roughly where is this phone" and not "where
 * is it going", and it costs no battery: these are positions other apps have
 * already caused the system to record. The answer can be nothing, when no app
 * has asked recently. That is reported honestly rather than papered over, and
 * the typed-in position remains the answer that always works.
 *
 * **[fresh] is the one place a position is asked for**, a single approximate
 * fix from the network provider with a hard timeout, for the background path
 * to notice that the phone has moved. No updates are subscribed to.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    override suspend fun current(): DeviceFix? {
        if (!hasPermission(context)) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()

        return LocationChoice.best(lastKnown(manager), now)
    }

    override suspend fun fresh(timeoutMillis: Long): DeviceFix? {
        if (!hasPermission(context)) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = freshProvider(manager)
        val asked = if (provider == null) null else {
            runCatching { withTimeoutOrNull(timeoutMillis) { requestOnce(manager, provider) } }
                .onFailure { Log.w(TAG, "could not ask for a fresh position", it) }
                .getOrNull()
        }

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

    /** Network first; the platform's fused provider only where it exists and is on. */
    private fun freshProvider(manager: LocationManager): String? = when {
        runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
            .getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) }
                .getOrDefault(false) -> LocationManager.FUSED_PROVIDER
        else -> null
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

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
