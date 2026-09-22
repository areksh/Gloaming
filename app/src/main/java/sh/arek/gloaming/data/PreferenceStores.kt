package sh.arek.gloaming.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings and alert state in plain `SharedPreferences`.
 *
 * Not `EncryptedSharedPreferences`: Jetpack Security is effectively
 * unmaintained, and this holds a location the user typed on their own device -
 * the same threat model as the rest of their phone. Encrypting it would add a
 * dependency and imply a protection it does not provide.
 *
 * These take `SharedPreferences` rather than a `Context` on purpose. Which
 * storage is in play - credential-encrypted or device-encrypted - is the whole
 * point of the split, so it is named at the call site instead of being derived
 * inside. Deriving it here once cost exactly that: calling `applicationContext`
 * on a device-protected context hands back the ordinary one, so both stores
 * quietly became the same file.
 */
const val PREFERENCES_FILE = "gloaming"

/** The wake record's own file. See [wakePreferences]. */
const val WAKE_FILE = "wake"

private const val KEY_LATITUDE = "latitude"
private const val KEY_LONGITUDE = "longitude"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_ALERTS_ENABLED = "alerts_enabled"
private const val KEY_POLL_MINUTES = "poll_minutes"
private const val KEY_BEFORE_UNLOCK = "alerts_before_unlock"
private const val KEY_CLOUD_TOLERANCE = "cloud_tolerance"
private const val KEY_CLOUD_GATE = "cloud_gate_enabled"
private const val KEY_QUIET_START = "quiet_hours_start"
private const val KEY_QUIET_END = "quiet_hours_end"
private const val KEY_QUIET_OVERRIDE = "quiet_hours_override"
private const val KEY_NIGHT_VISION = "night_vision"
private const val KEY_PASS_ALERTS = "pass_alerts_enabled"
private const val KEY_AURORA_INTERVAL = "aurora_alert_interval_minutes"
private const val KEY_ISS_INTERVAL = "iss_pass_alert_interval_minutes"
private const val KEY_BRIGHT_INTERVAL = "bright_pass_alert_interval_minutes"
private const val KEY_TRAIN_INTERVAL = "train_pass_alert_interval_minutes"
private const val KEY_FOLLOW_STATION = "follow_station_passes"
private const val KEY_WATCH_DURATION = "watch_duration"
private const val KEY_PRECISE_BEFORE_UNLOCK = "precise_location_before_unlock"
private const val KEY_PASS_LEAD = "pass_alert_lead_minutes"
private const val KEY_MINIMUM_ELEVATION = "minimum_pass_elevation_degrees"
private const val KEY_LIVE_LEAD = "live_follow_lead_minutes"
private const val KEY_LIVE_MAX = "live_follow_max_minutes"
private const val KEY_LOG_RETENTION = "log_retention_days"

private const val KEY_LAST_ALERT_AT = "last_alert_at"
private const val KEY_LAST_ALERT_PROBABILITY = "last_alert_probability"
private const val KEY_ARMED = "armed"
private const val KEY_LAST_POLL_AT = "last_poll_at"
private const val KEY_LAST_PROBABILITY = "last_probability"
private const val KEY_LAST_ERROR = "last_error"
private const val KEY_LAST_FROM_AURORAWATCH = "last_from_aurorawatch"
private const val KEY_LAST_PASS_PEAK_AT = "last_pass_peak_at"
private const val KEY_LAST_ISS_ALERT_AT = "last_iss_alert_at"
private const val KEY_LAST_BRIGHT_ALERT_AT = "last_bright_alert_at"
private const val KEY_LAST_TRAIN_ALERT_AT = "last_train_alert_at"
private const val KEY_NEXT_PASS_CHECK_AT = "next_pass_check_at"
private const val KEY_LOCATION_AUTOMATIC = "location_automatic"
private const val KEY_EVENT_ALERTS = "event_alerts_enabled"
private const val KEY_EVERY_EVENT = "notify_about_every_event"
private const val KEY_EVERY_CONSTELLATION = "show_every_constellation"
private const val KEY_STARRED_EVENTS = "starred_events"
private const val KEY_CALENDAR_SYNC = "calendar_sync"
private const val KEY_AUTOMATIC_SETTINGS = "automatic_settings"
private const val KEY_EVENT_HEADS_UP = "event_heads_up_days"
private const val KEY_EVENT_LEAD = "event_alert_lead_minutes"
private const val KEY_ECLIPSE_FOLLOW_MAX = "eclipse_follow_max_minutes"
private const val KEY_HIDDEN_KINDS = "hidden_event_kinds"
private const val KEY_ANNOUNCED_COMETS = "announced_comets"
private const val KEY_SYNCED_EVENTS = "synced_events"
private const val KEY_PENDING_VERDICT = "pending_verdict"
private const val KEY_COMET_MAGNITUDE = "comet_magnitude_threshold"
private const val KEY_COMET_ALTITUDE = "minimum_comet_altitude_degrees"
private const val KEY_COMET_STALE = "comet_observation_stale_days"
private const val KEY_COMET_MOON = "comet_moon_tolerance"
private const val KEY_ANNOUNCED_EVENTS = "announced_events"
private const val KEY_NEXT_EVENT_CHECK_AT = "next_event_check_at"
private const val KEY_LAST_PASS_CATALOGUE = "last_pass_catalogue"
private const val KEY_FOLLOWING_CATALOGUE = "following_catalogue"
private const val KEY_FOLLOWING_PEAK_AT = "following_peak_at"
private const val KEY_FOLLOWING_RISES_AT = "following_rises_at"

private const val UNSET = Long.MIN_VALUE

/**
 * Absent quiet hours, distinct from any real minute of the day.
 *
 * Midnight is 0 and a valid start, so zero cannot mean "unset" here - reading
 * it that way would either lose a period starting at midnight or invent one
 * that silences the app on its own initiative.
 */
private const val NO_MINUTE = -1

/**
 * Preferences in the ordinary, credential-encrypted store: unreadable until the
 * device has been unlocked after a boot. The precise position lives here.
 */
@Synchronized
fun credentialPreferences(context: Context): SharedPreferences =
    credentialCache ?: context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        .also { credentialCache = it }

/**
 * One instance per file per process, held here rather than left to chance.
 *
 * `SharedPreferences` keeps its values in memory and only reconciles with the
 * file on write, so two instances of the same file in one process are two
 * caches that silently diverge - the documented behaviour is that this is
 * unsupported. Every caller here used to build its own from a freshly created
 * device-protected context, which is a new `Context` each time, and relying on
 * the framework's own cache to collapse them again is relying on an
 * implementation detail for correctness.
 *
 * This bit: a calendar row id written by the screen was invisible to the very
 * next read, so unstarring an event could never find the row to delete.
 */
private var credentialCache: SharedPreferences? = null
private var deviceCache: SharedPreferences? = null
private var wakeCache: SharedPreferences? = null

/**
 * Preferences in the device-encrypted store: readable before first unlock.
 *
 * Note the deliberate absence of `applicationContext` - it would undo exactly
 * the property this function exists to provide.
 */
@Synchronized
fun devicePreferences(context: Context): SharedPreferences =
    deviceCache ?: BootAware.deviceContext(context)
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        .also { deviceCache = it }

/**
 * A file of its own, for the record each wake leaves behind.
 *
 * Device-encrypted like the settings mirror beside it, because the wake it
 * exists to explain is the one before anybody has unlocked the phone.
 *
 * Separate from [devicePreferences] for one reason: it is written with
 * `commit` rather than `apply` - the whole point of the record is to survive a
 * process about to be killed, and `apply` writes on the thread that does not
 * get to finish - and a synchronous write wants to be a small one. Four keys
 * of its own cost a fraction of what rewriting the settings file would, and
 * cannot contend with a settings save.
 */
@Synchronized
fun wakePreferences(context: Context): SharedPreferences =
    wakeCache ?: BootAware.deviceContext(context)
        .getSharedPreferences(WAKE_FILE, Context.MODE_PRIVATE)
        .also { wakeCache = it }

class PreferencesSettingsStore(private val prefs: SharedPreferences) : SettingsStore {

    override fun read(): UserSettings {
        val latitude = prefs.getLong(KEY_LATITUDE, UNSET)
        val longitude = prefs.getLong(KEY_LONGITUDE, UNSET)
        val quietStart = prefs.getInt(KEY_QUIET_START, NO_MINUTE)
        val quietEnd = prefs.getInt(KEY_QUIET_END, NO_MINUTE)
        return UserSettings(
            latitude = if (latitude == UNSET) null else Double.fromBits(latitude),
            longitude = if (longitude == UNSET) null else Double.fromBits(longitude),
            threshold = prefs.getInt(KEY_THRESHOLD, UserSettings.DEFAULT_THRESHOLD),
            alertsEnabled = prefs.getBoolean(KEY_ALERTS_ENABLED, true),
            pollIntervalMinutes = prefs.getInt(KEY_POLL_MINUTES, UserSettings.DEFAULT_POLL_MINUTES),
            alertsBeforeUnlock = prefs.getBoolean(KEY_BEFORE_UNLOCK, false),
            cloudTolerance = prefs.getInt(
                KEY_CLOUD_TOLERANCE,
                UserSettings.DEFAULT_CLOUD_TOLERANCE,
            ),
            cloudGateEnabled = prefs.getBoolean(KEY_CLOUD_GATE, true),
            quietHours = if (quietStart == NO_MINUTE || quietEnd == NO_MINUTE) {
                null
            } else {
                QuietHours(quietStart, quietEnd)
            },
            quietHoursOverride = prefs.getBoolean(KEY_QUIET_OVERRIDE, false),
            nightVision = prefs.getBoolean(KEY_NIGHT_VISION, false),
            passAlertsEnabled = prefs.getBoolean(KEY_PASS_ALERTS, true),
            auroraAlertIntervalMinutes = prefs.getInt(
                KEY_AURORA_INTERVAL,
                UserSettings.DEFAULT_ALERT_INTERVAL_MINUTES,
            ),
            issPassAlertIntervalMinutes = prefs.getInt(
                KEY_ISS_INTERVAL,
                UserSettings.DEFAULT_ALERT_INTERVAL_MINUTES,
            ),
            brightPassAlertIntervalMinutes = prefs.getInt(
                KEY_BRIGHT_INTERVAL,
                UserSettings.DEFAULT_ALERT_INTERVAL_MINUTES,
            ),
            trainPassAlertIntervalMinutes = prefs.getInt(
                KEY_TRAIN_INTERVAL,
                UserSettings.DEFAULT_ALERT_INTERVAL_MINUTES,
            ),
            followStationPassesAutomatically = prefs.getBoolean(KEY_FOLLOW_STATION, false),
            watchDuration = WatchDuration.of(prefs.getString(KEY_WATCH_DURATION, null)),
            preciseLocationBeforeUnlock = prefs.getBoolean(KEY_PRECISE_BEFORE_UNLOCK, false),
            passAlertLeadMinutes = prefs.getInt(
                KEY_PASS_LEAD,
                UserSettings.DEFAULT_PASS_LEAD_MINUTES,
            ),
            minimumPassElevationDegrees = prefs.getInt(
                KEY_MINIMUM_ELEVATION,
                UserSettings.DEFAULT_MINIMUM_ELEVATION_DEGREES,
            ),
            liveFollowLeadMinutes = prefs.getInt(
                KEY_LIVE_LEAD,
                UserSettings.DEFAULT_LIVE_LEAD_MINUTES,
            ),
            liveFollowMaxMinutes = prefs.getInt(
                KEY_LIVE_MAX,
                UserSettings.DEFAULT_LIVE_MAX_MINUTES,
            ),
            locationMode = if (prefs.getBoolean(KEY_LOCATION_AUTOMATIC, false)) {
                LocationMode.Automatic
            } else {
                LocationMode.Manual
            },
            eventAlertsEnabled = prefs.getBoolean(KEY_EVENT_ALERTS, true),
            notifyAboutEveryEvent = prefs.getBoolean(KEY_EVERY_EVENT, true),
            showEveryConstellation = prefs.getBoolean(KEY_EVERY_CONSTELLATION, false),
            starredEvents = prefs.getStringSet(KEY_STARRED_EVENTS, emptySet()).orEmpty(),
            // By name rather than by ordinal: an ordinal is a position in a
            // list, and inserting a destination in the middle of that list
            // would silently move everybody's setting to a different one.
            calendarSync = CalendarSync.entries
                .firstOrNull { it.name == prefs.getString(KEY_CALENDAR_SYNC, null) }
                ?: CalendarSync.Off,
            automaticSettings = prefs.getBoolean(KEY_AUTOMATIC_SETTINGS, false),
            // Read with the default as its fallback rather than an empty set,
            // so a fresh install still starts with the moon's quarters folded
            // away - there are as many of them as every other kind combined.
            hiddenEventKinds = prefs.getStringSet(
                KEY_HIDDEN_KINDS,
                UserSettings.DEFAULT_HIDDEN_EVENT_KINDS,
            ).orEmpty(),
            eventHeadsUpDays = prefs.getInt(
                KEY_EVENT_HEADS_UP,
                UserSettings.DEFAULT_EVENT_HEADS_UP_DAYS,
            ),
            eventAlertLeadMinutes = prefs.getInt(
                KEY_EVENT_LEAD,
                UserSettings.DEFAULT_EVENT_LEAD_MINUTES,
            ),
            eclipseFollowMaxMinutes = prefs.getInt(
                KEY_ECLIPSE_FOLLOW_MAX,
                UserSettings.DEFAULT_ECLIPSE_FOLLOW_MAX_MINUTES,
            ),
            logRetentionDays = prefs.getInt(
                KEY_LOG_RETENTION,
                UserSettings.DEFAULT_LOG_RETENTION_DAYS,
            ),
            cometMagnitudeThreshold = prefs.getInt(
                KEY_COMET_MAGNITUDE,
                UserSettings.DEFAULT_COMET_MAGNITUDE,
            ),
            minimumCometAltitudeDegrees = prefs.getInt(
                KEY_COMET_ALTITUDE,
                UserSettings.DEFAULT_COMET_ALTITUDE_DEGREES,
            ),
            cometObservationStaleDays = prefs.getInt(
                KEY_COMET_STALE,
                UserSettings.DEFAULT_COMET_STALE_DAYS,
            ),
            cometMoonTolerance = prefs.getInt(
                KEY_COMET_MOON,
                UserSettings.DEFAULT_COMET_MOON_TOLERANCE,
            ),
        )
    }

    override fun write(settings: UserSettings) {
        prefs.edit().apply {
            if (settings.latitude == null) {
                remove(KEY_LATITUDE)
            } else {
                putLong(KEY_LATITUDE, settings.latitude.toRawBits())
            }
            if (settings.longitude == null) {
                remove(KEY_LONGITUDE)
            } else {
                putLong(KEY_LONGITUDE, settings.longitude.toRawBits())
            }
            putInt(KEY_THRESHOLD, settings.threshold)
            putBoolean(KEY_ALERTS_ENABLED, settings.alertsEnabled)
            putInt(KEY_POLL_MINUTES, settings.pollIntervalMinutes)
            putBoolean(KEY_BEFORE_UNLOCK, settings.alertsBeforeUnlock)
            putInt(KEY_CLOUD_TOLERANCE, settings.cloudTolerance)
            putBoolean(KEY_CLOUD_GATE, settings.cloudGateEnabled)
            val quietHours = settings.quietHours
            if (quietHours == null) {
                remove(KEY_QUIET_START)
                remove(KEY_QUIET_END)
            } else {
                putInt(KEY_QUIET_START, quietHours.startMinute)
                putInt(KEY_QUIET_END, quietHours.endMinute)
            }
            putBoolean(KEY_QUIET_OVERRIDE, settings.quietHoursOverride)
            putBoolean(KEY_NIGHT_VISION, settings.nightVision)
            putBoolean(KEY_PASS_ALERTS, settings.passAlertsEnabled)
            putInt(KEY_AURORA_INTERVAL, settings.auroraAlertIntervalMinutes)
            putInt(KEY_ISS_INTERVAL, settings.issPassAlertIntervalMinutes)
            putInt(KEY_BRIGHT_INTERVAL, settings.brightPassAlertIntervalMinutes)
            putInt(KEY_TRAIN_INTERVAL, settings.trainPassAlertIntervalMinutes)
            putBoolean(KEY_FOLLOW_STATION, settings.followStationPassesAutomatically)
            putString(KEY_WATCH_DURATION, settings.watchDuration.name)
            putBoolean(KEY_PRECISE_BEFORE_UNLOCK, settings.preciseLocationBeforeUnlock)
            putInt(KEY_PASS_LEAD, settings.passAlertLeadMinutes)
            putInt(KEY_MINIMUM_ELEVATION, settings.minimumPassElevationDegrees)
            putInt(KEY_LIVE_LEAD, settings.liveFollowLeadMinutes)
            putInt(KEY_LIVE_MAX, settings.liveFollowMaxMinutes)
            putInt(KEY_LOG_RETENTION, settings.logRetentionDays)
            putBoolean(KEY_LOCATION_AUTOMATIC, settings.locationMode == LocationMode.Automatic)
            putBoolean(KEY_EVENT_ALERTS, settings.eventAlertsEnabled)
            putBoolean(KEY_EVERY_EVENT, settings.notifyAboutEveryEvent)
            putBoolean(KEY_EVERY_CONSTELLATION, settings.showEveryConstellation)
            // A defensive copy, for the reason the announced record carries one:
            // SharedPreferences keeps the very set handed to it.
            putStringSet(KEY_STARRED_EVENTS, settings.starredEvents.toSet())
            putString(KEY_CALENDAR_SYNC, settings.calendarSync.name)
            putBoolean(KEY_AUTOMATIC_SETTINGS, settings.automaticSettings)
            putStringSet(KEY_HIDDEN_KINDS, settings.hiddenEventKinds.toSet())
            putInt(KEY_EVENT_HEADS_UP, settings.eventHeadsUpDays)
            putInt(KEY_EVENT_LEAD, settings.eventAlertLeadMinutes)
            putInt(KEY_ECLIPSE_FOLLOW_MAX, settings.eclipseFollowMaxMinutes)
            putInt(KEY_COMET_MAGNITUDE, settings.cometMagnitudeThreshold)
            putInt(KEY_COMET_ALTITUDE, settings.minimumCometAltitudeDegrees)
            putInt(KEY_COMET_STALE, settings.cometObservationStaleDays)
            putInt(KEY_COMET_MOON, settings.cometMoonTolerance)
        }.apply()
    }

    /**
     * Removes everything **this store** wrote, and nothing else.
     *
     * Not `edit().clear()`, which it was. The settings and the alert state
     * share one preferences file, so clearing the file took the whole alert
     * state with it - the hysteresis latch, the last poll, which events had
     * already been announced, which comets had said goodbye, the next check
     * times. That ran on every settings save with before-unlock alerting off,
     * which is the default: changing any setting made the app forget it had
     * already told you about tonight's eclipse.
     *
     * Found by watching a calendar row id vanish six seconds after it was
     * written, with the write and the read-back both provably correct.
     */
    fun clear() {
        prefs.edit().apply { SETTINGS_KEYS.forEach(::remove) }.apply()
    }
}

/**
 * Every key [PreferencesSettingsStore] owns.
 *
 * Written out rather than derived, because the thing it guards against is a key
 * being added to `write` and forgotten here - which a reflective list would
 * hide rather than catch. `PreferenceStoresTest` fails if the two drift apart.
 */
internal val SETTINGS_KEYS = listOf(
    KEY_ALERTS_ENABLED,
    KEY_AURORA_INTERVAL,
    KEY_AUTOMATIC_SETTINGS,
    KEY_BEFORE_UNLOCK,
    KEY_BRIGHT_INTERVAL,
    KEY_CALENDAR_SYNC,
    KEY_CLOUD_GATE,
    KEY_CLOUD_TOLERANCE,
    KEY_COMET_ALTITUDE,
    KEY_COMET_MAGNITUDE,
    KEY_COMET_MOON,
    KEY_COMET_STALE,
    KEY_ECLIPSE_FOLLOW_MAX,
    KEY_EVENT_ALERTS,
    KEY_EVENT_HEADS_UP,
    KEY_EVENT_LEAD,
    KEY_EVERY_CONSTELLATION,
    KEY_EVERY_EVENT,
    KEY_FOLLOW_STATION,
    KEY_HIDDEN_KINDS,
    KEY_ISS_INTERVAL,
    KEY_LATITUDE,
    KEY_LIVE_LEAD,
    KEY_LIVE_MAX,
    KEY_LOCATION_AUTOMATIC,
    KEY_LOG_RETENTION,
    KEY_LONGITUDE,
    KEY_MINIMUM_ELEVATION,
    KEY_NIGHT_VISION,
    KEY_PASS_ALERTS,
    KEY_PASS_LEAD,
    KEY_POLL_MINUTES,
    KEY_PRECISE_BEFORE_UNLOCK,
    KEY_QUIET_END,
    KEY_QUIET_OVERRIDE,
    KEY_QUIET_START,
    KEY_STARRED_EVENTS,
    KEY_THRESHOLD,
    KEY_TRAIN_INTERVAL,
    KEY_WATCH_DURATION,
)

class PreferencesAlertStateStore(private val prefs: SharedPreferences) : AlertStateStore {

    override fun read(): AlertState {
        val lastAlertAt = prefs.getLong(KEY_LAST_ALERT_AT, UNSET)
        val lastPollAt = prefs.getLong(KEY_LAST_POLL_AT, UNSET)
        val lastAlertProbability = prefs.getInt(KEY_LAST_ALERT_PROBABILITY, -1)
        val lastProbability = prefs.getInt(KEY_LAST_PROBABILITY, -1)
        return AlertState(
            lastAlertAt = if (lastAlertAt == UNSET) null else lastAlertAt,
            lastAlertProbability = lastAlertProbability.takeIf { it >= 0 },
            armed = prefs.getBoolean(KEY_ARMED, true),
            lastPollAt = if (lastPollAt == UNSET) null else lastPollAt,
            lastProbability = lastProbability.takeIf { it >= 0 },
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            lastFromAuroraWatch = prefs.getBoolean(KEY_LAST_FROM_AURORAWATCH, false),
            lastPassPeakAt = prefs.getLong(KEY_LAST_PASS_PEAK_AT, UNSET).takeIf { it != UNSET },
            lastIssAlertAt = prefs.getLong(KEY_LAST_ISS_ALERT_AT, UNSET).takeIf { it != UNSET },
            lastBrightAlertAt = prefs.getLong(KEY_LAST_BRIGHT_ALERT_AT, UNSET)
                .takeIf { it != UNSET },
            lastTrainAlertAt = prefs.getLong(KEY_LAST_TRAIN_ALERT_AT, UNSET)
                .takeIf { it != UNSET },
            nextPassCheckAt = prefs.getLong(KEY_NEXT_PASS_CHECK_AT, UNSET).takeIf { it != UNSET },
            announcedEvents = prefs.getStringSet(KEY_ANNOUNCED_EVENTS, emptySet()).orEmpty(),
            announcedComets = prefs.getStringSet(KEY_ANNOUNCED_COMETS, emptySet()).orEmpty(),
            syncedEvents = prefs.getStringSet(KEY_SYNCED_EVENTS, emptySet()).orEmpty(),
            pendingVerdict = prefs.getString(KEY_PENDING_VERDICT, null),
            nextEventCheckAt = prefs.getLong(KEY_NEXT_EVENT_CHECK_AT, UNSET).takeIf { it != UNSET },
            lastPassCatalogue = prefs.getInt(KEY_LAST_PASS_CATALOGUE, -1).takeIf { it >= 0 },
            followingCatalogue = prefs.getInt(KEY_FOLLOWING_CATALOGUE, -1).takeIf { it >= 0 },
            followingPeakAt = prefs.getLong(KEY_FOLLOWING_PEAK_AT, UNSET).takeIf { it != UNSET },
            followingRisesAt = prefs.getLong(KEY_FOLLOWING_RISES_AT, UNSET)
                .takeIf { it != UNSET },
        )
    }

    override fun write(state: AlertState) {
        prefs.edit().apply {
            state.lastAlertAt?.let { putLong(KEY_LAST_ALERT_AT, it) } ?: remove(KEY_LAST_ALERT_AT)
            state.lastAlertProbability?.let { putInt(KEY_LAST_ALERT_PROBABILITY, it) }
                ?: remove(KEY_LAST_ALERT_PROBABILITY)
            putBoolean(KEY_ARMED, state.armed)
            state.lastPollAt?.let { putLong(KEY_LAST_POLL_AT, it) } ?: remove(KEY_LAST_POLL_AT)
            state.lastProbability?.let { putInt(KEY_LAST_PROBABILITY, it) }
                ?: remove(KEY_LAST_PROBABILITY)
            state.lastError?.let { putString(KEY_LAST_ERROR, it) } ?: remove(KEY_LAST_ERROR)
            putBoolean(KEY_LAST_FROM_AURORAWATCH, state.lastFromAuroraWatch)
            state.lastPassPeakAt?.let { putLong(KEY_LAST_PASS_PEAK_AT, it) }
                ?: remove(KEY_LAST_PASS_PEAK_AT)
            state.lastIssAlertAt?.let { putLong(KEY_LAST_ISS_ALERT_AT, it) }
                ?: remove(KEY_LAST_ISS_ALERT_AT)
            state.lastBrightAlertAt?.let { putLong(KEY_LAST_BRIGHT_ALERT_AT, it) }
                ?: remove(KEY_LAST_BRIGHT_ALERT_AT)
            state.lastTrainAlertAt?.let { putLong(KEY_LAST_TRAIN_ALERT_AT, it) }
                ?: remove(KEY_LAST_TRAIN_ALERT_AT)
            // A defensive copy: SharedPreferences keeps the very set handed to
            // it and hands the same instance back on read, so a later mutation
            // of ours would silently rewrite what is on disk.
            putStringSet(KEY_ANNOUNCED_EVENTS, state.announcedEvents.toSet())
            putStringSet(KEY_ANNOUNCED_COMETS, state.announcedComets.toSet())
            putStringSet(KEY_SYNCED_EVENTS, state.syncedEvents.toSet())
            state.pendingVerdict?.let { putString(KEY_PENDING_VERDICT, it) }
                ?: remove(KEY_PENDING_VERDICT)
            state.nextEventCheckAt?.let { putLong(KEY_NEXT_EVENT_CHECK_AT, it) }
                ?: remove(KEY_NEXT_EVENT_CHECK_AT)
            state.nextPassCheckAt?.let { putLong(KEY_NEXT_PASS_CHECK_AT, it) }
                ?: remove(KEY_NEXT_PASS_CHECK_AT)
            state.lastPassCatalogue?.let { putInt(KEY_LAST_PASS_CATALOGUE, it) }
                ?: remove(KEY_LAST_PASS_CATALOGUE)
            state.followingCatalogue?.let { putInt(KEY_FOLLOWING_CATALOGUE, it) }
                ?: remove(KEY_FOLLOWING_CATALOGUE)
            state.followingPeakAt?.let { putLong(KEY_FOLLOWING_PEAK_AT, it) }
                ?: remove(KEY_FOLLOWING_PEAK_AT)
            state.followingRisesAt?.let { putLong(KEY_FOLLOWING_RISES_AT, it) }
                ?: remove(KEY_FOLLOWING_RISES_AT)
        }.apply()
    }
}
