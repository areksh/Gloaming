package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.ui.theme.Palette
import sh.arek.gloaming.ui.theme.skyColour

/** The chart as a whole. */
const val UP_CHART_TAG = "up-chart"

private val LABEL_WIDTH = 92.dp
private val TOP_BAND = 20.dp
private val BOTTOM_BAND = 18.dp
private val ROW_HEIGHT = 24.dp
private val BAR_HEIGHT = 8.dp

/** Altitude at which a bar is drawn at full strength; lower, it fades. */
private const val FULL_ALTITUDE = 30f
private const val FAINTEST = 0.15f

/** How much a bar fades in a sky brighter than civil twilight. */
private const val DAYLIGHT_FADE = 0.35f
private const val CIVIL_DEGREES = -6.0

/**
 * The moon, the planets and any comet as bars across the night, above the
 * timeline that lists its moments.
 *
 * A bar is drawn wherever its body is above the horizon, fading as it gets
 * low and in a sky still bright, with a ring where it is highest. Sunset and
 * sunrise are marked, and the outlook's picks: the headline's trip shaded as
 * a band, the line under it as dotted lines, and a dot on a planet's bar for
 * each one about a planet - filled for the trip, hollow for the line under.
 *
 * Every bar is named with its symbol as well as its colour, so night vision's
 * one red loses nothing. A screen reader hears it as one sentence
 * ([UpCharts.summary]).
 */
@Composable
fun UpChartView(chart: UpChart, nowMillis: Long?, palette: Palette, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val summary = remember(chart) { UpCharts.summary(chart) }
    val label = MaterialTheme.typography.labelMedium
    val small = MaterialTheme.typography.labelSmall

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Up tonight", style = MaterialTheme.typography.titleMedium)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TOP_BAND + ROW_HEIGHT * chart.bodies.size + BOTTOM_BAND)
                .testTag(UP_CHART_TAG)
                .clearAndSetSemantics { contentDescription = summary },
        ) {
            drawChart(chart, nowMillis, palette, measurer, label.copy(color = palette.text), small.copy(color = palette.muted))
        }
    }
}

private fun DrawScope.drawChart(
    chart: UpChart,
    nowMillis: Long?,
    palette: Palette,
    measurer: TextMeasurer,
    label: TextStyle,
    small: TextStyle,
) {
    val left = LABEL_WIDTH.toPx()
    val right = size.width
    val top = TOP_BAND.toPx()
    val rowHeight = ROW_HEIGHT.toPx()
    val bottom = top + rowHeight * chart.bodies.size
    val span = (chart.untilMillis - chart.fromMillis).toFloat()
    fun x(t: Long) = left + (t - chart.fromMillis) / span * (right - left)

    // The sky behind the bars, by how far down the sun is.
    if (chart.sun.size >= 2) {
        val stops = chart.sun.map { ((it.atMillis - chart.fromMillis) / span) to skyColour(palette, it.altitude) }
        drawRoundRect(
            Brush.horizontalGradient(*stops.toTypedArray(), startX = left, endX = right),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
    }

    // The headline's trip as a band, the line under it as dotted lines.
    val trip = chart.marks.filter { it.tag == PickTag.Go }.map { it.atMillis }
    if (trip.isNotEmpty()) {
        val from = x(trip.min())
        val until = maxOf(x(trip.max()), from + 3.dp.toPx())
        drawRect(palette.accent.copy(alpha = 0.28f), Offset(from, top), Size(until - from, bottom - top))
        drawText(measurer, "Go out", Offset(from, 2.dp.toPx()), label.copy(color = palette.accent, fontWeight = FontWeight.Bold))
    }
    val dotted = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
    chart.marks.filter { it.tag == PickTag.Also }.forEach { mark ->
        val at = x(mark.atMillis)
        drawLine(palette.accent, Offset(at, top), Offset(at, bottom), 1.5.dp.toPx(), pathEffect = dotted)
    }

    // Sunset and sunrise.
    val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    for (edge in listOf(chart.sunsetMillis, chart.sunriseMillis)) {
        drawLine(palette.muted, Offset(x(edge), top - 2.dp.toPx()), Offset(x(edge), bottom), 1.dp.toPx(), pathEffect = dashed)
    }
    val sunset = measurer.measure("Sunset ${clock(chart.sunsetMillis)}", small)
    val sunrise = measurer.measure("Sunrise ${clock(chart.sunriseMillis)}", small)
    val sunsetX = (x(chart.sunsetMillis) - sunset.size.width / 2f).coerceAtLeast(left)
    // Clear of "Go out" where the trip starts at dusk: the trip's label wins.
    if (trip.isEmpty() || x(trip.min()) > sunsetX + sunset.size.width + 4.dp.toPx() || x(trip.max()) < sunsetX) {
        drawText(sunset, topLeft = Offset(sunsetX, 3.dp.toPx()))
    }
    drawText(sunrise, topLeft = Offset((x(chart.sunriseMillis) - sunrise.size.width / 2f).coerceAtMost(right - sunrise.size.width), 3.dp.toPx()))

    // The bars, each with its name.
    chart.bodies.forEachIndexed { row, body ->
        val centre = top + rowHeight * row + rowHeight / 2
        val colour = when (body.kind) {
            UpKind.Comet -> cometColour(palette)
            else -> bodyColour(body.body, palette)
        }
        val mark = when (body.kind) {
            UpKind.Moon -> glyph(SkyBody.Moon)
            UpKind.Comet -> COMET_GLYPH
            UpKind.Planet -> body.body?.let(::glyph) ?: ""
        }
        val name = measurer.measure(
            "$mark ${body.label}",
            label,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = Constraints(maxWidth = (left - 6.dp.toPx()).toInt()),
        )
        drawText(name, topLeft = Offset(0f, centre - name.size.height / 2f))

        val bar = BAR_HEIGHT.toPx()
        for (i in 0 until body.samples.size - 1) {
            val altitude = (body.samples[i] + body.samples[i + 1]) / 2
            if (altitude <= 0f) continue
            val daylight = chart.sun.getOrNull(i)?.altitude?.let { it > CIVIL_DEGREES } ?: false
            val strength = (altitude / FULL_ALTITUDE).coerceIn(FAINTEST, 1f) * if (daylight) DAYLIGHT_FADE else 1f
            val from = x(UpCharts.timeOf(chart, i))
            val until = x(UpCharts.timeOf(chart, i + 1))
            drawRect(colour.copy(alpha = strength), Offset(from, centre - bar / 2), Size(until - from + 0.5f, bar))
        }
        if (body.peakAltitude > 0 && body.peakAt in chart.fromMillis..chart.untilMillis) {
            drawCircle(colour, 4.5.dp.toPx(), Offset(x(body.peakAt), centre), style = Stroke(1.5.dp.toPx()))
        }
        // The outlook's picks about this planet.
        chart.marks.filter { it.body != null && it.body == body.body }.forEach { pick ->
            val at = Offset(x(pick.atMillis), centre)
            drawCircle(palette.ground, 5.5.dp.toPx(), at)
            if (pick.tag == PickTag.Go) {
                drawCircle(palette.accent, 4.dp.toPx(), at)
            } else {
                drawCircle(palette.accent, 3.5.dp.toPx(), at, style = Stroke(2.dp.toPx()))
            }
        }
    }

    // Hours along the bottom, every three named.
    val zone = ZoneId.systemDefault()
    var hour = Instant.ofEpochMilli(chart.fromMillis).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
    while (hour.toInstant().toEpochMilli() < chart.untilMillis) {
        val at = x(hour.toInstant().toEpochMilli())
        val named = hour.hour % 3 == 0
        drawLine(palette.line, Offset(at, bottom), Offset(at, bottom + (if (named) 4 else 2).dp.toPx()), 1.dp.toPx())
        if (named) {
            val text = measurer.measure("%02d".format(hour.hour), small)
            val tx = (at - text.size.width / 2f).coerceIn(left, right - text.size.width)
            drawText(text, topLeft = Offset(tx, bottom + 4.dp.toPx()))
        }
        hour = hour.plusHours(1)
    }

    nowMillis?.takeIf { it in chart.fromMillis..chart.untilMillis }?.let {
        drawLine(palette.accent, Offset(x(it), top), Offset(x(it), bottom), 2.dp.toPx(), StrokeCap.Round)
    }
}
