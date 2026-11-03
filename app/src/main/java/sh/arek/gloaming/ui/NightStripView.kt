package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlin.math.floor
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.ui.theme.Palette
import sh.arek.gloaming.ui.theme.skyColour

/** The strip as a whole: the drawing, and what a screen reader hears for it. */
const val NIGHT_STRIP_TAG = "night-strip"

/** The button that switches the strip between the hours round now and the whole night. */
const val STRIP_ZOOM_TAG = "strip-zoom"

private val HOURS_BAND = 14.dp
private val SKY_BAND = 26.dp
private val BAND_GAP = 6.dp
private val CLOUD_BAND = 12.dp
private val BODY_ROW = 24.dp
private val BAR_HEIGHT = 4.dp
private val TICK_BAND = 18.dp
private val BRACKET_BAND = 20.dp
private val OVERVIEW_HEIGHT = 10.dp
private val HATCH_SPACING = 5.dp
private val HATCH_STROKE = 1.5.dp
private val MIN_BRACKET = 6.dp

/** Altitude at which a bar is drawn at full strength; lower, it fades. */
private const val FULL_ALTITUDE = 30f
private const val FAINTEST = 0.15f

/** How much a bar fades in a sky brighter than civil twilight. */
private const val DAYLIGHT_FADE = 0.35f
private const val CIVIL_DEGREES = -6.0

/** How much of the past is dimmed behind the line at now. */
private const val PAST_DIM = 0.55f

/**
 * Tonight from sunset to sunrise on one horizontal axis, in place of the
 * chart and the vertical timeline it replaced: the sky by how far down the sun
 * is, the cloud as hatching, a bar for the moon and each planet and comet,
 * ticks for the passes and events the outlook picked, and a bracket over the
 * trip out.
 *
 * A bar is solid where its body is up in a clear sky and dotted where the
 * forecast puts more cloud than [tolerance] over it, so night vision's one red
 * loses nothing: every difference is in shape as well as colour.
 *
 * Once the night is under way it opens on three hours round now
 * ([NightStrips.window]), with a thin bar above showing where those hours sit
 * in the night and a button to see the whole of it.
 */
@Composable
fun NightStripView(timeline: Timeline, tolerance: Int, palette: Palette, modifier: Modifier = Modifier) {
    var zoom by rememberSaveable { mutableStateOf(true) }
    val window = NightStrips.window(timeline, zoom)
    val measurer = rememberTextMeasurer()
    val summary = remember(timeline) { NightStrips.summary(timeline) }
    val zone = ZoneId.systemDefault()
    val inWindow = window.fromMillis..window.untilMillis

    val chart = timeline.chart
    val bodies = chart?.bodies.orEmpty().filter { !window.zoomed || isUpIn(it, chart!!, inWindow) }
    val ticks = remember(timeline) { NightStrips.ticks(timeline) }.filter { it.atMillis in inWindow }
    val trip = timeline.trip.filter { it in inWindow }
    val anyHidden = chart != null && bodies.any { body -> segments(body, chart, inWindow).any { NightStrips.hidden(timeline.cloud, it.mid, tolerance) } }

    val height = HOURS_BAND + SKY_BAND + BAND_GAP + CLOUD_BAND + BAND_GAP + BODY_ROW * bodies.size +
        (if (ticks.isNotEmpty()) TICK_BAND else 0.dp) + (if (trip.isNotEmpty()) BRACKET_BAND else 0.dp)
    val small = MaterialTheme.typography.labelSmall
    val label = MaterialTheme.typography.labelMedium

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (timeline.nowMillis != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (window.zoomed) "Next 3 hours" else "Whole night", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { zoom = !zoom }, modifier = Modifier.testTag(STRIP_ZOOM_TAG)) {
                    Text(if (window.zoomed) "Whole night" else "Next 3 hours", fontWeight = FontWeight.Bold)
                }
            }
            if (window.zoomed) {
                Canvas(Modifier.fillMaxWidth().height(OVERVIEW_HEIGHT)) { drawOverview(timeline, window, palette) }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .testTag(NIGHT_STRIP_TAG)
                .clearAndSetSemantics { contentDescription = summary },
        ) {
            drawStrip(
                StripScene(timeline, window, bodies, ticks, trip, tolerance, zone),
                palette,
                measurer,
                small.copy(color = palette.muted),
                label.copy(color = palette.text),
            )
        }
        Key(cloudy = timeline.cloud.any { it.cover > 0 }, hidden = anyHidden, also = ticks.any { it.tag == PickTag.Also }, palette = palette)
    }
}

/** What one drawing of the strip needs, worked out before it is drawn. */
private class StripScene(
    val timeline: Timeline,
    val window: StripWindow,
    val bodies: List<UpBody>,
    val ticks: List<StripTick>,
    val trip: List<Long>,
    val tolerance: Int,
    val zone: ZoneId,
)

/** One step of a body's bar: its two ends, its middle, and its height there. */
private class Segment(val index: Int, val from: Long, val until: Long, val altitude: Float) {
    val mid: Long get() = (from + until) / 2
}

/** The steps of [body]'s bar that are above the horizon and inside [span]. */
private fun segments(body: UpBody, chart: UpChart, span: LongRange): List<Segment> =
    (0 until body.samples.size - 1).mapNotNull { i ->
        val from = UpCharts.timeOf(chart, i)
        val until = UpCharts.timeOf(chart, i + 1)
        val altitude = (body.samples[i] + body.samples[i + 1]) / 2
        if (altitude <= 0f || until <= span.first || from >= span.last) null else Segment(i, from, until, altitude)
    }

private fun isUpIn(body: UpBody, chart: UpChart, span: LongRange): Boolean = segments(body, chart, span).isNotEmpty()

private fun DrawScope.drawStrip(
    scene: StripScene,
    palette: Palette,
    measurer: TextMeasurer,
    small: TextStyle,
    label: TextStyle,
) {
    val timeline = scene.timeline
    val window = scene.window
    val width = size.width
    val span = (window.untilMillis - window.fromMillis).toFloat()
    fun x(t: Long) = (t - window.fromMillis) / span * width

    val skyTop = HOURS_BAND.toPx()
    val skyBottom = skyTop + SKY_BAND.toPx()
    val cloudTop = skyBottom + BAND_GAP.toPx()
    val cloudBottom = cloudTop + CLOUD_BAND.toPx()
    val bodiesTop = cloudBottom + BAND_GAP.toPx()
    val ticksTop = bodiesTop + BODY_ROW.toPx() * scene.bodies.size
    val bracketTop = ticksTop + if (scene.ticks.isNotEmpty()) TICK_BAND.toPx() else 0f

    // Hours along the top: every third, or every one when only three are shown.
    for (tick in NightStrips.hourTicks(window.fromMillis, window.untilMillis, scene.zone)) {
        if (!tick.named && !window.zoomed) continue
        val text = measurer.measure("%02d".format(tick.hour), small)
        val tx = (x(tick.atMillis) - text.size.width / 2f).coerceIn(0f, width - text.size.width)
        drawText(text, topLeft = Offset(tx, 0f))
    }

    // The sky, by how far down the sun is.
    val corner = CornerRadius(6.dp.toPx())
    val sun = timeline.sun
    val skyBrush = if (sun.size >= 2) {
        val first = sun.first().atMillis
        val whole = (sun.last().atMillis - first).toFloat().coerceAtLeast(1f)
        val stops = sun.map { ((it.atMillis - first) / whole) to skyColour(palette, it.altitude) }
        Brush.horizontalGradient(*stops.toTypedArray(), startX = x(first), endX = x(sun.last().atMillis))
    } else {
        Brush.horizontalGradient(listOf(palette.sky.last(), palette.sky.last()))
    }
    drawRoundRect(skyBrush, Offset(0f, skyTop), Size(width, skyBottom - skyTop), corner)
    drawRoundRect(palette.line, Offset(0f, skyTop), Size(width, skyBottom - skyTop), corner, style = Stroke(1.dp.toPx()))

    // Cloud, thicker the more of it there is.
    for (cloud in timeline.cloud) {
        if (cloud.cover <= 0) continue
        val colour = palette.hatch.copy(alpha = palette.hatch.alpha * cloud.cover.coerceAtMost(100) / 100f)
        hatch(x(cloud.fromMillis).coerceAtLeast(0f), x(cloud.untilMillis).coerceAtMost(width), cloudTop, cloudBottom, colour)
    }

    // A bar for each body: solid where it can be seen, dotted where cloud hides it.
    val chart = timeline.chart
    if (chart != null) {
        val bar = BAR_HEIGHT.toPx()
        val dotted = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx()))
        scene.bodies.forEachIndexed { row, body ->
            val rowTop = bodiesTop + BODY_ROW.toPx() * row
            val barY = rowTop + BODY_ROW.toPx() - bar - 3.dp.toPx()
            val colour = if (body.kind == UpKind.Comet) cometColour(palette) else bodyColour(body.body, palette)
            val steps = segments(body, chart, window.fromMillis..window.untilMillis)

            var hiddenFrom: Float? = null
            var hiddenUntil = 0f
            fun flushHidden() {
                hiddenFrom?.let { from ->
                    drawLine(palette.dim, Offset(from, barY + bar / 2), Offset(hiddenUntil, barY + bar / 2), 3.dp.toPx(), StrokeCap.Round, dotted)
                }
                hiddenFrom = null
            }
            for (step in steps) {
                val from = x(step.from).coerceAtLeast(0f)
                val until = x(step.until).coerceAtMost(width)
                if (NightStrips.hidden(timeline.cloud, step.mid, scene.tolerance)) {
                    if (hiddenFrom == null || from > hiddenUntil + 0.5f) {
                        flushHidden()
                        hiddenFrom = from
                    }
                    hiddenUntil = until
                } else {
                    flushHidden()
                    val daylight = chart.sun.getOrNull(step.index)?.altitude?.let { it > CIVIL_DEGREES } ?: false
                    val strength = (step.altitude / FULL_ALTITUDE).coerceIn(FAINTEST, 1f) * if (daylight) DAYLIGHT_FADE else 1f
                    drawRect(colour.copy(alpha = strength), Offset(from, barY), Size(until - from + 0.5f, bar))
                }
            }
            flushHidden()

            val first = steps.firstOrNull() ?: return@forEachIndexed
            val mark = when (body.kind) {
                UpKind.Moon -> glyph(SkyBody.Moon)
                UpKind.Comet -> COMET_GLYPH
                UpKind.Planet -> body.body?.let(::glyph) ?: ""
            }
            val name = measurer.measure("$mark ${body.label}".trim(), label)
            val nx = x(first.from).coerceIn(0f, (width - name.size.width).coerceAtLeast(0f))
            drawText(name, topLeft = Offset(nx, rowTop))
        }
    }

    // Ticks for the picked passes and events, through the sky; dashed for the line under the headline.
    val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    var labelsUntil = Float.NEGATIVE_INFINITY
    for (tick in scene.ticks) {
        val tx = x(tick.atMillis)
        drawLine(
            palette.accent, Offset(tx, skyTop - 3.dp.toPx()), Offset(tx, skyBottom + 3.dp.toPx()), 2.dp.toPx(),
            pathEffect = if (tick.tag == PickTag.Also) dashed else null,
        )
        val style = small.copy(color = palette.accent, fontWeight = if (tick.tag == PickTag.Go) FontWeight.Bold else null)
        val text = measurer.measure(tick.label, style)
        val pad = 3.dp.toPx()
        val right = tx + pad
        val left = tx - pad - text.size.width
        val lx = when {
            right >= labelsUntil && right + text.size.width <= width -> right
            left >= labelsUntil && left >= 0f -> left
            else -> null
        }
        if (lx != null) {
            drawText(text, topLeft = Offset(lx, ticksTop + 2.dp.toPx()))
            labelsUntil = lx + text.size.width + pad
        }
    }

    // The trip out, bracketed, with "go out" beside it: a trip is often too short to centre a word under.
    if (scene.trip.isNotEmpty()) {
        val from = x(scene.trip.min())
        val until = maxOf(x(scene.trip.max()), from + MIN_BRACKET.toPx())
        val y = bracketTop + 4.dp.toPx()
        val drop = 8.dp.toPx()
        val stroke = 2.dp.toPx()
        drawLine(palette.accent, Offset(from, y), Offset(until, y), stroke, StrokeCap.Round)
        drawLine(palette.accent, Offset(from, y), Offset(from, y + drop), stroke, StrokeCap.Round)
        drawLine(palette.accent, Offset(until, y), Offset(until, y + drop), stroke, StrokeCap.Round)
        val text = measurer.measure("go out", small.copy(color = palette.accent, fontWeight = FontWeight.Bold))
        val gx = if (until + 6.dp.toPx() + text.size.width <= width) until + 6.dp.toPx() else from - 6.dp.toPx() - text.size.width
        drawText(text, topLeft = Offset(gx.coerceAtLeast(0f), y))
    }

    // Now: the past dimmed, and a line through everything.
    timeline.nowMillis?.takeIf { it in window.fromMillis..window.untilMillis }?.let { now ->
        val nx = x(now)
        drawRect(palette.ground.copy(alpha = PAST_DIM), Offset(0f, skyTop), Size(nx, size.height - skyTop))
        drawLine(palette.accent, Offset(nx, 0f), Offset(nx, size.height), 2.dp.toPx(), StrokeCap.Round)
        val text = measurer.measure("now", small.copy(color = palette.accent, fontWeight = FontWeight.Bold))
        if (nx - text.size.width - 4.dp.toPx() >= 0f) {
            drawText(text, topLeft = Offset(nx - text.size.width - 4.dp.toPx(), size.height - text.size.height))
        }
    }
}

/** The whole night as a thin bar, with a box round the hours the strip is showing. */
private fun DrawScope.drawOverview(timeline: Timeline, window: StripWindow, palette: Palette) {
    val width = size.width
    val span = (timeline.endMillis - timeline.startMillis).toFloat()
    fun x(t: Long) = (t - timeline.startMillis) / span * width
    val bar = 4.dp.toPx()
    val top = (size.height - bar) / 2
    val sun = timeline.sun
    val brush = if (sun.size >= 2) {
        val stops = sun.map { ((it.atMillis - timeline.startMillis) / span) to skyColour(palette, it.altitude) }
        Brush.horizontalGradient(*stops.toTypedArray(), startX = 0f, endX = width)
    } else {
        Brush.horizontalGradient(listOf(palette.sky.last(), palette.sky.last()))
    }
    drawRoundRect(brush, Offset(0f, top), Size(width, bar), CornerRadius(bar / 2))
    drawRoundRect(palette.line, Offset(0f, top), Size(width, bar), CornerRadius(bar / 2), style = Stroke(1.dp.toPx()))
    val from = x(window.fromMillis)
    val until = x(window.untilMillis)
    val stroke = 1.5.dp.toPx()
    drawRoundRect(
        palette.accent, Offset(from + stroke / 2, stroke / 2), Size(until - from - stroke, size.height - stroke),
        CornerRadius(3.dp.toPx()), style = Stroke(stroke),
    )
}

/**
 * Diagonal lines between [from] and [until] across the band from [top] to
 * [bottom], anchored to the whole strip rather than to the span, so two cloudy
 * hours in a row read as one run of hatching rather than two that do not meet.
 */
private fun DrawScope.hatch(from: Float, until: Float, top: Float, bottom: Float, colour: Color) {
    if (until <= from) return
    val spacing = HATCH_SPACING.toPx()
    val stroke = HATCH_STROKE.toPx()
    val height = bottom - top
    clipRect(from, top, until, bottom) {
        var c = floor((from - height) / spacing) * spacing
        while (c < until) {
            drawLine(colour, Offset(c, bottom), Offset(c + height, top), stroke)
            c += spacing
        }
    }
}

/** What the marks mean, under the strip. Only what is drawn tonight is named, each with its own mark. */
@Composable
private fun Key(cloudy: Boolean, hidden: Boolean, also: Boolean, palette: Palette) {
    if (!cloudy && !hidden && !also) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (cloudy) {
            KeyItem("Cloud", palette) { hatch(0f, size.width, size.height * 0.1f, size.height * 0.9f, palette.hatch) }
        }
        if (hidden) {
            KeyItem("Up, behind cloud", palette) {
                drawLine(
                    palette.dim, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(), StrokeCap.Round,
                    PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
                )
            }
        }
        if (also) {
            KeyItem("Also tonight", palette) {
                drawLine(
                    palette.accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
            }
        }
    }
}

@Composable
private fun KeyItem(label: String, palette: Palette, swatch: DrawScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(SWATCH_WIDTH, SWATCH_HEIGHT), onDraw = swatch)
        Text(label, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}

private val SWATCH_WIDTH: Dp = 18.dp
private val SWATCH_HEIGHT: Dp = 10.dp
