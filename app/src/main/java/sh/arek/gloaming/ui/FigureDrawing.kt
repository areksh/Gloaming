package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.ConstellationFigures
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.SkyPositions
import sh.arek.gloaming.astronomy.SkyProjection
import sh.arek.gloaming.astronomy.placedAt
import sh.arek.gloaming.ui.theme.LocalPalette
import sh.arek.gloaming.ui.theme.Palette

/** The drawing of a pattern in its sheet. */
const val FIGURE_SKETCH_TAG = "figure-sketch"

/** Above this a direction stops meaning much: the pattern is over your head. */
private const val OVERHEAD_DEGREES = 75.0

/**
 * How large a star's dot is drawn, in dp: brighter is larger, between bounds
 * that keep Sirius from swamping a figure and a faint star from vanishing.
 */
internal fun starRadiusDp(magnitude: Double): Float = (3.6 - 0.55 * magnitude).coerceIn(1.5, 4.5).toFloat()

/**
 * What the sheet's drawing shows, said under it: the moment it is drawn for,
 * and which way to face to see it like that.
 */
internal fun figureCaption(seen: ConstellationTonight): String {
    val at = clock(seen.peakAt)
    return if (seen.peakAltitude >= OVERHEAD_DEGREES) {
        "As it stands at $at, nearly overhead."
    } else {
        "As it stands at $at, looking ${Compass.of(seen.peakAzimuth).label}."
    }
}

/**
 * A stick figure: its lines, then a dot for each star over them.
 *
 * Shared by the sheet and Find, so the pattern looks the same in both. A
 * null point is a star that is not on the drawing - behind the viewer in
 * Find - and it is left out along with every line that reaches it, rather
 * than drawn somewhere it is not.
 *
 * Lines in the muted colour and stars in the text colour: night vision has
 * only its red to give, and the figure reads by shape either way.
 */
internal fun DrawScope.drawFigure(
    points: List<Offset?>,
    magnitudes: List<Double>,
    segments: List<Pair<Int, Int>>,
    palette: Palette,
) {
    val width = 1.2.dp.toPx()
    for ((a, b) in segments) {
        val from = points[a] ?: continue
        val to = points[b] ?: continue
        drawLine(palette.muted, from, to, strokeWidth = width, cap = StrokeCap.Round)
    }
    points.forEachIndexed { i, point ->
        point ?: return@forEachIndexed
        drawCircle(palette.text, starRadiusDp(magnitudes[i]).dp.toPx(), point)
    }
}

/**
 * A pattern drawn the way it stands at its highest tonight, from here.
 *
 * Up is towards the zenith and right is clockwise round the horizon, so the
 * drawing is what somebody facing that way would see - Orion upright from
 * Scotland, on his head from Sydney - and never a north-up chart, which is
 * right in one hemisphere and upside down in the other. The brightest star
 * is named, because the pattern's row already names it.
 *
 * Draws nothing for a pattern with no figure.
 */
@Composable
internal fun FigureSketch(
    seen: ConstellationTonight,
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
) {
    val constellation = seen.constellation
    val figure = ConstellationFigures.of(constellation) ?: return
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = palette.muted)

    val flat = remember(seen, latitude, longitude) {
        val placed = figure.placedAt(latitude, longitude, seen.peakAt)
        val centre = SkyPositions.fixed(constellation.raHours, constellation.dec, latitude, longitude, seen.peakAt)
        placed.positions.map { SkyProjection.around(centre, it) }
    }
    val magnitudes = remember(figure) { figure.stars.map { it.magnitude } }
    val segments = remember(figure) { figure.segments() }
    val brightest = remember(figure) { figure.stars.indexOfFirst { it.name == constellation.brightestStar } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .testTag(FIGURE_SKETCH_TAG)
                .semantics {
                    contentDescription =
                        "Drawing of ${constellation.name}, with ${constellation.brightestStar} marked."
                }
                .drawWithCache {
                    val label = measurer.measure(constellation.brightestStar, labelStyle)
                    val pad = 16.dp.toPx()
                    val shown = flat.filterNotNull()
                    if (shown.isEmpty()) return@drawWithCache onDrawBehind {}
                    val minX = shown.minOf { it.x }
                    val maxX = shown.maxOf { it.x }
                    val minY = shown.minOf { it.y }
                    val maxY = shown.maxOf { it.y }
                    // Room on the right for the label, so the star it names is never cut off.
                    val roomX = (size.width - 2 * pad - label.size.width).coerceAtLeast(1f)
                    val roomY = (size.height - 2 * pad).coerceAtLeast(1f)
                    val scale = minOf(
                        roomX / (maxX - minX).coerceAtLeast(1e-6),
                        roomY / (maxY - minY).coerceAtLeast(1e-6),
                    ).toFloat()
                    val midX = (minX + maxX) / 2
                    val midY = (minY + maxY) / 2
                    val centreX = pad + roomX / 2
                    val centreY = size.height / 2
                    val points = flat.map { p ->
                        p?.let {
                            Offset(centreX + ((it.x - midX) * scale).toFloat(), centreY - ((it.y - midY) * scale).toFloat())
                        }
                    }
                    onDrawBehind {
                        drawFigure(points, magnitudes, segments, palette)
                        points.getOrNull(brightest)?.let { at ->
                            val gap = starRadiusDp(magnitudes[brightest]).dp.toPx() + 4.dp.toPx()
                            drawText(label, topLeft = Offset(at.x + gap, at.y - label.size.height / 2f))
                        }
                    }
                },
        )
        Text(figureCaption(seen), style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}
