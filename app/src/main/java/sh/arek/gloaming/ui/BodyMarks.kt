package sh.arek.gloaming.ui

import androidx.compose.ui.graphics.Color
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.ui.theme.Palette
import sh.arek.gloaming.ui.theme.Palettes

/** Text presentation, so a phone never swaps the symbol for a coloured emoji. */
private const val TEXT_STYLE = "︎"

/**
 * A body's astronomical symbol, which is what tells two planets apart in
 * night vision, where every one of them is the same red.
 */
fun glyph(body: SkyBody): String = when (body) {
    SkyBody.Mercury -> "☿"
    SkyBody.Venus -> "♀"
    SkyBody.Mars -> "♂"
    SkyBody.Jupiter -> "♃"
    SkyBody.Saturn -> "♄"
    SkyBody.Moon -> "☾"
    SkyBody.Sun -> "☉"
} + TEXT_STYLE

const val COMET_GLYPH = "☄$TEXT_STYLE"

/**
 * Roughly the colour each looks to the eye, for telling bars apart at a
 * glance. Never the only difference: each also has its symbol and its name.
 * Night vision has only its red to give, so there every one is the text.
 */
fun bodyColour(body: SkyBody?, palette: Palette): Color {
    if (palette == Palettes.Night) return palette.text
    return when (body) {
        SkyBody.Mercury -> Color(0xFF9FB7D9)
        SkyBody.Venus -> Color(0xFFF3E6B3)
        SkyBody.Mars -> Color(0xFFEE6A55)
        SkyBody.Jupiter -> Color(0xFFE79A6B)
        SkyBody.Saturn -> Color(0xFFD9B45A)
        SkyBody.Moon, SkyBody.Sun, null -> palette.text
    }
}

/** A comet's bar, greenish as a bright one looks. */
fun cometColour(palette: Palette): Color = if (palette == Palettes.Night) palette.text else Color(0xFF9FE0C9)
