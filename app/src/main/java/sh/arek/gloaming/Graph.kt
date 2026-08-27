package sh.arek.gloaming

import android.content.Context
import java.io.File
import sh.arek.gloaming.calendar.AndroidCalendarWriter
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.CometRepository
import sh.arek.gloaming.data.FileCometCache
import sh.arek.gloaming.data.FileManifestStore
import sh.arek.gloaming.data.FileSatelliteCache
import sh.arek.gloaming.data.FileSkyCache
import sh.arek.gloaming.data.FileWeatherCache
import sh.arek.gloaming.data.GloamingEndpoints
import sh.arek.gloaming.data.Journal
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.OpenMeteoWeather
import sh.arek.gloaming.data.PreferencesAlertStateStore
import sh.arek.gloaming.data.PreferencesSettingsStore
import sh.arek.gloaming.data.SatelliteRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.data.credentialPreferences
import sh.arek.gloaming.data.devicePreferences
import sh.arek.gloaming.domain.CalendarWriter
import sh.arek.gloaming.domain.EventPoller
import sh.arek.gloaming.domain.PassPoller
import sh.arek.gloaming.domain.SkyPoller
import sh.arek.gloaming.net.OkHttpFetcher
import sh.arek.gloaming.notify.AndroidAuroraNotifier
import sh.arek.gloaming.notify.AndroidEventNotifier
import sh.arek.gloaming.notify.AndroidPassNotifier
import sh.arek.gloaming.work.AndroidPassFollower
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

    /**
     * The app's own log.
     *
     * Beside the caches in device-protected storage, so a poll before first
     * unlock can record what it did - which is exactly the run hardest to find
     * out about afterwards.
     */
    fun journal(context: Context): Journal =
        Journal(File(cacheDirectory(context.applicationContext), Journal.FILE_NAME))

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

    /**
     * The cloud source, for anything that only wants what is already held.
     *
     * Its cheap reads - the last reading and the hours ahead - touch no network
     * at all, which is what lets the screen show a forecast without spending
     * the one request an hour Open-Meteo's terms allow.
     */
    fun weather(context: Context): WeatherSource {
        val application = context.applicationContext
        return OpenMeteoWeather(
            OkHttpFetcher(httpClient),
            FileWeatherCache(weatherDirectory(application)),
        )
    }

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

    /**
     * The elements as last fetched, for anything that only needs to read them.
     *
     * The live tracker takes this rather than the poller's own copy: it works
     * from what is already on the device and never fetches, which is what lets
     * it run in a field with no signal.
     */
    fun satellites(context: Context): SatelliteRepository {
        val application = context.applicationContext
        return SatelliteRepository(
            OkHttpFetcher(httpClient),
            FileSatelliteCache(cacheDirectory(application)),
        )
    }

    /**
     * The comets as last fetched.
     *
     * Read-only like [satellites], and for a stronger version of the same
     * reason: a comet's elements are good for months, so the screen has no
     * business fetching to draw a list.
     */
    fun comets(context: Context): CometRepository {
        val application = context.applicationContext
        return CometRepository(
            OkHttpFetcher(httpClient),
            FileCometCache(cacheDirectory(application)),
        )
    }

    /**
     * The pass planner, sharing the aurora poll's wake rather than owning an
     * alarm of its own.
     *
     * Passes are deterministic, so there is nothing to poll for: it keeps its
     * own next-interesting-moment in the same alert state and does nothing at
     * all on the wakes in between.
     */
    fun passPoller(context: Context): PassPoller {
        val application = context.applicationContext
        val http = OkHttpFetcher(httpClient)

        return PassPoller(
            manifests = ManifestRepository(
                http = http,
                store = FileManifestStore(cacheDirectory(application)),
                publicKey = GloamingEndpoints.MANIFEST_PUBLIC_KEY,
                manifestUrl = GloamingEndpoints.MANIFEST_URL,
            ),
            satellites = SatelliteRepository(
                http,
                FileSatelliteCache(cacheDirectory(application)),
            ),
            weather = OpenMeteoWeather(http, FileWeatherCache(weatherDirectory(application))),
            settings = BootAware.pollSettings(application),
            state = alertState(application),
            notifier = AndroidPassNotifier(application),
            follower = AndroidPassFollower(application),
        )
    }

    /**
     * The sky calendar's poller.
     *
     * Shares the aurora wake rather than owning an alarm, the same as
     * [passPoller]: the calendar has nothing to poll for, only moments to keep,
     * and a third alarm would be a third thing to get wrong after a reboot.
     */
    fun eventPoller(context: Context): EventPoller {
        val application = context.applicationContext
        val http = OkHttpFetcher(httpClient)

        return EventPoller(
            calendar = EventPoller.REAL,
            comets = CometRepository(http, FileCometCache(cacheDirectory(application))),
            manifests = ManifestRepository(
                http = http,
                store = FileManifestStore(cacheDirectory(application)),
                publicKey = GloamingEndpoints.MANIFEST_PUBLIC_KEY,
                manifestUrl = GloamingEndpoints.MANIFEST_URL,
            ),
            weather = OpenMeteoWeather(http, FileWeatherCache(weatherDirectory(application))),
            settings = BootAware.pollSettings(application),
            state = alertState(application),
            notifier = AndroidEventNotifier(application),
            calendarWriter = calendarWriter(application),
        )
    }

    /**
     * Where starred events go, when the user has asked for that.
     *
     * Constructed unconditionally and harmless when the feature is off or the
     * permission is not held: every method checks and returns rather than
     * throwing, which is what a background wake needs.
     */
    fun calendarWriter(context: Context): CalendarWriter =
        AndroidCalendarWriter(context.applicationContext)
}
