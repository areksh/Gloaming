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
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult
import sh.arek.gloaming.satellites.Elements
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.PassDescription
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatelliteElements
import sh.arek.gloaming.satellites.TrainFinder
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

    /**
     * The same orbit as a launch still bunched together: two Starlinks a couple
     * of degrees of mean anomaly apart, which is half a minute of sky.
     */
    private val trainDocument = """
        {"schema":1,"updated":"2021-11-24T00:00:00Z",
         "trains":[{"launch":"2026-123","members":22,"lead":101000,"tail":101007}],
         "satellites":[
          {"name":"STARLINK-101000","catalogue":101000,"epoch":21320.51955234,
           "epoch_millis":1637065689322,"mean_motion":15.48582035,"eccentricity":0.0004694,
           "inclination":51.6447,"raan":309.4881,"arg_perigee":203.6966,"mean_anomaly":299.8876,
           "bstar":0.000031985,"mean_motion_dot":0.00001288,"magnitude":null},
          {"name":"STARLINK-101007","catalogue":101007,"epoch":21320.51955234,
           "epoch_millis":1637065689322,"mean_motion":15.48582035,"eccentricity":0.0004694,
           "inclination":51.6447,"raan":309.4881,"arg_perigee":203.6966,"mean_anomaly":297.8876,
           "bstar":0.000031985,"mean_motion_dot":0.00001288,"magnitude":null}]}
    """.trimIndent()

    private fun firstVisibleTrain(from: Long): SatellitePass = PassPlanner.visible(
        TrainFinder.passes(
            Elements.parse(trainDocument),
            latitude,
            longitude,
            from,
            from + PassPlanner.HORIZON_MILLIS,
        ),
    ) { true }.first()

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
        val channels: List<PassChannel> get() = announced.map { it.channel }
        override fun notifyPass(pass: SatellitePass, description: PassDescription) {
            announced += pass
        }
    }

    private class Follower : PassFollower {
        val asked = mutableListOf<SatellitePass>()
        override fun follow(pass: SatellitePass) { asked += pass }
    }

    private object Offline : HttpFetcher {
        override suspend fun get(url: String, etag: String?): HttpResult =
            throw java.io.IOException("no network in this test")
    }

    private object NoWeather : WeatherSource {
        override suspend fun cloudCover(base: String, lat: Double, lon: Double): CloudCover? = null
        override fun lastKnown(latitude: Double, longitude: Double): CloudCover? = null
        override fun forecast(latitude: Double, longitude: Double) = emptyList<HourlyCover>()
    }

    private fun poller(
        settings: Settings,
        state: State,
        notifier: Notifier,
        cache: Cache,
        now: Long,
        follower: PassFollower = PassFollower {},
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
        follower = follower,
        now = { now },
        darkness = DarknessAt { _, _, _ -> true },
        localMinuteOfDay = { 2 * 60 },
    )

    /**
     * As [poller], but with the clock and the darkness gate under the test's
     * control: one to let a scan run past its budget, the other to put the next
     * visible pass beyond the near window.
     */
    private fun poller(
        settings: Settings,
        state: State,
        notifier: Notifier,
        cache: Cache,
        clock: () -> Long,
        isDark: (Double, Double, Long) -> Boolean = { _, _, _ -> true },
    ) = PassPoller(
        manifests = ManifestRepository(
            http = Offline,
            store = FileManifestStore(java.io.File("build/tmp/passpoller-test")),
            publicKey = ByteArray(32),
            manifestUrl = "https://sky.arek.sh/v1/index.json",
        ),
        satellites = SatelliteRepository(Offline, cache, clock),
        weather = NoWeather,
        settings = settings,
        state = state,
        notifier = notifier,
        now = clock,
        darkness = DarknessAt(isDark),
        localMinuteOfDay = { 2 * 60 },
    )

    /**
     * The scan is bounded so a wake cannot be killed part way through it.
     *
     * Found on real hardware: the catalogue sweep is 175 objects across a
     * twelve hour horizon at thirty second steps, which takes 7.4 seconds awake
     * and over 45 in Doze, where the CPU is throttled. It sits in a tight loop
     * with no suspension point, so the timeout around it never fired; the
     * broadcast was killed at sixty seconds, the `finally` went with it, and
     * nothing was recorded - twelve times in one night.
     */
    @Test
    fun `gives up a scan that runs past its budget rather than being killed`() = runTest {
        val from = 1637712000000L
        val pass = firstVisiblePass(from)
        val start = pass.peaksAt - 20 * 60_000L
        // Every reading of the clock jumps twenty seconds, so the budget is
        // gone by the first check however small the catalogue is. A real phone
        // gets there the slow way: 175 objects, throttled, over forty seconds.
        var ticks = 0L
        val creeping = { start + (ticks++) * 20_000L }
        val notifier = Notifier()

        val result = poller(settings(), State(), notifier, Cache(document, 600_000L), creeping).poll()

        assertEquals("a partial scan must not speak", 0, notifier.announced.size)
        assertTrue("gave no reason for the short answer", result.failure != null)
        assertTrue("did not come back soon after giving up", result.nextWakeAtMillis > start)
    }

    /**
     * The near window is scanned first and the rest only when nothing there
     * would be acted on. "Nothing there" has to mean nothing the *planner*
     * would choose, not merely no passes at all: a pass whose channel is still
     * rate-limited is skipped by chooseNext, and stopping at the near window
     * because such a pass exists would sleep straight past the real one.
     *
     * Here the sky is dark only after four hours, so every pass inside the near
     * window is invisible and the one that matters lies beyond it.
     */
    @Test
    fun `finds a pass beyond the near window when nothing near is worth choosing`() = runTest {
        // This element set gives three passes from here: +93m, +189m and +287m.
        // Dark only from +185m, so the one inside the near window is invisible
        // and the first that counts lies beyond it.
        val from = 1637712000000L - 4 * 60 * 60_000L
        val darkFrom = from + 185 * 60_000L
        val state = State()
        val notifier = Notifier()

        val result = poller(
            settings(), state, notifier, Cache(document, 600_000L), { from },
            isDark = { _, _, at -> at >= darkFrom },
        ).poll()

        assertTrue(
            "slept past the pass instead of seeing it: ${result.decision}",
            result.decision !is PassPlanner.Decision.Nothing,
        )
        assertTrue(
            "armed a dormant wake, which would sail past the pass",
            result.nextWakeAtMillis < from + PassPlanner.DORMANT_INTERVAL_MILLIS,
        )
    }

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

        // The interval now stops it before the already-told guard needs to,
        // which is the stronger protection of the two: one alert per pass, and
        // no more than one per channel per interval whatever happens.
        assertTrue(second.decision !is PassPlanner.Decision.Announce)
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

    /**
     * The live tracker is opt-in, and the opting-in is a setting the user has
     * to have found and turned on. An announcement on its own asks for nothing.
     */
    @Test
    fun `does not follow a pass live unless the user asked for that`() = runTest {
        val from = 1637712000000L
        val pass = firstVisiblePass(from)
        val now = pass.peaksAt - 20 * 60_000L
        val follower = Follower()
        val state = State()

        poller(settings(), state, Notifier(), Cache(document, 600_000L), now, follower).poll()

        assertTrue(follower.asked.isEmpty())
        assertEquals(null, state.value.followingCatalogue)
    }

    @Test
    fun `asks for a station pass to be followed once the user has said so`() = runTest {
        val from = 1637712000000L
        val pass = firstVisiblePass(from)
        val now = pass.peaksAt - 20 * 60_000L
        val follower = Follower()
        val state = State()
        val settings = settings()
        settings.value = settings.value.copy(followStationPassesAutomatically = true)

        poller(settings, state, Notifier(), Cache(document, 600_000L), now, follower).poll()

        assertEquals(1, follower.asked.size)
        assertEquals(PassChannel.ISS_CATALOGUE, follower.asked.single().catalogue)
        // Recorded as well as scheduled, so a reboot between now and the pass
        // does not lose a request the user has already acted on.
        assertEquals(PassChannel.ISS_CATALOGUE, state.value.followingCatalogue)
        assertEquals(follower.asked.single().peaksAt, state.value.followingPeakAt)
        // The rise as well: the peak says which pass it is, the rise says when
        // the tracker has to start, and putting it back needs both.
        assertEquals(follower.asked.single().risesAt, state.value.followingRisesAt)
    }

    /**
     * A train arrives as one thing to look at, on a channel of its own, and it
     * says how many. The count is the whole of what a train adds over a single
     * satellite crossing the same piece of sky.
     */
    @Test
    fun `announces a train on its own channel and says how many`() = runTest {
        val from = 1637712000000L
        val train = firstVisibleTrain(from)
        val now = train.peaksAt - 20 * 60_000L
        val notifier = Notifier()
        val state = State()

        poller(settings(), state, notifier, Cache(trainDocument, 600_000L), now).poll()

        assertEquals(1, notifier.announced.size)
        assertEquals(PassChannel.Trains, notifier.channels.single())
        assertEquals(22, notifier.announced.single().members)
        assertEquals(now, state.value.lastTrainAlertAt)
        assertEquals(null, state.value.lastBrightAlertAt)
    }

    /**
     * The two ends of a train are published so the train can be worked out, but
     * they are also two ordinary Starlinks. Without dropping them from the
     * ordinary sweep the same launch would be announced twice - once as a
     * train, and again a few minutes later as an unremarkable bright satellite.
     */
    @Test
    fun `never announces the ends of a train as bright satellites too`() = runTest {
        val from = 1637712000000L
        val train = firstVisibleTrain(from)
        val notifier = Notifier()
        val state = State()

        for (minutesBefore in listOf(25L, 20L, 10L, 0L, -10L)) {
            poller(
                settings(),
                state,
                notifier,
                Cache(trainDocument, 600_000L),
                train.peaksAt - minutesBefore * 60_000L,
            ).poll()
        }

        assertTrue(notifier.announced.isNotEmpty())
        assertTrue(notifier.channels.none { it == PassChannel.Bright })
    }
}
