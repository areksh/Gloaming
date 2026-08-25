package sh.arek.gloaming.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's own log.
 *
 * Everything worth knowing about an overnight run has been going to logcat,
 * which is a ring buffer the system owns: by the time anyone asks what happened
 * at three in the morning it has usually rolled away. That is precisely the
 * window this app works in, so it keeps its own.
 *
 * Bounded twice over, by age and by size, because a log that can grow without
 * limit on a phone is a bug waiting for a long night.
 */
class JournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private fun journal(maxBytes: Long = 64 * 1024) = Journal(File(folder.root, "log.txt"), maxBytes)

    @Test
    fun `writes an entry that says when, what and where from`() {
        val log = journal()

        log.append(now, "PollReceiver", "poll outcome: Quiet")

        val line = log.read().trim()
        assertTrue(line, line.contains("PollReceiver"))
        assertTrue(line, line.contains("poll outcome: Quiet"))
        // A fixed UTC stamp, so the file reads the same wherever it is opened
        // and can be pruned by parsing rather than by guessing.
        assertTrue(line, line.startsWith("2027-01-15T"))
    }

    @Test
    fun `keeps entries in the order they happened`() {
        val log = journal()

        log.append(now, "A", "first")
        log.append(now + 1000, "B", "second")

        val lines = log.read().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("first"))
        assertTrue(lines[1].contains("second"))
    }

    @Test
    fun `drops entries older than the retention the user chose`() {
        val log = journal()
        log.append(now - 9 * day, "old", "nine days ago")
        log.append(now - 2 * day, "recent", "two days ago")

        log.prune(now, retentionMillis = 7 * day)

        val kept = log.read()
        assertTrue(kept, kept.contains("two days ago"))
        assertTrue(kept, !kept.contains("nine days ago"))
    }

    /**
     * Age alone is not enough. A fault that logs every second would fill a
     * phone long before anything aged out, so the size bound is independent of
     * what the user asked to keep.
     */
    @Test
    fun `drops the oldest entries once the file is too big`() {
        val log = journal(maxBytes = 400)

        repeat(60) { log.append(now + it * 1000L, "Tag", "entry number $it") }

        assertTrue(log.sizeBytes() <= 400)
        val kept = log.read()
        assertTrue(kept, kept.contains("entry number 59"))
        assertTrue(kept, !kept.contains("entry number 0 "))
    }

    @Test
    fun `reads as empty before anything is written`() {
        assertEquals("", journal().read())
        assertEquals(0L, journal().sizeBytes())
    }

    @Test
    fun `clears on request`() {
        val log = journal()
        log.append(now, "Tag", "something")

        log.clear()

        assertEquals("", log.read())
    }

    /** A newline in a message must not become a second entry. */
    @Test
    fun `keeps a multi-line message on one line`() {
        val log = journal()

        log.append(now, "Tag", "first\nsecond")

        assertEquals(1, log.read().trim().lines().size)
    }

    /** Pruning an empty log is a normal thing to do, not an error. */
    @Test
    fun `prunes an empty log without complaint`() {
        val log = journal()

        log.prune(now, retentionMillis = day)

        assertEquals("", log.read())
    }
}
