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

    /**
     * How many minutes from [minuteOfDay] until the silence lifts.
     *
     * Always forwards, wrapping past midnight with the period itself, so 23:30
     * is seven and a half hours from a 07:00 end rather than sixteen and a half
     * hours back to it. Zero at the end minute, which the period does not
     * contain anyway.
     *
     * This is how the planners work out when to come back for something quiet
     * hours held back, and it is wall-clock minutes rather than elapsed ones:
     * a clock change inside the period moves the answer by an hour. That is
     * accepted rather than solved. Waking an hour early finds the silence still
     * in force and simply arms again, and waking an hour late delivers a notice
     * late rather than not at all - both of which are better than carrying a
     * time zone into a pure calculation that has no other use for one.
     */
    fun minutesUntilEnd(minuteOfDay: Int): Int =
        Math.floorMod(endMinute - minuteOfDay, MINUTES_PER_DAY)

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
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
/**
 * Where the position in [UserSettings] comes from.
 *
 * A setting rather than a one-off prompt, because both answers stay useful:
 * detecting is quicker at home, and typing is the only way to ask about
 * somewhere you are not - "will Shetland be clear on Tuesday?" is a question
 * device GPS cannot answer.
 */
/**
 * Where starred events go, if anywhere.
 *
 * Three answers rather than a switch, because "put it in my calendar" means two
 * genuinely different things to two reasonable people. Somebody whose calendar
 * syncs to an employer's server may want these entries and may very much not
 * want them leaving the phone; somebody who lives out of one calendar wants
 * them exactly where everything else is. Choosing for them would be wrong
 * either way.
 */
enum class CalendarSync(val label: String) {
    /** Nothing is written, and nothing is read. The default. */
    Off("Off"),

    /**
     * A calendar Gloaming creates and owns, local to this phone.
     *
     * Registered with no account, so nothing syncs it anywhere: it shows up
     * alongside the others in a calendar app and stays on the device. This is
     * the one that keeps the privacy posture the rest of the app has.
     */
    OwnCalendar("A Gloaming calendar on this phone"),

    /**
     * The phone's own primary calendar, wherever that syncs to.
     *
     * Worth being plain about: if that calendar is a Google or Exchange
     * account, these entries go to that server like everything else in it.
     * That is the user's existing arrangement rather than something the app
     * introduces, but it is their choice to make knowingly.
     */
    DeviceCalendar("Your default calendar"),
}

enum class LocationMode {
    /** Typed in. The default, and the one that always works. */
    Manual,

    /**
     * Read from the device: when the app is opened, when asked, or in the
     * background on an interval the user chose - see [LocationCadence] and
     * ADR-0016. Coarse in every case.
     */
    Automatic,
}

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
    /**
     * How long before a pass is highest the alert goes out, in minutes.
     *
     * Long enough to put a coat on, short enough that the cloud forecast
     * checked alongside it still describes the sky you walk out into. Whether
     * that is ten minutes or forty is a fact about the person, not about the
     * satellite: somebody in a flat with a balcony and somebody who drives out
     * of town want quite different numbers.
     *
     * It is also the window a channel picks the best pass from, so a longer
     * lead does not merely warn earlier - it chooses from more.
     */
    val passAlertLeadMinutes: Int = DEFAULT_PASS_LEAD_MINUTES,
    /**
     * The lowest a pass may get and still be worth mentioning, in degrees.
     *
     * Ten by default, which is roughly where roofs and trees stop. Somebody
     * with a clear horizon over the sea can lower it; somebody in a city with
     * buildings all round can raise it and stop being told about passes they
     * cannot possibly see.
     */
    val minimumPassElevationDegrees: Int = DEFAULT_MINIMUM_ELEVATION_DEGREES,
    /**
     * How long before a satellite rises the live tracker starts, in minutes.
     *
     * Long enough to get outside and let your eyes adjust, short enough that a
     * foreground service is not running for half an hour. Which of those
     * matters more depends entirely on how far you have to walk.
     */
    val liveFollowLeadMinutes: Int = DEFAULT_LIVE_LEAD_MINUTES,
    /**
     * The longest the live tracker may run, in minutes.
     *
     * A safety net rather than a feature: a low-orbit pass runs under ten
     * minutes, so anything approaching this is a bug holding a wake lock. It is
     * settable because the lead above is, and the two have to be able to add up
     * to something sensible.
     */
    val liveFollowMaxMinutes: Int = DEFAULT_LIVE_MAX_MINUTES,
    /**
     * How many days of the app's own log to keep. Zero keeps none.
     *
     * The log exists because logcat is the system's ring buffer and has usually
     * rolled away by the time anyone asks what happened overnight - which is
     * the only window this app works in. How long to keep it is the user's
     * call; it is their storage and their record.
     */
    val logRetentionDays: Int = DEFAULT_LOG_RETENTION_DAYS,

    /**
     * Whether the position above was typed in or detected.
     *
     * Defaults to [LocationMode.Manual]: the audience runs GrapheneOS and
     * denies location by default, so the app must be useful before it has asked
     * for anything.
     */
    val locationMode: LocationMode = LocationMode.Manual,

    /** How often a detected position is refreshed. See [LocationCadence]. */
    val locationCadence: LocationCadence = LocationCadence.OnOpen,

    /** When the position was last refreshed, or null if it never has been. */
    val locationUpdatedAt: Long? = null,

    /**
     * Set when a background refresh found the permission it needs had been
     * taken away, and so put the cadence back to [LocationCadence.OnOpen].
     * Kept so the screen can say why the choice changed under the user;
     * cleared when they choose a cadence again.
     */
    val backgroundLocationRevoked: Boolean = false,

    /**
     * Kinds of event the user does not want to see or hear about.
     *
     * Held as names rather than as the enum so the store stays a set of
     * strings, and so a kind that disappears in a later version leaves a
     * harmless orphan rather than failing to parse.
     *
     * The moon's quarters are hidden by default. There are as many of them as
     * there are new and full moons and they matter far less, so left in they
     * filled over half the calendar.
     */
    val hiddenEventKinds: Set<String> = DEFAULT_HIDDEN_EVENT_KINDS,

    /**
     * Whether every event worth seeing interrupts, or only the starred ones.
     *
     * On by default, so the calendar is useful before anybody has curated it -
     * a list that tells you nothing until you have gone through it is a list
     * most people never come back to. Turning it off makes starring the filter
     * rather than a bookmark.
     */
    val notifyAboutEveryEvent: Boolean = true,

    /**
     * Whether the constellations card lists every pattern up tonight, or the
     * handful worth leading with.
     *
     * Off by default, and the default is the interesting half. Ranked by
     * height alone the patterns that never set come first on every night of
     * the year - from Scotland they transit far higher than anything seasonal -
     * so the untrimmed list runs to seventeen rows whose first seven are the
     * same in January as in December. Trimmed, the card says what has changed
     * since last month and names the rest in one line.
     *
     * The full list is a setting rather than a lost option because somebody
     * learning the sky may well want all of it, and the app has no business
     * deciding they may not.
     */
    val showEveryConstellation: Boolean = false,

    /**
     * The events the user has picked out, by kind and time.
     *
     * Kept here rather than in [AlertState] because it is a choice the user
     * made, not a record of what the app did. Pruned once they are well past,
     * on the same reasoning as the announced record.
     */
    val starredEvents: Set<String> = emptySet(),

    /**
     * Whether the sky calendar may interrupt at all.
     *
     * One switch rather than one per channel: the channels are rare by nature
     * and Android already offers a per-channel switch in the system settings,
     * so a second set here would be the same control twice.
     */
    val eventAlertsEnabled: Boolean = true,

    /**
     * Whether starred events are written to the phone's calendar, and where.
     *
     * Off by default and asked for at the moment it is switched on, not on
     * first run: calendar permission is the most invasive thing this app could
     * ask for, and it should only ever be asked for by somebody who has just
     * said they want the feature it buys.
     */
    val calendarSync: CalendarSync = CalendarSync.Off,

    /**
     * Whether the app sets its own alert thresholds from your verdicts.
     *
     * Off by default, so no existing install changes behaviour without being
     * asked. With it on, the calibrated settings are composed over the stored
     * ones rather than written into them - so turning it off restores exactly
     * what was there before, because it was never overwritten.
     *
     * With it off the recommendations are still computed and still shown
     * underneath each control, which is most of the value at none of the cost:
     * the app says what it thinks and the user decides.
     */
    val automaticSettings: Boolean = false,

    /**
     * How many days of notice an eclipse or a shower peak gets.
     *
     * Only the kinds worth arranging an evening around get this at all. It is
     * a setting because how far ahead somebody wants to hear depends on how far
     * they would travel for it.
     */
    val eventHeadsUpDays: Int = DEFAULT_EVENT_HEADS_UP_DAYS,

    /** How long before it starts the notice on the day goes out. */
    val eventAlertLeadMinutes: Int = DEFAULT_EVENT_LEAD_MINUTES,

    /**
     * The cap on the eclipse countdown, separate from the pass one.
     *
     * A pass runs under ten minutes and a total lunar eclipse runs for over
     * three hours, so one number cannot serve both: the pass cap would cut an
     * eclipse off before the shadow arrived, and the eclipse cap would let a
     * bug hold a wake lock through a whole night.
     */
    val eclipseFollowMaxMinutes: Int = DEFAULT_ECLIPSE_FOLLOW_MAX_MINUTES,

    /**
     * How faint a comet may be and still be worth hearing about.
     *
     * The setting that decides whether anything is heard about in a typical
     * year. Most years have no naked-eye comet at all and several within reach
     * of binoculars, so where somebody puts this depends entirely on what they
     * are prepared to go and look through.
     */
    val cometMagnitudeThreshold: Int = DEFAULT_COMET_MAGNITUDE,

    /**
     * How high a comet must climb before it counts as up.
     *
     * Separate from [minimumPassElevationDegrees] rather than sharing it. A
     * satellite low in the murk is still a moving point of light and perfectly
     * obvious; a comet is a faint smudge, and the same murk simply erases it.
     */
    val minimumCometAltitudeDegrees: Int = DEFAULT_COMET_ALTITUDE_DEGREES,

    /**
     * How old a magnitude somebody measured may be before it stops being
     * believed.
     *
     * Past this the app falls back to the predicted magnitude and says that is
     * what it is doing. Brightness is the one thing about a comet that changes
     * fast, and a fortnight-old report of one in outburst is not evidence about
     * tonight.
     */
    val cometObservationStaleDays: Int = DEFAULT_COMET_STALE_DAYS,

    /**
     * How much moonlight is tolerable before a comet is called washed out, as a
     * percentage.
     *
     * **This matters more for a comet than for anything else the app covers.**
     * A planet at magnitude -2 does not care about the moon. A comet is a
     * diffuse smudge spread over a patch of sky, so what matters is its surface
     * brightness against the sky's, and moonlight raises the sky everywhere at
     * once. A magnitude-6 comet under a gibbous moon is, in practice, not
     * there.
     *
     * It is also the one most worth having a control on rather than a constant,
     * because how much moonlight is tolerable depends entirely on whether
     * somebody is driving an hour to a dark site or stepping into the garden.
     *
     * Expressed like [cloudTolerance] so the two read the same way: the share
     * of the night's moonlight that may be present before the app stops
     * recommending it.
     */
    val cometMoonTolerance: Int = DEFAULT_COMET_MOON_TOLERANCE,

    /**
     * Whether first-run setup has been walked through or skipped.
     *
     * Holds no position, so it lives wherever the rest of the settings do and
     * the device-encrypted mirror carries it along unread: nothing that runs
     * before first unlock consults it. A store with no record of it reads as
     * [hasLocation], so an install from before setup existed, which had to be
     * given a position to be of any use, is not walked through it again.
     */
    val setupDone: Boolean = false,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    /** [cometObservationStaleDays] as the repository compares it. */
    val cometObservationStaleMillis: Long
        get() = cometObservationStaleDays * 24L * 60L * 60L * 1000L

    /** [cometMoonTolerance] as the interference figure it is compared against. */
    val cometMoonFraction: Double get() = cometMoonTolerance / 100.0

    /** [passAlertLeadMinutes] as the planner uses it. */
    val passAlertLeadMillis: Long get() = passAlertLeadMinutes * 60_000L

    /** [logRetentionDays] in milliseconds. */
    val logRetentionMillis: Long get() = logRetentionDays * 24L * 60L * 60L * 1000L

    /** Whether the app should be keeping its own log at all. */
    val keepsLog: Boolean get() = logRetentionDays > 0

    val eventHeadsUpMillis: Long get() = eventHeadsUpDays * 24L * 60L * 60L * 1000L

    val eventAlertLeadMillis: Long get() = eventAlertLeadMinutes * 60_000L

    val eclipseFollowMaxMillis: Long get() = eclipseFollowMaxMinutes * 60_000L

    /** [liveFollowLeadMinutes] as the tracker uses it. */
    val liveFollowLeadMillis: Long get() = liveFollowLeadMinutes * 60_000L

    /**
     * [liveFollowMaxMinutes] as the tracker uses it, never below the lead.
     *
     * A cap shorter than the lead would stop the tracker before the satellite
     * had risen, which is the one moment it exists for.
     */
    val liveFollowMaxMillis: Long
        get() = maxOf(liveFollowMaxMinutes * 60_000L, liveFollowLeadMillis + 10 * 60_000L)

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

        /** What the threshold control offers, as a percentage. */
        val THRESHOLD_CHOICES = listOf(10, 20, 30, 50, 70)

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

        /** What the cloud control offers, as a percentage. 100 is "never hold one back". */
        val CLOUD_TOLERANCE_CHOICES = listOf(40, 60, 80, 100)

        /**
         * Half an hour before the highest point.
         *
         * Was a constant in the planner. The reasoning behind the number has
         * not changed - a coat, and a forecast still worth believing - but the
         * number itself is a fact about the user rather than about the sky.
         */
        const val DEFAULT_PASS_LEAD_MINUTES = 30

        /** Roughly where roofs and trees stop, for a typical horizon. */
        const val DEFAULT_MINIMUM_ELEVATION_DEGREES = 10

        /** What the lead control offers, in minutes. */
        val PASS_LEAD_CHOICES = listOf(10, 20, 30, 45, 60)

        /** What the horizon control offers, in degrees. */
        val MINIMUM_ELEVATION_CHOICES = listOf(5, 10, 15, 20, 30)

        /** Enough to get outside and let your eyes adjust. */
        const val DEFAULT_LIVE_LEAD_MINUTES = 10

        /** Three times the longest pass, so only a bug reaches it. */
        const val DEFAULT_LIVE_MAX_MINUTES = 30

        /** What the tracker's lead control offers, in minutes. */
        val LIVE_LEAD_CHOICES = listOf(2, 5, 10, 15, 20)

        /** A week, which outlasts the weekend somebody notices a problem over. */
        const val DEFAULT_LOG_RETENTION_DAYS = 7

        /** What the retention control offers, in days. Zero means keep none. */
        val LOG_RETENTION_CHOICES = listOf(0, 1, 3, 7, 14)

        /** The longest the log may be kept, which the custom entry and a stored value are held to. */
        const val MAX_LOG_RETENTION_DAYS = 365

        /** Whole days from one to [MAX_LOG_RETENTION_DAYS], or null for anything else. */
        fun parseLogRetention(input: String): Int? =
            input.trim().toIntOrNull()?.takeIf { it in 1..MAX_LOG_RETENTION_DAYS }

        /** Two days: long enough to change plans, short enough to still be the same week. */
        const val DEFAULT_EVENT_HEADS_UP_DAYS = 2

        val EVENT_HEADS_UP_CHOICES = listOf(1, 2, 3, 7)

        /** An hour, which is enough to get somewhere dark and let your eyes adjust. */
        const val DEFAULT_EVENT_LEAD_MINUTES = 60

        val EVENT_LEAD_CHOICES = listOf(30, 60, 120, 240)

        /** Three hours: past the longest umbral lunar eclipse, short of a night. */
        const val DEFAULT_ECLIPSE_FOLLOW_MAX_MINUTES = 180

        val ECLIPSE_FOLLOW_MAX_CHOICES = listOf(60, 120, 180, 240)

        /**
         * The moon's quarters, folded away to begin with.
         *
         * There are as many of them as of every other kind put together, and
         * they matter far less to an observer than the new and full moon do.
         * Named here rather than written inline so the preference store can
         * fall back to the same set - reading it with an empty default would
         * silently unhide them on the first read after an upgrade.
         */
        val DEFAULT_HIDDEN_EVENT_KINDS = setOf("MoonQuarter")

        /**
         * Magnitude 6, which is roughly what an unaided eye reaches from a
         * genuinely dark site - so the default is "tell me about anything I
         * could see without equipment", and the fainter settings are for people
         * willing to fetch binoculars.
         */
        const val DEFAULT_COMET_MAGNITUDE = 6

        /** What the brightness control offers. Lower is stricter. */
        val COMET_MAGNITUDE_CHOICES = listOf(4, 5, 6, 8)

        /** Higher than a satellite's, because haze erases a smudge it cannot erase a point. */
        const val DEFAULT_COMET_ALTITUDE_DEGREES = 15

        val COMET_ALTITUDE_CHOICES = listOf(5, 10, 15, 25)

        /** A week, which spans the cloudy spell that stops anybody looking. */
        const val DEFAULT_COMET_STALE_DAYS = 7

        val COMET_STALE_CHOICES = listOf(3, 7, 14, 30)

        /**
         * Half the night's moonlight, which is about the point a binocular
         * comet stops being findable from an ordinary garden.
         */
        const val DEFAULT_COMET_MOON_TOLERANCE = 50

        /** What the moonlight control offers, as a percentage. */
        val COMET_MOON_CHOICES = listOf(25, 50, 75, 100)

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
     * The calendar notices already given, keyed by event and phase.
     *
     * A set rather than a single "last announced", because two events can be
     * pending at once - an eclipse two days out and a shower peaking tonight -
     * and a single slot would lose one of them. Pruned to what is recent, which
     * these being rare keeps to a handful.
     */
    val announcedEvents: Set<String> = emptySet(),

    /** When the calendar poller asked to be woken next. */
    val nextEventCheckAt: Long? = null,

    /**
     * The comets the app has spoken about, by name, so it can say when one is
     * over.
     *
     * Kept apart from [announcedEvents] because a comet ends differently from
     * everything else. An eclipse finishes at a known moment and needs no
     * closing remark; a comet simply stops being worth going out for, on no
     * particular night. Having told somebody to look, the app owes them the
     * other half of the story - and comets disappointing is the normal case
     * rather than the exception.
     *
     * The name rather than the MPC designation, because the name is what the
     * closing notice has to print and a comet that has dropped out of the
     * published document leaves nothing else to print it from.
     */
    val announcedComets: Set<String> = emptySet(),
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

    /**
     * The calendar rows Gloaming has written, as "star key|row id".
     *
     * In [AlertState] rather than in [UserSettings] on the same reasoning that
     * puts the stars in the other one: a star is a choice the user made, and
     * this is a record of what the app did about it.
     *
     * It exists so the app can delete only its own rows, by the ids it put here
     * itself. The alternative - searching the calendar for entries that look
     * like Gloaming's - is a heuristic, and a heuristic that goes wrong here
     * deletes somebody's dentist appointment.
     */
    val syncedEvents: Set<String> = emptySet(),

    /**
     * The most recent alert nobody has said anything about, as encoded
     * [Conditions].
     *
     * Tonight's half of the "was it worth it?" question. The
     * notification carries the same conditions in its actions, but a
     * notification that has been swiped away carries them nowhere - and the
     * nights somebody swipes an alert away at two in the morning and forms an
     * opinion at nine are exactly the nights worth hearing about.
     *
     * One slot rather than a queue. The card asks about the last alert, which
     * is the one anybody can still remember.
     */
    val pendingVerdict: String? = null,
)

interface AlertStateStore {
    fun read(): AlertState
    fun write(state: AlertState)
}
