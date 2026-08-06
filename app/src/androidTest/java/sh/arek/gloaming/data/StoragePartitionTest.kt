package sh.arek.gloaming.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the two preference stores really are two.
 *
 * This is the seam a unit test cannot reach: the split is a property of the
 * Android contexts, not of any logic. It went wrong once already, and silently
 * - `applicationContext` on a device-protected context hands back the ordinary
 * one, so the precise position and its coarse mirror were writing to the same
 * file and the mirror won.
 */
@RunWith(AndroidJUnit4::class)
class StoragePartitionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearBothStores() {
        credentialPreferences(context).edit().clear().commit()
        devicePreferences(context).edit().clear().commit()
    }

    @Test
    fun credentialAndDeviceStoresAreDistinct() {
        credentialPreferences(context).edit().putString("marker", "credential").commit()
        devicePreferences(context).edit().putString("marker", "device").commit()

        assertEquals("credential", credentialPreferences(context).getString("marker", null))
        assertEquals("device", devicePreferences(context).getString("marker", null))
    }

    @Test
    fun writingTheMirrorDoesNotDisturbThePreciseLocation() {
        val settings = UserSettings(
            latitude = 55.95,
            longitude = -3.19,
            alertsBeforeUnlock = true,
        )

        BootAware.saveSettings(context, settings)

        // What the user sees comes back exactly as typed.
        val shown = PreferencesSettingsStore(credentialPreferences(context)).read()
        assertEquals(55.95, shown.latitude!!, 0.0)
        assertEquals(-3.19, shown.longitude!!, 0.0)

        // What survives a reboot is only the whole degree - a real longitude,
        // not the grid's own 0-359 column, so anything geographic that reads it
        // gets an answer it can use.
        val mirrored = PreferencesSettingsStore(devicePreferences(context)).read()
        assertEquals(56.0, mirrored.latitude!!, 0.0)
        assertEquals(-3.0, mirrored.longitude!!, 0.0)
        assertNotEquals(shown.longitude, mirrored.longitude, 0.0)
        // And it still lands on the cell the precise position does, so a poll
        // before unlock reads the same part of the grid as one after it.
        assertEquals(settings.cell, mirrored.cell)
    }

    @Test
    fun turningTheOptionOffDeletesTheMirroredLocation() {
        BootAware.saveSettings(
            context,
            UserSettings(latitude = 55.95, longitude = -3.19, alertsBeforeUnlock = true),
        )

        BootAware.saveSettings(
            context,
            UserSettings(latitude = 55.95, longitude = -3.19, alertsBeforeUnlock = false),
        )

        val mirrored = PreferencesSettingsStore(devicePreferences(context)).read()
        assertNull("the coarse location outlived the opt-out", mirrored.latitude)
        assertEquals(false, mirrored.alertsBeforeUnlock)
        // The precise copy is untouched by any of it.
        assertEquals(
            55.95,
            PreferencesSettingsStore(credentialPreferences(context)).read().latitude!!,
            0.0,
        )
    }

    @Test
    fun beforeUnlockFlagIsReadableFromTheDeviceStore() {
        BootAware.saveSettings(
            context,
            UserSettings(latitude = 55.95, longitude = -3.19, alertsBeforeUnlock = true),
        )

        assertEquals(true, BootAware.beforeUnlockEnabled(context))
    }

    @Test
    fun pollReadsTheMirrorWhenBeforeUnlockIsOn() {
        BootAware.saveSettings(
            context,
            UserSettings(latitude = 55.95, longitude = -3.19, alertsBeforeUnlock = true),
        )

        // The polling path must see the cell, which is what a locked wake has.
        val cell = BootAware.pollSettings(context).read().cell!!
        assertEquals(GridCell(56, 357), cell)
    }

    @Test
    fun pollReadsThePreciseStoreWhenBeforeUnlockIsOff() {
        BootAware.saveSettings(
            context,
            UserSettings(latitude = 55.95, longitude = -3.19, alertsBeforeUnlock = false),
        )

        assertEquals(55.95, BootAware.pollSettings(context).read().latitude!!, 0.0)
    }

    /** The caches are public data and live in device storage unconditionally. */
    @Test
    fun deviceStorageIsUsableForCaches() {
        val directory = java.io.File(BootAware.deviceContext(context).filesDir, "sky-test")
        val store = FileManifestStore(directory)

        store.write(CachedManifest("{}".toByteArray(), ByteArray(64)))

        assertEquals("{}", String(store.read()!!.body))
        directory.deleteRecursively()
    }
}
