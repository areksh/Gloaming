package sh.arek.gloaming.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The device's own idea of where it is, from `LocationManager`.
 *
 * **Never the fused provider**, which is Play Services and which this app does
 * not have - a constraint from Sec. 7 rather than an oversight.
 *
 * **Last-known fixes only; no location updates are ever requested.** That is
 * enough for the question the app asks, which is "roughly where is this phone"
 * and not "where is it going". It also means no GPS is ever switched on by
 * Gloaming, so asking costs no battery at all: these are positions other apps
 * have already caused the system to record.
 *
 * The consequence is that the answer can be nothing, when no app has asked
 * recently. That is reported honestly rather than papered over by turning the
 * radio on, and the typed-in position remains the answer that always works.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    override suspend fun current(): DeviceFix? {
        if (!hasPermission(context)) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val now = System.currentTimeMillis()

        val fixes = runCatching {
            manager.getProviders(true).mapNotNull { provider ->
                @Suppress("MissingPermission")
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
        }.onFailure { Log.w(TAG, "could not read a last-known position", it) }
            .getOrDefault(emptyList())
            .map {
                DeviceFix(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracyMetres = if (it.hasAccuracy()) it.accuracy else 0f,
                    atMillis = it.time,
                )
            }

        return LocationChoice.best(fixes, now)
    }

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

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
