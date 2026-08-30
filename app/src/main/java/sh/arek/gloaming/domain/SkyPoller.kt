package sh.arek.gloaming.domain

import java.util.Calendar
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.aurora.Likelihood
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.VerdictChannel
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WeatherSource
import sh.arek.gloaming.describe

/** Posts the user-facing notification. Local, always: nothing pushes to this app. */
interface AuroraNotifier {
    /**
     * @param likelihood how likely an aurora is here, and which reading said so
     *   - the notification attributes it, since AuroraWatch's terms require
     *   attribution and since "a UK magnetometer says this" carries more weight
     *   than a global model.
     * @param cloud the forecast over the observer, or null when it could not be
     *   reached - in which case the notification says cloud cover is unknown
     *   rather than implying a clear sky.
     */
    /**
     * @param threshold the bar in force at the moment of the alert, carried so
     *   the "was it worth it?" record says what the alert was judged against -
     *   the setting can be changed the next morning, and the record has to mean
     *   what it meant on the night.
     */
    fun notifyAurora(
        likelihood: Likelihood,
        status: SkyStatus,
        cloud: CloudCover?,
        threshold: Int? = null,
    )
}

/** How dark it is where the observer stands, and when that next changes. */
fun interface DarknessCheck {
    fun at(latitude: Double, longitude: Double, atMillis: Long): Darkness
}

/** What one poll did, for logging and for the UI to show. */
sealed interface PollOutcome {
    data class Notified(val probability: Int) : PollOutcome
    data class Quiet(val probability: Int, val reason: AlertDecision.Result) : PollOutcome
    data object NoLocation : PollOutcome
    data object AlertsDisabled : PollOutcome
    data object NoGrid : PollOutcome
    data class Failed(val reason: String) : PollOutcome
}

/**
 * What the poll did, and when to come back.
 *
 * The schedule comes out of the poll rather than being asked for separately
 * because the poll is what learned the things it depends on - and because the
 * next wake has to be armed even when the poll failed, from whatever is known.
 */
data class PollResult(
    val outcome: PollOutcome,
    val next: PollPlan,
    /** Whether storm mode should be running after this poll. */
    val storm: Boolean = false,
)

/**
 * One wake: fetch, look up, decide, and notify if warranted.
 *
 * Every step happens on the device. The server does not know where the user is,
 * so it could not make this decision even if it wanted to - which is the point.
 *
 * The gates run cheapest first. Threshold, darkness, hysteresis, quiet hours and
 * rate limiting all cost nothing, and between them they end most polls; only a
 * poll that has already decided to notify pays for a cloud forecast. That
 * ordering is why the darkness gate saves battery rather than merely improving
 * the decision.
 *
 * Nothing here throws. A background wake that crashes is worse than one that
 * records a failure and tries again later, and the failure is surfaced in the
 * UI rather than swallowed.
 */
class SkyPoller(
    private val manifests: ManifestRepository,
    private val sky: SkyRepository,
    private val weather: WeatherSource,
    private val settings: SettingsStore,
    private val state: AlertStateStore,
    private val notifier: AuroraNotifier,
    private val now: () -> Long = System::currentTimeMillis,
    private val darkness: DarknessCheck = DarknessCheck(Twilight::darkness),
    /** Minutes since local midnight, for quiet hours. The device's own zone. */
    private val localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
    /** Whether storm mode is running now, which decides which bar it has to clear. */
    private val stormRunning: () -> Boolean = { false },
) {

    suspend fun poll(): PollResult {
        val user = settings.read()
        val previous = state.read()
        val at = now()

        val attempt = runCatching { pollOnce(user, previous, at) }
            .getOrElse { Attempt(PollOutcome.Failed(it.describe())) }

        record(attempt, previous)
        return PollResult(
            outcome = attempt.outcome,
            next = nextPoll(user, at, attempt.status),
            storm = storm(user, at, attempt.probability),
        )
    }

    /** What one attempt produced, including what the schedule needs from it. */
    private class Attempt(
        val outcome: PollOutcome,
        val status: SkyStatus? = null,
        val probability: Int? = null,
        /** Which reading produced the probability, or null when none was taken. */
        val source: Likelihood.Source? = null,
        /** The sky as it was, where an alert went out about it. */
        val conditions: Conditions? = null,
    )

    private suspend fun pollOnce(user: UserSettings, previous: AlertState, at: Long): Attempt {
        if (!user.alertsEnabled) return Attempt(PollOutcome.AlertsDisabled)

        val latitude = user.latitude ?: return Attempt(PollOutcome.NoLocation)
        val longitude = user.longitude ?: return Attempt(PollOutcome.NoLocation)
        // The grid cell rather than the raw position, so a poll before unlock -
        // which only has the cell - produces exactly the same answer as one
        // after it.
        val cell = user.cell ?: return Attempt(PollOutcome.NoLocation)

        val manifest = manifests.refresh()
        val snapshot = sky.refresh(manifest)
        val status = snapshot.status
        val grid = snapshot.grid ?: return Attempt(PollOutcome.NoGrid, status)

        // One model, whichever source it came from: inside the UK a real
        // magnetometer reading can raise what the global grid says.
        val likelihood = Likelihood.evaluate(
            latitude = latitude,
            longitude = longitude,
            ovation = grid.probabilityAt(cell.latitude.toDouble(), cell.longitude.toDouble()),
            aurorawatch = status.aurorawatch,
        )
        val probability = likelihood.probability

        val decision = AlertDecision.evaluate(
            probability = probability,
            isDark = darkness.at(latitude, longitude, at) is Darkness.Now,
            localMinuteOfDay = localMinuteOfDay(at),
            settings = user,
            state = previous,
            now = at,
            // The user's own answer to how often this may interrupt them, the
            // same as quiet hours and for the same reason. It used to be a
            // constructor default nobody could reach.
            minimumIntervalMillis = user.auroraAlertIntervalMinutes.coerceAtLeast(1) * 60_000L,
        )
        if (decision != AlertDecision.Notify) {
            return Attempt(
                PollOutcome.Quiet(probability, decision), status, probability, likelihood.source,
            )
        }

        // Only now, with everything free already satisfied, is a radio wake
        // worth paying for.
        val cloud = cloudCover(manifest.endpoints[ENDPOINT_WEATHER], user, latitude, longitude)
        val gated = AlertDecision.cloudGate(cloud, user)
        if (gated != AlertDecision.Notify) {
            return Attempt(
                PollOutcome.Quiet(probability, gated), status, probability, likelihood.source,
            )
        }

        notifier.notifyAurora(likelihood, status, cloud, user.threshold)
        return Attempt(
            PollOutcome.Notified(probability),
            status,
            probability,
            likelihood.source,
            // The same conditions the notification's actions carry, kept here
            // too so the Tonight card can ask about an alert that was swiped
            // away at two in the morning. Kp and Bz live nowhere else at all.
            Conditions(
                atMillis = now(),
                channel = VerdictChannel.Aurora,
                probability = probability,
                source = likelihood.source.name,
                kp = status.kp?.value,
                bz = status.solarWind?.bz,
                cloudTotal = cloud?.total,
                cloudLow = cloud?.low,
                cloudMid = cloud?.mid,
                cloudHigh = cloud?.high,
                threshold = user.threshold,
                subject = "Aurora",
            ),
        )
    }

    /**
     * When to come back.
     *
     * Computed from whatever is known, including after a failure: darkness needs
     * no network, so even a poll that could reach nothing still sleeps through
     * the daylight rather than retrying at the storm rate all afternoon.
     *
     * Without a position there is nothing to compute, and the flat interval
     * stands in - which is also the only case where the user's own poll interval
     * is still used.
     */
    private fun nextPoll(user: UserSettings, at: Long, status: SkyStatus?): PollPlan {
        val latitude = user.latitude
        val longitude = user.longitude
        if (latitude == null || longitude == null) {
            return PollPlan(user.pollIntervalMinutes, PollingPolicy.Reason.QUIET)
        }

        return PollingPolicy.next(
            darkness = darkness.at(latitude, longitude, at),
            now = at,
            // Read from the cache rather than fetched: the schedule must not
            // cost the very radio wake it exists to save.
            cloud = weather.lastKnown(latitude, longitude),
            status = status,
        )
    }

    /**
     * Whether to watch minute by minute after this poll.
     *
     * A poll that never reached a reading leaves it alone rather than guessing:
     * false would end a display that is still going because the network
     * flickered, and true would start one on no evidence at all.
     */
    private fun storm(user: UserSettings, at: Long, probability: Int?): Boolean {
        val running = stormRunning()
        val latitude = user.latitude ?: return false
        val longitude = user.longitude ?: return false
        if (probability == null) return running

        return StormMode.shouldRun(
            running = running,
            probability = probability,
            darkness = darkness.at(latitude, longitude, at),
            cloud = weather.lastKnown(latitude, longitude),
        )
    }

    /**
     * The cloud forecast, or null for unknown - which notifies rather than
     * suppresses.
     *
     * Note that before first unlock the position here is the grid cell, a whole
     * degree, because that is all the device-protected mirror holds. The
     * forecast is correspondingly for the region rather than the spot, which is
     * the cost of the privacy choice the user made when they enabled it.
     */
    private suspend fun cloudCover(
        endpoint: String?,
        user: UserSettings,
        latitude: Double,
        longitude: Double,
    ): CloudCover? {
        if (!user.cloudGateEnabled || endpoint == null) return null
        return runCatching { weather.cloudCover(endpoint, latitude, longitude) }.getOrNull()
    }

    /**
     * Persists what happened.
     *
     * Re-arming is evaluated on every poll that produced a reading, not only on
     * quiet ones, so activity falling away between wakes still clears the latch.
     */
    private fun record(attempt: Attempt, previous: AlertState) {
        val outcome = attempt.outcome
        val timestamp = now()
        val threshold = settings.read().threshold
        val fromAuroraWatch = attempt.source == Likelihood.Source.AURORAWATCH

        val next = when (outcome) {
            is PollOutcome.Notified -> previous.copy(
                lastAlertAt = timestamp,
                lastAlertProbability = outcome.probability,
                armed = false,
                lastPollAt = timestamp,
                lastProbability = outcome.probability,
                lastError = null,
                lastFromAuroraWatch = fromAuroraWatch,
                // Replaces whatever was there. The card asks about the last
                // alert, which is the one anybody can still remember - and an
                // unanswered question from a fortnight ago is not one worth
                // holding a screen for.
                pendingVerdict = attempt.conditions?.encode() ?: previous.pendingVerdict,
            )

            is PollOutcome.Quiet -> previous.copy(
                armed = previous.armed ||
                    AlertDecision.shouldReArm(outcome.probability, threshold),
                lastPollAt = timestamp,
                lastProbability = outcome.probability,
                lastError = null,
                lastFromAuroraWatch = fromAuroraWatch,
            )

            is PollOutcome.Failed -> previous.copy(
                lastPollAt = timestamp,
                lastError = outcome.reason,
            )

            else -> previous.copy(lastPollAt = timestamp, lastError = null)
        }

        state.write(next)
    }

    companion object {
        /** The manifest key for the cloud forecast, which is never compiled in. */
        const val ENDPOINT_WEATHER = "weather"
    }
}

/**
 * Minutes since midnight in the device's own time zone.
 *
 * `Calendar` rather than `java.time`, which needs core library desugaring at
 * `minSdk 24` for the sake of one arithmetic.
 */
fun deviceLocalMinuteOfDay(atMillis: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = atMillis
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}
