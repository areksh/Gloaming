package sh.arek.gloaming.data

import java.io.File

/**
 * Keeps the last status document and aurora grid in the app's private storage.
 *
 * Plain files again: these are read on a background wake before anything
 * heavier is initialised, and the grid is an opaque blob that gains nothing
 * from a database.
 *
 * The grid is stored still compressed. It is about 2 KB that way against 65 KB
 * inflated, and inflating costs a millisecond on the rare wake that needs it.
 */
class FileSkyCache(directory: File) : SkyCache {

    private val statusFile = File(directory, "status.json")
    private val statusEtagFile = File(directory, "status.etag")
    private val gridFile = File(directory, "oval.bin.gz")
    private val gridEtagFile = File(directory, "oval.etag")

    init {
        directory.mkdirs()
    }

    override fun statusWrittenAt(): Long? = statusFile.takeIf { it.isFile }?.lastModified()

    override fun gridWrittenAt(): Long? = gridFile.takeIf { it.isFile }?.lastModified()

    override fun readStatus(): Pair<String, String?>? {
        if (!statusFile.isFile) return null
        return runCatching {
            statusFile.readText() to statusEtagFile.takeIf { it.isFile }?.readText()
        }.getOrNull()
    }

    override fun writeStatus(body: String, etag: String?) {
        runCatching {
            statusFile.writeText(body)
            if (etag == null) statusEtagFile.delete() else statusEtagFile.writeText(etag)
        }
    }

    override fun readGrid(): CachedGrid? {
        if (!gridFile.isFile) return null
        return runCatching {
            CachedGrid(gridEtagFile.takeIf { it.isFile }?.readText(), gridFile.readBytes())
        }.getOrNull()
    }

    /**
     * Writes the grid before its ETag.
     *
     * If that is interrupted, the ETag still names the older grid, so the pair
     * looks stale and is simply re-fetched. The other order could label a stale
     * grid as current, which would silently serve the wrong aurora data.
     */
    override fun writeGrid(cached: CachedGrid) {
        runCatching {
            gridFile.writeBytes(cached.gzipped)
            if (cached.etag == null) gridEtagFile.delete() else gridEtagFile.writeText(cached.etag)
        }
    }
}
