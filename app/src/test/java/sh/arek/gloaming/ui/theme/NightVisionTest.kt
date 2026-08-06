package sh.arek.gloaming.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That night vision actually preserves night vision.
 *
 * The whole feature is one property: no green and no blue reaches the eye. A
 * palette that merely looked reddish - a red tint over grey surfaces, or a
 * dimmed white - would pass any visual review and still cost twenty minutes of
 * dark adaptation the first time somebody opened the app on a hillside.
 */
class NightVisionTest {

    private val foregrounds = mapOf(
        "primary" to NightColorScheme.primary,
        "secondary" to NightColorScheme.secondary,
        "tertiary" to NightColorScheme.tertiary,
        "onBackground" to NightColorScheme.onBackground,
        "onSurface" to NightColorScheme.onSurface,
        "onSurfaceVariant" to NightColorScheme.onSurfaceVariant,
        "outline" to NightColorScheme.outline,
        "error" to NightColorScheme.error,
    )

    private val surfaces = mapOf(
        "background" to NightColorScheme.background,
        "surface" to NightColorScheme.surface,
        "surfaceVariant" to NightColorScheme.surfaceVariant,
        "onPrimary" to NightColorScheme.onPrimary,
    )

    @Test
    fun `emits no green or blue at all`() {
        for ((name, colour) in foregrounds) {
            assertEquals("$name has green in it", 0f, colour.green, 0f)
            assertEquals("$name has blue in it", 0f, colour.blue, 0f)
        }
    }

    @Test
    fun `everything readable is actually red`() {
        for ((name, colour) in foregrounds) {
            assertTrue("$name is not lit at all", colour.red > 0f)
        }
    }

    /**
     * Pure black, not near-black. These are OLED panels, so a black pixel is an
     * unlit pixel - no glow from the phone at all beyond the few characters
     * actually being read.
     */
    @Test
    fun `every surface is unlit`() {
        for ((name, colour) in surfaces) {
            assertEquals("$name is not black", Color.Black.value, colour.value)
        }
    }

    // Text has to stay legible, or the mode gets switched off and the point is
    // lost. The brightest red is well clear of the dimmest.
    @Test
    fun `keeps enough contrast between its reds to read`() {
        assertTrue(NightColorScheme.primary.red > NightColorScheme.outline.red + 0.4f)
    }
}
