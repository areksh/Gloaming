package sh.arek.gloaming

import android.content.Context
import java.io.File
import sh.arek.gloaming.calendar.AndroidCalendarWriter
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.AndroidCompassSource
import sh.arek.gloaming.data.AndroidLocationGrants
import sh.arek.gloaming.data.AndroidLocationSource
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.CometRepository
import sh.arek.gloaming.data.CompassSource
import sh.arek.gloaming.data.FileCometCache
import sh.arek.gloaming.data.FileManifestStore
import sh.arek.gloaming.data.FileSatelliteCache
import sh.arek.gloaming.data.FileSkyCache
import sh.arek.gloaming.data.FileVerdictLog
import sh.arek.gloaming.data.FileWeatherCache
import sh.arek.gloaming.data.GloamingEndpoints
import sh.arek.gloaming.data.Journal
import sh.arek.gloaming.data.LocationSource
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.OpenMeteoWeather
import sh.arek.gloaming.data.OutlookStore
import sh.arek.gloaming.data.PreferencesAlertStateStore
import sh.arek.gloaming.data.PreferencesSettingsStore
import sh.arek.gloaming.data.SatelliteRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.data.VerdictLog
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.data.credentialPreferences
import sh.arek.gloaming.data.devicePreferences
import sh.arek.gloaming.domain.Calibration
import sh.arek.gloaming.domain.CalendarWriter
import sh.arek.gloaming.domain.EventPoller
import sh.arek.gloaming.domain.LocationGrantSource
import sh.arek.gloaming.domain.PassPoller
import sh.arek.gloaming.domain.SkyPoller
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.OkHttpFetcher
import sh.arek.gloaming.net.ReportingFetcher
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
     * The outbound boundary, wrapped so that what it is doing can be shown.
     *
     * Every fetch in the app is built here, so one wrapper covers the manifest,
     * the status document, the grid, the elements, the comets and the forecast.
     * See [ReportingFetcher]: it changes no answer, only records one.
     */
    private fun fetcher(): HttpFetcher = ReportingFetcher(OkHttpFetcher(httpClient))

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

    /** Replaced by instrumented tests only; null means Android's own answer. */
    @Volatile
    var locationGrantsOverride: LocationGrantSource? = null

    /** Which location grants are held, for the screen to reconcile its settings with. */
    fun locationGrants(context: Context): LocationGrantSource =
        locationGrantsOverride ?: AndroidLocationGrants(context)

    /** Settings as shown and edited: the precise position the user typed. */
    fun settings(context: Context): SettingsStore =
        PreferencesSettingsStore(credentialPreferences(context))

    /**
     * Settings as every decision should actually see them.
     *
     * A layer between the store and the pollers rather than a rewrite of the
     * store, and that distinction is the whole design. With automatic settings
     * on, the calibrated values are composed over the stored ones here; with it
     * off, this is the store unchanged. Nothing ever overwrites what the user
     * typed, so switching automatic mode off restores exactly what they had -
     * which is what makes the switch safe to try.
     *
     * Wrapping [source] rather than taking a context, so the before-unlock
     * mirror gets the same treatment as the ordinary store: a poll before first
     * unlock should not quietly use different thresholds from one after it.
     */
    fun effectiveSettings(context: Context, source: SettingsStore): SettingsStore {
        val application = context.applicationContext
        return object : SettingsStore {
            override fun read(): UserSettings {
                val stored = source.read()
                if (!stored.automaticSettings) return stored
                return runCatching { Calibration.of(verdicts(application).read()).applyTo(stored) }
                    .getOrDefault(stored)
            }

            // Straight through. What is written is what the user chose, never
            // what was derived from it - otherwise one pass through the
            // composition would bake the recommendations into the store and
            // there would be nothing to go back to.
            override fun write(settings: UserSettings) = source.write(settings)
        }
    }

    /**
     * Alert state also lives in device-protected storage. It is a timestamp, a
     * probability and a latch - nothing worth protecting - and keeping it in
     * one place means a poll before unlock and one after share the same
     * hysteresis and rate limiting rather than each keeping their own.
     */
    fun alertState(context: Context): AlertStateStore =
        PreferencesAlertStateStore(devicePreferences(context))

    /** Tonight's outlook, in whichever store [BootAware.outlookPartition] chooses. */
    fun outlookStore(context: Context): OutlookStore = BootAware.outlookStore(context)

    /**
     * The cloud source, for anything that only wants what is already held.
     *
     * Its cheap reads - the last reading and the hours ahead - touch no network
     * at all, which is what lets the screen show a forecast without spending
     * the one request an hour Open-Meteo's terms allow.
     */
    /**
     * The signed manifest, for anything that needs an endpoint from it.
     *
     * Refreshing is a no-op for a copy less than a day old, so calling this on
     * a screen costs nothing on all but the first open of the day.
     */
    fun manifests(context: Context): ManifestRepository {
        val application = context.applicationContext
        return ManifestRepository(
            http = fetcher(),
            store = FileManifestStore(cacheDirectory(application)),
            publicKey = GloamingEndpoints.MANIFEST_PUBLIC_KEY,
            manifestUrl = GloamingEndpoints.MANIFEST_URL,
        )
    }

    fun weather(context: Context): WeatherSource {
        val application = context.applicationContext
        return OpenMeteoWeather(
            fetcher(),
            FileWeatherCache(weatherDirectory(application)),
        )
    }

    fun poller(context: Context): SkyPoller {
        val application = context.applicationContext
        val http = fetcher()

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
            settings = effectiveSettings(application, BootAware.pollSettings(application)),
            state = alertState(application),
            notifier = AndroidAuroraNotifier(application),
            stormRunning = { StormService.running },
        )
    }

    /**
     * The status document and the aurora grid as last fetched.
     *
     * For anything that only wants to ask how old they are - the downloads card
     * does, and has no business constructing a poller to find out.
     */
    fun sky(context: Context): SkyRepository {
        val application = context.applicationContext
        return SkyRepository(fetcher(), FileSkyCache(cacheDirectory(application)))
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
            fetcher(),
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
            fetcher(),
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
        val http = fetcher()

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
            settings = effectiveSettings(application, BootAware.pollSettings(application)),
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
        val http = fetcher()

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
            settings = effectiveSettings(application, BootAware.pollSettings(application)),
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
    /**
     * The "was it worth it?" record.
     *
     * Beside the journal in device-protected storage, so a verdict pressed from
     * a notification before the phone has been unlocked since boot still lands.
     * These alerts arrive overnight, which is exactly that state.
     */
    fun verdicts(context: Context): VerdictLog =
        FileVerdictLog(cacheDirectory(context.applicationContext))

    fun calendarWriter(context: Context): CalendarWriter =
        AndroidCalendarWriter(context.applicationContext)

    /**
     * The phone's compass, for Find. Nothing is registered until a screen
     * collects its readings, and nothing stays registered once it stops.
     */
    fun compass(context: Context): CompassSource = AndroidCompassSource(context.applicationContext)

    /** The phone's own position, for the background refresh. See [AndroidLocationSource]. */
    fun locationSource(context: Context): LocationSource =
        AndroidLocationSource(context.applicationContext)
}
