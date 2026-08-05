package sh.arek.gloaming

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the installed app declares what its code actually needs.
 *
 * The manifest is not compiled against the code, so a permission can vanish
 * without a single warning and surface only when a background alarm fires at
 * three in the morning. That happened: `WAKE_LOCK` was arriving transitively
 * from WorkManager, and removing WorkManager took it away silently. Every
 * scheduled poll then crashed the app.
 */
@RunWith(AndroidJUnit4::class)
class ManifestDeclarationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requestedPermissions(): List<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions?.toList().orEmpty()
    }

    @Test
    fun declaresEveryPermissionTheCodeUses() {
        val declared = requestedPermissions()

        // PollReceiver holds a partial wake lock while polling.
        assertTrue("WAKE_LOCK missing", declared.contains(android.Manifest.permission.WAKE_LOCK))
        // Every fetch.
        assertTrue("INTERNET missing", declared.contains(android.Manifest.permission.INTERNET))
        // BootReceiver re-arms the alarm; alarms do not survive a restart.
        assertTrue(
            "RECEIVE_BOOT_COMPLETED missing",
            declared.contains(android.Manifest.permission.RECEIVE_BOOT_COMPLETED),
        )
        // The exemption flow sends the user to the system setting.
        assertTrue(
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS missing",
            declared.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        )
        // Posting the aurora notification on Android 13 and later.
        assertTrue(
            "POST_NOTIFICATIONS missing",
            declared.contains("android.permission.POST_NOTIFICATIONS"),
        )
    }

    /**
     * Nothing arrives that the app did not ask for, beyond what the OS itself
     * adds.
     *
     * This catches a dependency contributing a permission during the manifest
     * merge - which is how WAKE_LOCK appeared in the first place, and how it
     * later vanished. What the app's own manifest declares is checked
     * separately and deterministically by ManifestPermissionsTest; the package
     * manager cannot serve that purpose because the OS adds to this list.
     */
    @Test
    fun requestsNothingBeyondItsOwnManifestAndTheSystem() {
        val ours = setOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.WAKE_LOCK,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.POST_NOTIFICATIONS",
        )

        val addedByTheSystem = setOf(
            // GrapheneOS injects its Sensors toggle into every app, so the user
            // can revoke access to sensors no other permission covers. Defined
            // by the platform (sourcePackage=android), not by this manifest.
            "android.permission.OTHER_SENSORS",
        )

        val unexpected = requestedPermissions()
            .filterNot { it in ours }
            .filterNot { it in addedByTheSystem }
            // The build tools synthesise this for runtime-registered receivers.
            .filterNot { it.endsWith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }

        assertEquals("permissions nobody here asked for", emptyList<String>(), unexpected)
    }

    /**
     * Both receivers must survive a reboot into the locked state, which is the
     * entire point of the before-unlock work.
     */
    @Test
    fun schedulingReceiversAreDirectBootAware() {
        val receivers = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_RECEIVERS,
        ).receivers.orEmpty()

        val ours = receivers.filter { it.name.startsWith("sh.arek.gloaming") }
        assertEquals(2, ours.size)
        for (receiver in ours) {
            assertTrue("${receiver.name} is not directBootAware", receiver.directBootAware)
        }
    }
}
