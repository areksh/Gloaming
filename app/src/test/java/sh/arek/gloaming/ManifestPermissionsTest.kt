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
        // Held while a scheduled poll runs so the device does not sleep mid-fetch.
        "android.permission.WAKE_LOCK",
        // Re-arms the alarm after a reboot; alarms do not survive a restart.
        "android.permission.RECEIVE_BOOT_COMPLETED",
        // Sends the user to the exemption setting that lifts Doze restrictions.
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        // Posting the aurora notification on Android 13 and later.
        "android.permission.POST_NOTIFICATIONS",
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

    @Test
    fun `asks for no location permission`() {
        // Location is typed in. Asking for the permission would undercut the
        // whole privacy posture for something the app does not need.
        val location = declaredPermissions().filter { it.contains("LOCATION") }
        assertEquals(emptyList<String>(), location)
    }
}
