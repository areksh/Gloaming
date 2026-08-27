package sh.arek.gloaming.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileWeatherCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun cache() = FileWeatherCache(temporaryFolder.root)

    private val reading = CachedCloud(
        cover = CloudCover(total = 55, low = 20, mid = 5, high = 40),
        latitude = 55.95,
        longitude = -3.19,
        fetchedAt = 1_700_000_000_000L,
    )

    @Test
    fun `returns nothing before anything has been written`() {
        assertNull(cache().read())
    }

    @Test
    fun `round-trips a reading, its place and its age`() {
        cache().write(reading)

        val read = cache().read()!!
        assertEquals(55, read.cover.total)
        assertEquals(20, read.cover.low)
        assertEquals(5, read.cover.mid)
        assertEquals(40, read.cover.high)
        assertEquals(55.95, read.latitude, 0.0)
        assertEquals(-3.19, read.longitude, 0.0)
        assertEquals(1_700_000_000_000L, read.fetchedAt)
    }

    @Test
    fun `keeps only the most recent reading`() {
        cache().write(reading)
        cache().write(CachedCloud(CloudCover(0, 0, 0, 0), 51.51, -0.13, 1_700_000_100_000L))

        val read = cache().read()!!
        assertEquals(0, read.cover.total)
        assertEquals(51.51, read.latitude, 0.0)
    }

    @Test
    fun `round-trips the hours ahead`() {
        cache().write(
            reading.let {
                CachedCloud(
                    it.cover, it.latitude, it.longitude, it.fetchedAt,
                    hourly = listOf(
                        HourlyCover(1_700_000_000_000L, 90),
                        HourlyCover(1_700_003_600_000L, 10),
                    ),
                )
            },
        )

        val read = cache().read()!!
        assertEquals(2, read.hourly.size)
        assertEquals(1_700_000_000_000L, read.hourly[0].atMillis)
        assertEquals(90, read.hourly[0].total)
        assertEquals(10, read.hourly[1].total)
    }

    /**
     * A file written before the forecast was ever asked for.
     *
     * It is still a perfectly good reading, and throwing it away would cost an
     * extra request against a limit of one an hour - on the upgrade, for every
     * install at once.
     */
    @Test
    fun `reads a file written before the hours ahead existed`() {
        File(temporaryFolder.root, "weather.json").writeText(
            """{"total":55,"low":20,"mid":5,"high":40,"latitude":55.95,
               "longitude":-3.19,"fetchedAt":1700000000000}""",
        )

        val read = cache().read()!!
        assertEquals(55, read.cover.total)
        assertEquals(emptyList<HourlyCover>(), read.hourly)
    }

    /**
     * A truncated write - the device losing power mid-poll - must read back as
     * "no reading" rather than throwing on a wake that has no user to see it.
     */
    @Test
    fun `returns nothing when the stored reading is unreadable`() {
        cache().write(reading)
        File(temporaryFolder.root, "weather.json").writeText("{ truncated")

        assertNull(cache().read())
    }
}
