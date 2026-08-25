package sh.arek.gloaming.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's own log, on the device.
 *
 * Everything worth knowing about an overnight run has been going to logcat,
 * which is a ring buffer belonging to the system: by the time anyone thinks to
 * ask what happened at three in the morning it has usually rolled away. That is
 * exactly the window this app works in - the whole point of it is the hours
 * nobody is watching - so it keeps a copy of its own.
 *
 * **Never write the user's position here.** The log is meant to be exported and
 * sent to somebody, and it lives in device-encrypted storage so that a poll
 * before first unlock can write to it, which is the weaker of the two stores.
 * What belongs here is what the app decided and why: outcomes, catalogue
 * numbers, times, wake reasons. Not where it was standing when it decided.
 *
 * Bounded twice, by age and by size. Age is the user's setting; size is not,
 * because a fault that logs every second would fill a phone long before
 * anything aged out.
 */
class Journal(private val file: File, private val maxBytes: Long = MAX_BYTES) {

    private val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.UK).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Adds one entry.
     *
     * Never throws. A log that can take the app down with it when the disk is
     * full is worse than no log: the thing being recorded matters more than the
     * recording of it.
     */
    fun append(atMillis: Long, tag: String, message: String) {
        runCatching {
            file.parentFile?.mkdirs()
            // Newlines are flattened so one entry is always one line. Otherwise
            // a stack trace becomes twenty entries and pruning by age gets it
            // wrong at every boundary.
            val flat = message.replace(Regex("\\s*\\R\\s*"), " | ")
            file.appendText("${stamp.format(Date(atMillis))} $tag $flat\n")
            if (file.length() > maxBytes) trimToSize()
        }
    }

    /** The whole log, oldest first, or empty if there is none. */
    fun read(): String = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")

    fun sizeBytes(): Long = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)

    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * Drops everything older than [retentionMillis].
     *
     * Entries the parser cannot read a date from are kept. A line that cannot
     * be dated is either a bug or a truncated write, and either way it is
     * evidence; silently dropping the one unusual line in the file would be
     * removing the interesting part.
     */
    fun prune(now: Long, retentionMillis: Long) {
        runCatching {
            if (!file.exists()) return
            val cutoff = now - retentionMillis
            val kept = file.readLines().filter { line ->
                val at = timeOf(line)
                at == null || at >= cutoff
            }
            file.writeText(kept.joinToString(separator = "\n", postfix = if (kept.isEmpty()) "" else "\n"))
        }
    }

    /** Drops whole lines from the front until the file is inside its bound. */
    private fun trimToSize() {
        val lines = file.readLines().toMutableList()
        while (lines.isNotEmpty() && lines.sumOf { it.toByteArray().size + 1 } > maxBytes) {
            lines.removeAt(0)
        }
        file.writeText(lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    private fun timeOf(line: String): Long? {
        val head = line.substringBefore(' ').takeIf { it.length == STAMP_LENGTH } ?: return null
        return runCatching { stamp.parse(head)?.time }.getOrNull()
    }

    companion object {
        private const val STAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        private const val STAMP_LENGTH = 24

        /**
         * The hard ceiling, whatever retention is set to.
         *
         * Roughly a fortnight of ordinary use, and small enough that exporting
         * it is a text file rather than an attachment somebody has to think
         * about.
         */
        const val MAX_BYTES: Long = 512 * 1024

        /** Where it lives, under the cache directory in device-protected storage. */
        const val FILE_NAME = "gloaming.log"
    }
}
