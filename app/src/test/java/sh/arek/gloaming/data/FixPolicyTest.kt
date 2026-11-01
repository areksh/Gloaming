package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the foreground asks the system for a new fix, and which providers it asks. */
class FixPolicyTest {

    private val now = 1_800_000_000_000L
    private val all = setOf("fused", "network", "gps")

    private fun fix(ageMillis: Long) = DeviceFix(
        latitude = 55.95,
        longitude = -3.19,
        accuracyMetres = 2000f,
        atMillis = now - ageMillis,
    )

    @Test
    fun `a fix from five minutes ago is recent enough`() {
        assertFalse(FixPolicy.needsFresh(fix(ageMillis = 300_000), now))
    }

    @Test
    fun `a fix older than ten minutes needs a fresh one`() {
        assertTrue(FixPolicy.needsFresh(fix(ageMillis = 600_001), now))
    }

    @Test
    fun `no fix at all needs a fresh one`() {
        assertTrue(FixPolicy.needsFresh(null, now))
    }

    @Test
    fun `the platform fused provider comes first where it exists`() {
        assertEquals(listOf("fused", "network", "gps"), FixPolicy.providers(31, all))
    }

    @Test
    fun `below Android 12 there is no platform fused provider`() {
        assertEquals(listOf("network", "gps"), FixPolicy.providers(30, all))
    }

    @Test
    fun `a provider switched off is not asked`() {
        assertEquals(listOf("gps"), FixPolicy.providers(34, setOf("gps")))
    }

    @Test
    fun `with every provider off nothing is asked`() {
        assertTrue(FixPolicy.providers(34, emptySet()).isEmpty())
    }
}
