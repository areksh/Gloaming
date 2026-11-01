package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.hypot
import kotlinx.coroutines.delay
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.data.CompassReading
import sh.arek.gloaming.data.CompassSource
import sh.arek.gloaming.data.magneticDeclination
import sh.arek.gloaming.domain.CompassCheck
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.FindTargets
import sh.arek.gloaming.domain.Guide
import sh.arek.gloaming.domain.Pointing
import sh.arek.gloaming.domain.TargetFix
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.domain.decode
import sh.arek.gloaming.domain.encode
import sh.arek.gloaming.ui.theme.GloamingTheme
import sh.arek.gloaming.ui.theme.LocalPalette
import sh.arek.gloaming.ui.theme.Palette

/** The circular view, heard by a screen reader as the instruction and where. */
const val FIND_VIEW_TAG = "find-view"

/** The instruction line, which TalkBack speaks as it changes. */
const val FIND_INSTRUCTION_TAG = "find-instruction"

/** Where the target is in words, and which way the phone is facing. */
const val FIND_WHERE_TAG = "find-where"

/** Check compass, on Find and on the Compass settings page. */
const val CHECK_COMPASS_TAG = "check-compass"

/** How a test reaches the chip for one target. */
fun findChipTag(target: FindTarget): String = "find-chip:${target.encode()}"

/** What Find says, in place of the view, on a phone with no rotation sensor. */
internal const val NO_COMPASS_LINE = "This phone has no compass, so Find cannot point."

/** Half the view's width, in degrees: the view is sixty degrees across. */
private const val HALF_VIEW_DEGREES = 30f

/** How often the target is placed again. Fast enough for a pass, which moves about a degree a second. */
private const val TICK_MILLIS = 1_000L

/** Declination changes by a fraction of a degree a year, so once a day is plenty. */
private const val DAY_MILLIS = 24 * 3_600_000L

/** Tonight with nothing in it, so the Plough can be found before the list is worked out. */
private val EMPTY_NIGHT = TonightList(
    darkFrom = null,
    darkUntil = null,
    passes = emptyList(),
    bestPasses = emptyList(),
    events = emptyList(),
    comets = emptyList(),
    noctilucent = null,
)

/**
 * Points the way to something in the sky.
 *
 * A row of targets, a view sixty degrees across with the horizon, the target
 * and a reticle at the centre, the one line saying what to do, where the
 * target is in words, and how to recognise it. Buzzes once on lining up,
 * keeps the screen on while it is shown, and turns red on black when night
 * vision when finding is on - which applies to this screen only, because it
 * is the one held up to the sky.
 *
 * The compass is listened to only while this is composed and the app is in
 * front, so leaving the tab or the app lets go of the sensor.
 *
 * @param initial the target to open on, from Find it on a row; null opens on
 *   the first target offered.
 * @param onTargetChange told of a target picked from the chips, so the caller
 *   can open on it again.
 * @param clock the time now; a parameter so a test can hold it still.
 */
@Composable
fun FindScreen(
    state: MainUiState,
    actions: UiActions,
    source: CompassSource,
    initial: FindTarget?,
    modifier: Modifier = Modifier,
    onTargetChange: (FindTarget) -> Unit = {},
    clock: () -> Long = { System.currentTimeMillis() },
) {
    KeepScreenOn()
    if (state.settings.nightVisionWhenFinding) {
        GloamingTheme(nightVision = true) {
            FindContent(state, actions, source, initial, modifier, onTargetChange, clock)
        }
    } else {
        FindContent(state, actions, source, initial, modifier, onTargetChange, clock)
    }
}

@Composable
private fun FindContent(
    state: MainUiState,
    actions: UiActions,
    source: CompassSource,
    initial: FindTarget?,
    modifier: Modifier,
    onTargetChange: (FindTarget) -> Unit,
    clock: () -> Long,
) {
    val palette = LocalPalette.current
    val now by produceState(clock()) {
        while (true) {
            delay(TICK_MILLIS)
            value = clock()
        }
    }
    val latitude = state.settings.latitude
    val longitude = state.settings.longitude

    Column(
        modifier = modifier
            .background(palette.ground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (latitude == null || longitude == null) {
            Text(
                "Set where you are in Settings first, so Gloaming knows where things are in your sky.",
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }

        val list = state.tonight ?: EMPTY_NIGHT
        val available = FindTargets.available(list, auroraKnown = state.alertState.lastProbability != null, now)
        // By its text, so a saved choice from a build with different targets
        // falls back to the first one rather than failing.
        var chosen by rememberSaveable(initial?.encode()) { mutableStateOf(initial?.encode()) }
        val target = chosen?.let { FindTarget.decode(it) } ?: available.first()
        // A target opened from a row is offered even when tonight's list has
        // moved on from it, so the chip it was opened on is still there.
        val chips = if (target in available) available else listOf(target) + available

        Text(
            "Hold the phone up and point its top edge at the sky.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (chip in chips) {
                FilterChip(
                    selected = chip == target,
                    onClick = {
                        chosen = chip.encode()
                        onTargetChange(chip)
                    },
                    label = { Text(chipLabel(chip, list, now)) },
                    modifier = Modifier.testTag(findChipTag(chip)),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.accent,
                        selectedLabelColor = palette.onAccent,
                        labelColor = palette.text,
                    ),
                )
            }
        }

        val fix = remember(target, list, state.elements, latitude, longitude, now) {
            FindTargets.fix(target, list, state.elements, latitude, longitude, now)
        }

        if (!source.present) {
            Text(NO_COMPASS_LINE, style = MaterialTheme.typography.titleMedium)
            fix?.let { whereLine(it, null) }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
            fix?.let { Text(it.recognise, style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }

        Guided(state, actions, source, fix, latitude, longitude, now)
    }
}

/**
 * Everything that follows the compass, in a composable of its own so a new
 * reading - a dozen a second - redraws this and not the chips above it.
 */
@Composable
private fun Guided(
    state: MainUiState,
    actions: UiActions,
    source: CompassSource,
    fix: TargetFix?,
    latitude: Double,
    longitude: Double,
    now: Long,
) {
    val palette = LocalPalette.current
    val reading = rememberCompassReading(source)
    val declination = remember(latitude, longitude, now / DAY_MILLIS) {
        magneticDeclination(latitude, longitude, now)
    }
    val pointing = reading?.let {
        Pointing.direction(it.rotation, declination, state.settings.compassOffsetDegrees)
    }
    val position = fix?.position
    val guidance = if (pointing != null && position != null) Guide.of(pointing, position) else null
    val aligned = guidance?.aligned == true

    // Keyed on the state, so it runs once each time it becomes true and not
    // on every reading that stays lined up.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(aligned) {
        if (aligned) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetOffered by rememberSaveable { mutableStateOf(false) }
    // Offered at most once per visit as well as once a week, so a screen whose
    // settings have not caught up with the stamp yet does not offer it twice.
    var offeredThisVisit by remember { mutableStateOf(false) }
    val accuracy = reading?.accuracy
    LaunchedEffect(accuracy) {
        if (accuracy != null && !offeredThisVisit &&
            CompassCheck.shouldOffer(accuracy, state.settings.compassOfferedAt, now)
        ) {
            offeredThisVisit = true
            sheetOffered = true
            sheetOpen = true
            actions.onCompassOffered(now)
        }
    }

    val instruction = findInstruction(fix, guidance)
    val where = fix?.let { whereLine(it, pointing) }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        FindView(
            pointing = pointing,
            fix = fix,
            aligned = aligned,
            palette = palette,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .testTag(FIND_VIEW_TAG)
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(instruction, where).joinToString(" ")
                },
        )
    }
    Text(
        instruction,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier
            .testTag(FIND_INSTRUCTION_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    where?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.testTag(FIND_WHERE_TAG),
        )
    }
    fix?.let { Text(it.recognise, style = MaterialTheme.typography.bodyMedium) }

    HorizontalDivider(color = palette.line)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            accuracy?.let(::accuracyLine) ?: "Waiting for the compass.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                sheetOffered = false
                sheetOpen = true
            },
            modifier = Modifier.testTag(CHECK_COMPASS_TAG),
        ) { Text("Check compass") }
    }

    if (sheetOpen) {
        CompassSheet(
            reading = reading,
            state = state,
            actions = actions,
            nowMillis = now,
            offered = sheetOffered,
            onDismiss = { sheetOpen = false },
        )
    }
}

/**
 * The latest reading from [source], or null before the first.
 *
 * Collected only while the app is resumed: the flow's collection is what
 * registers the sensors, so pausing the app or leaving the composition
 * unregisters them.
 */
@Composable
internal fun rememberCompassReading(source: CompassSource): CompassReading? {
    val readings = remember(source) { source.readings() }
    val reading by readings.collectAsStateWithLifecycle(
        initialValue = null,
        minActiveState = Lifecycle.State.RESUMED,
    )
    return reading
}

/** Holds the screen on while composed, and puts back whatever was there before. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

/**
 * The view: sixty degrees of sky around where the phone points.
 *
 * The horizon is a dashed line with the ground shaded below it. The target is
 * a ring with its name while it is inside the view; off the view, or not yet
 * risen, it is an arrow at the edge pointing the way to turn - the rise point
 * on the horizon for a target not up. The reticle is drawn thicker when lined
 * up, so the state reads by shape and not by colour alone, which night vision
 * would take away.
 */
@Composable
private fun FindView(
    pointing: AltAz?,
    fix: TargetFix?,
    aligned: Boolean,
    palette: Palette,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(color = palette.text)
    val horizonStyle = MaterialTheme.typography.labelSmall.copy(color = palette.muted)

    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val perDegree = radius / HALF_VIEW_DEGREES
        val circle = Path().apply { addOval(Rect(center, radius)) }

        drawCircle(palette.raised, radius, center)
        if (pointing != null) {
            clipPath(circle) {
                val horizonY = center.y + pointing.altitude.toFloat() * perDegree
                if (horizonY < size.height) {
                    drawRect(
                        palette.ground,
                        topLeft = Offset(0f, horizonY.coerceAtLeast(0f)),
                        size = Size(size.width, size.height),
                    )
                }
                drawLine(
                    palette.muted,
                    Offset(0f, horizonY),
                    Offset(size.width, horizonY),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
                )
                drawLabel(measurer, "Horizon", horizonStyle, Offset(center.x - radius * 0.6f, horizonY), above = true)

                val position = fix?.position
                if (position != null) {
                    val guidance = Guide.of(pointing, position)
                    val dx = guidance.turnDegrees.toFloat() * perDegree
                    val dy = -guidance.tiltDegrees.toFloat() * perDegree
                    val ring = 14.dp.toPx()
                    if (hypot(dx, dy) <= radius - ring) {
                        val at = Offset(center.x + dx, center.y + dy)
                        drawCircle(palette.text, ring, at, style = Stroke(2.dp.toPx()))
                        drawLabel(measurer, fix.label, labelStyle, Offset(at.x + ring + 4.dp.toPx(), at.y), above = false)
                    }
                }
            }
            edgeArrowDirection(pointing, fix, perDegree, radius - 14.dp.toPx())?.let { (dx, dy) ->
                drawEdgeArrow(dx, dy, radius, palette)
            }
        }
        drawCircle(palette.line, radius, center, style = Stroke(1.5.dp.toPx()))

        val reticle = if (aligned) palette.accent else palette.muted
        val width = if (aligned) 4.dp.toPx() else 1.6.dp.toPx()
        val r = 22.dp.toPx()
        drawCircle(reticle, r, center, style = Stroke(width))
        val from = r - 4.dp.toPx()
        val to = r + 10.dp.toPx()
        for ((ux, uy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            drawLine(
                reticle,
                Offset(center.x + ux * from, center.y + uy * from),
                Offset(center.x + ux * to, center.y + uy * to),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Which way the edge arrow points, as a screen offset, or null for no arrow:
 * towards a target further than [inView] from the centre, or towards the rise
 * point of one below the horizon.
 */
private fun edgeArrowDirection(pointing: AltAz, fix: TargetFix?, perDegree: Float, inView: Float): Pair<Float, Float>? {
    fix ?: return null
    val position = fix.position
    if (position != null) {
        val guidance = Guide.of(pointing, position)
        val dx = guidance.turnDegrees.toFloat() * perDegree
        val dy = -guidance.tiltDegrees.toFloat() * perDegree
        return if (hypot(dx, dy) > inView) dx to dy else null
    }
    val riseAzimuth = fix.riseAzimuth ?: return null
    val guidance = Guide.of(pointing, AltAz(0.0, riseAzimuth))
    val dx = guidance.turnDegrees.toFloat()
    val dy = -guidance.tiltDegrees.toFloat()
    // Pointing straight at the rise point: down to the horizon it comes up from.
    return if (hypot(dx, dy) < 0.5f) 0f to 1f else dx to dy
}

private fun DrawScope.drawEdgeArrow(dx: Float, dy: Float, radius: Float, palette: Palette) {
    val length = hypot(dx, dy)
    val ux = dx / length
    val uy = dy / length
    val tip = radius - 6.dp.toPx()
    val base = radius - 28.dp.toPx()
    val half = 10.dp.toPx()
    val path = Path().apply {
        moveTo(center.x + ux * tip, center.y + uy * tip)
        lineTo(center.x + ux * base - uy * half, center.y + uy * base + ux * half)
        lineTo(center.x + ux * base + uy * half, center.y + uy * base - ux * half)
        close()
    }
    drawPath(path, palette.accent)
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    at: Offset,
    above: Boolean,
) {
    val layout = measurer.measure(text, style)
    val y = if (above) at.y - layout.size.height - 2.dp.toPx() else at.y - layout.size.height / 2f
    drawText(layout, topLeft = Offset(at.x, y))
}
