package sh.arek.gloaming.domain

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.CometCache
import sh.arek.gloaming.data.CometRepository
import sh.arek.gloaming.data.FileManifestStore
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult

/**
 * The wake-by-wake behaviour of the sky calendar.
 *
 * The decisions belong to [EventPlannerTest]; what is only visible here is the
 * wiring - that a wake which is not a calendar check costs nothing, that an
 * event is announced once and remembered across wakes, and that the record of
 * what has been said does not grow forever.
 */
class EventPollerTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val eclipse = SkyEvent(
        kind = EventKind.SolarEclipse,
        at = at("2027-08-02T09:00:23Z"),
        from = at("2027-08-02T08:11:11Z"),
        until = at("2027-08-02T09:51:31Z"),
        title = "Partial eclipse of the sun",
        detail = "27 per cent of the sun covered at maximum.",
        visibility = Visibility.Good,
    )

    private class Settings(var value: UserSettings) : SettingsStore {
        override fun read() = value
        override fun write(settings: UserSettings) { value = settings }
    }

    private class State(var value: AlertState = AlertState()) : AlertStateStore {
        override fun read() = value
        override fun write(state: AlertState) { value = state }
    }

    private class Notifier : EventNotifier {
        val told = mutableListOf<Pair<SkyEvent, EventPlanner.Phase>>()
        val faded = mutableListOf<String>()
        override fun notifyEvent(event: SkyEvent, phase: EventPlanner.Phase) {
            told += event to phase
        }
        override fun notifyCometFaded(name: String) { faded += name }
    }

    /** No comet document at all, which is what most years look like anyway. */
    private object EmptyComets : CometCache {
        override fun read(): Pair<String, String?>? = null
        override fun write(body: String, etag: String?) = Unit
        override fun writtenAt(): Long? = null
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

    private val located = UserSettings(latitude = 55.95, longitude = -3.19)

    private fun poller(
        settings: Settings,
        state: State,
        notifier: Notifier,
        now: Long,
        events: List<SkyEvent> = listOf(eclipse),
        calls: MutableList<Long> = mutableListOf(),
        cometBody: String? = null,
    ) = EventPoller(
        calendar = { _, _, from -> calls += from; events },
        comets = CometRepository(
            Offline,
            object : CometCache {
                override fun read() = cometBody?.let { it to null }
                override fun write(body: String, etag: String?) = Unit
                override fun writtenAt(): Long? = null
            },
        ),
        // Never reached: the cloud gate is off in these settings, so nothing
        // here goes near the network.
        manifests = ManifestRepository(
            http = Offline,
            store = FileManifestStore(java.io.File("build/tmp/eventpoller-test")),
            publicKey = ByteArray(32),
            manifestUrl = "https://sky.arek.sh/v1/index.json",
        ),
        weather = NoWeather,
        settings = settings,
        state = state,
        notifier = notifier,
        now = { now },
        localMinuteOfDay = { 12 * 60 },
    )

    @Test
    fun `announces an event once and remembers it`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val state = State()
        val notifier = Notifier()
        val now = at("2027-07-31T08:11:11Z")

        val first = poller(settings, state, notifier, now).poll()

        assertTrue(first.decision.toString(), first.decision is EventPlanner.Decision.Announce)
        assertEquals(1, notifier.told.size)
        assertTrue(state.value.announcedEvents.isNotEmpty())

        // The same wake again, with the recorded state carried over.
        state.value = state.value.copy(nextEventCheckAt = null)
        poller(settings, state, notifier, now).poll()

        assertEquals("announced twice", 1, notifier.told.size)
    }

    /** A wake that is not this poller's costs nothing at all - not even the calendar. */
    @Test
    fun `rests without recomputing the calendar when its moment has not come`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val now = at("2027-07-01T00:00:00Z")
        val state = State(AlertState(nextEventCheckAt = now + 3_600_000))
        val calls = mutableListOf<Long>()

        val result = poller(settings, state, Notifier(), now, calls = calls).poll()

        assertEquals(EventPlanner.Decision.Resting, result.decision)
        assertTrue(calls.toString(), calls.isEmpty())
    }

    /** Without a location there is nothing the calendar could be about. */
    @Test
    fun `stays dormant without a location`() = runTest {
        val state = State()
        val calls = mutableListOf<Long>()

        val result = poller(Settings(UserSettings()), state, Notifier(), at("2027-08-02T08:00:00Z"), calls = calls).poll()

        assertEquals(EventPlanner.Decision.Nothing, result.decision)
        assertTrue(calls.isEmpty())
    }

    /** Switched off, the calendar never speaks however good the event. */
    @Test
    fun `stays dormant when calendar alerts are off`() = runTest {
        val settings = Settings(located.copy(eventAlertsEnabled = false))
        val notifier = Notifier()

        val result = poller(settings, State(), notifier, at("2027-08-02T08:00:00Z")).poll()

        assertEquals(EventPlanner.Decision.Nothing, result.decision)
        assertTrue(notifier.told.isEmpty())
    }

    /** A calendar that throws must not end the schedule. */
    @Test
    fun `records a failure and still asks to come back`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val now = at("2027-08-02T08:00:00Z")
        val poller = EventPoller(
            calendar = { _, _, _ -> throw IllegalStateException("ephemeris exploded") },
            comets = CometRepository(Offline, EmptyComets),
            manifests = ManifestRepository(
                http = Offline,
                store = FileManifestStore(java.io.File("build/tmp/eventpoller-test")),
                publicKey = ByteArray(32),
                manifestUrl = "https://sky.arek.sh/v1/index.json",
            ),
            weather = NoWeather,
            settings = settings,
            state = State(),
            notifier = Notifier(),
            now = { now },
            localMinuteOfDay = { 12 * 60 },
        )

        val result = poller.poll()

        assertEquals("ephemeris exploded", result.failure)
        assertTrue(result.nextWakeAtMillis > now)
    }

    /** What was announced long ago is forgotten, so the record stays small. */
    @Test
    fun `forgets old announcements`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val state = State(
            AlertState(announcedEvents = setOf("SolarEclipse|1000000000000|Tonight")),
        )

        poller(settings, state, Notifier(), at("2027-07-31T08:11:11Z")).poll()

        assertTrue(
            state.value.announcedEvents.toString(),
            state.value.announcedEvents.none { it.contains("1000000000000") },
        )
    }

    // --- comets ---

    /**
     * A comet document with one comet bright enough to be worth announcing:
     * Hale-Bopp's real elements, with the absolute magnitude that made it the
     * spectacle it actually was.
     */
    private fun cometBody(magnitudeH: Double = -1.0, name: String = "C/1995 O1 (Hale-Bopp)") =
        """
        {"schema":1,"updated":1,"comets":[
          {"key":"J95O010","name":"$name","q":0.9141695106942098,"e":0.9951306139219294,
           "i":89.43017886941958,"node":282.470593486876,"peri":130.5872422543277,
           "perihelion_jd_tt":2450539.633093244,"magnitude_h":$magnitudeH,"slope":4.0,
           "observed":null}]}
        """.trimIndent()

    /** A comet in the document reaches the planner exactly like anything else. */
    @Test
    fun `puts a comet from the document into the calendar it plans over`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val now = at("1997-03-20T20:00:00Z")

        val result = poller(
            settings, State(), Notifier(), now,
            events = emptyList(),
            cometBody = cometBody(),
        ).poll()

        // Nothing is announced - an unmeasured comet is marginal, and marginal
        // only interrupts when starred - but the schedule proves it was seen.
        assertTrue(result.decision.toString(), result.decision !is EventPlanner.Decision.Announce)
        assertTrue(result.nextWakeAtMillis > now)
    }

    /**
     * The closing half of a story the app started.
     *
     * A comet the app has spoken about leaves the list either by fading below
     * the threshold or by dropping out of the published document, and both mean
     * the same thing to somebody who was told to go and look.
     */
    @Test
    fun `says goodbye once when an announced comet stops being worth it`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val state = State(AlertState(announcedComets = setOf("C/1995 O1 (Hale-Bopp)")))
        val notifier = Notifier()
        val now = at("1997-03-20T20:00:00Z")

        poller(settings, state, notifier, now, events = emptyList()).poll()

        assertEquals(listOf("C/1995 O1 (Hale-Bopp)"), notifier.faded)
        assertTrue(state.value.announcedComets.isEmpty())

        // And not again on the next wake, because the name went with the notice.
        val second = Notifier()
        poller(settings, state, second, now + 1, events = emptyList()).poll()
        assertTrue(second.faded.isEmpty())
    }

    /** A comet still in the list is not eulogised. */
    @Test
    fun `keeps quiet about a comet that is still up`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val state = State(AlertState(announcedComets = setOf("C/1995 O1 (Hale-Bopp)")))
        val notifier = Notifier()

        poller(
            settings, state, notifier, at("1997-03-20T20:00:00Z"),
            events = emptyList(),
            cometBody = cometBody(),
        ).poll()

        assertTrue(notifier.faded.isEmpty())
        assertEquals(setOf("C/1995 O1 (Hale-Bopp)"), state.value.announcedComets)
    }

    /**
     * A comet that was never announced is never eulogised either. Otherwise
     * every install would be told of the passing of comets it had never heard
     * of, which is worse than saying nothing.
     */
    @Test
    fun `says nothing about a comet it never mentioned`() = runTest {
        val settings = Settings(located.copy(cloudGateEnabled = false))
        val notifier = Notifier()

        poller(settings, State(), notifier, at("1997-03-20T20:00:00Z"), events = emptyList()).poll()

        assertTrue(notifier.faded.isEmpty())
    }
}
