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
    /**
     * Whether to be told about satellite passes.
     *
     * Separate from [alertsEnabled], which covers aurora: the two are different
     * kinds of event and somebody may well want one without the other. The
     * notification channels split them again, so either can be silenced from
     * the system settings without coming back into the app.
     */
    val passAlertsEnabled: Boolean = true,
    /**
     * The shortest gap between two aurora alerts, in minutes.
     *
     * A storm is one event, not forty. Hysteresis stops a reading hovering at
     * the threshold from firing repeatedly, but a genuine storm that climbs all
     * night would still qualify over and over without this.
     */
    val auroraAlertIntervalMinutes: Int = DEFAULT_ALERT_INTERVAL_MINUTES,
    /**
     * The shortest gap between two space-station alerts, in minutes.
     *
     * Separate from the aurora one, and from the bright-satellite one, because
     * they are different appetites: an aurora is rare and worth waking for, the
     * station goes over most nights, and the rest go over constantly.
     */
    val issPassAlertIntervalMinutes: Int = DEFAULT_ALERT_INTERVAL_MINUTES,
    /**
     * The shortest gap between two bright-satellite alerts, in minutes.
     *
     * This is the one that needs it most. A clear night over Edinburgh has
     * upwards of forty passes above thirty degrees, nearly all rocket bodies,
     * and an app that mentioned each of them would be muted within the week.
     */
    val brightPassAlertIntervalMinutes: Int = DEFAULT_ALERT_INTERVAL_MINUTES,
    /**
     * How often at most a Starlink train may interrupt.
     *
     * Its own answer rather than a share of the bright catalogue's, because a
     * train is not a nightly event: a launch is a train for a few nights and
     * then never again, and a rate limit built for objects that come over every
     * night would routinely swallow the one that does not.
     */
    val trainPassAlertIntervalMinutes: Int = DEFAULT_ALERT_INTERVAL_MINUTES,
    /**
     * Whether every station pass is followed live without being asked.
     *
     * The station only. Not an oversight and not a default to be relaxed later:
     * a foreground service per pass is a persistent-notification generator, and
     * where the station goes over most nights the bright catalogue goes over
     * constantly. Offering this across `visual` would be offering to run a
     * foreground service for most of every clear night.
     *
     * Off to begin with, so the live tracker is something the user reaches for
     * from a notification rather than something that starts happening to them.
     */
    val followStationPassesAutomatically: Boolean = false,
    /**
     * How long a watch the user started runs before it stops itself.
     *
     * See [WatchDuration]. The span is theirs; the ceiling is not.
     */
    val watchDuration: WatchDuration = WatchDuration.DEFAULT,
    /**
     * Whether the mirror in device-encrypted storage keeps the precise position
     * rather than the grid cell.
     *
     * Off, and it should stay off for most people. Device-encrypted storage is
     * readable before the device has been unlocked since boot - that is the
     * entire reason it exists - so it is protected by a device-bound key rather
     * than by the user's credential, and a phone that is lost or taken before
     * anyone unlocks it still yields what is in there. Reducing the mirror to a
     * whole degree means what it yields is a region about 111 km across rather
     * than an address.
     *
     * The reason to turn it on anyway is passes, not aurora. The aurora grid is
     * looked up at whole degrees regardless, so rounding costs an aurora alert
     * nothing at all. A pass prediction is different: 111 km moves the rise and
     * set by tens of seconds and the peak by whole degrees of elevation, which
     * is the difference between "look south-west at 22:14" being true and being
     * approximately true.
     *
     * Only meaningful with [alertsBeforeUnlock] on; nothing is mirrored at all
     * without it.
     */
    val preciseLocationBeforeUnlock: Boolean = false,
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

        /**
         * Six hours between alerts on any one channel, until the user says
         * otherwise.
         *
         * How often an app may interrupt someone is the user's call, not the
         * app's - the same reasoning as quiet hours. This is only where the
         * three channels start, and like every other default here it wants a
         * season of real nights to calibrate against.
         */
        const val DEFAULT_ALERT_INTERVAL_MINUTES = 6 * 60

        /** The choices offered, in minutes: hourly through to twice a night. */
        val ALERT_INTERVAL_CHOICES = listOf(60, 180, 360, 720)
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
    /**
     * Roughly when the pass last announced was highest, with the object it
     * belonged to.
     *
     * Together these identify a pass well enough to avoid announcing it twice.
     * Only roughly, and deliberately: the peak is re-derived on each wake from
     * a different search origin and, once the elements are freshened, from
     * different elements - which moves it by milliseconds in the first case and
     * by up to minutes in the second. Matching on the exact instant would
     * announce the same pass again every time it shifted.
     */
    val lastPassPeakAt: Long? = null,
    val lastPassCatalogue: Int? = null,
    /**
     * When each pass channel last spoke.
     *
     * Kept per channel rather than as one timestamp, because the whole point of
     * the channels is that somebody may want the station often and the rocket
     * bodies rarely. A single figure would let either one spend the other's
     * budget.
     */
    val lastIssAlertAt: Long? = null,
    val lastBrightAlertAt: Long? = null,
    val lastTrainAlertAt: Long? = null,
    /**
     * When the pass planner next wants to be asked.
     *
     * Passes are deterministic, so there is nothing to poll for: the planner
     * names the next moment that matters and every wake before it skips the
     * work entirely. That matters because working out a night of passes for the
     * whole bright catalogue is seconds of arithmetic, not a network call, and
     * doing it on an aurora poll every five minutes would be a battery
     * complaint rather than a feature.
     */
    val nextPassCheckAt: Long? = null,
    /**
     * The pass the live tracker has been asked to follow, if any.
     *
     * Held rather than carried in the alarm alone so that a reboot between
     * asking and the pass does not silently lose it - which would be the worst
     * possible moment to drop a request, because the user has already decided
     * to go outside.
     *
     * The peak is what identifies it, for the same reason [lastPassPeakAt]
     * does: the service re-derives the pass from the freshest elements it
     * holds, and that moves the moment by up to minutes.
     */
    val followingCatalogue: Int? = null,
    val followingPeakAt: Long? = null,
    /**
     * The rise of the same pass, which is a separate question from identifying
     * it: the peak says *which* pass, the rise says *when to start*. Both are
     * needed to put the request back after a reboot.
     */
    val followingRisesAt: Long? = null,
)

interface AlertStateStore {
    fun read(): AlertState
    fun write(state: AlertState)
}
