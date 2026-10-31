package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogRetentionTest {
    @Test
    fun `accepts whole days from one to a year`() {
        assertEquals(1, UserSettings.parseLogRetention("1"))
        assertEquals(30, UserSettings.parseLogRetention(" 30 "))
        assertEquals(365, UserSettings.parseLogRetention("365"))
    }

    @Test
    fun `refuses zero, a year and a day, fractions and words`() {
        listOf("0", "366", "2.5", "-3", "", "thirty").forEach {
            assertNull(it, UserSettings.parseLogRetention(it))
        }
    }
}
