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

/**
 * Fetching `comets.json`.
 *
 * The same contract as the satellite elements - conditional, parse before
 * caching, fall back rather than throw - so the tests here are about what
 * differs. A comet's elements stay usable for months where a satellite's are
 * stale in hours, so a failed fetch costs almost nothing; what does go off is
 * the measured magnitude underneath, and the document carries the time of each
 * observation so the app can tell.
 */
class CometRepositoryTest {

    private val url = "https://sky.arek.sh/v1/comets.json"

    private val manifest = Manifest(
        schema = 1,
        minSupportedVersion = 1,
        endpoints = mapOf("comets" to url),
    )

    private fun body(magnitude: Double? = 8.3, observedAt: Long = 1_787_584_031_000L) = """
        {"schema":1,"updated":1787675570,"comets":[
          {"key":"10P","name":"10P/Tempel","q":1.417738,"e":0.537447,"i":12.0272,
           "node":117.7973,"peri":195.469,"perihelion_jd_tt":2461254.6161,
           "magnitude_h":5.0,"slope":10.0,
           "observed":${
        if (magnitude == null) {
            "null"
        } else {
            """{"magnitude":$magnitude,"atMillis":$observedAt,"reports":59,"observers":22}"""
        }
    }}]}
    """.trimIndent()

    private class FakeFetcher : HttpFetcher {
        val routes = mutableMapOf<String, Result<HttpResult>>()
        val requested = mutableListOf<Pair<String, String?>>()

        override suspend fun get(url: String, etag: String?): HttpResult {
            requested += url to etag
            return (routes[url] ?: throw IOException("no route for $url")).getOrThrow()
        }
    }

    private class MemoryCache : CometCache {
        var stored: Pair<String, String?>? = null
        var writtenAt: Long? = null
        var writes = 0

        override fun read() = stored
        override fun write(body: String, etag: String?) {
            stored = body to etag
            writes += 1
            writtenAt = 1_000L
        }
        override fun writtenAt() = writtenAt
    }

    @Test
    fun `reads the comets the fetcher published`() = runTest {
        val http = FakeFetcher()
        http.routes[url] = Result.success(HttpResult.Ok(body().toByteArray(), "\"v1\""))

        val document = CometRepository(http, MemoryCache()).refresh(manifest)

        assertEquals(1, document?.comets?.size)
        assertEquals("10P/Tempel", document?.comets?.first()?.name)
        assertEquals(8.3, document?.comets?.first()?.observed?.magnitude!!, 1e-9)
        assertEquals(22, document.comets.first().observed?.observers)
    }

    @Test
    fun `sends the stored tag back so an unchanged document is not transferred`() = runTest {
        val http = FakeFetcher()
        val cache = MemoryCache().apply { stored = body() to "\"v1\"" }
        http.routes[url] = Result.success(HttpResult.NotModified)

        val document = CometRepository(http, cache).refresh(manifest)

        assertEquals(listOf(url to "\"v1\""), http.requested)
        assertNotNull(document)
        assertEquals(0, cache.writes)
    }

    /**
     * A comet's elements are good for months, so a failed fetch is a much
     * smaller matter here than for a satellite. Falling back is the right
     * answer rather than a grudging one.
     */
    @Test
    fun `falls back to what it already has when the fetch fails`() = runTest {
        val http = FakeFetcher()
        val cache = MemoryCache().apply { stored = body() to null }
        http.routes[url] = Result.failure(IOException("no network"))

        assertEquals(1, CometRepository(http, cache).refresh(manifest)?.comets?.size)
    }

    /**
     * Parsed before it is cached, so a truncated or reworded body never
     * replaces a good one. This is the failure mode that would otherwise
     * survive a restart and take every comet off the device until the next
     * good fetch.
     */
    @Test
    fun `never lets an unreadable body replace a good one`() = runTest {
        val http = FakeFetcher()
        val cache = MemoryCache().apply { stored = body() to null }
        http.routes[url] = Result.success(HttpResult.Ok("{not json".toByteArray(), "\"v2\""))

        val document = CometRepository(http, cache).refresh(manifest)

        assertEquals(1, document?.comets?.size)
        assertEquals(0, cache.writes)
    }

    /**
     * An install that has not re-fetched the manifest since Phase 5 has no
     * `comets` key at all. It should keep whatever it holds rather than lose
     * its comets over a key that will appear within the day.
     */
    @Test
    fun `keeps its comets when the manifest does not mention them yet`() = runTest {
        val http = FakeFetcher()
        val cache = MemoryCache().apply { stored = body() to null }

        val document = CometRepository(http, cache).refresh(
            manifest.copy(endpoints = mapOf("sats" to "https://sky.arek.sh/v1/sats.json")),
        )

        assertEquals(1, document?.comets?.size)
        assertTrue(http.requested.isEmpty())
    }

    @Test
    fun `has nothing to offer before anything has ever been fetched`() = runTest {
        val http = FakeFetcher()
        http.routes[url] = Result.failure(IOException("no network"))

        assertNull(CometRepository(http, MemoryCache()).refresh(manifest))
    }

    /**
     * A field this version has never heard of must not break an install that
     * never updates, which on F-Droid and Obtainium is most of them.
     */
    @Test
    fun `ignores fields a later version added`() = runTest {
        val http = FakeFetcher()
        val extended = body().replace("\"slope\":10.0", "\"slope\":10.0,\"tail_length\":3.1")
        http.routes[url] = Result.success(HttpResult.Ok(extended.toByteArray(), null))

        assertEquals(1, CometRepository(http, MemoryCache()).refresh(manifest)?.comets?.size)
    }

    /** A comet nobody has measured still arrives, with the prediction to fall back on. */
    @Test
    fun `reads a comet with no measurement at all`() = runTest {
        val http = FakeFetcher()
        http.routes[url] = Result.success(HttpResult.Ok(body(magnitude = null).toByteArray(), null))

        val comet = CometRepository(http, MemoryCache()).refresh(manifest)?.comets?.first()

        assertNull(comet?.observed)
        assertEquals(5.0, comet?.magnitudeH!!, 1e-9)
    }

    // --- what the document knows about its own age ---

    @Test
    fun `calls a measurement fresh only while it is inside the window`() = runTest {
        val http = FakeFetcher()
        val day = 24L * 60 * 60 * 1000
        http.routes[url] = Result.success(
            HttpResult.Ok(body(observedAt = 1_000 * day).toByteArray(), null),
        )
        val comet = CometRepository(http, MemoryCache()).refresh(manifest)!!.comets.first()

        assertTrue(comet.hasFreshObservation(1_003 * day, 7 * day))
        assertTrue(comet.hasFreshObservation(1_007 * day, 7 * day))
        assertTrue(!comet.hasFreshObservation(1_008 * day, 7 * day))
    }

    @Test
    fun `has no fresh measurement when it has none at all`() = runTest {
        val http = FakeFetcher()
        http.routes[url] = Result.success(HttpResult.Ok(body(magnitude = null).toByteArray(), null))
        val comet = CometRepository(http, MemoryCache()).refresh(manifest)!!.comets.first()

        assertTrue(!comet.hasFreshObservation(0, Long.MAX_VALUE))
    }

    /** The elements come out in the form the solver takes, unit for unit. */
    @Test
    fun `hands its elements to the solver unchanged`() = runTest {
        val http = FakeFetcher()
        http.routes[url] = Result.success(HttpResult.Ok(body().toByteArray(), null))
        val comet = CometRepository(http, MemoryCache()).refresh(manifest)!!.comets.first()

        val orbit = comet.orbit

        assertEquals(1.417738, orbit.perihelionAu, 0.0)
        assertEquals(0.537447, orbit.eccentricity, 0.0)
        assertEquals(12.0272, orbit.inclinationDegrees, 0.0)
        assertEquals(117.7973, orbit.nodeDegrees, 0.0)
        assertEquals(195.469, orbit.perihelionArgumentDegrees, 0.0)
        assertEquals(2461254.6161, orbit.perihelionJdTt, 0.0)
    }
}
