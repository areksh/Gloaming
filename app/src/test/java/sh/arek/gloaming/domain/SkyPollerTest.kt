package sh.arek.gloaming.domain

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.aurora.OvalGrid
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CachedGrid
import sh.arek.gloaming.data.CachedManifest
import sh.arek.gloaming.data.Manifest
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.ManifestStore
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.SkyCache
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult

class SkyPollerTest {

    private val statusUrl = "https://sky.arek.sh/v1/status.json"
    private val ovalUrl = "https://sky.arek.sh/v1/oval.bin"
    private val manifestUrl = "https://sky.arek.sh/v1/index.json"
    private val nowMillis = 10L * 24 * 3_600_000

    private fun hex(v: String) = v.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val publicKey = hex("225e9e2dbb74f7daedc7ff9d9f5bda8714068a2ad7ecf27a312838fc6bc6d93d")
    private val signature = hex(
        "ca47a38885037b3646291bfd9f6e6d14e89c1381ed966e07f42a6ec581aa6388" +
            "1343eed0721ef7c67e0987ff32ca90f0f7a6da3de03daf0554739e73d8cf6f0f",
    )
    private val manifestBody = (
        """{"schema":1,"min_supported_version":1,"message":null,""" +
            """"endpoints":{"status":"https://sky.arek.sh/v1/status.json",""" +
            """"oval":"https://sky.arek.sh/v1/oval.bin",""" +
            """"weather":"https://api.open-meteo.com/v1/forecast"}}"""
        ).toByteArray()

    private fun grid(probability: Int, lat: Int = 56, lon: Int = 357): ByteArray {
        val cells = ByteArray(OvalGrid.BYTES)
        cells[(lat + 90) * OvalGrid.LONGITUDES + lon] = probability.toByte()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(cells) }
        return out.toByteArray()
    }

    private val statusBody =
        """{"schema":1,"generated":"g","global_activity":"high","max_probability":80,"oval_etag":"e1"}"""

    private class Fetcher(val bodies: Map<String, ByteArray>, val failing: Set<String> = emptySet()) :
        HttpFetcher {
        override suspend fun get(url: String, etag: String?): HttpResult {
            if (url in failing) throw java.io.IOException("offline")
            return HttpResult.Ok(bodies.getValue(url), "etag")
        }
    }

    private class Settings(var value: UserSettings) : SettingsStore {
        override fun read() = value
        override fun write(settings: UserSettings) { value = settings }
    }

    private class State(var value: AlertState = AlertState()) : AlertStateStore {
        override fun read() = value
        override fun write(state: AlertState) { value = state }
    }

    private class Notifier : AuroraNotifier {
        val notified = mutableListOf<Int>()
        override fun notifyAurora(probability: Int, status: SkyStatus) {
            notified += probability
        }
    }

    private class Manifests(var stored: CachedManifest?) : ManifestStore {
        override fun read() = stored
        override fun write(cached: CachedManifest) { stored = cached }
    }

    private class Cache(var status: Pair<String, String?>? = null, var g: CachedGrid? = null) :
        SkyCache {
        override fun readStatus() = status
        override fun writeStatus(body: String, etag: String?) { status = body to etag }
        override fun readGrid() = g
        override fun writeGrid(cached: CachedGrid) { g = cached }
    }

    private fun poller(
        probability: Int,
        settings: Settings,
        state: State,
        notifier: Notifier,
        failing: Set<String> = emptySet(),
    ): SkyPoller {
        val http = Fetcher(
            mapOf(
                manifestUrl to manifestBody,
                "$manifestUrl.sig" to signature,
                statusUrl to statusBody.toByteArray(),
                ovalUrl to grid(probability),
            ),
            failing,
        )
        return SkyPoller(
            manifests = ManifestRepository(http, Manifests(null), publicKey, manifestUrl),
            sky = SkyRepository(http, Cache(), now = { nowMillis }),
            settings = settings,
            state = state,
            notifier = notifier,
            now = { nowMillis },
        )
    }

    private fun settingsAt(threshold: Int = 30) = Settings(
        // Edinburgh: latitude 56 rounds from 55.95, longitude 357 from -3.19.
        UserSettings(latitude = 55.95, longitude = -3.19, threshold = threshold),
    )

    @Test
    fun `notifies when the local probability crosses the threshold`() = runTest {
        val notifier = Notifier()
        val state = State()

        val outcome = poller(70, settingsAt(), state, notifier).poll()

        assertEquals(PollOutcome.Notified(70), outcome)
        assertEquals(listOf(70), notifier.notified)
    }

    @Test
    fun `stays quiet below the threshold and notifies nobody`() = runTest {
        val notifier = Notifier()

        val outcome = poller(5, settingsAt(), State(), notifier).poll()

        assertEquals(PollOutcome.Quiet(5, AlertDecision.BelowThreshold), outcome)
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `disarms after alerting so the next poll stays quiet`() = runTest {
        val state = State()
        val notifier = Notifier()
        val settings = settingsAt()

        poller(70, settings, state, notifier).poll()
        assertFalse(state.value.armed)

        val second = poller(70, settings, state, notifier).poll()

        assertEquals(PollOutcome.Quiet(70, AlertDecision.NotReArmed), second)
        assertEquals(1, notifier.notified.size)
    }

    @Test
    fun `re-arms once activity falls away`() = runTest {
        val state = State(AlertState(armed = false))
        val settings = settingsAt()

        poller(5, settings, state, Notifier()).poll()

        assertTrue(state.value.armed)
    }

    @Test
    fun `records the probability and poll time for the UI`() = runTest {
        val state = State()

        poller(42, settingsAt(), state, Notifier()).poll()

        assertEquals(42, state.value.lastProbability)
        assertEquals(nowMillis, state.value.lastPollAt)
        assertNull(state.value.lastError)
    }

    // A background wake that crashes is worse than one that records a failure
    // and tries again later.
    @Test
    fun `records a failure rather than throwing when the network is down`() = runTest {
        val state = State()

        val outcome = poller(70, settingsAt(), state, Notifier(), failing = setOf(manifestUrl)).poll()

        assertTrue(outcome is PollOutcome.Failed)
        assertEquals(nowMillis, state.value.lastPollAt)
        assertTrue(state.value.lastError!!.isNotEmpty())
    }

    @Test
    fun `does nothing without a location`() = runTest {
        val notifier = Notifier()

        val outcome = poller(70, Settings(UserSettings()), State(), notifier).poll()

        assertEquals(PollOutcome.NoLocation, outcome)
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `does nothing when the user has turned alerts off`() = runTest {
        val notifier = Notifier()
        val settings = Settings(
            UserSettings(latitude = 55.95, longitude = -3.19, alertsEnabled = false),
        )

        val outcome = poller(90, settings, State(), notifier).poll()

        assertEquals(PollOutcome.AlertsDisabled, outcome)
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `does not alert twice inside the minimum interval`() = runTest {
        val notifier = Notifier()
        val state = State(
            AlertState(
                lastAlertAt = nowMillis - 3_600_000,
                lastAlertProbability = 70,
                armed = true,
            ),
        )

        val outcome = poller(72, settingsAt(), state, notifier).poll()

        assertEquals(PollOutcome.Quiet(72, AlertDecision.RateLimited), outcome)
        assertTrue(notifier.notified.isEmpty())
    }
}
