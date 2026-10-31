package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings

class LocationRefreshTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    /** Detected, every six hours, last refreshed seven hours ago: due. */
    private val due = UserSettings(
        locationMode = LocationMode.Automatic,
        locationCadence = LocationCadence.Every(6),
        locationUpdatedAt = now - 7 * hour,
    )

    private fun skip(
        settings: UserSettings = due,
        unlocked: Boolean = true,
        backgroundPermission: Boolean = true,
    ): RefreshSkip? = LocationRefresh.skip(settings, unlocked, backgroundPermission, now)

    @Test
    fun `skips while typed in`() {
        assertEquals(RefreshSkip.NotAutomatic, skip(due.copy(locationMode = LocationMode.Manual)))
    }

    @Test
    fun `skips a cadence that only runs when the app opens`() {
        assertEquals(
            RefreshSkip.NotBackground,
            skip(due.copy(locationCadence = LocationCadence.OnOpen)),
        )
        assertEquals(
            RefreshSkip.NotBackground,
            skip(due.copy(locationCadence = LocationCadence.Manual)),
        )
        // The cadence is named ahead of a missing grant: it is the reason the
        // user can act on, and the grant does not matter for it.
        assertEquals(
            RefreshSkip.NotBackground,
            skip(due.copy(locationCadence = LocationCadence.OnOpen), backgroundPermission = false),
        )
    }

    @Test
    fun `skips before first unlock`() {
        assertEquals(RefreshSkip.Locked, skip(unlocked = false))
    }

    @Test
    fun `skips without the background permission`() {
        assertEquals(RefreshSkip.NoPermission, skip(backgroundPermission = false))
    }

    @Test
    fun `runs when an interval is due`() {
        assertNull(skip())
    }

    @Test
    fun `runs when the position has never been refreshed`() {
        assertNull(skip(due.copy(locationUpdatedAt = null)))
    }

    @Test
    fun `skips an interval not yet due`() {
        assertEquals(RefreshSkip.NotDue, skip(due.copy(locationUpdatedAt = now - 5 * hour)))
    }

    @Test
    fun `next refresh is the interval after the last one`() {
        val settings = due.copy(locationUpdatedAt = now - 2 * hour)

        assertEquals(now + 4 * hour, LocationRefresh.nextAt(settings, now))
    }

    /**
     * Never refreshed means overdue, not "a whole interval from whenever this
     * was asked": the latter pushed a 48 hour cadence's first refresh two days
     * out, and again on every save that re-armed it.
     */
    @Test
    fun `a never-refreshed interval tries soon`() {
        val settings = due.copy(
            locationCadence = LocationCadence.Every(48),
            locationUpdatedAt = null,
        )

        assertEquals(now + 15 * 60_000L, LocationRefresh.nextAt(settings, now))
    }

    @Test
    fun `never sooner than a quarter of an hour`() {
        val settings = due.copy(
            locationCadence = LocationCadence.Every(1),
            locationUpdatedAt = now - 2 * hour,
        )

        assertEquals(now + 15 * 60_000L, LocationRefresh.nextAt(settings, now))
    }

    @Test
    fun `no alarm for cadences that do not run in the background`() {
        assertNull(LocationRefresh.nextAt(due.copy(locationCadence = LocationCadence.OnOpen), now))
        assertNull(LocationRefresh.nextAt(due.copy(locationCadence = LocationCadence.Manual), now))
    }

    @Test
    fun `no alarm while typed in`() {
        assertNull(LocationRefresh.nextAt(due.copy(locationMode = LocationMode.Manual), now))
    }
}
