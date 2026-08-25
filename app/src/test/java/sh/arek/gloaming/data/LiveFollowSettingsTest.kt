package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tracker's two numbers, which have to stay consistent with each other.
 *
 * Both are the user's to set - how far somebody has to walk to see the sky is
 * not something the app can know - but they are not independent. A cap shorter
 * than the lead would stop the tracker before the satellite had even risen,
 * which is the single moment the whole feature exists for.
 */
class LiveFollowSettingsTest {

    @Test
    fun `uses the lead and cap as set`() {
        val settings = UserSettings(liveFollowLeadMinutes = 5, liveFollowMaxMinutes = 45)

        assertEquals(5 * 60_000L, settings.liveFollowLeadMillis)
        assertEquals(45 * 60_000L, settings.liveFollowMaxMillis)
    }

    @Test
    fun `never lets the cap cut the lead short`() {
        val settings = UserSettings(liveFollowLeadMinutes = 20, liveFollowMaxMinutes = 5)

        // Room for the lead and a pass after it, whatever was asked for.
        assertEquals(30 * 60_000L, settings.liveFollowMaxMillis)
    }
}
