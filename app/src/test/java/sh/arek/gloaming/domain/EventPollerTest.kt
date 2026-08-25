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
import sh.arek.gloaming.data.FileManifestStore
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
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
        override fun notifyEvent(event: SkyEvent, phase: EventPlanner.Phase) {
            told += event to phase
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

    private val located = UserSettings(latitude = 55.95, longitude = -3.19)

    private fun poller(
        settings: Settings,
        state: State,
        notifier: Notifier,
        now: Long,
        events: List<SkyEvent> = listOf(eclipse),
        calls: MutableList<Long> = mutableListOf(),
    ) = EventPoller(
        calendar = { _, _, from -> calls += from; events },
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
}
