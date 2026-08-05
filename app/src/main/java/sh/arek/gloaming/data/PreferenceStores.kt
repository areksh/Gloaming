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

private const val KEY_LATITUDE = "latitude"
private const val KEY_LONGITUDE = "longitude"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_ALERTS_ENABLED = "alerts_enabled"
private const val KEY_POLL_MINUTES = "poll_minutes"
private const val KEY_BEFORE_UNLOCK = "alerts_before_unlock"

private const val KEY_LAST_ALERT_AT = "last_alert_at"
private const val KEY_LAST_ALERT_PROBABILITY = "last_alert_probability"
private const val KEY_ARMED = "armed"
private const val KEY_LAST_POLL_AT = "last_poll_at"
private const val KEY_LAST_PROBABILITY = "last_probability"
private const val KEY_LAST_ERROR = "last_error"

private const val UNSET = Long.MIN_VALUE

/**
 * Preferences in the ordinary, credential-encrypted store: unreadable until the
 * device has been unlocked after a boot. The precise position lives here.
 */
fun credentialPreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

/**
 * Preferences in the device-encrypted store: readable before first unlock.
 *
 * Note the deliberate absence of `applicationContext` - it would undo exactly
 * the property this function exists to provide.
 */
fun devicePreferences(context: Context): SharedPreferences =
    BootAware.deviceContext(context).getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

class PreferencesSettingsStore(private val prefs: SharedPreferences) : SettingsStore {

    override fun read(): UserSettings {
        val latitude = prefs.getLong(KEY_LATITUDE, UNSET)
        val longitude = prefs.getLong(KEY_LONGITUDE, UNSET)
        return UserSettings(
            latitude = if (latitude == UNSET) null else Double.fromBits(latitude),
            longitude = if (longitude == UNSET) null else Double.fromBits(longitude),
            threshold = prefs.getInt(KEY_THRESHOLD, UserSettings.DEFAULT_THRESHOLD),
            alertsEnabled = prefs.getBoolean(KEY_ALERTS_ENABLED, true),
            pollIntervalMinutes = prefs.getInt(KEY_POLL_MINUTES, UserSettings.DEFAULT_POLL_MINUTES),
            alertsBeforeUnlock = prefs.getBoolean(KEY_BEFORE_UNLOCK, false),
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
        }.apply()
    }

    /** Removes everything, used when a storage mirror should no longer exist. */
    fun clear() {
        prefs.edit().clear().apply()
    }
}

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
        }.apply()
    }
}
