package sh.arek.gloaming.data

import android.content.Context
import android.os.UserManager
import java.io.File
import kotlin.math.roundToInt

/**
 * Which storage the app uses, and whether it may run yet.
 *
 * Android splits app storage in two. Credential-encrypted storage is the
 * default and is cryptographically unavailable until the device is first
 * unlocked after a boot. Device-encrypted storage is available immediately.
 *
 * That distinction matters here because GrapheneOS can reboot on a timer: after
 * one of those reboots the device sits at the lock screen, and an app whose
 * data and scheduler live in credential-encrypted storage is simply asleep
 * until someone picks the phone up. For a four-hour reboot interval that is
 * most of a night.
 *
 * The split adopted:
 *
 * - **Caches** (manifest, status, grid) always live in device-encrypted
 *   storage. They are public data fetched from a public endpoint, so there is
 *   nothing to protect.
 * - **Alert state** (timestamps, a probability, a latch) likewise.
 * - **Settings used by the poll** live in device-encrypted storage only when
 *   before-unlock alerting is switched on, and then carry the observer's
 *   *grid cell* rather than the position typed in.
 * - **The location cadence and when the position was last refreshed** are
 *   ordinary settings, kept beside the precise position in
 *   credential-encrypted storage (the mirror carries a copy, like the rest of
 *   the settings). Only the background location alarm reads
 *   them, and that only runs while unlocked.
 * - **The precise position** never leaves credential-encrypted storage, and
 *   nor does the cloud reading taken at it - see [weatherCacheContext].
 */
object BootAware {

    /** Storage that survives a reboot without needing the user's credential. */
    fun deviceContext(context: Context): Context =
        context.applicationContext.createDeviceProtectedStorageContext()

    /** True once the user has unlocked since boot. */
    fun isUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /**
     * Whether before-unlock alerting is enabled.
     *
     * Read from device-encrypted storage because it has to be answerable in
     * exactly the state where the other store cannot be opened.
     */
    fun beforeUnlockEnabled(context: Context): Boolean =
        PreferencesSettingsStore(devicePreferences(context)).read().alertsBeforeUnlock

    /**
     * Whether a scheduled poll can do anything right now.
     *
     * Before first unlock without the option enabled there is no location to
     * read and no settings to obey, so the wake reschedules and does nothing.
     */
    fun canPollNow(context: Context): Boolean =
        isUnlocked(context) || beforeUnlockEnabled(context)

    /**
     * Persists settings to the right places.
     *
     * The precise position goes to credential-encrypted storage. The mirror in
     * device-encrypted storage is written only when before-unlock alerting is
     * on, and is reduced to the grid cell first. Turning the option off clears
     * that mirror rather than leaving a stale copy behind, which would keep the
     * data at the weaker protection after the user opted out of it.
     */
    fun saveSettings(context: Context, settings: UserSettings) {
        PreferencesSettingsStore(credentialPreferences(context)).write(settings)

        val mirror = PreferencesSettingsStore(devicePreferences(context))
        // Read before it is overwritten: coarsening the mirror has to take the
        // cloud reading with it, and only the old value says whether the
        // reading was taken at a finer position than the new one allows.
        val wasPrecise = mirror.read().preciseLocationBeforeUnlock
        val toMirror = mirrorOf(settings)
        if (wasPrecise && toMirror?.preciseLocationBeforeUnlock != true) {
            FileWeatherCache(File(deviceContext(context).filesDir, CACHE_DIRECTORY)).clear()
        }
        if (toMirror == null) {
            mirror.clear()
            // The cloud reading goes with it. It was taken at the mirror's
            // whole degree, so leaving it would keep a position in the weaker
            // store after the user opted out of having one there - the same
            // reason the mirror itself is cleared rather than left stale.
            FileWeatherCache(File(deviceContext(context).filesDir, CACHE_DIRECTORY)).clear()
            // The flag itself must survive, or the next locked wake cannot tell
            // that the user turned the option off rather than never set it.
            mirror.write(UserSettings(alertsBeforeUnlock = false))
        } else {
            mirror.write(toMirror)
        }
    }

    /**
     * What belongs in device-encrypted storage, or null if nothing should.
     *
     * The position is rounded to the grid's own resolution first: the lookup
     * rounds to whole degrees anyway, so alerting loses nothing while what sits
     * in the weaker store is a region rather than an address.
     *
     * Rounded, not converted to a [GridCell]'s coordinates. The cell numbers a
     * column from 0 to 359, and storing that as a longitude put every user west
     * of Greenwich at, say, 357 - which the aurora lookup wraps and does not
     * notice, but which the cloud forecast rejects outright and which would put
     * Edinburgh in Kazakhstan for anything that compares longitudes. This stays
     * a real longitude; `GridCell.of` does the wrapping where the grid needs it.
     */
    fun mirrorOf(settings: UserSettings): UserSettings? {
        val latitude = settings.latitude
        val longitude = settings.longitude
        if (!settings.alertsBeforeUnlock || latitude == null || longitude == null) return null
        // Asked for explicitly, and only worth asking for because of passes:
        // see UserSettings.preciseLocationBeforeUnlock.
        if (settings.preciseLocationBeforeUnlock) return settings
        return settings.copy(
            latitude = latitude.roundToInt().toDouble(),
            longitude = longitude.roundToInt().toDouble(),
        )
    }

    /**
     * Where the cloud reading may be kept.
     *
     * The reading records where the user was, at the rounded position the
     * request carried - two decimal places, about a kilometre. That is far
     * finer than the whole degree the settings mirror is reduced to, so it
     * cannot simply sit in device-encrypted storage with the caches: doing so
     * would put a kilometre-precise position where a phone that has not been
     * unlocked since boot will hand it over, which is exactly what the split
     * exists to prevent.
     *
     * So it follows the settings it was taken from. With before-unlock alerting
     * off, the poll reads the precise position and the reading belongs in
     * credential-encrypted storage; a locked poll does not run in that
     * configuration anyway. With it on, the poll reads the whole-degree mirror,
     * the request carries no more than that, and the reading is no more
     * revealing than the mirror already beside it.
     */
    fun weatherCacheContext(context: Context): Context =
        if (beforeUnlockEnabled(context)) {
            deviceContext(context)
        } else {
            context.applicationContext
        }

    /** Settings for the polling path, from whichever store is authoritative. */
    fun pollSettings(context: Context): SettingsStore =
        if (beforeUnlockEnabled(context)) {
            PreferencesSettingsStore(devicePreferences(context))
        } else {
            PreferencesSettingsStore(credentialPreferences(context))
        }
    /** Where the cached documents live under whichever store holds them. */
    const val CACHE_DIRECTORY = "sky"
}
