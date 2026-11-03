package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.ui.theme.Palette
import sh.arek.gloaming.ui.theme.skyColour

/** The whole timeline: its key, the drawing and the rows. */
const val TIMELINE_TAG = "timeline"

/** One row, by its moment and its words; stable for as long as the row is. */
fun timelineRowTag(row: TimelineRow): String = "row:${row.atMillis}:${row.title}"

/** The label at now, beside the rows. */
const val TIMELINE_NOW_TAG = "timeline-now"

/** The Busy switch above the timeline. */
const val TIMELINE_BUSY_TAG = "timeline-busy"

/** The line under a quiet timeline counting what it leaves out. */
const val TIMELINE_HIDDEN_TAG = "timeline-hidden"

/**
 * One hour of the night is at least this tall, so a glance reads durations;
 * where the rows crowd it stretches rather than let them drift off their
 * times ([NightTimeline.place]).
 */
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

private val NOW_STROKE = 2.dp

/** Where the bracket joining the headline's trip runs, just left of the rows. */
private val BRACKET_X = ROWS_START - 6.dp
private val BRACKET_TICK = 4.dp
private val BRACKET_STROKE = 2.dp

/** How far left of the rows the label at now starts, so it sits over their times. */
private val NOW_LABEL_INSET = 8.dp

private enum class Part { Rows, Now, Sky }

/**
 * Tonight from sunset at the top to sunrise at the bottom, at [HOUR_HEIGHT] an
 * hour or more.
 *
 * The drawing - the sky column shaded by how far down the sun is, the cloud
 * hatched over it, the moon and up to two planets as lanes, and the line at
 * now - is one `Canvas` that a screen reader hears as a single sentence
 * ([NightTimeline.summary]). The rows are real nodes laid over it, each one
 * tappable, so TalkBack reads the night as a list in time order.
 *
 * The rows are placed first and the drawing follows them: where rows would
 * collide they are pushed apart and the hours there stretch to match
 * ([NightTimeline.place]), so everything level with a row - cloud, lanes, the
 * sky's colour - is of that row's moment. Now is placed as one of the rows,
 * so its line falls between the row before it and the row after, crossing
 * neither.
 *
 * Nothing here is said by colour alone, because night vision has only red to
 * give: a row behind cloud is dimmed, one the outlook sends you out for is
 * bold and carries a pill that says so in words, and the moon's lane is
 * named in the key above.
 *
 * [timeline] is the whole night; with [busy] off its busy rows are left out
 * ([TimelineShown]) and counted in a line under the rest, which turns Busy
 * on.
 */
@Composable
fun NightTimelineView(
    timeline: Timeline,
    palette: Palette,
    onRow: (TimelineRow) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = true,
    onBusyChange: (Boolean) -> Unit = {},
) {
    val shown = remember(timeline, busy) { TimelineShown.of(timeline, busy) }
    val hidden = remember(timeline, busy) { if (busy) null else TimelineShown.hidden(timeline).sentence() }
    val summary = remember(shown) { NightTimeline.summary(shown) }
    val now = shown.nowMillis

    Column(modifier.testTag(TIMELINE_TAG), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BusySwitch(busy, palette, onBusyChange)
        Key(shown, palette)

        // Subcomposed because the drawing is of the axis the rows' heights make.
        SubcomposeLayout { constraints ->
            val width = constraints.maxWidth
            val rowStart = ROWS_START.roundToPx()
            val rowConstraints = Constraints(maxWidth = (width - rowStart).coerceAtLeast(0))
            val rows = subcompose(Part.Rows) {
                shown.rows.forEachIndexed { i, row ->
                    // Rows at one minute stack under a single time.
                    val showTime = i == 0 || shown.rows[i - 1].time != row.time
                    TimelineRowView(row, now, palette, showTime, onClick = { onRow(row) })
                }
            }.map { it.measure(rowConstraints) }

            val label = now?.let { at ->
                subcompose(Part.Now) {
                    // Read as part of the drawing's sentence, so silent here.
                    Text(
                        "${clock(at)} Now",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent,
                        modifier = Modifier
                            .clearAndSetSemantics { testTag = TIMELINE_NOW_TAG }
                            .background(palette.ground)
                            .padding(horizontal = 8.dp),
                    )
                }.single().measure(Constraints())
            }

            val lead = FIRST_LINE_MIDDLE.roundToPx()
            val slots = shown.rows.mapIndexed { i, row -> NightTimeline.Slot(row.atMillis, lead, rows[i].height) }
            // After every row at or before now, before every row after it.
            val nowSlot = if (now != null && label != null) NightTimeline.Slot(now, label.height / 2, label.height) else null
            val nowIndex = nowSlot?.let { at -> shown.rows.count { it.atMillis <= at.atMillis } }
            val placed = NightTimeline.place(
                shown.startMillis,
                if (nowSlot == null || nowIndex == null) slots else slots.take(nowIndex) + nowSlot + slots.drop(nowIndex),
                HOUR_HEIGHT.toPx(),
                END_PAD.roundToPx(),
                ROW_GAP.roundToPx(),
            )
            val rowTops = if (nowIndex == null) placed.tops else placed.tops.filterIndexed { i, _ -> i != nowIndex }
            val labelTop = nowIndex?.let { placed.tops[it] }
            // The label's own middle, not the axis at now: a row at that very
            // minute takes the top of the step and now sits below it.
            val nowY = if (label != null && labelTop != null) labelTop + label.height / 2f else null

            // The headline's trip, joined where it is more than one row.
            val trip = shown.rows.indices.filter { shown.rows[it].pick == PickTag.Go }.map { rowTops[it] + lead.toFloat() }

            val height = maxOf(
                ceil(placed.axis.y(shown.endMillis) + END_PAD.toPx()).toInt(),
                rows.indices.maxOfOrNull { rowTops[it] + rows[it].height } ?: 0,
                if (label != null && labelTop != null) labelTop + label.height else 0,
            )
            val sky = subcompose(Part.Sky) {
                Canvas(Modifier.clearAndSetSemantics { contentDescription = summary }) {
                    drawNight(shown, placed.axis, nowY, palette)
                    if (trip.size >= 2) bracket(trip.first(), trip.last(), palette)
                }
            }.single().measure(Constraints.fixed(width, height))

            layout(width, height) {
                sky.place(0, 0)
                rows.forEachIndexed { i, placeable -> placeable.place(rowStart, rowTops[i]) }
                if (label != null && labelTop != null) {
                    label.place(rowStart - NOW_LABEL_INSET.roundToPx(), labelTop)
                }
            }
        }

        hidden?.let { HiddenLine(it, palette) { onBusyChange(true) } }
    }
}

/**
 * The Busy setting, where it acts: the same switch as in Settings, so
 * somebody browsing tonight need not go looking for it.
 */
@Composable
private fun BusySwitch(busy: Boolean, palette: Palette, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Busy timeline", style = MaterialTheme.typography.titleSmall)
            Text(
                if (busy) "Dates, constellations and more passes too" else "The night's moments only",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        Switch(checked = busy, onCheckedChange = onChange, modifier = Modifier.testTag(TIMELINE_BUSY_TAG))
    }
}

/** What a quiet timeline leaves out, and the way to show it. */
@Composable
private fun HiddenLine(sentence: String, palette: Palette, onShow: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().testTag(TIMELINE_HIDDEN_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            sentence,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        TextButton(onClick = onShow) { Text("Show everything") }
    }
}

/**
 * Joins the headline's trip down the gutter: two rows that are one trip out
 * read as one, even with other rows between them.
 */
private fun DrawScope.bracket(top: Float, bottom: Float, palette: Palette) {
    val x = BRACKET_X.toPx()
    val tick = BRACKET_TICK.toPx()
    val path = Path().apply {
        moveTo(x + tick, top)
        lineTo(x, top)
        lineTo(x, bottom)
        lineTo(x + tick, bottom)
    }
    drawPath(path, palette.accent, style = Stroke(BRACKET_STROKE.toPx(), cap = StrokeCap.Round))
}

/** Where each kind of lane runs, to the right of the sky column. */
private fun laneX(style: LaneStyle): Dp = when (style) {
    LaneStyle.Solid -> 38.dp
    LaneStyle.Dashed -> 47.dp
    LaneStyle.Dotted -> 55.dp
}

private fun DrawScope.drawNight(timeline: Timeline, axis: NightAxis, nowY: Float?, palette: Palette) {
    fun y(t: Long): Float = axis.y(t)

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
        laneLine(lane.style, palette, Offset(x, y(lane.fromMillis)), Offset(x, y(lane.untilMillis)))
    }

    nowY?.let { drawLine(palette.accent, Offset(0f, it), Offset(size.width, it), NOW_STROKE.toPx()) }
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
    laneLine(style, palette, Offset(x, 0f), Offset(x, size.height))
}

/**
 * One lane's line, in the drawing and in the key alike, so the key cannot
 * show a mark the drawing does not. A dash is more than twice as long as the
 * line is wide: the key's had been a 3 dp square, and beside the dots it was
 * the same mark, which in night vision's one red left two planets alike.
 */
private fun DrawScope.laneLine(style: LaneStyle, palette: Palette, from: Offset, until: Offset) {
    when (style) {
        LaneStyle.Solid -> drawLine(palette.text, from, until, 4.dp.toPx(), StrokeCap.Round)
        LaneStyle.Dashed -> drawLine(
            palette.muted, from, until, 3.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx())),
        )
        LaneStyle.Dotted -> drawLine(
            palette.muted, from, until, 3.dp.toPx(), StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
        )
    }
}

/**
 * One row: its time, what happens, a line of detail, and for a pass a small
 * compass of the way it crosses. Dimmed behind cloud, bold where the outlook
 * sends you out - weight and lightness both, never hue alone - and a pill
 * saying which of its recommendations this is. A planet's row leads with
 * its symbol. [showTime] is false under a row at the same minute, so a
 * moment's rows stack under one time.
 */
@Composable
private fun TimelineRowView(
    row: TimelineRow,
    nowMillis: Long?,
    palette: Palette,
    showTime: Boolean,
    onClick: () -> Unit,
) {
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
            if (showTime) row.time else "",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = weight,
            color = colour,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val planet = (row.subject as? RowSubject.Planet)?.planet?.body
            val title = buildAnnotatedString {
                planet?.let {
                    withStyle(SpanStyle(color = bodyColour(it, palette))) { append(glyph(it)) }
                    append(" ")
                }
                append(row.title)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                row.pick?.let { PickPill(it, palette) }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.labelLarge.lineHeight),
                    fontWeight = weight,
                    color = colour,
                )
            }
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

/** "GO OUT" filled for the headline's trip, "ALSO" outlined for the line under it. */
@Composable
private fun PickPill(tag: PickTag, palette: Palette) {
    val shape = RoundedCornerShape(4.dp)
    val go = tag == PickTag.Go
    Text(
        if (go) "GO OUT" else "ALSO",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (go) palette.onAccent else palette.accent,
        modifier = Modifier
            .then(if (go) Modifier.background(palette.accent, shape) else Modifier.border(1.dp, palette.accent, shape))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
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
