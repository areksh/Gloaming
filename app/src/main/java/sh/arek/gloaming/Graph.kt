package sh.arek.gloaming

import android.content.Context
import java.io.File
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.FileManifestStore
import sh.arek.gloaming.data.FileSkyCache
import sh.arek.gloaming.data.FileWeatherCache
import sh.arek.gloaming.data.GloamingEndpoints
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.OpenMeteoWeather
import sh.arek.gloaming.data.PreferencesAlertStateStore
import sh.arek.gloaming.data.credentialPreferences
import sh.arek.gloaming.data.devicePreferences
import sh.arek.gloaming.data.PreferencesSettingsStore
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.domain.SkyPoller
import sh.arek.gloaming.net.OkHttpFetcher
import sh.arek.gloaming.notify.AndroidAuroraNotifier
import sh.arek.gloaming.work.StormService

/**
 * How the pieces are wired together.
 *
 * Deliberately hand-written rather than a DI framework. The graph is a handful
 * of objects with no scoping to speak of, and a background wake wants to be
 * cheap to start. Hilt earns its keep when the graph grows past what this file
 * can state plainly; it has not yet.
 *
 * Everything the background poll touches is anchored in device-protected
 * storage, so a wake before first unlock finds its caches and state where it
 * expects them.
 */
object Graph {

    /** One client, so connections and the thread pool are reused across wakes. */
    private val httpClient by lazy { OkHttpFetcher.defaultClient() }

    /**
     * Caches live in device-protected storage unconditionally. They hold the
     * manifest, the status document and the aurora grid - all published at a
     * public URL, so there is nothing here that credential encryption would be
     * protecting.
     */
    private fun cacheDirectory(context: Context): File =
        File(BootAware.deviceContext(context).filesDir, BootAware.CACHE_DIRECTORY)

    /**
     * The cloud reading is the one cached thing that says where the user was,
     * and at about a kilometre rather than the whole degree the settings mirror
     * keeps - so it does not live with the rest unconditionally. See
     * [BootAware.weatherCacheContext].
     */
    private fun weatherDirectory(context: Context): File =
        File(BootAware.weatherCacheContext(context).filesDir, BootAware.CACHE_DIRECTORY)

    /** Settings as shown and edited: the precise position the user typed. */
    fun settings(context: Context): SettingsStore =
        PreferencesSettingsStore(credentialPreferences(context))

    /**
     * Alert state also lives in device-protected storage. It is a timestamp, a
     * probability and a latch - nothing worth protecting - and keeping it in
     * one place means a poll before unlock and one after share the same
     * hysteresis and rate limiting rather than each keeping their own.
     */
    fun alertState(context: Context): AlertStateStore =
        PreferencesAlertStateStore(devicePreferences(context))

    fun poller(context: Context): SkyPoller {
        val application = context.applicationContext
        val http = OkHttpFetcher(httpClient)

        return SkyPoller(
            manifests = ManifestRepository(
                http = http,
                store = FileManifestStore(cacheDirectory(application)),
                publicKey = GloamingEndpoints.MANIFEST_PUBLIC_KEY,
                manifestUrl = GloamingEndpoints.MANIFEST_URL,
            ),
            sky = SkyRepository(http, FileSkyCache(cacheDirectory(application))),
            weather = OpenMeteoWeather(http, FileWeatherCache(weatherDirectory(application))),
            // Whichever store is authoritative for polling: the device-protected
            // mirror when before-unlock alerting is on, the normal one otherwise.
            settings = BootAware.pollSettings(application),
            state = alertState(application),
            notifier = AndroidAuroraNotifier(application),
            stormRunning = { StormService.running },
        )
    }
}
