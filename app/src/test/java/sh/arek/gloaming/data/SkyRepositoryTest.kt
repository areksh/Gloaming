package sh.arek.gloaming.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import sh.arek.gloaming.aurora.OvalGrid
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult

class SkyRepositoryTest {

    private val statusUrl = "https://sky.arek.sh/v1/status.json"
    private val ovalUrl = "https://sky.arek.sh/v1/oval.bin"

    private val manifest = Manifest(
        schema = 1,
        minSupportedVersion = 1,
        endpoints = mapOf("status" to statusUrl, "oval" to ovalUrl),
    )

    private fun statusJson(ovalEtag: String?, activity: String = "low") = """
        {"schema":1,"generated":"2026-08-05T22:24:42.437Z","global_activity":"$activity",
         "max_probability":15,"oval_etag":${ovalEtag?.let { "\"$it\"" } ?: "null"},
         "kp":{"value":0.0,"time":"t"},"solar_wind":{"bz":-2.0,"bt":3.0,"speed":351.0,"time":"t"},
         "aurorawatch":{"status":"green","updated":"u","site_id":"s"}}
    """.trimIndent()

    private fun gridBytes(peak: Int, lon: Int = 10, lat: Int = 55): ByteArray {
        val cells = ByteArray(OvalGrid.BYTES)
        cells[(lat + 90) * OvalGrid.LONGITUDES + lon] = peak.toByte()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(cells) }
        return out.toByteArray()
    }

    /** Serves canned responses and records requests, including sent ETags. */
    private class FakeFetcher(val routes: MutableMap<String, Result<HttpResult>> = mutableMapOf()) :
        HttpFetcher {
        val requested = mutableListOf<Pair<String, String?>>()

        override suspend fun get(url: String, etag: String?): HttpResult {
            requested += url to etag
            return (routes[url] ?: throw IOException("no route for $url")).getOrThrow()
        }

        fun ok(url: String, body: ByteArray, etag: String?) {
            routes[url] = Result.success(HttpResult.Ok(body, etag))
        }

        fun notModified(url: String) {
            routes[url] = Result.success(HttpResult.NotModified)
        }

        fun fails(url: String) {
            routes[url] = Result.failure(IOException("offline"))
        }
    }

    private class FakeCache(
        var status: Pair<String, String?>? = null,
        var grid: CachedGrid? = null,
    ) : SkyCache {
        override fun readStatus() = status
        override fun writeStatus(body: String, etag: String?) {
            status = body to etag
        }

        override fun readGrid() = grid
        override fun writeGrid(cached: CachedGrid) {
            grid = cached
        }
    }

    private fun repository(http: HttpFetcher, cache: SkyCache) =
        SkyRepository(http, cache, now = { 1_000L })

    @Test
    fun `fetches status and grid on a cold start`() = runTest {
        val http = FakeFetcher().apply {
            ok(statusUrl, statusJson("etag-1").toByteArray(), "status-1")
            ok(ovalUrl, gridBytes(73), "etag-1")
        }
        val cache = FakeCache()

        val snapshot = repository(http, cache).refresh(manifest)

        assertEquals("low", snapshot.status.globalActivity)
        assertEquals(-2.0, snapshot.status.solarWind!!.bz, 0.0)
        assertEquals(73, snapshot.grid!!.probabilityAt(55.0, 10.0))
    }

    // oval.bin is several times the size of status.json for data that is
    // usually all zeros, so it must not be re-fetched when it has not changed.
    @Test
    fun `does not re-fetch the grid when its etag is unchanged`() = runTest {
        val http = FakeFetcher().apply { ok(statusUrl, statusJson("etag-1").toByteArray(), "s2") }
        val cache = FakeCache(grid = CachedGrid("etag-1", gridBytes(64)))

        val snapshot = repository(http, cache).refresh(manifest)

        assertEquals(64, snapshot.grid!!.probabilityAt(55.0, 10.0))
        assertEquals(listOf(statusUrl to null), http.requested)
    }

    @Test
    fun `re-fetches the grid when the status reports a new etag`() = runTest {
        val http = FakeFetcher().apply {
            ok(statusUrl, statusJson("etag-2").toByteArray(), "s2")
            ok(ovalUrl, gridBytes(90), "etag-2")
        }
        val cache = FakeCache(grid = CachedGrid("etag-1", gridBytes(10)))

        val snapshot = repository(http, cache).refresh(manifest)

        assertEquals(90, snapshot.grid!!.probabilityAt(55.0, 10.0))
        assertEquals("etag-2", cache.grid!!.etag)
    }

    // The grid is already current here, so a quiet poll should put nothing at
    // all on the wire: one conditional request answered 304.
    @Test
    fun `sends the cached etag so an unchanged status transfers nothing`() = runTest {
        val http = FakeFetcher().apply { notModified(statusUrl) }
        val cache = FakeCache(
            status = statusJson("etag-1") to "status-1",
            grid = CachedGrid("etag-1", gridBytes(64)),
        )

        val snapshot = repository(http, cache).refresh(manifest)

        assertEquals(listOf(statusUrl to "status-1"), http.requested)
        assertEquals("low", snapshot.status.globalActivity)
        assertEquals(64, snapshot.grid!!.probabilityAt(55.0, 10.0))
    }

    @Test
    fun `falls back to the cached status when the network fails`() = runTest {
        val http = FakeFetcher().apply { fails(statusUrl) }
        val cache = FakeCache(status = statusJson("etag-1") to "status-1")

        val snapshot = repository(http, cache).refresh(manifest)

        assertEquals(15, snapshot.status.maxProbability)
    }

    // A bad body must not evict a good cached one; the app would rather show
    // an hour-old answer with its age than nothing at all.
    @Test
    fun `keeps the cached status when the response will not parse`() = runTest {
        val http = FakeFetcher().apply { ok(statusUrl, "not json".toByteArray(), "s9") }
        val cache = FakeCache(status = statusJson("etag-1") to "status-1")

        val snapshot = repository(http, cache).refresh(manifest)

        assertEquals(15, snapshot.status.maxProbability)
        assertEquals("status-1", cache.status!!.second)
    }

    @Test
    fun `keeps the cached grid when the fetched one will not inflate`() = runTest {
        val http = FakeFetcher().apply {
            ok(statusUrl, statusJson("etag-2").toByteArray(), "s2")
            ok(ovalUrl, "not gzip".toByteArray(), "etag-2")
        }
        val cache = FakeCache(grid = CachedGrid("etag-1", gridBytes(64)))

        val snapshot = repository(http, cache).refresh(manifest)

        assertEquals(64, snapshot.grid!!.probabilityAt(55.0, 10.0))
        assertEquals("etag-1", cache.grid!!.etag)
    }

    @Test
    fun `still reports the status when the grid cannot be fetched at all`() = runTest {
        val http = FakeFetcher().apply {
            ok(statusUrl, statusJson("etag-1").toByteArray(), "s1")
            fails(ovalUrl)
        }

        val snapshot = repository(http, FakeCache()).refresh(manifest)

        assertNotNull(snapshot.status)
        assertNull(snapshot.grid)
    }

    @Test
    fun `fails when there is no status anywhere`() {
        assertThrows(SkyUnavailableException::class.java) {
            runTest {
                val http = FakeFetcher().apply { fails(statusUrl) }
                repository(http, FakeCache()).refresh(manifest)
            }
        }
    }

    @Test
    fun `fails when the manifest advertises no status endpoint`() {
        assertThrows(SkyUnavailableException::class.java) {
            runTest {
                repository(FakeFetcher(), FakeCache())
                    .refresh(Manifest(schema = 1, minSupportedVersion = 1, endpoints = emptyMap()))
            }
        }
    }

    /** The document the deployed Worker actually publishes. */
    @Test
    fun `parses the status document the worker publishes`() = runTest {
        val real = checkNotNull(javaClass.classLoader?.getResourceAsStream("status-sample.json"))
            .use { it.readBytes() }
        val http = FakeFetcher().apply {
            ok(statusUrl, real, "s1")
            fails(ovalUrl)
        }

        val status = repository(http, FakeCache()).refresh(manifest).status

        assertEquals(1, status.schema)
        assertEquals("low", status.globalActivity)
        assertEquals(15, status.maxProbability)
        assertEquals("green", status.aurorawatch!!.status)
        assertEquals(-2.0, status.solarWind!!.bz, 0.0)
        assertEquals(351.0, status.solarWind!!.speed!!, 0.0)
    }
}
