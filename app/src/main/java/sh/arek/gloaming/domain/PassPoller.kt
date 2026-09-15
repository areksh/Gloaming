package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SatelliteRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.describe
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.PassDescription
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.satellites.SatellitesDocument
import sh.arek.gloaming.satellites.TrainFinder

/** Posts the pass notification. Local, like everything else the app announces. */
interface PassNotifier {
    fun notifyPass(pass: SatellitePass, description: PassDescription)
}

/**
 * Asks for a pass to be followed live when it comes round.
 *
 * Separate from [PassNotifier] because it is a different promise: the notifier
 * has spoken by the time it returns, whereas this books something for later and
 * may be refused by the system when the moment arrives.
 */
fun interface PassFollower {
    fun follow(pass: SatellitePass)
}

/** Whether the sun is far enough down where the observer stands. */
fun interface DarknessAt {
    fun isDark(latitude: Double, longitude: Double, atMillis: Long): Boolean
}

/** What one pass check did, and when to come back. */
data class PassPollResult(
    val decision: PassPlanner.Decision,
    val nextWakeAtMillis: Long,
    val failure: String? = null,
)

/**
 * One wake's worth of satellite passes.
 *
 * The aurora poller's sibling, and deliberately only that: they share the gates
 * and the channel taxonomy and no machinery. Aurora asks an upstream what the
 * sky is doing now; this works out what the sky will do from elements it
 * already holds, which is a different problem with a different shape.
 *
 * Elements are freshened before a pass rather than on a cadence. There is no
 * point polling for something deterministic - but a pass predicted from a
 * two-hourly cron that landed badly is worth re-deriving, because reboosts and
 * debris avoidance move a pass by tens of seconds to minutes.
 *
 * Nothing here throws. A background wake that crashes is worse than one that
 * records a failure and comes back.
 */
class PassPoller(
    private val manifests: ManifestRepository,
    private val satellites: SatelliteRepository,
    private val weather: WeatherSource,
    private val settings: SettingsStore,
    private val state: AlertStateStore,
    private val notifier: PassNotifier,
    private val follower: PassFollower = PassFollower {},
    private val now: () -> Long = System::currentTimeMillis,
    private val darkness: DarknessAt = DarknessAt(Twilight::isDark),
    private val localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
) {

    private companion object {
        /**
         * How far ahead the first sweep looks.
         *
         * The same three hours `PassPlanner.FRESHEN_BEFORE_MILLIS` uses for "a
         * pass is close enough to be worth spending a fetch on", because it is
         * the same judgement: near enough to act on.
         */
        val NEAR_HORIZON_MILLIS = PassPlanner.FRESHEN_BEFORE_MILLIS

        /**
         * What the sweep may spend before it gives up its turn.
         *
         * The poll runs in a broadcast Android kills at sixty seconds, and the
         * kill takes the reschedule with it - no bookmark written, no alarm
         * armed, the provisional one left standing, and the next wake repeating
         * the work that was just killed. Twelve times in one night, measured.
         *
         * A timeout around the call cannot help: the sweep is a tight loop with
         * no suspension point for cancellation to land on, which is why the one
         * in `Refresh` never fired. This is the bound that actually holds.
         */
        const val SCAN_BUDGET_MILLIS = 18_000L

        /**
         * How soon to come back when this wake learned nothing.
         *
         * Covers both a sweep that ran out of time and a check that threw, and
         * they are the same case: the app does not know the sky is empty, it
         * knows it failed to find out. Both used to come back as
         * `Decision.Nothing` carrying the dormant interval - the answer for a
         * sky that really is empty - so one transient fault at dusk took six
         * hours of passes with it, silently.
         */
        const val RETRY_AFTER_NO_ANSWER_MILLIS = 15 * 60_000L
    }

    suspend fun poll(): PassPollResult {
        val at = now()
        return runCatching { pollOnce(at) }.getOrElse {
            PassPollResult(
                decision = PassPlanner.Decision.Nothing,
                nextWakeAtMillis = at + RETRY_AFTER_NO_ANSWER_MILLIS,
                failure = it.describe(),
            )
        }
    }

    private suspend fun pollOnce(at: Long): PassPollResult {
        val user = settings.read()
        val latitude = user.latitude
        val longitude = user.longitude

        if (!user.alertsEnabled || !user.passAlertsEnabled || latitude == null || longitude == null) {
            return dormant(at)
        }

        val held = state.read()
        val due = held.nextPassCheckAt
        if (due != null && at < due) {
            return PassPollResult(PassPlanner.Decision.Resting, due)
        }

        val isDarkAt = { instant: Long -> darkness.isDark(latitude, longitude, instant) }
        val scan = predictions(
            at = at,
            latitude = latitude,
            longitude = longitude,
            isDarkAt = isDarkAt,
            bar = user.minimumPassElevationDegrees.toDouble(),
            user = user,
            held = held,
            deadline = at + SCAN_BUDGET_MILLIS,
        )

        // A sweep that ran out of time says nothing, rather than saying what it
        // managed to see. Half a catalogue is not a quiet sky: announcing from
        // it could speak about a later pass while an earlier one sat unread,
        // and the channel's rate limit would then hold its tongue about the one
        // that mattered. Better to come back and look properly.
        if (!scan.complete) {
            return PassPollResult(
                decision = PassPlanner.Decision.Resting,
                nextWakeAtMillis = at + RETRY_AFTER_NO_ANSWER_MILLIS,
                failure = "the pass sweep ran out of time; nothing decided this wake",
            )
        }

        val upcoming = PassPlanner.visible(passes = scan.passes, isDarkAt = isDarkAt)

        val next = PassPlanner.chooseNext(upcoming, at, user, held)
        // The forecast is only worth a radio wake once a pass is close enough
        // to speak about, which is the same reason the aurora poll leaves it
        // until last.
        val closeEnough = next != null && at >= next.peaksAt - user.passAlertLeadMillis
        val cloud = if (closeEnough) cloudCover(user, latitude, longitude) else null

        val decision = PassPlanner.decide(
            passes = upcoming,
            now = at,
            settings = user,
            state = held,
            localMinuteOfDay = localMinuteOfDay(at),
            cloud = cloud,
        )

        val announced = decision as? PassPlanner.Decision.Announce
        val channel = announced?.pass?.channel
        announced?.let { (pass) ->
            notifier.notifyPass(
                pass = pass,
                description = PassDescription.of(pass, observerIsDark = true),
            )
        }

        // Only the station, and only when the user has said so. The bright
        // catalogue cannot reach here at all - see PassPlanner.shouldFollow.
        val followed = announced?.pass
            ?.takeIf { channel != null && PassPlanner.shouldFollow(channel, user) }
            ?.also { runCatching { follower.follow(it) } }

        val wake = PassPlanner.nextWake(decision, at, upcoming, user, held)
        state.write(
            held.copy(
                lastPassPeakAt = announced?.pass?.peaksAt ?: held.lastPassPeakAt,
                lastPassCatalogue = announced?.pass?.catalogue ?: held.lastPassCatalogue,
                // Only the channel that spoke starts its clock again; the other
                // keeps whatever budget it had.
                lastIssAlertAt =
                    if (channel == PassChannel.Iss) at else held.lastIssAlertAt,
                lastBrightAlertAt =
                    if (channel == PassChannel.Bright) at else held.lastBrightAlertAt,
                lastTrainAlertAt =
                    if (channel == PassChannel.Trains) at else held.lastTrainAlertAt,
                nextPassCheckAt = wake,
                // Written as well as scheduled: an alarm does not survive a
                // reboot, and losing a follow the user has already acted on is
                // worst at exactly the moment it matters.
                followingCatalogue = followed?.catalogue ?: held.followingCatalogue,
                followingPeakAt = followed?.peaksAt ?: held.followingPeakAt,
                followingRisesAt = followed?.risesAt ?: held.followingRisesAt,
            ),
        )
        return PassPollResult(decision, wake)
    }

    /**
     * The passes ahead, from elements freshened only when one is close.
     *
     * The held copy answers "how soon is the next pass", which is what decides
     * whether a fetch is worth making at all - so a dormant device with nothing
     * coming makes no request. The prediction is redone only if the fetch
     * actually brought back something different.
     */
    /**
     * What one sweep of the catalogue found, and whether it got to the end.
     *
     * Incomplete is not an error and not an empty sky. It means the answer is
     * not known yet, which is a different thing from knowing there is nothing -
     * and the caller has to keep them apart, because one of them is a reason to
     * sleep for six hours.
     */
    private data class Scan(val passes: List<SatellitePass>, val complete: Boolean)

    private suspend fun predictions(
        at: Long,
        latitude: Double,
        longitude: Double,
        isDarkAt: (Long) -> Boolean,
        bar: Double,
        user: UserSettings,
        held: AlertState,
        deadline: Long,
    ): Scan {
        val document = satellites.cached()
        val fromHeld = document
            ?.let { staged(it, latitude, longitude, at, bar, isDarkAt, user, held, deadline) }
            ?: Scan(emptyList(), complete = true)
        if (!fromHeld.complete) return fromHeld

        val soonest = PassPlanner.next(PassPlanner.visible(fromHeld.passes, isDarkAt), at)
            ?.let { it.risesAt - at }

        if (!PassPlanner.shouldFreshen(satellites.cachedAgeMillis(), soonest)) return fromHeld

        val fresh = runCatching { satellites.refresh(manifests.refresh()) }.getOrNull()
        return if (fresh == null || fresh == document) fromHeld
        else staged(fresh, latitude, longitude, at, bar, isDarkAt, user, held, deadline)
    }

    /**
     * The near hours first, and the rest only if the near hours settle nothing.
     *
     * The sweep costs time in proportion to the window: the whole horizon is
     * 175 objects at thirty second steps, which measures 7.4 seconds on a woken
     * Pixel 8a and over 45 in Doze, where the CPU is throttled. Most of that is
     * wasted most of the time, because the pass that matters is usually the next
     * one and it is usually soon.
     *
     * The condition for stopping early is the delicate part, and it is not "the
     * near window has a pass in it". A pass can be there and count for nothing -
     * invisible because it is daylight then, or on a channel still inside its
     * rate limit, both of which `chooseNext` weighs and discards. Stopping on
     * one of those would sleep straight past the real pass behind it. So the
     * test is whether the planner would actually *choose* something in the near
     * window; if it would, nothing further out can be sooner, and the rest of
     * the horizon cannot change the answer.
     *
     * When it would not, the far window is swept and added. The two are
     * adjacent rather than overlapping - `PassFinder` reports passes that
     * *begin* inside the window it is given, so a pass under way at the seam
     * belongs to the near sweep and is not counted twice.
     *
     * DORMANT_INTERVAL_MILLIS is six hours against a twelve hour horizon, and
     * that margin is what makes the whole scheme safe: the app only commits to
     * a long sleep having seen the full horizon, never the near window alone.
     */
    private fun staged(
        document: SatellitesDocument,
        latitude: Double,
        longitude: Double,
        at: Long,
        bar: Double,
        isDarkAt: (Long) -> Boolean,
        user: UserSettings,
        held: AlertState,
        deadline: Long,
    ): Scan {
        val nearEnd = at + NEAR_HORIZON_MILLIS
        val near = predict(document, latitude, longitude, at, nearEnd, bar, deadline)
        if (!near.complete) return near

        val choosable = PassPlanner.chooseNext(
            PassPlanner.visible(near.passes, isDarkAt),
            at,
            user,
            held,
        )
        if (choosable != null) return near

        val far = predict(
            document, latitude, longitude, nearEnd, at + PassPlanner.HORIZON_MILLIS, bar, deadline,
        )
        return Scan(near.passes + far.passes, far.complete)
    }

    /**
     * Every pass in the horizon, single objects and trains alike.
     *
     * The two ends of a train are dropped from the ordinary sweep. Their
     * elements are published so the train can be worked out, but they are also
     * two Starlinks, and without this the same launch would be announced twice
     * over - once as a train and once as an unremarkable bright satellite a few
     * minutes later.
     */
    private fun predict(
        document: SatellitesDocument,
        latitude: Double,
        longitude: Double,
        from: Long,
        until: Long,
        bar: Double,
        deadline: Long,
    ): Scan {
        val ends = document.trains.flatMap { listOf(it.lead, it.tail) }.toSet()
        val found = mutableListOf<SatellitePass>()

        // Checked between objects rather than inside the sweep of one, which
        // keeps PassFinder free of a clock it has no business knowing about.
        // One object is a fraction of a second even throttled, so the grain is
        // fine enough to stop well short of the sixty a broadcast gets.
        for (satellite in document.satellites) {
            if (now() >= deadline) return Scan(found, complete = false)
            if (satellite.catalogue in ends) continue
            found += PassFinder.passes(satellite, latitude, longitude, from, until, bar)
        }

        if (now() >= deadline) return Scan(found, complete = false)
        return Scan(
            found + TrainFinder.passes(document, latitude, longitude, from, until, bar),
            complete = true,
        )
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

    private fun dormant(at: Long) = PassPollResult(
        decision = PassPlanner.Decision.Nothing,
        nextWakeAtMillis = at + PassPlanner.DORMANT_INTERVAL_MILLIS,
    )
}
