package sh.arek.gloaming.data

import android.content.Context
import android.os.UserManager

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
 * - **The precise position** never leaves credential-encrypted storage.
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
        PreferencesSettingsStore(deviceContext(context)).read().alertsBeforeUnlock

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
        PreferencesSettingsStore(context.applicationContext).write(settings)

        val mirror = PreferencesSettingsStore(deviceContext(context))
        val cell = settings.cell
        if (settings.alertsBeforeUnlock && cell != null) {
            mirror.write(
                settings.copy(
                    latitude = cell.latitude.toDouble(),
                    longitude = cell.longitude.toDouble(),
                ),
            )
        } else {
            mirror.clear()
            // The flag itself must survive, or the next locked wake cannot tell
            // that the user turned the option off rather than never set it.
            mirror.write(UserSettings(alertsBeforeUnlock = settings.alertsBeforeUnlock))
        }
    }

    /** Settings for the polling path, from whichever store is authoritative. */
    fun pollSettings(context: Context): SettingsStore =
        if (beforeUnlockEnabled(context)) {
            PreferencesSettingsStore(deviceContext(context))
        } else {
            PreferencesSettingsStore(context.applicationContext)
        }
}
