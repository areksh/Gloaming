package sh.arek.gloaming.data

import java.io.File

/**
 * Keeps the published elements in the app's private storage.
 *
 * Device-protected, like the rest of the caches: `sats.json` is served from a
 * public URL and is identical on every install, so there is nothing here
 * credential encryption would be protecting.
 */
class FileSatelliteCache(directory: File) : SatelliteCache {

    private val bodyFile = File(directory, "sats.json")
    private val etagFile = File(directory, "sats.etag")

    init {
        directory.mkdirs()
    }

    override fun read(): Pair<String, String?>? {
        if (!bodyFile.isFile) return null
        return runCatching {
            bodyFile.readText() to etagFile.takeIf { it.isFile }?.readText()
        }.getOrNull()
    }

    override fun write(body: String, etag: String?) {
        runCatching {
            bodyFile.writeText(body)
            if (etag == null) etagFile.delete() else etagFile.writeText(etag)
        }
    }

    /**
     * The file's own timestamp rather than a recorded one.
     *
     * A conditional GET that comes back 304 does not rewrite the body, so this
     * is the age of the *elements*, not of the last request - which is the age
     * that matters when deciding whether a prediction can still be trusted.
     */
    override fun writtenAt(): Long? = bodyFile.takeIf { it.isFile }?.lastModified()
}
