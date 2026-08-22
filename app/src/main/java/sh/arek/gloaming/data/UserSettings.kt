package sh.arek.gloaming.data

/**
 * A stretch of the local day during which the app stays silent.
 *
 * Minutes since local midnight, half-open: an end of 07:00 means alerts resume
 * at 07:00 rather than a minute later. Wrapping past midnight is the only case
 * anybody actually sets, so it is the case the type is built around.
 */
data class QuietHours(val startMinute: Int, val endMinute: Int) {

    /**
     * Note that a period whose start and end are equal covers nothing rather
     * than everything. "Quiet from 23:00 until 23:00" is a contradiction, and
     * reading it as a whole day of silence would mute the app permanently
     * through a plausible slip in a time picker.
     */
    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) {
            minuteOfDay >= startMinute && minuteOfDay < endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
}

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
    /**
     * Only a fallback. How often the app really looks is worked out from the
     * sky - nothing while the sun is up, every few minutes when the solar wind
     * turns southward - and this stands in only when there is no position to
     * work that out from.
     */
    val pollIntervalMinutes: Int = DEFAULT_POLL_MINUTES,
    /**
     * Whether to keep alerting after a reboot, before the device is unlocked.
     *
     * Off by default, and deliberately so. Enabling it copies the observer's
     * grid cell and alert settings into device-protected storage, which is
     * readable by the system before first unlock - the very state a scheduled
     * reboot exists to return the device to. Only the rounded cell is stored,
     * never the precise position, so the cost is a region rather than an
     * address; but it is a real cost and the user makes that choice.
     */
    val alertsBeforeUnlock: Boolean = false,
    /**
     * Total cloud cover, as a percentage, above which an alert is held back.
     *
     * Permissive by default. Failing to mention a real aurora is the app not
     * doing its job, while an alert under broken cloud costs a look out of the
     * window - and hysteresis and rate limiting already control the volume.
     */
    val cloudTolerance: Int = DEFAULT_CLOUD_TOLERANCE,
    /**
     * Whether cloud is consulted at all. Switching it off also stops the one
     * request that carries the observer's position to a third party, which is
     * the other reason someone might want it off.
     */
    val cloudGateEnabled: Boolean = true,
    /** Null when the user has set none, which is the default. */
    val quietHours: QuietHours? = null,
    /**
     * Whether an exceptional event may break quiet hours. Opt-in, and off: the
     * user decides what is worth being woken for, not the app.
     */
    val quietHoursOverride: Boolean = false,
    /**
     * Red on black, for reading the screen outside without losing twenty
     * minutes of dark adaptation to it.
     */
    val nightVision: Boolean = false,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    /** The observer position at the grid's own resolution, if one is set. */
    val cell: GridCell?
        get() = if (latitude != null && longitude != null) {
            GridCell.of(latitude, longitude)
        } else {
            null
        }

    companion object {
        /**
         * Conservative by default. A high-latitude user wants a much higher bar
         * than someone in Kent, and a default that cries wolf is how an app
         * gets muted in its first week. Real values need a season of data to
         * calibrate against.
         */
        const val DEFAULT_THRESHOLD = 30

        /**
         * The fallback interval, used only when there is no position to work a
         * schedule out from. Everything else is decided by
         * [sh.arek.gloaming.domain.PollingPolicy] from the sky itself.
         */
        const val DEFAULT_POLL_MINUTES = 15

        /**
         * Below total overcast, because a sky at 80% usually has gaps and the
         * aurora is bright enough to work through them. Like the alert
         * threshold, this wants a season of real data to calibrate against.
         */
        const val DEFAULT_CLOUD_TOLERANCE = 80
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
    /**
     * Whether the last reading came from the UK magnetometer layer rather than
     * the global grid. Kept so the screen can attribute it: AuroraWatch's terms
     * require their credit beside their data, and the notification alone does
     * not discharge that - a quiet poll posts nothing, yet its number sits on
     * the screen until the next one replaces it.
     */
    val lastFromAuroraWatch: Boolean = false,
)

interface AlertStateStore {
    fun read(): AlertState
    fun write(state: AlertState)
}
