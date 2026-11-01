package sh.arek.gloaming.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the three palettes can actually be read, and that night vision stays
 * pure red. The hex values are the spec's; these tests hold the properties the
 * values exist to have, so a later tweak that looks fine on one phone cannot
 * quietly break another.
 */
class PaletteTest {

    private val all = mapOf(
        "dark" to Palettes.Dark,
        "light" to Palettes.Light,
        "night" to Palettes.Night,
    )

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `every text colour meets 4 point 5 to 1 on its ground`() {
        for ((name, p) in all) {
            assertTrue("$name text", contrast(p.text, p.ground) >= 4.5)
            assertTrue("$name text on raised", contrast(p.text, p.raised) >= 4.5)
            assertTrue("$name accent", contrast(p.accent, p.ground) >= 4.5)
            assertTrue("$name onAccent", contrast(p.onAccent, p.accent) >= 4.5)
            assertTrue("$name alarm", contrast(p.alarm, p.ground) >= 4.5)
        }
        // Night vision's muted red is deliberately dimmer than text, as the
        // spec has it: it is the only way to rank two reds, and a brighter
        // one would cost the adaptation night vision exists to protect.
        for (name in listOf("dark", "light")) {
            val p = all.getValue(name)
            assertTrue("$name muted", contrast(p.muted, p.ground) >= 4.5)
            assertTrue("$name muted on raised", contrast(p.muted, p.raised) >= 4.5)
        }
    }

    @Test
    fun `night vision carries no green or blue anywhere`() {
        val p = Palettes.Night
        val colours = listOf(
            p.ground, p.raised, p.text, p.muted, p.line, p.accent, p.onAccent, p.alarm,
            p.dim, p.hatch,
        ) + p.sky
        for (c in colours) {
            assertEquals("green in $c", 0f, c.green, 0f)
            assertEquals("blue in $c", 0f, c.blue, 0f)
        }
    }

    @Test
    fun `night vision grounds are unlit`() {
        assertEquals(Color.Black, Palettes.Night.ground)
        assertEquals(Color.Black, Palettes.Night.raised)
    }

    @Test
    fun `the sky ramp darkens as the sun sinks`() {
        for ((name, p) in all) {
            val lums = listOf(0.0, -6.0, -12.0, -18.0, -24.0).map { luminance(skyColour(p, it)) }
            for (i in 1 until lums.size) {
                assertTrue("$name at step $i: $lums", lums[i] < lums[i - 1])
            }
        }
    }

    @Test
    fun `the sky ramp holds its ends outside the stops`() {
        val p = Palettes.Dark
        assertEquals(p.sky.first(), skyColour(p, 40.0))
        assertEquals(p.sky.last(), skyColour(p, -60.0))
    }

    @Test
    fun `the sky ramp blends between stops`() {
        val p = Palettes.Dark
        val mid = skyColour(p, -3.0)
        assertEquals((p.sky[0].red + p.sky[1].red) / 2, mid.red, 0.002f)
    }

    @Test
    fun `cloud hatch is the text colour, fainter by day than in night vision`() {
        assertEquals(0.42f, Palettes.Dark.hatch.alpha, 0.005f)
        assertEquals(Palettes.Dark.text.red, Palettes.Dark.hatch.red, 0.005f)
        assertEquals(0.55f, Palettes.Night.hatch.alpha, 0.005f)
    }
}
