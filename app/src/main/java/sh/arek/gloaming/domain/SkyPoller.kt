package sh.arek.gloaming.domain

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.AlertStateStore
import sh.arek.gloaming.data.ManifestRepository
import sh.arek.gloaming.data.SettingsStore
import sh.arek.gloaming.data.SkyRepository
import sh.arek.gloaming.data.SkyStatus

/** Posts the user-facing notification. Local, always: nothing pushes to this app. */
interface AuroraNotifier {
    fun notifyAurora(probability: Int, status: SkyStatus)
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
 * Nothing here throws. A background wake that crashes is worse than one that
 * records a failure and tries again later, and the failure is surfaced in the
 * UI rather than swallowed.
 */
class SkyPoller(
    private val manifests: ManifestRepository,
    private val sky: SkyRepository,
    private val settings: SettingsStore,
    private val state: AlertStateStore,
    private val notifier: AuroraNotifier,
    private val now: () -> Long = System::currentTimeMillis,
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

    private suspend fun pollOnce(
        user: sh.arek.gloaming.data.UserSettings,
        previous: AlertState,
    ): PollOutcome {
        if (!user.alertsEnabled) return PollOutcome.AlertsDisabled
        // The grid cell rather than the raw position, so a poll before unlock -
        // which only has the cell - produces exactly the same answer as one
        // after it.
        val cell = user.cell ?: return PollOutcome.NoLocation

        val manifest = manifests.refresh()
        val snapshot = sky.refresh(manifest)
        val grid = snapshot.grid ?: return PollOutcome.NoGrid

        val probability = grid.probabilityAt(cell.latitude.toDouble(), cell.longitude.toDouble())

        val decision = AlertDecision.evaluate(
            probability = probability,
            threshold = user.threshold,
            lastAlertAt = previous.lastAlertAt,
            lastAlertProbability = previous.lastAlertProbability,
            armed = previous.armed,
            now = now(),
            minimumIntervalMillis = minimumAlertIntervalMillis,
        )

        return if (decision == AlertDecision.Notify) {
            notifier.notifyAurora(probability, snapshot.status)
            PollOutcome.Notified(probability)
        } else {
            PollOutcome.Quiet(probability, decision)
        }
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
    }
}
