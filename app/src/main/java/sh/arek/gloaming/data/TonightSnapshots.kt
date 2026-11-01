package sh.arek.gloaming.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.ui.Timeline

/** Bumped whenever a type reachable from [TonightSnapshot] changes shape; an old file is then dropped. */
const val SNAPSHOT_VERSION = 1

private const val FALLBACK_NIGHT_MILLIS = 12 * 60 * 60 * 1000L

/**
 * The last worked-out Tonight, kept so that opening the app shows it at once
 * rather than an empty screen for the twenty seconds the real work takes.
 *
 * It holds a night worked out from the precise position, so it is kept only
 * in credential-encrypted storage (see [Graph.tonightSnapshots]); the grid
 * cell it is keyed by is as coarse as the stores that may live before first
 * unlock, but the content is not.
 *
 * The satellite elements are not kept: they are large, and the fresh run
 * brings them. A snapshot is therefore a picture of the night as it was, shown
 * as updating until that run lands.
 */
@Serializable
data class TonightSnapshot(
    val version: Int,
    val cell: GridCell,
    /** When the night this describes is over, after which it is no use. */
    val nightEndMillis: Long,
    val writtenAt: Long,
    val list: TonightList,
    val outlook: OutlookReport?,
    val hourly: List<HourlyCover>,
    val timeline: Timeline?,
) {
    companion object {
        /** A snapshot of the current version, with its night's end worked out from what it holds. */
        fun of(
            cell: GridCell,
            writtenAt: Long,
            list: TonightList,
            outlook: OutlookReport?,
            hourly: List<HourlyCover>,
            timeline: Timeline?,
        ) = TonightSnapshot(
            version = SNAPSHOT_VERSION,
            cell = cell,
            nightEndMillis = timeline?.endMillis ?: list.darkUntil ?: (writtenAt + FALLBACK_NIGHT_MILLIS),
            writtenAt = writtenAt,
            list = list,
            outlook = outlook,
            hourly = hourly,
            timeline = timeline,
        )
    }
}

/**
 * Whether this snapshot is still about the night in front of the observer:
 * the same grid cell, and the night not yet over. A snapshot written at 02:00
 * and read at 15:00 the next day is not.
 */
fun TonightSnapshot.usable(cell: GridCell, nowMillis: Long): Boolean =
    cell == this.cell && nowMillis < nightEndMillis

/** Where the last worked-out Tonight is kept between runs of the process. */
interface TonightSnapshots {
    /** The snapshot held, or null when there is none or it cannot be read. */
    fun read(): TonightSnapshot?

    fun write(snapshot: TonightSnapshot)

    fun clear()
}

/**
 * One JSON file in [dir], which must be credential-encrypted storage.
 *
 * Written to a temporary file and renamed over the old one, so a process
 * killed mid-write leaves the previous snapshot rather than half of a new
 * one. A file that will not decode, or was written by another
 * [SNAPSHOT_VERSION], reads as null and is deleted.
 */
class FileTonightSnapshots(private val dir: File) : TonightSnapshots {

    private val file = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): TonightSnapshot? {
        if (!file.isFile) return null
        val snapshot = runCatching {
            json.decodeFromString(TonightSnapshot.serializer(), file.readText())
        }.getOrNull()
        if (snapshot == null || snapshot.version != SNAPSHOT_VERSION) {
            clear()
            return null
        }
        return snapshot
    }

    override fun write(snapshot: TonightSnapshot) {
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, "$FILE_NAME.tmp")
            temporary.writeText(json.encodeToString(TonightSnapshot.serializer(), snapshot))
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "tonight-snapshot.json"
    }
}
