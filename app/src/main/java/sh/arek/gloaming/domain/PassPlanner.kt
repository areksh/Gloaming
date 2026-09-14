package sh.arek.gloaming.domain

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.PassDescription
import sh.arek.gloaming.satellites.Prominence
import sh.arek.gloaming.satellites.SatellitePass

/**
 * When to speak about a pass, and when to come back.
 *
 * Passes are deterministic once the elements are held, so this is not a poll
 * cadence: it is a list of moments. The tiers in the design are dormant (no
 * pass within twelve hours), armed (one is known, so freshen the elements
 * before it and re-check the cloud close to it), and the alert itself.
 *
 * Everything here is pure. The same pass, state and settings give the same
 * answer, with no clock, no network and no Android.
 *
 * Deliberately *not* a generalisation of [AlertDecision]. Aurora is driven by
 * an upstream feed and passes by a local prediction; they share the gates and
 * the channel taxonomy and essentially nothing else, and building the framework
 * that unified them is how a two-week feature becomes a six-month one.
 */
object PassPlanner {

    /** How far ahead passes are worked out. */
    const val HORIZON_MILLIS: Long = 12 * 60 * 60 * 1000L

    /**
     * How long before the highest point the alert goes out, when nobody has
     * said otherwise.
     *
     * The number itself now lives in [UserSettings]: how much warning somebody
     * wants is a fact about them and not about the satellite. This remains for
     * the places that have no settings to hand.
     */
    val LEAD_MILLIS: Long = UserSettings.DEFAULT_PASS_LEAD_MINUTES * 60_000L

    /**
     * How long before a pass the elements are re-fetched.
     *
     * Reboosts and debris-avoidance manoeuvres move a pass by tens of seconds
     * to minutes. Having told somebody 22:14, it is worth re-deriving that from
     * fresh elements rather than serving a prediction from a two-hourly cron
     * that happened to land badly.
     */
    const val FRESHEN_BEFORE_MILLIS: Long = 3 * 60 * 60 * 1000L

    /** Elements older than this are refetched whether or not a pass is due. */
    const val STALE_AFTER_MILLIS: Long = 24 * 60 * 60 * 1000L

    /** A floor on refetching, so the last three hours are not a fetch per wake. */
    const val MINIMUM_REFETCH_MILLIS: Long = 60 * 60 * 1000L

    /**
     * How far a pass may move and still be the same pass.
     *
     * Wide enough to cover a re-derivation from freshened elements, which can
     * shift a pass by minutes after a reboost; far narrower than the ninety
     * minutes between one pass of an object and the next, so it cannot swallow
     * a real second pass.
     */
    const val SAME_PASS_TOLERANCE_MILLIS: Long = 10 * 60 * 1000L

    /** With nothing coming, look again at this cadence rather than not at all. */
    const val DORMANT_INTERVAL_MILLIS: Long = 6 * 60 * 60 * 1000L

    /** What the planner decided about the next pass. */
    sealed interface Decision {
        /** Tell the user now. */
        data class Announce(val pass: SatellitePass) : Decision

        /** Known, but not yet time to speak. */
        data class TooSoon(val pass: SatellitePass) : Decision

        /** Already announced; nothing more to say about this one. */
        data class AlreadyTold(val pass: SatellitePass) : Decision

        /**
         * Held back by the user's quiet hours.
         *
         * Carries when the silence lifts, in the same way [RateLimited] carries
         * when the channel is free again, so the planner can come back for a
         * pass that is still in the sky by then rather than writing the pass
         * off. Somebody who asked not to be woken at 06:30 has not asked never
         * to be told about a satellite that is overhead at 07:00.
         */
        data class InQuietHours(val pass: SatellitePass, val until: Long) : Decision

        data class TooCloudy(val pass: SatellitePass) : Decision

        /**
         * Passes are coming, but this channel has spoken too recently.
         *
         * A distinct answer rather than silence, because "nothing is visible
         * tonight" and "you asked me not to mention it this often" are
         * different things and the log should not conflate them.
         */
        data class RateLimited(val until: Long) : Decision

        /** Nothing visible within the horizon. */
        data object Nothing : Decision

        /**
         * Not a pass check at all: the moment this poller asked for has not
         * arrived, and the wake belongs to something else.
         */
        data object Resting : Decision
    }

    /**
     * Whether the held elements should be replaced before predicting again.
     *
     * @param ageMillis how old the cached elements are, null when there are none.
     * @param millisToNextPass time until the next known pass, null when none is.
     */
    fun shouldFreshen(ageMillis: Long?, millisToNextPass: Long?): Boolean {
        if (ageMillis == null) return true
        if (ageMillis >= STALE_AFTER_MILLIS) return true
        if (millisToNextPass == null) return false
        return millisToNextPass <= FRESHEN_BEFORE_MILLIS && ageMillis >= MINIMUM_REFETCH_MILLIS
    }

    /**
     * The passes there is any point mentioning.
     *
     * Two gates, both free: the satellite has to catch the sun at some point
     * during the pass, and the observer has to be in darkness when it is
     * highest. A pass through a bright sky is a pass nobody can see, however
     * good the geometry.
     */
    fun visible(passes: List<SatellitePass>, isDarkAt: (Long) -> Boolean): List<SatellitePass> =
        passes.filter { pass ->
            val everSunlit = pass.sunlitAtPeak ||
                pass.entersShadowAt != null ||
                pass.leavesShadowAt != null
            everSunlit && isDarkAt(pass.peaksAt)
        }

    /** The soonest pass still ahead of [now], if any. */
    fun next(passes: List<SatellitePass>, now: Long): SatellitePass? =
        passes.filter { it.peaksAt > now }.minByOrNull { it.peaksAt }

    /** The user's own bar for how often this channel may interrupt them. */
    fun intervalMillis(channel: PassChannel, settings: UserSettings): Long {
        val minutes = when (channel) {
            PassChannel.Iss -> settings.issPassAlertIntervalMinutes
            PassChannel.Bright -> settings.brightPassAlertIntervalMinutes
            PassChannel.Trains -> settings.trainPassAlertIntervalMinutes
        }
        return minutes.coerceAtLeast(1) * 60_000L
    }

    private fun lastAlertAt(channel: PassChannel, state: AlertState): Long? = when (channel) {
        PassChannel.Iss -> state.lastIssAlertAt
        PassChannel.Bright -> state.lastBrightAlertAt
        PassChannel.Trains -> state.lastTrainAlertAt
    }

    /** When each channel is allowed to speak again. */
    private fun freeAgain(settings: UserSettings, state: AlertState): List<Long> =
        PassChannel.entries.mapNotNull { channel ->
            lastAlertAt(channel, state)?.plus(intervalMillis(channel, settings))
        }

    /**
     * The one pass worth speaking about next, across both channels.
     *
     * Two things happen here, and the second is the reason the first is safe.
     *
     * A channel may only speak once per its interval, which is what stops a
     * clear night producing forty notifications. But a rate limit applied to
     * "the next pass" would announce whichever pass happened to come first and
     * then fall silent through the good one twenty minutes later - which is a
     * worse app than one that said nothing. So the interval is also the
     * selection window: of the passes a channel could announce before it must
     * go quiet again, this takes the **best** one.
     *
     * Read together: "tell me about the best pass, at most this often". A pass
     * is eligible only if the channel will actually be free when its alert
     * falls due, so a limit in force now does not discard a pass that comes
     * after it lifts.
     */
    fun chooseNext(
        passes: List<SatellitePass>,
        now: Long,
        settings: UserSettings,
        state: AlertState,
    ): SatellitePass? = PassChannel.entries
        .mapNotNull { channel ->
            val free = lastAlertAt(channel, state)
                ?.plus(intervalMillis(channel, settings))
                ?: Long.MIN_VALUE
            val eligible = passes.filter {
                it.channel == channel &&
                    it.peaksAt > now &&
                    it.peaksAt - settings.passAlertLeadMillis >= free
            }
            val opens = maxOf(now, free)
            val window = eligible.filter { it.peaksAt <= opens + intervalMillis(channel, settings) }
            // Ranked by what a person standing outside would notice, then by
            // how high it gets. Prominence rather than a second set of
            // criteria, so "good pass" means one thing in this app.
            (window.ifEmpty { eligible }).maxWithOrNull(
                compareBy({ rank(it) }, { it.peakElevation }),
            )
        }
        .minByOrNull { it.peaksAt }

    /**
     * How much of a show a pass is, as a number that can be sorted.
     *
     * Darkness is taken as given: only passes that already cleared [visible]
     * reach here, and that gate is what establishes it.
     */
    private fun rank(pass: SatellitePass): Int =
        when (PassDescription.of(pass, observerIsDark = true).prominence) {
            Prominence.Striking -> 3
            Prominence.Good -> 2
            Prominence.Modest -> 1
            Prominence.InShadow, Prominence.TooLight -> 0
        }

    /**
     * Whether to speak about [pass] on this wake.
     *
     * The gates are the ones aurora uses, applied to a scheduled event rather
     * than a reading: quiet hours, one alert per pass, and cloud - re-checked
     * here rather than at the moment the pass was first predicted, because a
     * forecast three hours stale is worse than useless.
     *
     * A cloud forecast that could not be reached notifies anyway and says so.
     * An app that goes quiet whenever a third party is down is
     * indistinguishable from one that is broken.
     */
    fun decide(
        passes: List<SatellitePass>,
        now: Long,
        settings: UserSettings,
        state: AlertState,
        localMinuteOfDay: Int,
        cloud: CloudCover?,
    ): Decision {
        val pass = chooseNext(passes, now, settings, state)
        if (pass == null) {
            // Something is up there, so the silence is the interval's doing
            // rather than an empty sky. Come back when it lifts.
            val lifts = freeAgain(settings, state).filter { it > now }.minOrNull()
            return if (next(passes, now) != null && lifts != null) {
                Decision.RateLimited(lifts)
            } else {
                Decision.Nothing
            }
        }
        if (now < pass.peaksAt - settings.passAlertLeadMillis) return Decision.TooSoon(pass)
        if (isAlreadyTold(pass, state)) return Decision.AlreadyTold(pass)

        val quietHours = settings.quietHours
        if (quietHours != null && quietHours.contains(localMinuteOfDay)) {
            return Decision.InQuietHours(
                pass = pass,
                until = now + quietHours.minutesUntilEnd(localMinuteOfDay) * 60_000L,
            )
        }

        if (settings.cloudGateEnabled && cloud != null && cloud.total > settings.cloudTolerance) {
            return Decision.TooCloudy(pass)
        }

        return Decision.Announce(pass)
    }

    /**
     * Whether [channel] should be followed live without the user asking.
     *
     * Structural rather than a default: there is one setting and it names the
     * station, so no combination of switches can turn the bright catalogue into
     * a foreground service every clear night. Sec. 9.4 calls that frequency
     * asymmetry the risk that sinks live mode, and the cheapest way to honour
     * it is to make the wrong answer unrepresentable.
     *
     * Following a single pass the user tapped is a separate route and does not
     * come through here.
     */
    fun shouldFollow(channel: PassChannel, settings: UserSettings): Boolean =
        channel == PassChannel.Iss && settings.followStationPassesAutomatically

    /**
     * Whether this is the pass that was last announced.
     *
     * Matched by object and by an approximate time, because the exact instant
     * moves: the peak is re-derived on every wake, and freshened elements move
     * it deliberately. An exact comparison would announce the same pass again
     * each time it shifted by a millisecond.
     */
    private fun isAlreadyTold(pass: SatellitePass, state: AlertState): Boolean {
        val peak = state.lastPassPeakAt ?: return false
        if (state.lastPassCatalogue != pass.catalogue) return false
        return kotlin.math.abs(pass.peaksAt - peak) <= SAME_PASS_TOLERANCE_MILLIS
    }

    /**
     * When to wake next, given what was decided.
     *
     * The next interesting moment rather than a fixed interval: the point at
     * which the elements want freshening, the point at which the alert is due,
     * or - once a pass has been dealt with - after it has finished, so the one
     * behind it can be considered.
     */
    /**
     * When to look again.
     *
     * [passes], [settings] and [state] are the same ones [decide] was given,
     * and they are needed for one case only: having announced a pass, the
     * planner must still wake for another channel's pass that falls due before
     * this one is over. See the [Decision.Announce] branch.
     */
    /**
     * The soonest moment another channel needs an alert, or null if none does.
     *
     * Channels that have already spoken and are still inside their interval are
     * skipped: waking for one would find it rate-limited and achieve nothing.
     * Anything else is worth a wake, even if the pass turns out not to be
     * announced when we get there - one spare wake is cheap and a missed pass
     * is what this exists to prevent.
     */
    private fun soonestElsewhere(
        passes: List<SatellitePass>,
        announced: PassChannel,
        now: Long,
        settings: UserSettings,
        state: AlertState,
    ): Long? = passes
        .asSequence()
        .filter { it.channel != announced }
        .filter {
            channelFreeAt(it.channel, settings, state) <= it.peaksAt - settings.passAlertLeadMillis
        }
        .map { it.peaksAt - settings.passAlertLeadMillis }
        .filter { it > now }
        .minOrNull()

    /** When [channel] may next speak, given what it last said. */
    private fun channelFreeAt(
        channel: PassChannel,
        settings: UserSettings,
        state: AlertState,
    ): Long = lastAlertAt(channel, state)?.plus(intervalMillis(channel, settings)) ?: Long.MIN_VALUE

    fun nextWake(
        decision: Decision,
        now: Long,
        passes: List<SatellitePass>,
        settings: UserSettings,
        state: AlertState,
    ): Long = when (decision) {
        is Decision.Nothing -> now + DORMANT_INTERVAL_MILLIS
        // Exactly when the channel is free again; there is nothing to do until
        // then and nothing to gain from waking earlier.
        is Decision.RateLimited -> maxOf(decision.until, now + 1)
        /*
         * Nothing was decided, so nothing about the plan changes - which means
         * handing back the check already planned, not the current instant.
         *
         * `now` read as "wake immediately": Refresh.minutesUntil floors at a
         * minute, so a Resting poll asked to be woken sixty seconds later, and
         * a run of them would have spun. Nothing reaches this today, because
         * PassPoller builds Resting itself and returns the held instant without
         * calling here - but a branch that turns a no-op into a one-minute loop
         * is worth not leaving lying about.
         */
        is Decision.Resting -> state.nextPassCheckAt ?: (now + DORMANT_INTERVAL_MILLIS)

        /*
         * Both of these are deadlines rather than cadences - be there by the
         * lead, be there before the elements go stale - so they are asked for
         * early enough that the alarm's own slack still lands in time. Asking
         * for the moment itself delivered an alert two minutes before ALOS
         * (DAICHI) rose, against a lead of thirty. See Delivery.
         */
        is Decision.TooSoon -> {
            val alertAt = decision.pass.peaksAt - settings.passAlertLeadMillis
            val freshenAt = decision.pass.risesAt - FRESHEN_BEFORE_MILLIS
            val target = minOf(alertAt, if (freshenAt > now) freshenAt else alertAt)
            maxOf(Delivery.askAt(target, now), now + 1)
        }

        /*
         * Waiting out the pass, unless another channel needs speaking to first.
         *
         * The pass just announced is dealt with and nothing more will change
         * about it, so the default is to sleep until it is over and then
         * consider what is behind it. But the channels have separate budgets
         * precisely so that one does not crowd out another, and a pass on a
         * different channel can fall due while this one is still in the sky -
         * its alert is thirty minutes before *its* peak, which can easily be
         * before this pass sets.
         *
         * Found on real hardware, 2026-08-25: a rocket body announced at
         * 03:00:53 armed the next wake for its own set at 03:34, sleeping past
         * 03:18:24 when the station's pass came due. The station was never
         * mentioned.
         */
        is Decision.Announce -> {
            // Waiting out this pass is a cadence and arrives when it arrives.
            // The other channel's alert is a deadline, so it gets the early ask.
            val afterThisPass = decision.pass.setsAt + 1
            val elsewhere = soonestElsewhere(passes, decision.pass.channel, now, settings, state)
                ?.let { Delivery.askAt(it, now) }
            minOf(afterThisPass, elsewhere ?: afterThisPass)
        }

        is Decision.AlreadyTold -> decision.pass.setsAt + 1

        /*
         * Whichever comes first: the end of the pass, or the end of the
         * silence. A pass still in the sky when quiet hours lift is a pass
         * worth mentioning then - the user declined to be woken at 06:30, not
         * to be told at 07:00 about something overhead until 07:04. Waiting the
         * pass out unconditionally, as this used to, threw that away.
         *
         * The pass ends first for most of any quiet period, and then this is
         * the old behaviour exactly. Only the last half-hour of the silence
         * differs, which is the window where anything was being lost.
         */
        is Decision.InQuietHours ->
            minOf(decision.pass.setsAt + 1, Delivery.askAt(decision.until, now))

        is Decision.TooCloudy -> decision.pass.setsAt + 1
    }
}
