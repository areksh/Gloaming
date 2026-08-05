package sh.arek.gloaming.data

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
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
    fun `creates its directory if it does not exist yet`() {
        val nested = File(temporaryFolder.root, "does/not/exist")

        store(nested).write(CachedManifest("{}".toByteArray(), ByteArray(64)))

        assertArrayEquals("{}".toByteArray(), store(nested).read()!!.body)
    }
}
