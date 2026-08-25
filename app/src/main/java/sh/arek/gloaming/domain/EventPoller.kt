package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WeatherSource

/** Posts a calendar notification. Local, like everything else the app announces. */
interface EventNotifier {
    fun notifyEvent(event: SkyEvent, phase: EventPlanner.Phase)
}

/**
 * Where the calendar comes from.
 *
 * An interface only so tests can hand over a fixed list. There is nothing to
 * fetch: the real implementation is arithmetic over a vendored ephemeris.
 */
fun interface CalendarSource {
    fun events(latitude: Double, longitude: Double, fromMillis: Long): List<SkyEvent>
}

/** What one calendar check did, and when to come back. */
data class EventPollResult(
    val decision: EventPlanner.Decision,
    val nextWakeAtMillis: Long,
    val failure: String? = null,
)

/**
 * One wake's worth of sky calendar.
 *
 * The third sibling, after [SkyPoller] and [PassPoller], and the least busy of
 * them by a long way: aurora asks an upstream what the sky is doing now, passes
 * work out the next twelve hours from held elements, and this works out a year
 * from arithmetic that was equally true a century ago. There is nothing to poll
 * for, only moments to keep.
 *
 * Recomputing a year costs about a quarter of a second, so it happens only when
 * the moment the last check asked for has actually arrived.
 *
 * Nothing here throws. A background wake that crashes is worse than one that
 * records a failure and comes back.
 */
class EventPoller(
    private val calendar: CalendarSource,
    private val manifests: ManifestRepository,
    private val weather: WeatherSource,
    private val settings: SettingsStore,
    private val state: AlertStateStore,
    private val notifier: EventNotifier,
    private val now: () -> Long = System::currentTimeMillis,
    private val localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
) {

    suspend fun poll(): EventPollResult {
        val at = now()
        return runCatching { pollOnce(at) }.getOrElse {
            EventPollResult(
                decision = EventPlanner.Decision.Nothing,
                nextWakeAtMillis = at + EventPlanner.DORMANT_INTERVAL_MILLIS,
                failure = it.message ?: it::class.java.simpleName,
            )
        }
    }

    private suspend fun pollOnce(at: Long): EventPollResult {
        val user = settings.read()
        val latitude = user.latitude
        val longitude = user.longitude

        if (!user.alertsEnabled || !user.eventAlertsEnabled || latitude == null || longitude == null) {
            return dormant(at)
        }

        val held = state.read()
        val due = held.nextEventCheckAt
        if (due != null && at < due) {
            return EventPollResult(EventPlanner.Decision.Resting, due)
        }

        val events = calendar.events(latitude, longitude, at)
        val pending = EventPlanner.due(events, at, user, held)

        // The forecast is only worth a radio wake when something is about to be
        // announced for tonight, which is the same reason the other two pollers
        // leave it until last. A heads-up two days out is not cloud-gated at
        // all, so it never costs one.
        val cloud = if (pending?.second == EventPlanner.Phase.Tonight) {
            cloudCover(user, latitude, longitude)
        } else {
            null
        }

        val decision = EventPlanner.decide(
            events = events,
            now = at,
            settings = user,
            state = held,
            localMinuteOfDay = localMinuteOfDay(at),
            cloud = cloud,
        )

        val announced = decision as? EventPlanner.Decision.Announce
        announced?.let { notifier.notifyEvent(it.event, it.phase) }

        val told = announced
            ?.let { held.announcedEvents + EventPlanner.key(it.event, it.phase) }
            ?: held.announcedEvents

        val remembered = EventPlanner.prune(told, at)
        val wake = EventPlanner.nextWake(
            events,
            at,
            user,
            held.copy(announcedEvents = remembered),
        )

        state.write(
            held.copy(
                announcedEvents = remembered,
                nextEventCheckAt = wake,
            ),
        )
        return EventPollResult(decision, wake)
    }

    private suspend fun cloudCover(
        user: UserSettings,
        latitude: Double,
        longitude: Double,
    ): CloudCover? {
        if (!user.cloudGateEnabled) return null
        val endpoint = runCatching { manifests.refresh().endpoints[SkyPoller.ENDPOINT_WEATHER] }
            .getOrNull() ?: return null
        return runCatching { weather.cloudCover(endpoint, latitude, longitude) }.getOrNull()
    }

    private fun dormant(at: Long) = EventPollResult(
        decision = EventPlanner.Decision.Nothing,
        nextWakeAtMillis = at + EventPlanner.DORMANT_INTERVAL_MILLIS,
    )

    companion object {
        /** The real calendar: a year of arithmetic, no network, no hosted file. */
        val REAL: CalendarSource = CalendarSource { latitude, longitude, fromMillis ->
            SkyCalendar.events(latitude, longitude, fromMillis)
        }
    }
}
