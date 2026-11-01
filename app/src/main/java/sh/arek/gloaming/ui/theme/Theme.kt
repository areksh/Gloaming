package sh.arek.gloaming.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun Palette.toColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            tertiary = accent,
            onTertiary = onAccent,
            background = ground,
            onBackground = text,
            surface = ground,
            onSurface = text,
            surfaceVariant = raised,
            onSurfaceVariant = muted,
            surfaceContainerLow = raised,
            surfaceContainer = raised,
            surfaceContainerHigh = raised,
            outline = line,
            outlineVariant = line,
            error = alarm,
            onError = onAccent,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            secondary = accent,
            onSecondary = onAccent,
            tertiary = accent,
            onTertiary = onAccent,
            background = ground,
            onBackground = text,
            surface = ground,
            onSurface = text,
            surfaceVariant = raised,
            onSurfaceVariant = muted,
            surfaceContainerLow = raised,
            surfaceContainer = raised,
            surfaceContainerHigh = raised,
            outline = line,
            outlineVariant = line,
            error = alarm,
            onError = onAccent,
        )
    }

/**
 * Red on black, for use outside in the dark.
 *
 * Dark adaptation takes twenty to thirty minutes to build and one glance at a
 * white screen to lose. This removes the green and blue channels entirely
 * rather than dimming or tinting: a dimmed white screen still emits across the
 * spectrum, and rod cells are nearly blind only above about 620 nm.
 *
 * Surfaces are pure black, which on the OLED panels these phones use means
 * those pixels are simply off - no glow at all when the app is open on a
 * hillside.
 */
internal val NightColorScheme = darkColorScheme(
    primary = NightRed,
    onPrimary = NightBlack,
    secondary = NightRedDim,
    onSecondary = NightBlack,
    tertiary = NightRedDim,
    onTertiary = NightBlack,
    background = NightBlack,
    onBackground = NightRed,
    surface = NightBlack,
    onSurface = NightRed,
    surfaceVariant = NightBlack,
    onSurfaceVariant = NightRedDim,
    outline = NightRedFaint,
    outlineVariant = NightRedFaint,
    error = NightRed,
    onError = NightBlack,
)

@Composable
fun GloamingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Overrides everything else when on. A user who turned this on is standing
     * outside in the dark, and the system's theme is not relevant to them.
     */
    nightVision: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = when {
        nightVision -> Palettes.Night
        darkTheme -> Palettes.Dark
        else -> Palettes.Light
    }
    val colorScheme = if (nightVision) NightColorScheme else palette.toColorScheme(darkTheme)

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
