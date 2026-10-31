package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Choosing between the fixes the device offers.
 *
 * `LocationManager` hands back a last-known position per provider, and they
 * disagree: the network provider answers instantly and coarsely, GPS answers
 * precisely and may be hours old because the phone was indoors. Picking well
 * is pure arithmetic over what they returned, so it is tested here and the
 * Android glue stays thin enough not to need a device.
 */
class DeviceLocationTest {

    private val now = 1_800_000_000_000L

    private fun fix(
        ageMinutes: Long,
        accuracy: Float,
        latitude: Double = 55.95,
        longitude: Double = -3.19,
    ) = DeviceFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = accuracy,
        atMillis = now - ageMinutes * 60_000L,
    )

    @Test
    fun `prefers the more accurate of two recent fixes`() {
        val coarse = fix(ageMinutes = 2, accuracy = 2000f)
        val fine = fix(ageMinutes = 5, accuracy = 30f)

        assertEquals(fine, LocationChoice.best(listOf(coarse, fine), now))
    }

    /**
     * A precise fix from three hours ago is a precise answer to where the phone
     * was, which is not the question. Somebody who has driven to a dark site
     * would be given the town they left.
     */
    @Test
    fun `discards a stale fix however accurate it is`() {
        val ancient = fix(ageMinutes = 180, accuracy = 5f)
        val recent = fix(ageMinutes = 3, accuracy = 1500f)

        assertEquals(recent, LocationChoice.best(listOf(ancient, recent), now))
    }

    @Test
    fun `finds nothing when every fix is stale`() {
        assertNull(LocationChoice.best(listOf(fix(600, 5f), fix(900, 10f)), now))
    }

    @Test
    fun `finds nothing when there are no fixes at all`() {
        assertNull(LocationChoice.best(emptyList(), now))
    }

    /** A fix dated in the future is a clock correction, not a better answer. */
    @Test
    fun `ignores a fix from the future`() {
        val future = DeviceFix(55.95, -3.19, 5f, now + 60 * 60_000L)
        val ordinary = fix(ageMinutes = 10, accuracy = 900f)

        assertEquals(ordinary, LocationChoice.best(listOf(future, ordinary), now))
    }

    /**
     * A fix good to fifty kilometres is worse than the grid cell the app would
     * have used anyway, and would move a satellite pass by a visible amount.
     */
    @Test
    fun `discards a fix too vague to be worth having`() {
        assertNull(LocationChoice.best(listOf(fix(ageMinutes = 1, accuracy = 60_000f)), now))
    }

    /** An unknown accuracy is treated as the worst usable case, not as perfect. */
    @Test
    fun `does not treat an unknown accuracy as a perfect one`() {
        val unknown = DeviceFix(55.95, -3.19, accuracyMetres = 0f, atMillis = now)
        val known = fix(ageMinutes = 1, accuracy = 100f)

        assertEquals(known, LocationChoice.best(listOf(unknown, known), now))
    }

    /** Nonsense coordinates are refused rather than stored and puzzled over later. */
    @Test
    fun `refuses coordinates outside the world`() {
        assertNull(LocationChoice.best(listOf(fix(1, 10f, latitude = 91.0)), now))
        assertNull(LocationChoice.best(listOf(fix(1, 10f, longitude = 181.0)), now))
    }

    /** The null island is where a broken fix lands, and is almost never real. */
    @Test
    fun `refuses a fix at exactly zero`() {
        assertNull(LocationChoice.best(listOf(fix(1, 10f, latitude = 0.0, longitude = 0.0)), now))
    }

    /** A fix just asked for beats an older cached one that is no better. */
    @Test
    fun `prefers a fresh fix to an older cached one of the same accuracy`() {
        val old = fix(ageMinutes = 10, accuracy = 1500f)
        val fresh = fix(ageMinutes = 0, accuracy = 1500f, latitude = 56.0)

        assertEquals(fresh, LocationChoice.best(listOf(old, fresh), now))
        assertEquals(fresh, LocationChoice.best(listOf(fresh, old), now))
    }
}
