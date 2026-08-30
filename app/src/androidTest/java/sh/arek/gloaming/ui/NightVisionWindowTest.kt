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
import sh.arek.gloaming.work.RealSchedule

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
        // Launching the activity arms an alarm at whatever the settings said at
        // the time, so the phone's own schedule is put back rather than left
        // cancelled - a suite must not take a phone off watch for the night.
        RealSchedule.restore(context)
    }

    private fun brightnessWith(nightVision: Boolean): Float {
        // One field changed on this phone's own settings, not a fresh default
        // object written over them. A default carries every other setting with
        // it - the location, the thresholds, whether alerting before first
        // unlock is on - and the restore afterwards is then only as good as
        // what `original` happened to capture. Changing the one field under
        // test leaves nothing else to put back.
        BootAware.saveSettings(context, original.copy(nightVision = nightVision))

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
