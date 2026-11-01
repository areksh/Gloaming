package sh.arek.gloaming.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceExplainerTest {

    @Test
    fun `tells Android 12 and later to leave precise location off`() {
        val text = cadenceExplainerText(sdk = 31)

        assertTrue(text.contains("\"Allow all the time\""))
        assertTrue(text.contains("\"Use precise location\""))
    }

    @Test
    fun `does not mention a precise location toggle before Android 12`() {
        val text = cadenceExplainerText(sdk = 30)

        assertTrue(text.contains("\"Allow all the time\""))
        assertFalse(text.contains("precise"))
    }
}
