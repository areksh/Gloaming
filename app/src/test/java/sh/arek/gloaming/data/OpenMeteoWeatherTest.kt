package sh.arek.gloaming.data

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult

class OpenMeteoWeatherTest {

    private val endpoint = "https://api.open-meteo.com/v1/forecast"

    private fun responseJson(total: Int, low: Int = 0, mid: Int = 0, high: Int = 0) = """
        {"latitude":55.94,"longitude":-3.17,"elevation":73.0,
         "current_units":{"cloud_cover":"%"},
         "current":{"time":"2026-08-06T18:15","interval":900,"cloud_cover":$total,
          "cloud_cover_low":$low,"cloud_cover_mid":$mid,"cloud_cover_high":$high}}
    """.trimIndent()

    /** Serves one canned response and records every URL asked for. */
    private class FakeFetcher(private var response: Result<ByteArray>) : HttpFetcher {
        val requested = mutableListOf<String>()

        override suspend fun get(url: String, etag: String?): HttpResult {
            requested += url
            return HttpResult.Ok(response.getOrThrow(), null)
        }

        fun answers(body: String) {
            response = Result.success(body.toByteArray())
        }

        companion object {
            fun serving(body: String) = FakeFetcher(Result.success(body.toByteArray()))
            fun failing() = FakeFetcher(Result.failure(IOException("offline")))
        }
    }

    private class FakeCache(var cached: CachedCloud? = null) : WeatherCache {
        override fun read() = cached
        override fun write(cached: CachedCloud) {
            this.cached = cached
        }
    }

    private fun weather(
        http: HttpFetcher,
        cache: WeatherCache = FakeCache(),
        now: Long = 1_000_000L,
    ) = OpenMeteoWeather(http, cache, now = { now })

    @Test
    fun `reports the cloud cover the forecast gives`() = runTest {
        val http = FakeFetcher.serving(responseJson(total = 55, low = 20, mid = 5, high = 40))

        val cover = weather(http).cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertNotNull(cover)
        assertEquals(55, cover!!.total)
        assertEquals(20, cover.low)
        assertEquals(5, cover.mid)
        assertEquals(40, cover.high)
    }

    @Test
    fun `asks the endpoint for the cloud cover fields and nothing else`() = runTest {
        val http = FakeFetcher.serving(responseJson(total = 10))

        weather(http).cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        val url = http.requested.single()
        assertTrue(url, url.startsWith("$endpoint?"))
        assertTrue(url, url.contains("current=cloud_cover%2Ccloud_cover_low%2Ccloud_cover_mid%2Ccloud_cover_high"))
    }

    /**
     * Open-Meteo is the one call that carries the observer's position off the
     * device, so it carries as little of it as the forecast can use. Two
     * decimal places is about 1.1 km - the resolution of the finest model
     * Open-Meteo runs - so nothing is lost, and an address does not go on the
     * wire.
     */
    @Test
    fun `rounds the position it sends to the resolution the forecast models have`() = runTest {
        val http = FakeFetcher.serving(responseJson(total = 10))

        weather(http).cloudCover(endpoint, latitude = 55.946372, longitude = -3.190851)

        val url = http.requested.single()
        assertTrue(url, url.contains("latitude=55.95"))
        assertTrue(url, url.contains("longitude=-3.19"))
    }

    // Open-Meteo's terms cap refreshes at one an hour, and cloud fields do not
    // move faster than that either.
    @Test
    fun `serves a reading under an hour old without touching the network`() = runTest {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 80, low = 80, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )
        val http = FakeFetcher.serving(responseJson(total = 5))

        val cover = weather(http, cache, now = 1_000L + 59 * 60_000L)
            .cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertEquals(80, cover!!.total)
        assertEquals(emptyList<String>(), http.requested)
    }

    @Test
    fun `re-fetches once the cached reading is over an hour old`() = runTest {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 80, low = 80, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )
        val http = FakeFetcher.serving(responseJson(total = 5))

        val cover = weather(http, cache, now = 1_000L + 61 * 60_000L)
            .cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertEquals(5, cover!!.total)
        assertEquals(1, http.requested.size)
    }

    // Cloud is local in a way the aurora is not: a reading from the last town
    // is not an answer, however fresh it is.
    @Test
    fun `re-fetches when the observer has moved, however fresh the reading`() = runTest {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 80, low = 80, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )
        val http = FakeFetcher.serving(responseJson(total = 5))

        val cover = weather(http, cache, now = 2_000L)
            .cloudCover(endpoint, latitude = 51.51, longitude = -0.13)

        assertEquals(5, cover!!.total)
        assertEquals(1, http.requested.size)
    }

    // Rounding is what makes the cache usable at all: a position from
    // LocationManager jitters in the sixth decimal place on every reading, and
    // an unrounded cache key would miss every single time.
    @Test
    fun `treats a position that differs below the rounding as the same place`() = runTest {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 80, low = 80, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )
        val http = FakeFetcher.serving(responseJson(total = 5))

        val cover = weather(http, cache, now = 2_000L)
            .cloudCover(endpoint, latitude = 55.943871, longitude = -3.172094)

        assertEquals(80, cover!!.total)
        assertEquals(emptyList<String>(), http.requested)
    }

    @Test
    fun `caches what it fetched, at the rounded position`() = runTest {
        val cache = FakeCache()
        val http = FakeFetcher.serving(responseJson(total = 42))

        weather(http, cache, now = 5_000L).cloudCover(endpoint, 55.946372, -3.190851)

        val cached = assertNotNull(cache.cached).let { cache.cached!! }
        assertEquals(42, cached.cover.total)
        assertEquals(55.95, cached.latitude, 0.0)
        assertEquals(-3.19, cached.longitude, 0.0)
        assertEquals(5_000L, cached.fetchedAt)
    }

    /**
     * A reading past its refresh window but not yet stale still beats nothing:
     * losing the gate to a momentary connection failure would let through
     * exactly the notification the gate exists to suppress.
     */
    @Test
    fun `falls back to a recent cached reading when the fetch fails`() = runTest {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 80, low = 80, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )

        val cover = weather(FakeFetcher.failing(), cache, now = 1_000L + 2 * 60 * 60_000L)
            .cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertEquals(80, cover!!.total)
    }

    /**
     * Past that, unknown. The gate notifies anyway on an unknown reading, which
     * is the right failure - a half-day-old cloud field would suppress a real
     * alert on data that means nothing.
     */
    @Test
    fun `reports nothing rather than a stale reading when the fetch fails`() = runTest {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 80, low = 80, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )

        val cover = weather(FakeFetcher.failing(), cache, now = 1_000L + 4 * 60 * 60_000L)
            .cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertNull(cover)
    }

    @Test
    fun `reports nothing when there is neither a network nor a cache`() = runTest {
        val cover = weather(FakeFetcher.failing())
            .cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertNull(cover)
    }

    // A bad body must not evict a good cached one.
    @Test
    fun `keeps the cached reading when the response will not parse`() = runTest {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 80, low = 80, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )
        val http = FakeFetcher.serving("not json")

        val cover = weather(http, cache, now = 1_000L + 2 * 60 * 60_000L)
            .cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertEquals(80, cover!!.total)
        assertEquals(1_000L, cache.cached!!.fetchedAt)
    }

    @Test
    fun `reports nothing when the response carries no cloud cover at all`() = runTest {
        val http = FakeFetcher.serving(
            """{"latitude":55.94,"longitude":-3.17,"current":{"time":"2026-08-06T18:15"}}""",
        )

        val cover = weather(http).cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertNull(cover)
    }

    /**
     * A missing layer read as zero would report a clear sky - the one wrong
     * answer that silently suppresses a real alert. Unknown notifies; clear
     * does not.
     */
    @Test
    fun `reports nothing when the response is missing a cloud layer`() = runTest {
        val http = FakeFetcher.serving(
            """{"current":{"cloud_cover":90,"cloud_cover_low":90,"cloud_cover_mid":0}}""",
        )

        val cover = weather(http).cloudCover(endpoint, latitude = 55.94, longitude = -3.17)

        assertNull(cover)
    }

    /**
     * The scheduler asks what the sky was doing, and must not pay for a radio
     * wake to find out - the whole point of adapting the poll rate is to spend
     * less, not to spend it differently.
     */
    @Test
    fun `reports the last reading without touching the network`() {
        val cache = FakeCache(
            CachedCloud(CloudCover(total = 95, low = 95, mid = 0, high = 0), 55.94, -3.17, 1_000L),
        )
        val http = FakeFetcher.failing()

        val cover = weather(http, cache, now = 1_000L + 2 * 60 * 60_000L)
            .lastKnown(latitude = 55.94, longitude = -3.17)

        assertEquals(95, cover!!.total)
        assertEquals(emptyList<String>(), http.requested)
    }

    @Test
    fun `reports no last reading once it is stale`() {
        val cache = FakeCache(
            CachedCloud(CloudCover(95, 95, 0, 0), 55.94, -3.17, 1_000L),
        )

        val cover = weather(FakeFetcher.failing(), cache, now = 1_000L + 4 * 60 * 60_000L)
            .lastKnown(latitude = 55.94, longitude = -3.17)

        assertNull(cover)
    }

    @Test
    fun `reports no last reading for somewhere else`() {
        val cache = FakeCache(
            CachedCloud(CloudCover(95, 95, 0, 0), 55.94, -3.17, 1_000L),
        )

        val cover = weather(FakeFetcher.failing(), cache, now = 2_000L)
            .lastKnown(latitude = 51.51, longitude = -0.13)

        assertNull(cover)
    }

    @Test
    fun `reports no last reading when nothing has been fetched`() {
        assertNull(weather(FakeFetcher.failing()).lastKnown(55.94, -3.17))
    }

    /** The document Open-Meteo actually serves. */
    @Test
    fun `parses the response open-meteo really returns`() = runTest {
        val real = checkNotNull(javaClass.classLoader?.getResourceAsStream("open-meteo-sample.json"))
            .use { it.readBytes() }
        val http = FakeFetcher.serving(String(real))

        val cover = weather(http).cloudCover(endpoint, latitude = 55.95, longitude = -3.19)

        assertEquals(100, cover!!.total)
        assertEquals(62, cover.low)
        assertEquals(0, cover.mid)
        assertEquals(100, cover.high)
    }
}
