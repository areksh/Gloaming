package sh.arek.gloaming.data

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The verdicts, in one small JSON file.
 *
 * Modelled on [FileWeatherCache] rather than on anything larger, and for the
 * same reasons: it is read on a background wake before anything heavy is
 * initialised, and there is not much of it. A season of real nights at this
 * latitude is a few dozen entries.
 *
 * No database. The project has rejected both Room and DataStore in writing, and
 * a list this size read whole and written whole is the honest shape for it.
 *
 * In device-protected storage beside the journal, so a verdict pressed from a
 * notification before the phone has been unlocked since boot still lands - which
 * is a real case, since the alerts this asks about arrive overnight.
 *
 * **Records no position.** The same rule the journal follows: what is kept is
 * what the sky was doing and what the user thought of it.
 */
class FileVerdictLog(directory: File, private val cap: Int = CAP) : VerdictLog {

    private val file = File(directory, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Verdict.serializer())

    init {
        directory.mkdirs()
    }

    override fun read(): List<Verdict> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    /**
     * Appends, oldest dropped first once the cap is reached.
     *
     * Never throws, for the reason the journal does not: a full disk should
     * cost a record, not a broadcast receiver.
     */
    override fun append(verdict: Verdict) {
        runCatching {
            val kept = (read() + verdict).takeLast(cap)
            file.writeText(json.encodeToString(serializer, kept))
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "verdicts.json"

        /**
         * Two seasons' worth, which is well past the point the recommendations
         * stop moving - and small enough that reading the file whole on a
         * background wake costs nothing worth measuring.
         */
        const val CAP = 500
    }
}
