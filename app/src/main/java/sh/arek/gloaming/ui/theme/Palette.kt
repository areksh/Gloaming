package sh.arek.gloaming.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Every colour a screen draws with, by role rather than by hue.
 *
 * Screens read these from [LocalPalette] instead of reaching for Material's
 * scheme, because the design needs roles Material has no name for (the sky
 * ramp, the cloud hatch, the rows dimmed as unseen). The Material scheme is
 * derived from the same values so stock components agree with them.
 *
 * @property dim rows that will not be seen: still legible, visibly set back.
 * @property sky the night column's backdrop at sun altitude 0, -6, -12, -18
 *   degrees and below; read it through [skyColour].
 * @property hatch the cloud pattern, the text colour at partial alpha.
 */
data class Palette(
    val ground: Color,
    val raised: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val alarm: Color,
    val dim: Color,
    val sky: List<Color>,
    val hatch: Color,
)

private val DaySky = listOf(
    Color(0xFFD2A7BC),
    Color(0xFF7189BD),
    Color(0xFF34487F),
    Color(0xFF1B2452),
    Color(0xFF090E24),
)

object Palettes {
    val Dark = Palette(
        ground = Color(0xFF171B2C),
        raised = Color(0xFF20253A),
        text = Color(0xFFECEEF6),
        muted = Color(0xFFA3AAC4),
        line = Color(0xFF3A405C),
        accent = Color(0xFFF0B8CC),
        onAccent = Color(0xFF171B2C),
        alarm = Color(0xFFF3D08A),
        dim = Color(0xFF7F87A3),
        sky = DaySky,
        hatch = Color(0xFFECEEF6).copy(alpha = 0.42f),
    )

    val Light = Palette(
        ground = Color(0xFFEEF0F6),
        raised = Color(0xFFFFFFFF),
        text = Color(0xFF1A1E30),
        muted = Color(0xFF545B75),
        line = Color(0xFFC9CDDB),
        accent = Color(0xFF9C3D63),
        onAccent = Color(0xFFFFFFFF),
        alarm = Color(0xFF8A5A00),
        dim = Color(0xFF656B83),
        sky = DaySky,
        hatch = Color(0xFF1A1E30).copy(alpha = 0.42f),
    )

    /**
     * Pure red on black; see [NightRed] for why. Built from the same constants
     * as `NightColorScheme`, so the two cannot drift apart.
     */
    val Night = Palette(
        ground = NightBlack,
        raised = NightBlack,
        text = NightRed,
        muted = NightRedDim,
        line = NightRedFaint,
        accent = NightRed,
        onAccent = NightBlack,
        alarm = NightRed,
        dim = NightRedDim,
        sky = listOf(
            Color(0xFF9A0000),
            Color(0xFF6A0000),
            Color(0xFF420000),
            Color(0xFF260000),
            Color(0xFF120000),
        ),
        hatch = NightRed.copy(alpha = 0.55f),
    )
}

val LocalPalette = staticCompositionLocalOf { Palettes.Dark }

/** Sun altitudes, in degrees, the ramp's five stops sit at. The last is "anything lower". */
private val SKY_STOPS = doubleArrayOf(0.0, -6.0, -12.0, -18.0, -24.0)

/**
 * The sky behind a moment, blended between the ramp's stops. Altitudes above
 * the first stop or below the last take the end colour.
 */
fun skyColour(palette: Palette, sunAltitude: Double): Color {
    val sky = palette.sky
    if (sunAltitude >= SKY_STOPS.first()) return sky.first()
    if (sunAltitude <= SKY_STOPS.last()) return sky.last()
    val i = (1 until SKY_STOPS.size).first { sunAltitude > SKY_STOPS[it] }
    val t = ((SKY_STOPS[i - 1] - sunAltitude) / (SKY_STOPS[i - 1] - SKY_STOPS[i])).toFloat()
    return lerp(sky[i - 1], sky[i], t)
}
