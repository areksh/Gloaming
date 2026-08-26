package sh.arek.gloaming.data

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileManifestStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(directory: File = temporaryFolder.root) = FileManifestStore(directory)

    @Test
    fun `returns null before anything has been written`() {
        assertNull(store().read())
    }

    @Test
    fun `round-trips the manifest and its signature byte for byte`() {
        val body = """{"schema":1}""".toByteArray()
        val signature = ByteArray(64) { it.toByte() }

        store().write(CachedManifest(body, signature))
        val read = store().read()

        assertArrayEquals(body, read!!.body)
        assertArrayEquals(signature, read.signature)
    }

    @Test
    fun `overwrites a previous manifest`() {
        val first = """{"schema":1}""".toByteArray()
        val second = """{"schema":2}""".toByteArray()
        val signature = ByteArray(64)

        store().write(CachedManifest(first, signature))
        store().write(CachedManifest(second, signature))

        assertArrayEquals(second, store().read()!!.body)
    }

    // A half-written pair must not be presented as usable. Reading it back
    // would hand the repository a body and signature that cannot match.
    @Test
    fun `returns null when only one of the two files exists`() {
        store().write(CachedManifest("{}".toByteArray(), ByteArray(64)))
        File(temporaryFolder.root, "manifest.sig").delete()

        assertNull(store().read())
    }

    @Test
    fun `has no timestamp before anything has been written`() {
        assertNull(store().writtenAt())
    }

    // The stamp is what holds the daily fetch cadence, so it has to move with
    // the write - a stamp that survived an overwrite would starve the app of
    // a manifest that had genuinely changed.
    @Test
    fun `stamps the moment the manifest was written`() {
        val before = System.currentTimeMillis()

        store().write(CachedManifest("{}".toByteArray(), ByteArray(64)))
        val written = store().writtenAt()

        assertTrue("no stamp after a write", written != null)
        // File timestamps can be coarser than the clock, so a second of slack.
        assertTrue(
            "stamp $written is not around $before",
            written!! >= before - 1_000 && written <= System.currentTimeMillis() + 1_000,
        )
    }

    @Test
    fun `creates its directory if it does not exist yet`() {
        val nested = File(temporaryFolder.root, "does/not/exist")

        store(nested).write(CachedManifest("{}".toByteArray(), ByteArray(64)))

        assertArrayEquals("{}".toByteArray(), store(nested).read()!!.body)
    }
}
