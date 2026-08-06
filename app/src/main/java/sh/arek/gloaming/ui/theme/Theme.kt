package sh.arek.gloaming.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

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
     * Overrides everything else when on, including dynamic colour. A user who
     * turned this on is standing outside in the dark, and the system's opinion
     * about wallpaper colours is not relevant to them.
     */
    nightVision: Boolean = false,
    // Dynamic colour is available on Android 12+.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        nightVision -> NightColorScheme

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
