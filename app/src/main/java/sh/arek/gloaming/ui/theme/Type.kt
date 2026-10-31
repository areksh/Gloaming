package sh.arek.gloaming.ui.theme

import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import sh.arek.gloaming.R

/**
 * Atkinson Hyperlegible Next, bundled as one variable font (SIL OFL; see
 * `fonts/README.md`).
 *
 * The weight axis needs API 26. On 24 and 25 the file renders at its default
 * weight and Compose thickens bold text synthetically, so those phones read
 * correctly with fewer distinct weights rather than carrying four static files
 * for them.
 */
@OptIn(ExperimentalTextApi::class)
val Gloaming: FontFamily =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        FontFamily(
            listOf(400, 500, 600, 700).map { w ->
                Font(
                    R.font.atkinson_hyperlegible_next,
                    weight = FontWeight(w),
                    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                )
            },
        )
    } else {
        FontFamily(Font(R.font.atkinson_hyperlegible_next, FontWeight.Normal))
    }

/** Tabular figures, so a column of times lines up and a ticking one does not jitter. */
private const val TABULAR = "tnum"

private val base = Typography()

/** The scale is 13, 15, 18, 20, 24 and 28 sp; nothing else is drawn. */
val Typography = Typography(
    displaySmall = base.displaySmall.copy(
        fontFamily = Gloaming, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = base.headlineMedium.copy(
        fontFamily = Gloaming, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = base.headlineSmall.copy(
        fontFamily = Gloaming, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = base.titleLarge.copy(
        fontFamily = Gloaming, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = base.titleMedium.copy(
        fontFamily = Gloaming, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleSmall = base.titleSmall.copy(
        fontFamily = Gloaming, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Gloaming, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Gloaming, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Gloaming, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Gloaming, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    labelMedium = TextStyle(
        fontFamily = Gloaming, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
    labelSmall = TextStyle(
        fontFamily = Gloaming, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, fontFeatureSettings = TABULAR,
    ),
)
