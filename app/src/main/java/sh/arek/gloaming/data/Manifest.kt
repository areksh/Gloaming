package sh.arek.gloaming.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The signed manifest: the one document whose URL is compiled into the APK.
 *
 * APKs live forever. F-Droid and Obtainium users do not reliably update, so any
 * other URL baked into a release would leave those installs polling a dead
 * endpoint indefinitely. Everything else the app talks to is reached through
 * [endpoints].
 */
@Serializable
data class Manifest(
    val schema: Int,
    /**
     * Kill switch. An install older than this should tell the user to update
     * rather than carry on with assumptions that no longer hold.
     */
    @SerialName("min_supported_version") val minSupportedVersion: Int,
    /**
     * Broadcast channel into a notification on every install. Rendered as inert
     * plain text with no clickable links, whatever it contains - it is the
     * reason the manifest is signed at all.
     */
    val message: String? = null,
    /** Named endpoints: `status`, `oval`, `weather`, and later `sats`, `comets`. */
    val endpoints: Map<String, String> = emptyMap(),
)

/** A manifest and its detached signature, exactly as fetched. */
class CachedManifest(val body: ByteArray, val signature: ByteArray)

/** Persistence for the last known-good manifest. */
interface ManifestStore {
    fun read(): CachedManifest?
    fun write(cached: CachedManifest)
}

/**
 * Raised when no manifest can be trusted: the network copy failed verification
 * and there is no good cached copy to fall back on.
 *
 * Deliberately fatal. Guessing an endpoint, or using an unverified one, would
 * defeat the point of signing the manifest.
 */
class ManifestUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
