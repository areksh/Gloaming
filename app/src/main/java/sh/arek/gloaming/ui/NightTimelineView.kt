package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.ui.theme.Palette
import sh.arek.gloaming.ui.theme.skyColour

/** The whole timeline: its key, the drawing and the rows. */
const val TIMELINE_TAG = "timeline"

/** One row, by its moment and its words; stable for as long as the row is. */
fun timelineRowTag(row: TimelineRow): String = "row:${row.atMillis}:${row.title}"

/** One hour of the night is always this tall, so a glance reads durations. */
private val HOUR_HEIGHT = 56.dp

/** Room above sunset and below sunrise for the rows that sit on them. */
private val END_PAD = 10.dp

private val SKY_WIDTH = 30.dp
private val ROWS_START = 68.dp
private val ROW_GAP = 4.dp
private val ROW_PADDING = 4.dp

/** From a row's top to the middle of its first line, which is what sits on its time. */
private val FIRST_LINE_MIDDLE = ROW_PADDING + 10.dp

private val HATCH_SPACING = 5.dp
private val HATCH_STROKE = 1.5.dp

/**
 * Tonight from sunset at the top to sunrise at the bottom, at [HOUR_HEIGHT] an
 * hour.
 *
 * The drawing - the sky column shaded by how far down the sun is, the cloud
 * hatched over it, the moon and up to two planets as lanes, and the line at
 * now - is one `Canvas` that a screen reader hears as a single sentence
 * ([NightTimeline.summary]). The rows are real nodes laid over it, each one
 * tappable, placed at their times and nudged down only where they would
 * collide ([NightTimeline.layout]), so TalkBack reads the night as a list in
 * time order.
 *
 * Nothing here is said by colour alone, because night vision has only red to
 * give: a row behind cloud is dimmed, one the outlook sends you out for is
 * bold, and the three lanes are solid, dashed and dotted, named in the key
 * above.
 */
@Composable
fun NightTimelineView(
    timeline: Timeline,
    palette: Palette,
    onRow: (TimelineRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = remember(timeline) { NightTimeline.summary(timeline) }
    val now = timeline.nowMillis

    Column(modifier.testTag(TIMELINE_TAG), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Key(timeline, palette)

        Layout(
            content = {
                Canvas(Modifier.clearAndSetSemantics { contentDescription = summary }) {
                    drawNight(timeline, palette)
                }
                for (row in timeline.rows) {
                    TimelineRowView(row, now, palette, onClick = { onRow(row) })
                }
                // Read as part of the drawing's sentence, so silent here.
                if (now != null) {
                    Text(
                        "${clock(now)} Now",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent,
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .background(palette.ground)
                            .padding(horizontal = 8.dp),
                    )
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val pad = END_PAD.roundToPx()
            val hour = HOUR_HEIGHT.toPx()
            fun y(t: Long): Int = pad + ((t - timeline.startMillis) / HOUR_MILLIS * hour).roundToInt()

            val skyHeight = y(timeline.endMillis) + pad
            val canvas = measurables.first().measure(Constraints.fixed(width, skyHeight))

            val rowStart = ROWS_START.roundToPx()
            val rowConstraints = Constraints(maxWidth = (width - rowStart).coerceAtLeast(0))
            val rows = measurables.subList(1, 1 + timeline.rows.size).map { it.measure(rowConstraints) }
            val ideal = timeline.rows.map { (y(it.atMillis) - FIRST_LINE_MIDDLE.roundToPx()).coerceAtLeast(0) }
            val tops = NightTimeline.layout(ideal, rows.map { it.height }, ROW_GAP.roundToPx())

            val nowLabel = if (now != null) measurables.last().measure(Constraints()) else null
            val bottom = rows.indices.maxOfOrNull { tops[it] + rows[it].height } ?: 0

            layout(width, maxOf(skyHeight, bottom)) {
                canvas.place(0, 0)
                rows.forEachIndexed { i, placeable -> placeable.place(rowStart, tops[i]) }
                if (nowLabel != null && now != null) {
                    nowLabel.place(rowStart - 8.dp.roundToPx(), y(now) - nowLabel.height / 2)
                }
            }
        }
    }
}

private const val HOUR_MILLIS = 3_600_000.0

/** Where each kind of lane runs, to the right of the sky column. */
private fun laneX(style: LaneStyle): Dp = when (style) {
    LaneStyle.Solid -> 38.dp
    LaneStyle.Dashed -> 47.dp
    LaneStyle.Dotted -> 55.dp
}

private fun DrawScope.drawNight(timeline: Timeline, palette: Palette) {
    val pad = END_PAD.toPx()
    val hour = HOUR_HEIGHT.toPx()
    fun y(t: Long): Float = pad + ((t - timeline.startMillis) / HOUR_MILLIS * hour).toFloat()

    val top = y(timeline.startMillis)
    val bottom = y(timeline.endMillis)
    val skyWidth = SKY_WIDTH.toPx()
    val column = Path().apply {
        addRoundRect(RoundRect(0f, top, skyWidth, bottom, CornerRadius(skyWidth / 2)))
    }

    clipPath(column) {
        val sun = timeline.sun
        if (sun.size >= 2 && bottom > top) {
            val stops = sun.map { ((y(it.atMillis) - top) / (bottom - top)) to skyColour(palette, it.altitude) }
            drawRect(
                Brush.verticalGradient(*stops.toTypedArray(), startY = top, endY = bottom),
                topLeft = Offset(0f, top),
                size = Size(skyWidth, bottom - top),
            )
        } else {
            drawRect(palette.sky.last(), Offset(0f, top), Size(skyWidth, bottom - top))
        }

        for (span in timeline.cloud) {
            if (span.cover <= 0) continue
            val colour = palette.hatch.copy(alpha = palette.hatch.alpha * span.cover.coerceAtMost(100) / 100f)
            hatch(y(span.fromMillis), y(span.untilMillis), skyWidth, colour)
        }
    }

    for (lane in timeline.lanes) {
        val x = laneX(lane.style).toPx()
        val from = Offset(x, y(lane.fromMillis))
        val until = Offset(x, y(lane.untilMillis))
        when (lane.style) {
            LaneStyle.Solid -> drawLine(palette.text, from, until, 4.dp.toPx(), StrokeCap.Round)
            LaneStyle.Dashed -> drawLine(
                palette.muted, from, until, 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
            LaneStyle.Dotted -> drawLine(
                palette.muted, from, until, 3.dp.toPx(), StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
            )
        }
    }

    timeline.nowMillis?.let {
        val at = y(it)
        drawLine(palette.accent, Offset(0f, at), Offset(size.width, at), 2.dp.toPx())
    }
}

/**
 * Diagonal lines between [from] and [until], anchored to the whole column
 * rather than to the span, so that two cloudy hours in a row read as one run
 * of hatching rather than two patterns that do not meet.
 */
private fun DrawScope.hatch(from: Float, until: Float, width: Float, colour: Color) {
    if (until <= from) return
    val spacing = HATCH_SPACING.toPx()
    val stroke = HATCH_STROKE.toPx()
    clipRect(0f, from, width, until) {
        var c = floor(from / spacing) * spacing
        while (c < until + width) {
            drawLine(colour, Offset(0f, c), Offset(width, c - width), stroke)
            c += spacing
        }
    }
}

/**
 * What the shading and the lanes mean, above them. Only what is drawn tonight
 * is named, and each with the same mark it has in the drawing.
 */
@Composable
private fun Key(timeline: Timeline, palette: Palette) {
    val cloudy = timeline.cloud.any { it.cover > 0 }
    val lanes = timeline.lanes.distinctBy { it.label }
    if (!cloudy && lanes.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (cloudy) KeyItem("Cloud", palette) { hatchSwatch(palette) }
        for (lane in lanes) {
            val label = if (lane.style == LaneStyle.Solid) "Moon up" else lane.label
            KeyItem(label, palette) { laneSwatch(lane.style, palette) }
        }
    }
}

@Composable
private fun KeyItem(label: String, palette: Palette, swatch: DrawScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp), onDraw = swatch)
        Text(label, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}

private fun DrawScope.hatchSwatch(palette: Palette) {
    val top = size.height * 0.15f
    val bottom = size.height * 0.85f
    hatch(top, bottom, size.width, palette.hatch)
    drawRect(palette.line, Offset(0f, top), Size(size.width, bottom - top), style = Stroke(1.dp.toPx()))
}

private fun DrawScope.laneSwatch(style: LaneStyle, palette: Palette) {
    val x = size.width / 2
    val from = Offset(x, 0f)
    val until = Offset(x, size.height)
    when (style) {
        LaneStyle.Solid -> drawLine(palette.text, from, until, 4.dp.toPx(), StrokeCap.Round)
        LaneStyle.Dashed -> drawLine(
            palette.muted, from, until, 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
        )
        LaneStyle.Dotted -> drawLine(
            palette.muted, from, until, 3.dp.toPx(), StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 4.dp.toPx())),
        )
    }
}

/**
 * One row: its time, what happens, a line of detail, and for a pass a small
 * compass of the way it crosses. Dimmed behind cloud, bold where the outlook
 * sends you out - weight and lightness both, never hue alone.
 */
@Composable
private fun TimelineRowView(row: TimelineRow, nowMillis: Long?, palette: Palette, onClick: () -> Unit) {
    val colour = if (row.tone == RowTone.Dim) palette.dim else palette.text
    val weight = if (row.tone == RowTone.Strong) FontWeight.Bold else FontWeight.Normal
    val event = (row.subject as? RowSubject.Event)?.event
    val underWay = nowMillis != null && event?.isUnderWay(nowMillis) == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(timelineRowTag(row))
            .padding(vertical = ROW_PADDING),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            row.time,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = weight,
            color = colour,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.labelLarge.lineHeight),
                fontWeight = weight,
                color = colour,
            )
            // Said in words and weight, not in the accent alone.
            if (underWay) {
                Text(
                    HAPPENING_NOW,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent,
                )
            }
            row.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.tone == RowTone.Dim) palette.dim else palette.muted,
                )
            }
        }
        (row.subject as? RowSubject.Pass)?.let { PassCompass(it.pass, colour, palette) }
    }
}

/**
 * The way a pass crosses, drawn on a compass ring with north at the top: an
 * arc from where it rises to where it sets, bowed towards the middle by how
 * high it climbs, with a dot where it goes. Decoration for the detail line
 * beside it, which says the same in words.
 */
@Composable
private fun PassCompass(pass: SatellitePass, colour: Color, palette: Palette) {
    Canvas(Modifier.size(26.dp).clearAndSetSemantics {}) {
        val c = size.width / 2
        val r = c - 2.dp.toPx()
        fun point(azimuth: Double): Offset {
            val rad = Math.toRadians(azimuth)
            return Offset(c + (r * sin(rad)).toFloat(), c - (r * cos(rad)).toFloat())
        }

        drawCircle(palette.line, r, Offset(c, c), style = Stroke(1.2.dp.toPx()))
        drawLine(palette.muted, Offset(c, c - r - 1.dp.toPx()), Offset(c, c - r + 2.dp.toPx()), 1.2.dp.toPx())

        val rise = point(pass.riseAzimuth)
        val set = point(pass.setAzimuth)
        val bow = (pass.peakElevation / 90.0).coerceIn(0.0, 1.0).toFloat()
        val mid = Offset((rise.x + set.x) / 2, (rise.y + set.y) / 2)
        val control = Offset(mid.x + (c - mid.x) * bow * 2, mid.y + (c - mid.y) * bow * 2)
        val arc = Path().apply {
            moveTo(rise.x, rise.y)
            quadraticTo(control.x, control.y, set.x, set.y)
        }
        drawPath(arc, colour, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(colour, 2.4.dp.toPx(), set)
    }
}
