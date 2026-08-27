package sh.arek.gloaming

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.arek.gloaming.work.StormService

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
        // StormService, which cannot start in the foreground without both.
        assertTrue(
            "FOREGROUND_SERVICE missing",
            declared.contains("android.permission.FOREGROUND_SERVICE"),
        )
        assertTrue(
            "FOREGROUND_SERVICE_SPECIAL_USE missing",
            declared.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),
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
            // Coarse only, foreground only, and never required - the app works
            // with it denied forever. ManifestPermissionsTest guards that fine
            // and background location stay out.
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.WAKE_LOCK,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            // Calendar sync, which is off by default and asked for only when
            // somebody switches it on.
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
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
     * Every receiver is named, so one cannot appear without somebody deciding
     * whether it needs to survive a locked boot.
     *
     * The scheduling receivers must: a poll before first unlock is the entire
     * point of the before-unlock work. The two pass receivers must not, and the
     * asymmetry is deliberate rather than an omission - both lead to a
     * foreground service, which cannot be started before the device has been
     * unlocked once, so being delivered early would produce a crash where there
     * is currently nothing at all.
     *
     * The verdict receiver must, and can: everything it touches - the verdict
     * log, the journal and the alert state - already lives in device-protected
     * storage. Before-unlock alerting is a supported configuration, so an alert
     * can be posted before first unlock, and a "was it worth it?" button that
     * silently did nothing would be worse than no button.
     */
    @Test
    fun everyReceiverHasDecidedAboutALockedBoot() {
        val ours = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_RECEIVERS,
        ).receivers.orEmpty()
            .filter { it.name.startsWith("sh.arek.gloaming") }
            .associate { it.name to it.directBootAware }

        assertEquals(
            mapOf(
                "sh.arek.gloaming.work.PollReceiver" to true,
                "sh.arek.gloaming.work.BootReceiver" to true,
                "sh.arek.gloaming.widget.AuroraWidget" to true,
                "sh.arek.gloaming.work.PassReceiver" to false,
                "sh.arek.gloaming.work.FollowPassReceiver" to false,
                "sh.arek.gloaming.work.FollowEclipseReceiver" to false,
                "sh.arek.gloaming.work.WorthItReceiver" to true,
            ),
            ours,
        )
    }

    /**
     * Storm mode's service, checked from the installed package rather than by
     * starting it.
     *
     * A `specialUse` service that is missing its type, or is not declared at
     * all, fails only when it is started - and it is started only during an
     * aurora large enough to be worth standing outside for, which is not a
     * condition anyone can reproduce on demand. So the declaration is asserted
     * instead, from what the system actually installed.
     */
    @Test
    fun stormServiceIsInstalledAsSpecialUse() {
        val services = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES,
        ).services.orEmpty()

        val storm = services.singleOrNull { it.name == StormService::class.java.name }
        assertNotNull("StormService is not declared", storm)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            storm!!.foregroundServiceType,
        )
    }
}
