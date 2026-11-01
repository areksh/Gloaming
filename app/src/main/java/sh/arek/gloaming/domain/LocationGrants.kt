package sh.arek.gloaming.domain

import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings

/**
 * Brings the stored location settings into line with what Android lets the
 * app do now.
 *
 * Either grant can be taken away in Android's own settings while the app is
 * closed, and nothing tells the app. Left alone, the settings go on claiming
 * a mode or cadence that can no longer work.
 */
object LocationGrants {

    const val LOCATION_REMOVED_NOTE: String =
        "Location access was turned off in Android's settings, so Gloaming is using " +
            "the position it last had. Type a new one here, or turn location back on."

    /**
     * The settings to keep, and a note to show for the change when the
     * screen has no other way of saying it.
     *
     * [Reconciled.settings] is the very object passed in when nothing needed
     * changing, so a caller can tell by identity.
     */
    data class Reconciled(val settings: UserSettings, val note: String?)

    /**
     * Without the location grant, automatic goes back to typed in and keeps
     * the position it had. Without the background grant, an interval falls
     * back to each open and sets [UserSettings.backgroundLocationRevoked],
     * whose line on the Location page is the explanation, so no note is
     * returned for it.
     */
    fun reconcile(
        settings: UserSettings,
        locationGranted: Boolean,
        backgroundGranted: Boolean,
    ): Reconciled = when {
        settings.locationMode != LocationMode.Automatic -> Reconciled(settings, null)

        !locationGranted -> Reconciled(
            settings.copy(locationMode = LocationMode.Manual),
            LOCATION_REMOVED_NOTE,
        )

        settings.locationCadence is LocationCadence.Every && !backgroundGranted -> Reconciled(
            settings.copy(
                locationCadence = LocationCadence.OnOpen,
                backgroundLocationRevoked = true,
            ),
            null,
        )

        else -> Reconciled(settings, null)
    }
}
