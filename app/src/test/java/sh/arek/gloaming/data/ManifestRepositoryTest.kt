package sh.arek.gloaming.data

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
    private class FakeStore(
        var stored: CachedManifest? = null,
        var storedAt: Long? = null,
    ) : ManifestStore {
        override fun read(): CachedManifest? = stored

        override fun write(cached: CachedManifest) {
            stored = cached
        }

        override fun writtenAt(): Long? = storedAt
    }

    private fun fetcher(vararg overrides: Pair<String, Result<ByteArray>>) = FakeFetcher(
        mapOf(
            manifestUrl to Result.success(body),
            "$manifestUrl.sig" to Result.success(signature),
            *overrides,
        ),
    )

    private val now = 1_756_200_000_000L

    private fun repository(http: HttpFetcher, store: ManifestStore) =
        ManifestRepository(http, store, publicKey, manifestUrl, now = { now })

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

    /**
     * On a fresh install there is nothing cached by definition, so "No cached
     * manifest" is the least informative true thing that can be said: what a
     * reader needs is why the fetch did not work. That reason reaches the
     * Tonight screen as "Last check failed: ...".
     */
    @Test
    fun `blames the failed fetch rather than the empty cache`() {
        val store = FakeStore(stored = null)

        val failure = assertThrows(ManifestUnavailableException::class.java) {
            runTest {
                repository(
                    fetcher(manifestUrl to Result.failure(IOException("nothing answered"))),
                    store,
                ).refresh()
            }
        }

        assertEquals("nothing answered", failure.cause?.message)
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

    // --- the daily cadence ---

    /**
     * The manifest's contract is one request a day, and every poller calls
     * refresh() on every wake - so the repository is where the cadence has to
     * live, or an elevated night of five-minute polls turns one request into
     * six hundred.
     */
    @Test
    fun `answers from a fresh cached copy without touching the network`() = runTest {
        val http = fetcher()
        val store = FakeStore(CachedManifest(body, signature), storedAt = now - 60 * 60 * 1000L)

        val manifest = repository(http, store).refresh()

        assertEquals(1, manifest.schema)
        assertEquals(emptyList<String>(), http.requested)
    }

    @Test
    fun `fetches again once the held copy is a day old`() = runTest {
        val http = fetcher()
        val store = FakeStore(CachedManifest(body, signature), storedAt = now - 25 * 60 * 60 * 1000L)

        repository(http, store).refresh()

        assertTrue(http.requested.contains(manifestUrl))
    }

    /** A clock that has jumped backwards must refetch, not hold the copy for ever. */
    @Test
    fun `treats a copy stamped in the future as stale`() = runTest {
        val http = fetcher()
        val store = FakeStore(CachedManifest(body, signature), storedAt = now + 60 * 60 * 1000L)

        repository(http, store).refresh()

        assertTrue(http.requested.contains(manifestUrl))
    }

    /** Freshness is a property of a copy that verifies; a corrupt one has none. */
    @Test
    fun `ignores freshness when the cached copy does not verify`() = runTest {
        val http = fetcher()
        val store = FakeStore(
            CachedManifest("{}".toByteArray(), signature),
            storedAt = now - 60 * 60 * 1000L,
        )

        val manifest = repository(http, store).refresh()

        assertTrue(http.requested.contains(manifestUrl))
        assertEquals(1, manifest.schema)
    }

    /**
     * A real keypair, generated for the test.
     *
     * The app signs nothing, so the production code has no way to produce the
     * pair of manifests these cases need. The JVM has had Ed25519 since 15,
     * which the unit tests run on; the app does not, which is why it carries its
     * own verifier.
     */
    private class Signer {
        private val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        /** The raw 32 bytes, which is what the verifier takes: the tail of the X.509 form. */
        val publicKey: ByteArray = pair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

        fun sign(body: ByteArray): ByteArray =
            java.security.Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(body)
                sign()
            }
    }

    private fun manifestBody(issuedAt: Long, status: String): ByteArray =
        ("""{"schema":1,"min_supported_version":1,"issued_at":$issuedAt,"message":null,""" +
            """"endpoints":{"status":"$status"}}""").toByteArray(Charsets.UTF_8)

    /**
     * A signature stays valid for ever, so signing alone does not stop an
     * attacker who can answer for the domain from replaying an older manifest -
     * moving the endpoints back to ones they have since taken over, or
     * restoring a `message` that was withdrawn. Refusing anything older than
     * what is already held is what closes that.
     */
    @Test
    fun `refuses a correctly signed manifest older than the one it holds`() = runTest {
        val signer = Signer()
        val current = manifestBody(2_000, "https://sky.arek.sh/v1/status.json")
        val replayed = manifestBody(1_000, "https://attacker.example/status.json")
        val store = FakeStore(CachedManifest(current, signer.sign(current)))
        val http = FakeFetcher(
            mapOf(
                manifestUrl to Result.success(replayed),
                "$manifestUrl.sig" to Result.success(signer.sign(replayed)),
            ),
        )

        val manifest = ManifestRepository(http, store, signer.publicKey, manifestUrl).refresh()

        assertEquals("https://sky.arek.sh/v1/status.json", manifest.endpoints["status"])
        assertArrayEquals(current, store.stored!!.body)
    }

    @Test
    fun `accepts a manifest newer than the one it holds`() = runTest {
        val signer = Signer()
        val current = manifestBody(1_000, "https://sky.arek.sh/v1/status.json")
        val newer = manifestBody(2_000, "https://sky.arek.sh/v2/status.json")
        val store = FakeStore(CachedManifest(current, signer.sign(current)))
        val http = FakeFetcher(
            mapOf(
                manifestUrl to Result.success(newer),
                "$manifestUrl.sig" to Result.success(signer.sign(newer)),
            ),
        )

        val manifest = ManifestRepository(http, store, signer.publicKey, manifestUrl).refresh()

        assertEquals("https://sky.arek.sh/v2/status.json", manifest.endpoints["status"])
    }

    /**
     * Republished unchanged, which the Worker does whenever its contents
     * genuinely change and never otherwise. Equal is not a rollback.
     */
    @Test
    fun `accepts a manifest issued at the same moment as the one it holds`() = runTest {
        val signer = Signer()
        val current = manifestBody(1_000, "https://sky.arek.sh/v1/status.json")
        val store = FakeStore(CachedManifest(current, signer.sign(current)))
        val http = FakeFetcher(
            mapOf(
                manifestUrl to Result.success(current),
                "$manifestUrl.sig" to Result.success(signer.sign(current)),
            ),
        )

        val manifest = ManifestRepository(http, store, signer.publicKey, manifestUrl).refresh()

        assertEquals("https://sky.arek.sh/v1/status.json", manifest.endpoints["status"])
    }

    /**
     * The manifest deployed before stamps existed carries no `issued_at` and
     * reads as zero. An install holding one must still accept the first stamped
     * manifest it sees, or the change would strand it on the old copy for good.
     */
    @Test
    fun `accepts the first stamped manifest over an unstamped one`() = runTest {
        val signer = Signer()
        val unstamped = (
            """{"schema":1,"min_supported_version":1,"message":null,""" +
                """"endpoints":{"status":"https://sky.arek.sh/v1/status.json"}}"""
            ).toByteArray(Charsets.UTF_8)
        val stamped = manifestBody(1_000, "https://sky.arek.sh/v2/status.json")
        val store = FakeStore(CachedManifest(unstamped, signer.sign(unstamped)))
        val http = FakeFetcher(
            mapOf(
                manifestUrl to Result.success(stamped),
                "$manifestUrl.sig" to Result.success(signer.sign(stamped)),
            ),
        )

        val manifest = ManifestRepository(http, store, signer.publicKey, manifestUrl).refresh()

        assertEquals("https://sky.arek.sh/v2/status.json", manifest.endpoints["status"])
    }
}
