package sh.arek.gloaming.domain

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.UserSettings
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
        pass: SatellitePass?,
        now: Long,
        settings: UserSettings,
        state: AlertState,
        localMinuteOfDay: Int,
        cloud: CloudCover?,
    ): Decision {
        if (pass == null) return Decision.Nothing
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
