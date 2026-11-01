package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings

/**
 * What the stored location settings come to once Android's grants are taken
 * into account, decided without Android.
 */
class LocationGrantsTest {

    private fun settings(
        mode: LocationMode = LocationMode.Automatic,
        cadence: LocationCadence = LocationCadence.OnOpen,
        revoked: Boolean = false,
    ) = UserSettings(
        latitude = 55.95,
        longitude = -3.19,
        locationMode = mode,
        locationCadence = cadence,
        backgroundLocationRevoked = revoked,
    )

    @Test
    fun `automatic without the location grant becomes typed in with the position kept`() {
        val result = LocationGrants.reconcile(
            settings(cadence = LocationCadence.Every(3)),
            locationGranted = false,
            backgroundGranted = false,
        )

        assertEquals(LocationMode.Manual, result.settings.locationMode)
        assertEquals(55.95, result.settings.latitude!!, 0.0)
        assertEquals(-3.19, result.settings.longitude!!, 0.0)
        assertEquals(LocationGrants.LOCATION_REMOVED_NOTE, result.note)
    }

    @Test
    fun `an interval without the background grant falls back to each open and says so by the red line`() {
        val result = LocationGrants.reconcile(
            settings(cadence = LocationCadence.Every(3)),
            locationGranted = true,
            backgroundGranted = false,
        )

        assertEquals(LocationMode.Automatic, result.settings.locationMode)
        assertEquals(LocationCadence.OnOpen, result.settings.locationCadence)
        assertTrue(result.settings.backgroundLocationRevoked)
        assertNull(result.note)
    }

    @Test
    fun `each open without the background grant is left alone`() {
        val before = settings(cadence = LocationCadence.OnOpen)

        val result = LocationGrants.reconcile(before, locationGranted = true, backgroundGranted = false)

        assertSame(before, result.settings)
        assertNull(result.note)
    }

    @Test
    fun `typed in settings are left alone whatever is granted`() {
        val before = settings(mode = LocationMode.Manual, cadence = LocationCadence.Every(3))

        val result = LocationGrants.reconcile(before, locationGranted = false, backgroundGranted = false)

        assertSame(before, result.settings)
        assertNull(result.note)
    }

    @Test
    fun `an interval with both grants is left alone`() {
        val before = settings(cadence = LocationCadence.Every(1))

        val result = LocationGrants.reconcile(before, locationGranted = true, backgroundGranted = true)

        assertSame(before, result.settings)
        assertNull(result.note)
    }
}
