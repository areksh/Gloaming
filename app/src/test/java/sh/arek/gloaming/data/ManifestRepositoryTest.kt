package sh.arek.gloaming.data

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult

class ManifestRepositoryTest {

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The live manifest and signature the deployed Worker produces. */
    private val publicKey = hex("225e9e2dbb74f7daedc7ff9d9f5bda8714068a2ad7ecf27a312838fc6bc6d93d")
    private val signature = hex(
        "ca47a38885037b3646291bfd9f6e6d14e89c1381ed966e07f42a6ec581aa6388" +
            "1343eed0721ef7c67e0987ff32ca90f0f7a6da3de03daf0554739e73d8cf6f0f",
    )
    private val body = (
        """{"schema":1,"min_supported_version":1,"message":null,""" +
            """"endpoints":{"status":"https://sky.arek.sh/v1/status.json",""" +
            """"oval":"https://sky.arek.sh/v1/oval.bin",""" +
            """"weather":"https://api.open-meteo.com/v1/forecast"}}"""
        ).toByteArray(Charsets.UTF_8)

    private val manifestUrl = "https://sky.arek.sh/v1/index.json"

    /** Serves canned responses, and records what was asked for. */
    private class FakeFetcher(
        private val responses: Map<String, Result<ByteArray>>,
    ) : HttpFetcher {
        val requested = mutableListOf<String>()

        override suspend fun get(url: String, etag: String?): HttpResult {
            requested += url
            val response = responses[url] ?: throw IOException("no route for $url")
            return HttpResult.Ok(response.getOrThrow(), null)
        }
    }

    /** Keeps the cached manifest in memory rather than on disk. */
    private class FakeStore(var stored: CachedManifest? = null) : ManifestStore {
        override fun read(): CachedManifest? = stored

        override fun write(cached: CachedManifest) {
            stored = cached
        }
    }

    private fun fetcher(vararg overrides: Pair<String, Result<ByteArray>>) = FakeFetcher(
        mapOf(
            manifestUrl to Result.success(body),
            "$manifestUrl.sig" to Result.success(signature),
            *overrides,
        ),
    )

    private fun repository(http: HttpFetcher, store: ManifestStore) =
        ManifestRepository(http, store, publicKey, manifestUrl)

    @Test
    fun `accepts and parses a correctly signed manifest`() = runTest {
        val store = FakeStore()

        val manifest = repository(fetcher(), store).refresh()

        assertEquals(1, manifest.schema)
        assertEquals(1, manifest.minSupportedVersion)
        assertNull(manifest.message)
        assertEquals("https://sky.arek.sh/v1/status.json", manifest.endpoints["status"])
        assertEquals("https://api.open-meteo.com/v1/forecast", manifest.endpoints["weather"])
    }

    @Test
    fun `caches a verified manifest as the new known-good copy`() = runTest {
        val store = FakeStore()

        repository(fetcher(), store).refresh()

        assertTrue(store.stored != null)
        assertEquals(String(body), String(store.stored!!.body))
    }

    // A manifest that fails verification is hostile, not merely stale: `message`
    // renders into a notification and `endpoints` decides where the app talks.
    // Falling back to the last known-good copy is the only safe response.
    @Test
    fun `falls back to the cached manifest when the signature does not verify`() = runTest {
        val tampered = String(body).replace(""""message":null""", """"message":"pwned"""")
            .toByteArray(Charsets.UTF_8)
        val store = FakeStore(CachedManifest(body, signature))

        val manifest = repository(
            fetcher(manifestUrl to Result.success(tampered)),
            store,
        ).refresh()

        assertNull(manifest.message)
        // The bad copy must not become the new known-good.
        assertEquals(String(body), String(store.stored!!.body))
    }

    @Test
    fun `falls back when the signature is for a different document`() = runTest {
        val otherSignature = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val store = FakeStore(CachedManifest(body, signature))

        val manifest = repository(
            fetcher("$manifestUrl.sig" to Result.success(otherSignature)),
            store,
        ).refresh()

        assertEquals(1, manifest.schema)
        assertEquals(String(body), String(store.stored!!.body))
    }

    @Test
    fun `falls back to the cached manifest when the network fails`() = runTest {
        val store = FakeStore(CachedManifest(body, signature))

        val manifest = repository(
            fetcher(manifestUrl to Result.failure(IOException("offline"))),
            store,
        ).refresh()

        assertEquals("https://sky.arek.sh/v1/status.json", manifest.endpoints["status"])
    }

    @Test
    fun `falls back when the manifest is not valid JSON`() = runTest {
        val store = FakeStore(CachedManifest(body, signature))

        val manifest = repository(
            fetcher(manifestUrl to Result.success("not json".toByteArray())),
            store,
        ).refresh()

        assertEquals(1, manifest.schema)
    }

    // With nothing cached there is no safe answer, and guessing an endpoint
    // would defeat the point of signing the manifest at all.
    @Test
    fun `fails when verification fails and nothing is cached`() {
        val store = FakeStore(stored = null)

        assertThrows(ManifestUnavailableException::class.java) {
            runTest {
                repository(
                    fetcher(manifestUrl to Result.success("not json".toByteArray())),
                    store,
                ).refresh()
            }
        }
    }

    @Test
    fun `re-verifies the cached manifest rather than trusting the file`() {
        // A cached pair that does not verify - a corrupted or tampered file.
        val store = FakeStore(CachedManifest("{}".toByteArray(), signature))

        assertThrows(ManifestUnavailableException::class.java) {
            runTest {
                repository(
                    fetcher(manifestUrl to Result.failure(IOException("offline"))),
                    store,
                ).refresh()
            }
        }
    }

    @Test
    fun `reads the signature from the manifest url with a sig suffix`() = runTest {
        val http = fetcher()

        repository(http, FakeStore()).refresh()

        assertTrue(http.requested.contains(manifestUrl))
        assertTrue(http.requested.contains("$manifestUrl.sig"))
    }
}
