package sh.arek.gloaming.data

/**
 * What the user has chosen.
 *
 * Location is stored as plain numbers the user typed, and never leaves the
 * device. Manual entry is a first-class input rather than a fallback: the
 * audience denies location by default, the app does not need the permission,
 * and typing a location is the only way to plan a trip ("will Shetland be clear
 * on Tuesday?").
 */
data class UserSettings(
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Local aurora probability, as a percentage, worth an alert. */
    val threshold: Int = DEFAULT_THRESHOLD,
    val alertsEnabled: Boolean = true,
    val pollIntervalMinutes: Int = DEFAULT_POLL_MINUTES,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    companion object {
        /**
         * Conservative by default. A high-latitude user wants a much higher bar
         * than someone in Kent, and a default that cries wolf is how an app
         * gets muted in its first week. Real values need a season of data to
         * calibrate against.
         */
        const val DEFAULT_THRESHOLD = 30

        /**
         * Phase 1 polls at a flat interval. Adaptive polling arrives in Phase 2
         * once the darkness and cloud inputs exist to drive it.
         */
        const val DEFAULT_POLL_MINUTES = 15
    }
}

interface SettingsStore {
    fun read(): UserSettings
    fun write(settings: UserSettings)
}

/**
 * What the alerting logic remembers between wakes, plus what the UI shows.
 *
 * @property armed false after an alert until activity falls clearly away, which
 *   is what stops a value hovering at the threshold producing a stream of
 *   notifications.
 * @property lastPollAt when a poll last completed, successfully or not. The UI
 *   needs this to distinguish "quiet" from "not looked recently" - a device
 *   dormant through a fortnight of overcast nights is correctly holding old
 *   data, and must not present it as current.
 */
data class AlertState(
    val lastAlertAt: Long? = null,
    val lastAlertProbability: Int? = null,
    val armed: Boolean = true,
    val lastPollAt: Long? = null,
    val lastProbability: Int? = null,
    val lastError: String? = null,
)

interface AlertStateStore {
    fun read(): AlertState
    fun write(state: AlertState)
}
