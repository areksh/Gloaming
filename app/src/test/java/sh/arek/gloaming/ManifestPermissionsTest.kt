package sh.arek.gloaming

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app's own manifest declares, read from the source file.
 *
 * The permission list is the first thing this audience reads, so it should only
 * ever grow deliberately. Checking the source manifest rather than the
 * installed package keeps this deterministic: the OS can add permissions of its
 * own at install time - GrapheneOS injects its `OTHER_SENSORS` toggle into every
 * app - and a dependency can contribute more during the manifest merge, neither
 * of which is a change anyone made here.
 */
class ManifestPermissionsTest {

    /** Every permission this app intends to ask for. Grow only on purpose. */
    private val intended = setOf(
        // Fetching the manifest, status and grid.
        "android.permission.INTERNET",
        // Offered as a convenience, never required: coarse only, foreground
        // only, and the app is fully functional with it denied forever.
        "android.permission.ACCESS_COARSE_LOCATION",
        // Held while a scheduled poll runs so the device does not sleep mid-fetch.
        "android.permission.WAKE_LOCK",
        // Re-arms the alarm after a reboot; alarms do not survive a restart.
        "android.permission.RECEIVE_BOOT_COMPLETED",
        // Sends the user to the exemption setting that lifts Doze restrictions.
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        // Posting the aurora notification on Android 13 and later.
        "android.permission.POST_NOTIFICATIONS",
        // Storm mode only: following a display that is overhead right now.
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        // The one optional feature that needs a permission: writing starred
        // events into the phone's calendar. Off by default, requested only when
        // somebody turns it on, and the Share and Export buttons on every event
        // need neither of these.
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
    )

    private fun manifestSource(): File =
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("could not locate AndroidManifest.xml from ${File("").absolutePath}")

    private fun declaredPermissions(): Set<String> =
        Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifestSource().readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `declares exactly the permissions the app intends to ask for`() {
        assertEquals(intended, declaredPermissions())
    }

    /**
     * The one that actually bit: WAKE_LOCK arrived transitively from
     * WorkManager, and removing WorkManager took it away with no build error.
     * Every scheduled poll then crashed.
     */
    @Test
    fun `declares WAKE_LOCK, which the poll receiver cannot run without`() {
        assertTrue(declaredPermissions().contains("android.permission.WAKE_LOCK"))
    }

    /**
     * `specialUse` needs the subtype property alongside it from `targetSdk` 34.
     * Without it the service will not start - and it is the one code path that
     * only runs during an event nobody is going to reproduce on demand.
     */
    @Test
    fun `declares the special-use subtype the foreground service needs`() {
        val manifest = manifestSource().readText()

        assertTrue(manifest.contains("""android:foregroundServiceType="specialUse""""))
        assertTrue(
            manifest.contains("""android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE""""),
        )
    }

    /**
     * Narrowed rather than dropped.
     *
     * This began as "asks for no location permission at all", which was right
     * while the position was only ever typed in. Detecting it is now offered as
     * the convenience Sec. 7 always described - manual stays first-class and
     * the app is fully functional with this denied - so the guard moves to
     * where the risk actually is: asking for *more* location than the app uses.
     *
     * Coarse is genuinely all it uses. The aurora grid is one-degree cells,
     * Open-Meteo is given a position rounded to about a kilometre, and a few
     * kilometres of error moves a satellite pass by a second. Fine location
     * would buy nothing and cost the posture.
     */
    @Test
    fun `asks for coarse location and never fine`() {
        val location = declaredPermissions().filter { it.contains("LOCATION") }

        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), location)
    }

    /**
     * Calendar access is write-shaped, not read-shaped.
     *
     * The app writes rows it created and deletes rows it created, by ids it
     * recorded itself. READ_CALENDAR comes with WRITE because the provider
     * needs a read to find which calendar to write to - not because anything
     * here wants to know what is in somebody's diary. If a future change ever
     * reads an event the app did not write, this test is where the argument for
     * it has to be made.
     */
    @Test
    fun `asks for calendar access only alongside the ability to write`() {
        val declared = declaredPermissions()

        assertEquals(
            "android.permission.READ_CALENDAR" in declared,
            "android.permission.WRITE_CALENDAR" in declared,
        )
    }

    /**
     * The line that must never be crossed. Background location would turn a
     * convenience into tracking, and nothing in the app needs it: a position is
     * read only while somebody is looking at the screen, and the stored one is
     * what every scheduled wake uses.
     */
    @Test
    fun `never asks to know where you are in the background`() {
        // The declarations, not the raw file: the manifest comment says in
        // words that there is no background location, and grepping the text
        // caught its own explanation.
        val declared = declaredPermissions()

        assertTrue(declared.toString(), "android.permission.ACCESS_BACKGROUND_LOCATION" !in declared)
        assertTrue(declared.toString(), "android.permission.ACCESS_FINE_LOCATION" !in declared)
    }
}
