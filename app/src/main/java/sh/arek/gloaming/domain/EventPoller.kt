package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.Noctilucent
import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.comets.Comets
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CometRepository
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.describe

/** Posts a calendar notification. Local, like everything else the app announces. */
interface EventNotifier {
    fun notifyEvent(event: SkyEvent, phase: EventPlanner.Phase)

    /**
     * The closing half of a story the app started.
     *
     * Having told somebody a comet was worth going out for, it owes them the
     * news that it no longer is - once, and then silence. Comets disappointing
     * is the normal case rather than the exception, and an app that only ever
     * announces arrivals quietly teaches people that its comets never amount to
     * anything.
     */
    fun notifyCometFaded(name: String)
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
    /**
     * The published comets, refreshed on the calendar's own wake.
     *
     * Here rather than on a wake of its own because comets are calendar events
     * and the document changes far more slowly than this poller runs: elements
     * are republished weekly, magnitudes daily, and the dormant interval caps
     * this at twelve hours. Most refreshes will be a 304.
     */
    private val comets: CometRepository,
    private val manifests: ManifestRepository,
    private val weather: WeatherSource,
    private val settings: SettingsStore,
    private val state: AlertStateStore,
    private val notifier: EventNotifier,
    /**
     * Where starred events go, when the user has asked for that.
     *
     * Null where nothing can write to a calendar, which is the default and is
     * also what the two other pollers pass. Here rather than on a wake of its
     * own because this is the poller that already recomputes the year ahead:
     * a comet's dates move on every recompute, and the entry has to move with
     * them.
     */
    private val calendarWriter: CalendarWriter? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
) {

    suspend fun poll(): EventPollResult {
        val at = now()
        return runCatching { pollOnce(at) }.getOrElse {
            EventPollResult(
                decision = EventPlanner.Decision.Nothing,
                nextWakeAtMillis = at + RETRY_AFTER_NO_ANSWER_MILLIS,
                failure = it.describe(),
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

        // Comets come from a published document rather than from arithmetic,
        // so they are worked out separately and merged in. Kept against the
        // comet that produced each, because saying one has faded means knowing
        // which comet an announcement was about.
        val published = runCatching { comets.refresh(manifests.refresh()) }
            .getOrElse { comets.cached() }
        val byComet = Comets.byComet(published, latitude, longitude, user, at)

        // Tonight's noctilucent prompt is merged in here rather than coming
        // from the calendar, because it is a condition rather than a dated
        // occasion: it holds most clear nights for three months, and ninety
        // identical entries would bury the things worth scrolling to.
        val events = calendar.events(latitude, longitude, at) +
            listOfNotNull(Noctilucent.event(latitude, longitude, at)) +
            byComet.values
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
        val comets = closeOffFadedComets(held, byComet, announced)
        val wake = EventPlanner.nextWake(
            decision,
            events,
            at,
            user,
            held.copy(announcedEvents = remembered),
        )

        // After the announcement rather than before it: the calendar is a
        // convenience and the notification is the thing the app is for, so a
        // provider that hangs must not be able to delay one.
        val synced = CalendarSyncer.sync(
            writer = calendarWriter,
            destination = user.calendarSync,
            starred = user.starredEvents,
            events = events,
            synced = held.syncedEvents,
        )

        state.write(
            held.copy(
                announcedEvents = remembered,
                announcedComets = comets,
                nextEventCheckAt = wake,
                syncedEvents = synced,
            ),
        )
        return EventPollResult(decision, wake)
    }

    /**
     * Says goodbye to any comet that has stopped being worth going out for, and
     * remembers any newly spoken about.
     *
     * A comet leaves the list either by fading below the threshold or by
     * dropping out of the published document, and both mean the same thing to
     * somebody who was told to look for it. Exactly one notice each way: the
     * name is forgotten as the notice goes out, so a comet cannot say goodbye
     * twice, and a comet that brightens again is announced again from scratch.
     */
    private fun closeOffFadedComets(
        held: sh.arek.gloaming.data.AlertState,
        byComet: Map<String, SkyEvent>,
        announced: EventPlanner.Decision.Announce?,
    ): Set<String> {
        for (name in held.announcedComets - byComet.keys) {
            notifier.notifyCometFaded(name)
        }

        val surviving = held.announcedComets intersect byComet.keys
        val spokenAbout = announced
            ?.let { decision -> byComet.entries.firstOrNull { it.value === decision.event }?.key }

        return surviving + listOfNotNull(spokenAbout)
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
        /**
         * How soon to come back when this wake learned nothing.
         *
         * A calendar that threw does not know there is nothing coming; it knows
         * it failed to look. Both come back as `Decision.Nothing`, so carrying
         * the dormant interval - half a day, the answer for a calendar that
         * really is empty - hid every notice in between on one transient fault.
         */
        const val RETRY_AFTER_NO_ANSWER_MILLIS = 15 * 60_000L

        /** The real calendar: a year of arithmetic, no network, no hosted file. */
        val REAL: CalendarSource = CalendarSource { latitude, longitude, fromMillis ->
            SkyCalendar.events(latitude, longitude, fromMillis)
        }
    }
}
