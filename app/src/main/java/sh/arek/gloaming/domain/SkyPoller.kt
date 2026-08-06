package sh.arek.gloaming.domain

import java.util.Calendar
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WeatherSource

/** Posts the user-facing notification. Local, always: nothing pushes to this app. */
interface AuroraNotifier {
    /**
     * @param cloud the forecast over the observer, or null when it could not be
     *   reached - in which case the notification says cloud cover is unknown
     *   rather than implying a clear sky.
     */
    fun notifyAurora(probability: Int, status: SkyStatus, cloud: CloudCover?)
}

/** Whether it is dark enough where the observer stands for anything to show. */
fun interface DarknessCheck {
    fun isDark(latitude: Double, longitude: Double, atMillis: Long): Boolean
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
    private val darkness: DarknessCheck = DarknessCheck(Twilight::isDark),
    /** Minutes since local midnight, for quiet hours. The device's own zone. */
    private val localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
    private val minimumAlertIntervalMillis: Long = DEFAULT_MINIMUM_INTERVAL,
) {

    suspend fun poll(): PollOutcome {
        val user = settings.read()
        val previous = state.read()

        val outcome = runCatching { pollOnce(user, previous) }
            .getOrElse { PollOutcome.Failed(it.message ?: it::class.java.simpleName) }

        record(outcome, previous)
        return outcome
    }

    private suspend fun pollOnce(user: UserSettings, previous: AlertState): PollOutcome {
        if (!user.alertsEnabled) return PollOutcome.AlertsDisabled

        val latitude = user.latitude ?: return PollOutcome.NoLocation
        val longitude = user.longitude ?: return PollOutcome.NoLocation
        // The grid cell rather than the raw position, so a poll before unlock -
        // which only has the cell - produces exactly the same answer as one
        // after it.
        val cell = user.cell ?: return PollOutcome.NoLocation

        val manifest = manifests.refresh()
        val snapshot = sky.refresh(manifest)
        val grid = snapshot.grid ?: return PollOutcome.NoGrid

        val at = now()
        val probability = grid.probabilityAt(cell.latitude.toDouble(), cell.longitude.toDouble())

        val decision = AlertDecision.evaluate(
            probability = probability,
            isDark = darkness.isDark(latitude, longitude, at),
            localMinuteOfDay = localMinuteOfDay(at),
            settings = user,
            state = previous,
            now = at,
            minimumIntervalMillis = minimumAlertIntervalMillis,
        )
        if (decision != AlertDecision.Notify) return PollOutcome.Quiet(probability, decision)

        // Only now, with everything free already satisfied, is a radio wake
        // worth paying for.
        val cloud = cloudCover(manifest.endpoints[ENDPOINT_WEATHER], user, latitude, longitude)
        val gated = AlertDecision.cloudGate(cloud, user)
        if (gated != AlertDecision.Notify) return PollOutcome.Quiet(probability, gated)

        notifier.notifyAurora(probability, snapshot.status, cloud)
        return PollOutcome.Notified(probability)
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
    private fun record(outcome: PollOutcome, previous: AlertState) {
        val timestamp = now()
        val threshold = settings.read().threshold

        val next = when (outcome) {
            is PollOutcome.Notified -> previous.copy(
                lastAlertAt = timestamp,
                lastAlertProbability = outcome.probability,
                armed = false,
                lastPollAt = timestamp,
                lastProbability = outcome.probability,
                lastError = null,
            )

            is PollOutcome.Quiet -> previous.copy(
                armed = previous.armed ||
                    AlertDecision.shouldReArm(outcome.probability, threshold),
                lastPollAt = timestamp,
                lastProbability = outcome.probability,
                lastError = null,
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
        /** A storm is one event, not forty. */
        const val DEFAULT_MINIMUM_INTERVAL: Long = 6 * 60 * 60 * 1000L

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
