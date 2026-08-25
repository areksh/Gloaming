package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.EventChannel
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.UserSettings

/**
 * When to speak about something in the calendar, and when to come back.
 *
 * The third small specific thing, alongside [AlertDecision] for aurora and
 * [PassPlanner] for passes, and deliberately not a generalisation of either.
 * The design warns that "an adaptive attention system for all event types" is
 * how a two-week feature becomes a six-month one; these three share the gates
 * and the channel taxonomy and no machinery at all.
 *
 * Everything here is pure. The same events, settings and state give the same
 * answer, with no clock, no network and no Android.
 *
 * **The darkness gate is deliberately not applied.** Sec. 9.2 says every
 * outdoor event is gated on darkness, and that rule was written with aurora and
 * passes in mind. A solar eclipse happens in daylight by definition, so gating
 * on the sun being down would suppress every one of them. Whether a thing can
 * be seen from here is already settled, per kind and with the right rule for
 * each, by the visibility verdict the calendar attaches - so applying a blanket
 * sun-below-six gate on top would be answering the same question twice and
 * getting it wrong the second time.
 *
 * **There are no per-channel intervals either**, which is the other departure
 * from [PassPlanner]. Passes needed them because a clear night over Edinburgh
 * has upwards of forty worth looking at. The whole calendar produces about
 * forty events a year, of which only the good ones speak, so a rate limit built
 * for things that come over nightly would be apparatus with nothing to do. The
 * per-channel switch Android already gives every notification channel is the
 * control that fits.
 */
object EventPlanner {

    /**
     * How long to sleep when the calendar has nothing coming.
     *
     * Twelve hours rather than the pass poller's six: these events are known
     * months ahead and never arrive unexpectedly, so there is nothing a shorter
     * wake could discover.
     */
    const val DORMANT_INTERVAL_MILLIS: Long = 12 * 60 * 60 * 1000L

    /**
     * How long an announced event is remembered after it has finished.
     *
     * Long enough that a device which sleeps through the event itself does not
     * wake afterwards and announce it retrospectively, short enough that the
     * record stays a handful of entries. Events arrive about weekly at most.
     */
    const val REMEMBER_FOR_MILLIS: Long = 7 * 24 * 60 * 60 * 1000L

    /**
     * How late a heads-up may be and still be one.
     *
     * A notice that arrives two days late, an hour before the on-the-day alert,
     * is not advance warning - it is the same alert twice. This happens to
     * anybody who installs the app shortly before an eclipse, and dropping it
     * is better than doubling up.
     */
    const val HEADS_UP_GRACE_MILLIS: Long = 12 * 60 * 60 * 1000L

    /** Which of the two notices this is. */
    enum class Phase { HeadsUp, Tonight }

    sealed interface Decision {
        data class Announce(val event: SkyEvent, val phase: Phase) : Decision

        /** Known and worth telling about, but not yet. */
        data class TooSoon(val event: SkyEvent, val phase: Phase) : Decision

        data class AlreadyTold(val event: SkyEvent) : Decision

        data class InQuietHours(val event: SkyEvent) : Decision

        data class TooCloudy(val event: SkyEvent) : Decision

        /** Nothing in the calendar has anything to say. */
        data object Nothing : Decision

        /** The moment this poller asked for has not arrived; the wake is someone else's. */
        data object Resting : Decision
    }

    /**
     * Which kinds are worth warning about in advance.
     *
     * An eclipse or a shower peak is something people arrange an evening or a
     * journey around, so a couple of days of notice is the useful part. A
     * conjunction is a look out of the window on the night, and two alerts for
     * one would be the surest way to have the channel muted.
     */
    private fun wantsHeadsUp(kind: EventKind): Boolean = when (kind) {
        EventKind.SolarEclipse, EventKind.LunarEclipse, EventKind.MeteorShower -> true
        EventKind.Conjunction, EventKind.Elongation, EventKind.MoonPhase -> false
        // Tonight or not at all: the conditions are worked out for the night
        // they apply to, so there is no future one to give notice of.
        EventKind.Noctilucent -> false
    }

    /**
     * The events allowed to interrupt: seeable from here, and on a channel.
     *
     * Marginal is the interesting exclusion. A near-total lunar eclipse with the
     * moon seven degrees up in a brightening sky is worth a line in the calendar
     * and is not worth waking somebody at half past four for, and the whole
     * point of having a verdict rather than a boolean is to be able to say so.
     */
    fun announceable(events: List<SkyEvent>, settings: UserSettings): List<SkyEvent> {
        if (!settings.alertsEnabled || !settings.eventAlertsEnabled) return emptyList()
        return events
            .filter { it.visibility == Visibility.Good && EventChannel.of(it.kind) != null }
            .filter { settings.notifyAboutEveryEvent || !canBeStarred(it) || isStarred(it, settings) }
    }

    fun isStarred(event: SkyEvent, settings: UserSettings): Boolean =
        starKey(event) in settings.starredEvents

    /**
     * The starred record with everything long past dropped.
     *
     * The same treatment as the announced record, and for the same reason: a
     * star on an eclipse that happened last year is a preference about nothing.
     */
    fun pruneStars(keys: Set<String>, now: Long): Set<String> = prune(keys, now)

    /**
     * Identifies one event, for starring and for the announced record alike.
     *
     * Kind and time rather than the title, because a title is prose and prose
     * gets reworded - a star should survive that.
     */
    fun starKey(event: SkyEvent): String = "${event.kind}|${event.at}"

    /** Identifies one notice about one event, so neither phase repeats. */
    fun key(event: SkyEvent, phase: Phase): String = "${starKey(event)}|$phase"

    /**
     * Whether starring this is a thing the user could have done.
     *
     * Noctilucent conditions never appear in the calendar - they are worked out
     * for tonight and would otherwise fill it with ninety identical entries -
     * so there is nothing to put a star on. Making them obey the star filter
     * would silence them permanently the moment somebody turned it on, for a
     * reason they could never see or undo.
     */
    fun canBeStarred(event: SkyEvent): Boolean = event.kind != EventKind.Noctilucent

    /** When [phase] of [event] becomes due. */
    private fun dueAt(event: SkyEvent, phase: Phase, settings: UserSettings): Long = when (phase) {
        Phase.HeadsUp -> event.from - settings.eventHeadsUpMillis
        Phase.Tonight -> event.from - settings.eventAlertLeadMillis
    }

    /**
     * The notice that is due now, if any.
     *
     * A heads-up whose moment has already passed is skipped rather than fired
     * late: somebody installing the app the day before an eclipse would
     * otherwise get a "in two days" notice and an "in an hour" notice within
     * the same afternoon, and only the second is any use.
     */
    fun due(
        events: List<SkyEvent>,
        now: Long,
        settings: UserSettings,
        state: AlertState,
    ): Pair<SkyEvent, Phase>? = announceable(events, settings)
        .filter { now <= it.until }
        .flatMap { event ->
            Phase.entries
                .filter { it != Phase.HeadsUp || wantsHeadsUp(event.kind) }
                .filter {
                    it != Phase.HeadsUp ||
                        now <= dueAt(event, Phase.HeadsUp, settings) + HEADS_UP_GRACE_MILLIS
                }
                .map { event to it }
        }
        .filter { (event, phase) -> now >= dueAt(event, phase, settings) }
        .filter { (event, phase) -> key(event, phase) !in state.announcedEvents }
        .minByOrNull { (event, phase) -> dueAt(event, phase, settings) }

    /**
     * What to do about the calendar at [now].
     *
     * The cloud gate applies to the notice on the day and not to the heads-up.
     * A forecast two days out says nothing useful about the sky, and the point
     * of advance notice is to let somebody arrange to be somewhere - which is a
     * decision they should get to make against their own forecast.
     */
    fun decide(
        events: List<SkyEvent>,
        now: Long,
        settings: UserSettings,
        state: AlertState,
        localMinuteOfDay: Int,
        cloud: CloudCover?,
    ): Decision {
        val (event, phase) = due(events, now, settings, state)
            ?: return alreadyToldOrNothing(events, now, settings, state)

        val quietHours = settings.quietHours
        if (quietHours != null && quietHours.contains(localMinuteOfDay)) {
            return Decision.InQuietHours(event)
        }

        if (phase == Phase.Tonight &&
            settings.cloudGateEnabled &&
            cloud != null &&
            cloud.total > settings.cloudTolerance
        ) {
            return Decision.TooCloudy(event)
        }

        return Decision.Announce(event, phase)
    }

    /**
     * Nothing is due - but saying why matters, because "there is nothing on"
     * and "you have already been told" read the same in a log and are not the
     * same thing.
     */
    private fun alreadyToldOrNothing(
        events: List<SkyEvent>,
        now: Long,
        settings: UserSettings,
        state: AlertState,
    ): Decision {
        // Not merely the ones under way: an eclipse announced this morning and
        // starting this afternoon is still the thing the app has spoken about,
        // and the log should say so rather than reporting an empty sky.
        val underway = announceable(events, settings)
            .filter { now <= it.until }
            .firstOrNull { event ->
                Phase.entries.any { key(event, it) in state.announcedEvents }
            }
        return if (underway != null) Decision.AlreadyTold(underway) else Decision.Nothing
    }

    /**
     * When to wake next: the soonest notice still ahead, or a long sleep.
     *
     * There is no cadence here at all. Every one of these events was knowable
     * a century ago, so a poll would only ever rediscover what the last one
     * already worked out.
     */
    fun nextWake(
        events: List<SkyEvent>,
        now: Long,
        settings: UserSettings,
        state: AlertState,
    ): Long {
        val soonest = announceable(events, settings)
            .flatMap { event ->
                Phase.entries
                    .filter { it != Phase.HeadsUp || wantsHeadsUp(event.kind) }
                    .filter { key(event, it) !in state.announcedEvents }
                    .map { dueAt(event, it, settings) }
            }
            .filter { it > now }
            .minOrNull()

        return minOf(soonest ?: Long.MAX_VALUE, now + DORMANT_INTERVAL_MILLIS)
    }

    /**
     * The announced record, with everything long over dropped.
     *
     * Unparseable keys go too. They can only come from a version that wrote a
     * different format, and keeping one forever would be a slow leak in a
     * preferences file.
     */
    fun prune(keys: Set<String>, now: Long): Set<String> = keys.filterTo(mutableSetOf()) { key ->
        val at = key.split("|").getOrNull(1)?.toLongOrNull() ?: return@filterTo false
        now - at <= REMEMBER_FOR_MILLIS
    }
}
