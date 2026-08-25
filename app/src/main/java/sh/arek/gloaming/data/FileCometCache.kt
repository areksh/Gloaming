package sh.arek.gloaming.data

import java.io.File

/**
 * Keeps the published comets in the app's private storage.
 *
 * Device-protected, like the rest of the caches: `comets.json` is served from a
 * public URL and is identical on every install, so there is nothing here that
 * credential encryption would be protecting.
 */
class FileCometCache(directory: File) : CometCache {

    private val bodyFile = File(directory, "comets.json")
    private val etagFile = File(directory, "comets.etag")

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

    /** The file's own timestamp, so a 304 does not look like a refresh. */
    override fun writtenAt(): Long? = bodyFile.takeIf { it.isFile }?.lastModified()
}
