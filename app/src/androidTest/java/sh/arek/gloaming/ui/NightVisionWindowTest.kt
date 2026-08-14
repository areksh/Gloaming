package sh.arek.gloaming.ui

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.arek.gloaming.Graph
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.work.PollAlarm

/**
 * That night vision actually dims the screen.
 *
 * The palette is covered by a JVM test, but the dimming is a window attribute
 * the framework applies, and there is no way to observe it without a real
 * activity. It is half the feature: red on black still floods a dark-adapted
 * eye if the backlight is at full.
 *
 * Reads the window rather than the pixels, deliberately: brightness is set on
 * the window and never reaches the composition, so there is nothing for a
 * semantics assertion to find.
 */
@RunWith(AndroidJUnit4::class)
class NightVisionWindowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = Graph.settings(context).read()

    @After
    fun restoreSettings() {
        BootAware.saveSettings(context, original)
        PollAlarm.cancel(context)
    }

    private fun brightnessWith(nightVision: Boolean): Float {
        BootAware.saveSettings(context, UserSettings(nightVision = nightVision))

        var brightness = Float.NaN
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { brightness = it.window.attributes.screenBrightness }
        }
        return brightness
    }

    @Test
    fun dimsTheScreenWhenNightVisionIsOn() {
        val brightness = brightnessWith(nightVision = true)

        assertTrue("screen was left at $brightness", brightness in 0f..0.1f)
    }

    /**
     * And hands it straight back otherwise. A brightness override that leaked
     * would leave the app permanently unreadable in daylight, which is a far
     * more visible bug than the one it came from.
     */
    @Test
    fun leavesTheScreenAloneOtherwise() {
        val brightness = brightnessWith(nightVision = false)

        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            brightness,
            0f,
        )
    }
}
