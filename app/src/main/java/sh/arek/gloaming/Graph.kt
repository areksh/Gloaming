package sh.arek.gloaming

import android.content.Context
import java.io.File
import okhttp3.OkHttpClient
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.FileManifestStore
import sh.arek.gloaming.data.FileSkyCache
import sh.arek.gloaming.data.GloamingEndpoints
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.PreferencesAlertStateStore
import sh.arek.gloaming.data.PreferencesSettingsStore
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.domain.SkyPoller
import sh.arek.gloaming.net.OkHttpFetcher
import sh.arek.gloaming.notify.AndroidAuroraNotifier

/**
 * How the pieces are wired together.
 *
 * Deliberately hand-written rather than a DI framework. The graph is a handful
 * of objects with no scoping to speak of, and a background wake wants to be
 * cheap to start. Hilt earns its keep when the graph grows past what this file
 * can state plainly; it has not yet.
 */
object Graph {

    /** One client, so connections and the thread pool are reused across wakes. */
    private val httpClient by lazy { OkHttpClient() }

    private fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "sky")

    fun settings(context: Context): SettingsStore =
        PreferencesSettingsStore(context.applicationContext)

    fun alertState(context: Context): AlertStateStore =
        PreferencesAlertStateStore(context.applicationContext)

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
            settings = settings(application),
            state = alertState(application),
            notifier = AndroidAuroraNotifier(application),
        )
    }
}
