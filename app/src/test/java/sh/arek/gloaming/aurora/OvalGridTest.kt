package sh.arek.gloaming.aurora

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OvalGridTest {

    /** Builds a packed grid using the Worker's layout: row-major by latitude. */
    private fun packed(value: (lon: Int, lat: Int) -> Int): ByteArray {
        val cells = ByteArray(OvalGrid.BYTES)
        for (lat in -90..90) {
            for (lon in 0 until OvalGrid.LONGITUDES) {
                cells[(lat + 90) * OvalGrid.LONGITUDES + lon] = value(lon, lat).toByte()
            }
        }
        return cells
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .use { it.readBytes() }

    @Test
    fun `reads the cell for an observer position`() {
        val grid = OvalGrid.fromPacked(packed { lon, lat -> if (lon == 10 && lat == 55) 73 else 0 })

        assertEquals(73, grid.probabilityAt(latitude = 55.0, longitude = 10.0))
    }

    @Test
    fun `rounds an observer position to the nearest whole degree`() {
        val grid = OvalGrid.fromPacked(packed { lon, lat -> if (lon == 10 && lat == 55) 73 else 0 })

        assertEquals(73, grid.probabilityAt(latitude = 54.7, longitude = 10.4))
        assertEquals(73, grid.probabilityAt(latitude = 55.2, longitude = 9.6))
    }

    // The grid runs 0-359 east, but users and every location API give -180..180.
    // Column numbers above 255 cannot be stored in a byte, so distinct marker
    // values stand in for the columns under test.
    @Test
    fun `maps western longitudes onto the grid's 0-359 range`() {
        val grid = OvalGrid.fromPacked(
            packed { lon, _ ->
                when (lon) {
                    357 -> 21 // Edinburgh, about 3.2 W
                    1 -> 22
                    else -> 0
                }
            },
        )

        assertEquals(21, grid.probabilityAt(latitude = 55.9, longitude = -3.2))
        assertEquals(22, grid.probabilityAt(latitude = 0.0, longitude = -359.0))
    }

    // 359.6 rounds to 360, which is not a column. Wrapping must happen after
    // rounding or the lookup runs off the end of the row.
    @Test
    fun `wraps a longitude that rounds up onto 360`() {
        val grid = OvalGrid.fromPacked(packed { lon, _ -> if (lon == 0) 42 else 0 })

        assertEquals(42, grid.probabilityAt(latitude = 0.0, longitude = 359.6))
        assertEquals(42, grid.probabilityAt(latitude = 0.0, longitude = -0.4))
        assertEquals(42, grid.probabilityAt(latitude = 0.0, longitude = 720.0))
    }

    @Test
    fun `clamps latitudes at the poles rather than running off the grid`() {
        val grid = OvalGrid.fromPacked(packed { _, lat -> if (lat == 90) 88 else if (lat == -90) 11 else 0 })

        assertEquals(88, grid.probabilityAt(latitude = 90.0, longitude = 0.0))
        assertEquals(88, grid.probabilityAt(latitude = 95.0, longitude = 0.0))
        assertEquals(11, grid.probabilityAt(latitude = -90.0, longitude = 0.0))
        assertEquals(11, grid.probabilityAt(latitude = -100.0, longitude = 0.0))
    }

    @Test
    fun `covers both hemispheres, so the aurora australis works too`() {
        val grid = OvalGrid.fromPacked(packed { lon, lat -> if (lon == 147 && lat == -43) 64 else 0 })

        // Hobart, Tasmania.
        assertEquals(64, grid.probabilityAt(latitude = -42.9, longitude = 147.3))
    }

    @Test
    fun `inflates the gzip the worker publishes`() {
        val grid = OvalGrid.fromGzip(gzip(packed { lon, lat -> if (lon == 10 && lat == 55) 73 else 0 }))

        assertEquals(73, grid.probabilityAt(latitude = 55.0, longitude = 10.0))
    }

    // A short or truncated grid would read as "no aurora anywhere" and silently
    // suppress every alert, so it must be rejected outright.
    @Test
    fun `rejects a grid that is not the expected size`() {
        assertThrows(IllegalArgumentException::class.java) {
            OvalGrid.fromPacked(ByteArray(OvalGrid.BYTES - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OvalGrid.fromGzip(gzip(ByteArray(100)))
        }
    }

    /**
     * The size check happens after inflating, which is too late: a couple of
     * kilobytes of gzipped zeros expand to gigabytes, and the process that
     * would run out of memory is a background poll at three in the morning.
     * Capping the download does not help - the whole point of compression is
     * that the two sizes are unrelated - so inflation has to stop by itself.
     */
    @Test
    fun `refuses a small payload that inflates to an enormous one`() {
        val bomb = gzip(ByteArray(64 * 1024 * 1024))
        assertTrue("fixture is not small enough to be a bomb", bomb.size < 100_000)

        val error = assertThrows(IllegalArgumentException::class.java) { OvalGrid.fromGzip(bomb) }

        // The message distinguishes the two ways this can be rejected. Being
        // told the size after the fact means the whole thing was inflated
        // first, which is the failure rather than the check.
        assertTrue(
            "rejected only after inflating it: ${error.message}",
            error.message.orEmpty().contains("stopped inflating"),
        )
    }

    @Test
    fun `rejects bytes that are not gzip at all`() {
        assertThrows(IllegalArgumentException::class.java) {
            OvalGrid.fromGzip("this is not gzip".toByteArray())
        }
    }

    /**
     * The real published grid, captured from the deployed endpoint. This is the
     * Worker-to-app contract: if the layout, size or compression ever diverge,
     * this fails.
     */
    @Test
    fun `reads the grid the deployed worker actually publishes`() {
        val grid = OvalGrid.fromGzip(resource("oval-sample.bin"))

        // Every cell is a percentage.
        for (lat in -90..90 step 10) {
            for (lon in 0 until 360 step 10) {
                val probability = grid.probabilityAt(lat.toDouble(), lon.toDouble())
                assertTrue("cell $lon,$lat was $probability", probability in 0..100)
            }
        }
        // A quiet grid is mostly zero but must not be entirely zero, or the
        // fixture would prove nothing about the mapping.
        val anyActivity = (-90..90).any { lat ->
            (0 until 360).any { lon -> grid.probabilityAt(lat.toDouble(), lon.toDouble()) > 0 }
        }
        assertTrue("the sample grid had no activity anywhere", anyActivity)
    }
}
