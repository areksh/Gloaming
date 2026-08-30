package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.EventChannel
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyCalendar
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
     * How long an announced event is remembered after its date.
     *
     * Long enough that a device which sleeps through the event itself does not
     * wake afterwards and announce it retrospectively, short enough that the
     * record stays a handful of entries. Events arrive about weekly at most.
     *
     * A week of that, and the rest is the longest an entry can still be worth
     * looking at once its date has passed. The calendar keeps an entry while
     * any part of it is still ahead, so an opposition is on the list for three
     * weeks afterwards - and a record forgotten inside that would announce the
     * same one a second time in the same season. Taken from the calendar rather
     * than written out again, because the two have to move together.
     */
    const val REMEMBER_FOR_MILLIS: Long =
        SkyCalendar.LONGEST_WINDOW_MILLIS + 7 * 24 * 60 * 60 * 1000L

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

        /**
         * Held back by the user's quiet hours, with when the silence lifts.
         *
         * The moment matters because a calendar notice, unlike an aurora
         * reading, is not offered again on the next poll: its due moment is in
         * the past by then and it drops out of the reckoning entirely. Carrying
         * the end of the silence is what lets the planner come back for an
         * event that is still going on.
         */
        data class InQuietHours(val event: SkyEvent, val until: Long) : Decision

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
        // A comet is the strongest case of all for advance notice: it is the
        // one thing here somebody might take a night off work or drive to a
        // dark site for, and unlike an eclipse it is not in the newspaper.
        EventKind.Comet -> true
        // An occultation gets notice too, unlike the conjunction it replaces.
        // It lasts under an hour, happens at a specific minute, and a handful
        // of naked-eye ones reach any one place in a decade - so a notice on
        // the night is a notice that arrives while it is already half over.
        EventKind.Occultation -> true
        EventKind.Conjunction,
        EventKind.Elongation,
        EventKind.MoonPhase,
        EventKind.MoonQuarter,
        EventKind.Season,
        // An opposition or a greatest brilliancy is a good few weeks either
        // side of its date rather than an appointment, so notice on the night
        // is the useful half and days of warning would only be noise.
        EventKind.Opposition,
        EventKind.GreatestBrightness,
        -> false
        // Tonight or not at all: the conditions are worked out for the night
        // they apply to, so there is no future one to give notice of.
        EventKind.Noctilucent -> false
        // Never announced at all, so there is no advance notice to give.
        EventKind.Constellation -> false
    }

    /**
     * The events allowed to interrupt: seeable from here, and on a channel.
     */
    fun announceable(events: List<SkyEvent>, settings: UserSettings): List<SkyEvent> {
        if (!settings.alertsEnabled || !settings.eventAlertsEnabled) return emptyList()
        return events
            .filter { EventChannel.of(it.kind) != null }
            .filterNot { isHidden(it, settings) }
            .filter { worthAnnouncing(it, settings) }
            .filter { settings.notifyAboutEveryEvent || !canBeStarred(it) || isStarred(it, settings) }
    }

    /**
     * Whether this one is worth an interruption, given what the user has said.
     *
     * Marginal is the interesting case, and a star is the answer to it. A
     * near-total lunar eclipse with the moon seven degrees up in a brightening
     * sky is not worth waking somebody at half past four for uninvited - but if
     * they have put a star on it, they have invited it, and overruling that
     * would make the star a control that does nothing on exactly the events
     * somebody would most want it for.
     *
     * A star never rescues [Visibility.NotFromHere]. That is not a judgement
     * about whether it is worth the trouble; it is the ground being in the way.
     */
    private fun worthAnnouncing(event: SkyEvent, settings: UserSettings): Boolean =
        when (event.visibility) {
            Visibility.Good -> true
            is Visibility.Marginal -> isStarred(event, settings)
            is Visibility.NotFromHere -> false
        }

    /**
     * Whether the user has filtered this kind out.
     *
     * One answer for the list and for the alerts alike: hiding meteor showers
     * should mean not being woken about them, not merely not seeing them in a
     * list somebody may never scroll.
     */
    fun isHidden(event: SkyEvent, settings: UserSettings): Boolean =
        event.kind.name in settings.hiddenEventKinds

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
     *
     * **A comet is the exception, and has to be.** Every other event here has a
     * moment fixed by orbital mechanics: an eclipse is at its instant whenever
     * you ask. A comet has no such moment, so the calendar invents one - the
     * best night from now - which moves every time the calendar is recomputed.
     * Keyed on time, a star would be lost within a day of being set and a comet
     * would be announced afresh on every wake. Its name is the stable thing, so
     * that is what it is keyed on.
     */
    fun starKey(event: SkyEvent): String = when (event.kind) {
        EventKind.Comet -> "${event.kind}|${event.title}"
        else -> "${event.kind}|${event.at}"
    }

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
            return Decision.InQuietHours(
                event = event,
                until = now + quietHours.minutesUntilEnd(localMinuteOfDay) * 60_000L,
            )
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
     *
     * The exception is a notice quiet hours held back, which is why [decision]
     * is here. Everything else this looks at is still ahead of [now]; a
     * suppressed notice is behind it, so it contributes nothing to the
     * reckoning below and the wake would fall twelve hours later - past the
     * end of any event it concerned. Coming back when the silence lifts gives
     * the notice its one remaining chance, and if the event has finished by
     * then [due] finds nothing and nothing is said.
     */
    fun nextWake(
        decision: Decision,
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

        val silenceLifts = (decision as? Decision.InQuietHours)?.until ?: Long.MAX_VALUE

        return minOf(soonest ?: Long.MAX_VALUE, silenceLifts, now + DORMANT_INTERVAL_MILLIS)
    }

    /**
     * The announced record, with everything long over dropped.
     *
     * Unparseable keys go too. They can only come from a version that wrote a
     * different format, and keeping one forever would be a slow leak in a
     * preferences file.
     */
    fun prune(keys: Set<String>, now: Long): Set<String> = keys.filterTo(mutableSetOf()) { key ->
        // A comet's key carries its name where everything else carries a time,
        // so there is no age to compare and dropping it would be wrong twice
        // over: the star would vanish, and the comet would be announced again
        // on the very next wake. Comets are kept until they fade, which the
        // poller handles separately through the announced-comets record.
        if (key.startsWith(COMET_PREFIX)) return@filterTo true

        val at = key.split("|").getOrNull(1)?.toLongOrNull() ?: return@filterTo false
        now - at <= REMEMBER_FOR_MILLIS
    }

    private val COMET_PREFIX = "${EventKind.Comet}|"
}
