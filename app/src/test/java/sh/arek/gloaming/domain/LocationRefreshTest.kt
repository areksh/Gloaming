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

    // --- a save from a screen that has not seen the latest fix ---

    /** What the screen held when it last read the settings: a fix from yesterday. */
    private val screen = due.copy(
        latitude = 55.95,
        longitude = -3.19,
        locationUpdatedAt = now - 24 * hour,
        threshold = 40,
    )

    /** What is stored now: the background refresh found the phone somewhere else since. */
    private val stored = screen.copy(
        latitude = 57.48,
        longitude = -4.22,
        locationUpdatedAt = now - hour,
        threshold = 30,
    )

    @Test
    fun `a newer stored fix wins over the screen's older one`() {
        val saved = LocationRefresh.keepNewerFix(screen, stored)

        assertEquals(57.48, saved.latitude!!, 0.0)
        assertEquals(-4.22, saved.longitude!!, 0.0)
        assertEquals(now - hour, saved.locationUpdatedAt)
        // Everything else is what the screen is saving.
        assertEquals(40, saved.threshold)
    }

    @Test
    fun `a stored fix counts as newer than none at all`() {
        val saved = LocationRefresh.keepNewerFix(screen.copy(locationUpdatedAt = null), stored)

        assertEquals(57.48, saved.latitude!!, 0.0)
        assertEquals(now - hour, saved.locationUpdatedAt)
    }

    @Test
    fun `the screen's fix stands when it is as new or newer`() {
        val asOld = stored.copy(locationUpdatedAt = now - 24 * hour)
        val undated = stored.copy(locationUpdatedAt = null)

        assertEquals(screen, LocationRefresh.keepNewerFix(screen, asOld))
        assertEquals(screen, LocationRefresh.keepNewerFix(screen, undated))
    }

    /** A typed position is the user's answer, however recent the detected one. */
    @Test
    fun `a typed position is never replaced`() {
        val typed = screen.copy(locationMode = LocationMode.Manual, latitude = 51.5, longitude = -0.12)

        assertEquals(typed, LocationRefresh.keepNewerFix(typed, stored))
    }
}
