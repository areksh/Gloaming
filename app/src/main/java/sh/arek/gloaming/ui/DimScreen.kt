package sh.arek.gloaming.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** As dim as the platform allows without switching the backlight off entirely. */
internal const val NIGHT_BRIGHTNESS = 0.01f

/**
 * Dims the screen while [enabled], and hands back the brightness that was
 * there before once it is not, or once this leaves the composition.
 *
 * Red on black is most of night vision but not all of it: a bright screen of
 * any colour still floods a dark-adapted eye. Set on this window rather than
 * in system settings, so it applies to this app alone and reverts the moment
 * it closes. Restoring the previous value rather than the system's lets one
 * use sit inside another - Find dimming under an app that is not, or under
 * one already dimmed - and each puts back what it found.
 */
@Composable
fun DimScreen(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val before = activity.window.attributes.screenBrightness
        activity.setBrightness(NIGHT_BRIGHTNESS)
        onDispose { activity.setBrightness(before) }
    }
}

private fun Activity.setBrightness(value: Float) {
    window.attributes = window.attributes.apply { screenBrightness = value }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
