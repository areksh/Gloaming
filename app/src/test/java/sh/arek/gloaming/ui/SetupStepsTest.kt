package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStepsTest {

    @Test
    fun `asks about notifications from Android 13`() {
        assertEquals(SetupStep.entries, setupSteps(sdk = 33))
    }

    @Test
    fun `leaves out notifications below Android 13`() {
        assertEquals(
            listOf(SetupStep.Location, SetupStep.BackgroundChecks),
            setupSteps(sdk = 32),
        )
    }
}
