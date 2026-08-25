package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SatelliteRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WeatherSource
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

    suspend fun poll(): PassPollResult {
        val at = now()
        return runCatching { pollOnce(at) }.getOrElse {
            PassPollResult(
                decision = PassPlanner.Decision.Nothing,
                nextWakeAtMillis = at + PassPlanner.DORMANT_INTERVAL_MILLIS,
                failure = it.message ?: it::class.java.simpleName,
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
        val upcoming = PassPlanner.visible(
            passes = predictions(
                at,
                latitude,
                longitude,
                isDarkAt,
                user.minimumPassElevationDegrees.toDouble(),
            ),
            isDarkAt = isDarkAt,
        )

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
    private suspend fun predictions(
        at: Long,
        latitude: Double,
        longitude: Double,
        isDarkAt: (Long) -> Boolean,
        bar: Double,
    ): List<SatellitePass> {
        val held = satellites.cached()
        val fromHeld = held?.let { predict(it, latitude, longitude, at, bar) } ?: emptyList()
        val soonest = PassPlanner.next(PassPlanner.visible(fromHeld, isDarkAt), at)
            ?.let { it.risesAt - at }

        if (!PassPlanner.shouldFreshen(satellites.cachedAgeMillis(), soonest)) return fromHeld

        val fresh = runCatching { satellites.refresh(manifests.refresh()) }.getOrNull()
        return if (fresh == null || fresh == held) fromHeld
        else predict(fresh, latitude, longitude, at, bar)
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
        at: Long,
        bar: Double,
    ): List<SatellitePass> {
        val until = at + PassPlanner.HORIZON_MILLIS
        val ends = document.trains.flatMap { listOf(it.lead, it.tail) }.toSet()

        val singles = document.satellites
            .filterNot { it.catalogue in ends }
            .flatMap { PassFinder.passes(it, latitude, longitude, at, until, bar) }

        return singles + TrainFinder.passes(document, latitude, longitude, at, until, bar)
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
