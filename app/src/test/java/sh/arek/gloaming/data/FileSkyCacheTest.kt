package sh.arek.gloaming.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSkyCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun cache() = FileSkyCache(temporaryFolder.root)

    @Test
    fun `returns nothing before anything has been written`() {
        assertNull(cache().readStatus())
        assertNull(cache().readGrid())
    }

    @Test
    fun `round-trips the status and its etag`() {
        cache().writeStatus("""{"schema":1}""", "\"abc\"")

        val (body, etag) = cache().readStatus()!!
        assertEquals("""{"schema":1}""", body)
        assertEquals("\"abc\"", etag)
    }

    @Test
    fun `round-trips the grid still compressed`() {
        val gzipped = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)

        cache().writeGrid(CachedGrid("\"grid-1\"", gzipped))

        val read = cache().readGrid()!!
        assertArrayEquals(gzipped, read.gzipped)
        assertEquals("\"grid-1\"", read.etag)
    }

    // Without an ETag the next poll must fetch unconditionally rather than
    // reuse a stale one left behind from an earlier write.
    @Test
    fun `clears a previous etag when written without one`() {
        cache().writeStatus("a", "\"first\"")
        cache().writeStatus("b", null)

        assertNull(cache().readStatus()!!.second)
    }

    @Test
    fun `clears a previous grid etag when written without one`() {
        cache().writeGrid(CachedGrid("\"first\"", byteArrayOf(1)))
        cache().writeGrid(CachedGrid(null, byteArrayOf(2)))

        assertNull(cache().readGrid()!!.etag)
    }

    @Test
    fun `reads back a status written with no etag`() {
        cache().writeStatus("""{"schema":1}""", null)

        val (body, etag) = cache().readStatus()!!
        assertEquals("""{"schema":1}""", body)
        assertNull(etag)
    }
}
