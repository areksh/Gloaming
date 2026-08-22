package sh.arek.gloaming.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.FileManifestStore
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SatelliteCache
import sh.arek.gloaming.data.SatelliteRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult
import sh.arek.gloaming.satellites.PassDescription
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatelliteElements
import sh.arek.gloaming.satellites.SatellitePass

/**
 * The wake-by-wake behaviour: when the planner is asked, and what it does with
 * the answer.
 *
 * The decisions themselves belong to [PassPlannerTest]; what is only visible
 * here is the wiring - that a wake which is not a pass check costs nothing,
 * that a pass is announced once, and that what was announced is remembered
 * across wakes.
 */
class PassPollerTest {

    /** Wellington: this element set gives it a pre-dawn pass that catches the sun. */
    private val latitude = -41.29
    private val longitude = 174.78

    private val iss = SatelliteElements(
        name = "ISS (ZARYA)",
        catalogue = 25544,
        epoch = 21320.51955234,
        epochMillis = 1637065689322L,
        meanMotion = 15.48582035,
        eccentricity = 0.0004694,
        inclination = 51.6447,
        raan = 309.4881,
        argPerigee = 203.6966,
        meanAnomaly = 299.8876,
        bstar = 0.000031985,
        meanMotionDot = 0.00001288,
    )

    private val document = """
        {"schema":1,"updated":"2021-11-24T00:00:00Z","satellites":[
          {"name":"ISS (ZARYA)","catalogue":25544,"epoch":21320.51955234,
           "epoch_millis":1637065689322,"mean_motion":15.48582035,"eccentricity":0.0004694,
           "inclination":51.6447,"raan":309.4881,"arg_perigee":203.6966,"mean_anomaly":299.8876,
           "bstar":0.000031985,"mean_motion_dot":0.00001288,"magnitude":-1.8}]}
    """.trimIndent()

    /** The first pass in the window that is worth announcing at all. */
    private fun firstVisiblePass(from: Long): SatellitePass = PassPlanner.visible(
        PassFinder.passes(iss, latitude, longitude, from, from + PassPlanner.HORIZON_MILLIS),
    ) { true }.first()

    private class Settings(var value: UserSettings) : SettingsStore {
        override fun read() = value
        override fun write(settings: UserSettings) { value = settings }
    }

    private class State(var value: AlertState = AlertState()) : AlertStateStore {
        override fun read() = value
        override fun write(state: AlertState) { value = state }
    }

    private class Cache(body: String?, private val age: Long) : SatelliteCache {
        var stored = body
        var writes = 0
        override fun read() = stored?.let { it to null }
        override fun write(body: String, etag: String?) { stored = body; writes++ }
        override fun writtenAt() = if (stored == null) null else System.currentTimeMillis() - age
    }

    private class Notifier : PassNotifier {
        val announced = mutableListOf<SatellitePass>()
        val channels = mutableListOf<PassChannel>()
        override fun notifyPass(
            pass: SatellitePass,
            description: PassDescription,
            channel: PassChannel,
        ) {
            announced += pass
            channels += channel
        }
    }

    private object Offline : HttpFetcher {
        override suspend fun get(url: String, etag: String?): HttpResult =
            throw java.io.IOException("no network in this test")
    }

    private object NoWeather : WeatherSource {
        override suspend fun cloudCover(base: String, lat: Double, lon: Double): CloudCover? = null
        override fun lastKnown(latitude: Double, longitude: Double): CloudCover? = null
    }

    private fun poller(
        settings: Settings,
        state: State,
        notifier: Notifier,
        cache: Cache,
        now: Long,
    ) = PassPoller(
        // Never reached: the cache is fresh and the cloud gate is off, so
        // nothing in these tests goes near the network.
        manifests = ManifestRepository(
            http = Offline,
            store = FileManifestStore(java.io.File("build/tmp/passpoller-test")),
            publicKey = ByteArray(32),
            manifestUrl = "https://sky.arek.sh/v1/index.json",
        ),
        satellites = SatelliteRepository(Offline, cache) { now },
        weather = NoWeather,
        settings = settings,
        state = state,
        notifier = notifier,
        now = { now },
        darkness = DarknessAt { _, _, _ -> true },
        localMinuteOfDay = { 2 * 60 },
    )

    private fun settings() = Settings(
        UserSettings(
            latitude = latitude,
            longitude = longitude,
            cloudGateEnabled = false,
            passAlertsEnabled = true,
        ),
    )

    @Test
    fun `announces the next pass once it is close`() = runTest {
        val from = 1637712000000L // 2021-11-24T00:00:00Z
        val pass = firstVisiblePass(from)
        val now = pass.peaksAt - 20 * 60_000L
        val notifier = Notifier()
        val state = State()

        val result = poller(settings(), state, notifier, Cache(document, 600_000L), now).poll()

        assertTrue(result.decision is PassPlanner.Decision.Announce)
        assertEquals(1, notifier.announced.size)
        assertEquals(PassChannel.Iss, notifier.channels.single())
        // Not the identical instant: the poller searches from its own wake
        // time, so the peak it lands on differs by a few milliseconds.
        assertTrue(
            kotlin.math.abs(state.value.lastPassPeakAt!! - pass.peaksAt) < 1000L,
        )
        assertEquals(25544, state.value.lastPassCatalogue)
    }

    /** A pass is one event. The second wake inside its window says nothing. */
    @Test
    fun `does not announce the same pass twice`() = runTest {
        val from = 1637712000000L
        val pass = firstVisiblePass(from)
        val now = pass.peaksAt - 20 * 60_000L
        val notifier = Notifier()
        val state = State()
        val cache = Cache(document, 600_000L)

        poller(settings(), state, notifier, cache, now).poll()
        // The planner asks to be left alone until the pass is over; a wake that
        // arrives anyway must not repeat itself.
        state.value = state.value.copy(nextPassCheckAt = null)
        val second = poller(settings(), state, notifier, cache, now + 60_000L).poll()

        assertTrue(second.decision is PassPlanner.Decision.AlreadyTold)
        assertEquals(1, notifier.announced.size)
    }

    /**
     * The point of keeping its own next moment: an aurora poll every five
     * minutes must not drag a night of orbital arithmetic along with it.
     */
    @Test
    fun `does nothing at all before the moment it asked for`() = runTest {
        val from = 1637712000000L
        val now = from + 60_000L
        val notifier = Notifier()
        val state = State(AlertState(nextPassCheckAt = now + 3_600_000L))

        val result = poller(settings(), state, notifier, Cache(document, 600_000L), now).poll()

        assertEquals(PassPlanner.Decision.Resting, result.decision)
        assertEquals(now + 3_600_000L, result.nextWakeAtMillis)
        assertTrue(notifier.announced.isEmpty())
    }

    @Test
    fun `says nothing when the user has turned pass alerts off`() = runTest {
        val from = 1637712000000L
        val pass = firstVisiblePass(from)
        val now = pass.peaksAt - 20 * 60_000L
        val settings = settings().also { it.value = it.value.copy(passAlertsEnabled = false) }
        val notifier = Notifier()

        val result = poller(settings, State(), notifier, Cache(document, 600_000L), now).poll()

        assertEquals(PassPlanner.Decision.Nothing, result.decision)
        assertTrue(notifier.announced.isEmpty())
    }

    /** No elements, no network, no crash: it comes back later. */
    @Test
    fun `survives having nothing to predict from`() = runTest {
        val now = 1637712000000L
        val notifier = Notifier()

        val result = poller(settings(), State(), notifier, Cache(null, 0L), now).poll()

        assertEquals(PassPlanner.Decision.Nothing, result.decision)
        assertTrue(result.nextWakeAtMillis > now)
    }
}
