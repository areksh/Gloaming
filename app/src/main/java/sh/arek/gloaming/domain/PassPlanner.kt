package sh.arek.gloaming.domain

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.satellites.PassDescription
import sh.arek.gloaming.satellites.Prominence
import sh.arek.gloaming.satellites.SatellitePass

/** Which notification channel a pass belongs to, so either can be silenced alone. */
enum class PassChannel(val id: String) {
    Iss("iss-passes"),
    Bright("bright-satellites"),
    ;

    companion object {
        /** The station has its own channel: it is the one most people want. */
        const val ISS_CATALOGUE = 25544

        fun of(catalogue: Int): PassChannel =
            if (catalogue == ISS_CATALOGUE) Iss else Bright
    }
}

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
     * How long before the highest point the alert goes out.
     *
     * Long enough to put a coat on, short enough that the cloud forecast
     * checked alongside it still describes the sky the user will walk out into.
     */
    const val LEAD_MILLIS: Long = 30 * 60 * 1000L

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

        data class InQuietHours(val pass: SatellitePass) : Decision

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
        }
        return minutes.coerceAtLeast(1) * 60_000L
    }

    private fun lastAlertAt(channel: PassChannel, state: AlertState): Long? = when (channel) {
        PassChannel.Iss -> state.lastIssAlertAt
        PassChannel.Bright -> state.lastBrightAlertAt
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
                PassChannel.of(it.catalogue) == channel &&
                    it.peaksAt > now &&
                    it.peaksAt - LEAD_MILLIS >= free
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
        if (now < pass.peaksAt - LEAD_MILLIS) return Decision.TooSoon(pass)
        if (isAlreadyTold(pass, state)) return Decision.AlreadyTold(pass)

        val quietHours = settings.quietHours
        if (quietHours != null && quietHours.contains(localMinuteOfDay)) {
            return Decision.InQuietHours(pass)
        }

        if (settings.cloudGateEnabled && cloud != null && cloud.total > settings.cloudTolerance) {
            return Decision.TooCloudy(pass)
        }

        return Decision.Announce(pass)
    }

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
    fun nextWake(decision: Decision, now: Long): Long = when (decision) {
        is Decision.Nothing -> now + DORMANT_INTERVAL_MILLIS
        // Exactly when the channel is free again; there is nothing to do until
        // then and nothing to gain from waking earlier.
        is Decision.RateLimited -> maxOf(decision.until, now + 1)
        // Nothing was decided, so nothing about the plan changes.
        is Decision.Resting -> now

        is Decision.TooSoon -> {
            val alertAt = decision.pass.peaksAt - LEAD_MILLIS
            val freshenAt = decision.pass.risesAt - FRESHEN_BEFORE_MILLIS
            maxOf(minOf(alertAt, if (freshenAt > now) freshenAt else alertAt), now + 1)
        }

        // The pass has been dealt with, one way or another. Nothing more will
        // change about it, so wait until it is over.
        is Decision.Announce -> decision.pass.setsAt + 1
        is Decision.AlreadyTold -> decision.pass.setsAt + 1
        is Decision.InQuietHours -> decision.pass.setsAt + 1
        is Decision.TooCloudy -> decision.pass.setsAt + 1
    }
}
