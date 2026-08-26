package sh.arek.gloaming.data

import java.io.File

/**
 * Keeps the last known-good manifest as two files in the app's private storage.
 *
 * A plain file rather than DataStore or Room: this is one small opaque blob and
 * its signature, written about once a day, and it must be readable early in a
 * background wake before anything heavier is initialised.
 *
 * The bytes are stored exactly as received. The signature covers those exact
 * bytes, so anything that re-encoded them - even re-serialising equivalent JSON
 * - would make the cached copy fail verification.
 */
class FileManifestStore(directory: File) : ManifestStore {

    private val bodyFile = File(directory, "manifest.json")
    private val signatureFile = File(directory, "manifest.sig")

    init {
        directory.mkdirs()
    }

    override fun read(): CachedManifest? {
        if (!bodyFile.isFile || !signatureFile.isFile) return null
        return runCatching { CachedManifest(bodyFile.readBytes(), signatureFile.readBytes()) }
            .getOrNull()
    }

    /**
     * The body file's own timestamp, the same choice the element and comet
     * caches make: the body is written last, so this stamps the moment the
     * pair was complete.
     */
    override fun writtenAt(): Long? = bodyFile.takeIf { it.isFile }?.lastModified()

    /**
     * Writes the signature first, then the body.
     *
     * A crash between the two leaves a signature that does not match the older
     * body, so the pair fails verification and is discarded - which is correct.
     * The other order could leave a new body paired with an old signature, and
     * that also fails, so either way the store fails closed rather than
     * presenting an unverified manifest as trusted.
     */
    override fun write(cached: CachedManifest) {
        signatureFile.writeBytes(cached.signature)
        bodyFile.writeBytes(cached.body)
    }
}
