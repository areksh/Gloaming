package sh.arek.gloaming.ui

import androidx.compose.foundation.background
import sh.arek.gloaming.domain.EverythingView
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.SkyProjection
import sh.arek.gloaming.data.CompassReading
import sh.arek.gloaming.data.CompassSource
import sh.arek.gloaming.data.FindAim
import sh.arek.gloaming.data.magneticDeclination
import sh.arek.gloaming.domain.AlignBuzz
import sh.arek.gloaming.domain.CompassCheck
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.FindTargets
import sh.arek.gloaming.domain.Sky
import sh.arek.gloaming.astronomy.Twilight
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

/** The choice of how the phone is aimed, one segment per [FindAim]. */
fun findAimTag(aim: FindAim): String = "find-aim:${aim.name}"

/** How to hold the phone, said above the view for the way it is aimed. */
internal fun aimLine(aim: FindAim): String = when (aim) {
    FindAim.TopEdge -> "Aim the phone's top edge at the sky like a pointer, screen tipped up towards you."
    FindAim.LookThrough -> "Hold the phone up in front of you and look through it, as if it were a window."
}

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

/**
 * Tonight with nothing in it, so the Plough or the Southern Cross can be found
 * before the list is worked out.
 */
private val EMPTY_NIGHT = TonightList(
    darkFrom = null,
    darkUntil = null,
    passes = emptyList(),
    bestPasses = emptyList(),
    events = emptyList(),
    comets = emptyList(),
    noctilucent = null,
)

/** How a test finds the line shown when nothing is up to point at. */
const val FIND_NOTHING_TAG = "find-nothing"

/** The saved choice that means Everything mode rather than a target. */
internal const val FIND_EVERYTHING = "everything"

/** The Everything chip; a chip's tag, so the row's order reads as one list. */
const val FIND_EVERYTHING_TAG = "find-chip:everything"

/** The line naming what is in view, in Everything mode. */
const val FIND_IN_VIEW_TAG = "find-in-view"

/** A row of what is up, in Everything mode on a phone with no compass. */
fun findUpTag(target: FindTarget): String = "find-up:${target.encode()}"

private const val MINUTE_MILLIS = 60_000L

private fun skyFor(state: MainUiState, latitude: Double, longitude: Double, now: Long): Sky {
    val alert = state.alertState
    return FindTargets.skyAt(
        latitude, longitude, now, alert.lastProbability, alert.lastPollAt, state.settings.threshold,
    )
}

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
    everything: Boolean = false,
    onEverythingChosen: () -> Unit = {},
) {
    KeepScreenOn()
    // Dimmed as well as red, the same as night vision everywhere else, and
    // only while Find is shown: leaving it puts the brightness back.
    DimScreen(state.settings.nightVisionWhenFinding)
    if (state.settings.nightVisionWhenFinding) {
        GloamingTheme(nightVision = true) {
            FindContent(state, actions, source, initial, everything, modifier, onTargetChange, onEverythingChosen, clock)
        }
    } else {
        FindContent(state, actions, source, initial, everything, modifier, onTargetChange, onEverythingChosen, clock)
    }
}

@Composable
private fun FindContent(
    state: MainUiState,
    actions: UiActions,
    source: CompassSource,
    initial: FindTarget?,
    everything: Boolean,
    modifier: Modifier,
    onTargetChange: (FindTarget) -> Unit,
    onEverythingChosen: () -> Unit,
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
        val handedIn = initial
        // Worked out at most once a minute, keyed on the minute and on what it
        // reads: nothing is visible or not visible faster than that, and the
        // work must stay out of the per-second tick and the compass readings.
        val available = remember(
            now / MINUTE_MILLIS, list, state.elements, latitude, longitude,
            state.settings.threshold, state.alertState.lastProbability, state.alertState.lastPollAt, handedIn,
        ) {
            FindTargets.availableAt(
                list, state.elements, latitude, longitude, now, handedIn, skyFor(state, latitude, longitude, now),
            )
        }
        // The search a day ahead is for the empty state only, and runs off the
        // main thread: the line is shown at once and the next-up part follows.
        val nothingOffered = available.isEmpty()
        val next by produceState<Pair<FindTarget, Long>?>(
            null, nothingOffered, now / MINUTE_MILLIS, list, latitude, longitude,
        ) {
            value = if (nothingOffered) {
                val at = now
                withContext(Dispatchers.Default) {
                    FindTargets.nextUp(
                        list, at,
                        altitudeAt = { target, t -> FindTargets.altitudeAt(target, latitude, longitude, t) ?: -90.0 },
                        sunAt = { t -> Twilight.sunAltitude(latitude, longitude, t) },
                    )
                }
            } else {
                null
            }
        }
        // By its text, so a saved choice from a build with different targets
        // falls back to the first one rather than failing.
        // Keyed on what the host holds, so a choice made here and handed back
        // up - a chip, or Everything - is still the choice after a visit to
        // another tab, and a target handed in afresh replaces it.
        var chosen by rememberSaveable(initial?.encode(), everything) {
            mutableStateOf(if (everything) FIND_EVERYTHING else initial?.encode())
        }
        // Everything is a mode of this screen, not a target, so it is checked
        // for before the choice is read as one; underneath it, the first
        // target stands in for the row's sake.
        val inEverything = chosen == FIND_EVERYTHING
        val asked = (if (inEverything) null else chosen?.let { FindTarget.decode(it) }) ?: available.firstOrNull()
        // A default nobody tapped is held once shown, so a list that changes
        // underneath it does not move the guidance to another target.
        LaunchedEffect(asked) {
            if (chosen == null && asked != null) chosen = asked.encode()
        }

        Text(
            aimLine(state.settings.findAim),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
        )
        AimChoice(state.settings.findAim, actions.onFindAimChange)
        if (asked == null) {
            Text(
                if (!findTonightKnown(state.tonight, state.tonightUpdating, state.elements)) {
                    WORKING_OUT_TONIGHT
                } else {
                    nothingToPointAt(next?.let { (target, at) -> chipLabel(target, list, Long.MAX_VALUE, state.elements) to at })
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(FIND_NOTHING_TAG),
            )
            return@Column
        }
        // A pass handed in by a notification rises a second or so from
        // tonight's own entry for it, so it is matched to that entry rather
        // than standing beside it as a second chip.
        val target = FindTargets.matching(asked, available) ?: asked
        // A target opened from a row, or chosen and since set, stays offered
        // so the chip it was picked on is still there.
        val chips = if (target in available) available else listOf(target) + available

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = inEverything,
                onClick = {
                    chosen = FIND_EVERYTHING
                    onEverythingChosen()
                },
                label = { Text("Everything") },
                modifier = Modifier.testTag(FIND_EVERYTHING_TAG),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.accent,
                    selectedLabelColor = palette.onAccent,
                    labelColor = palette.text,
                ),
            )
            for (chip in chips) {
                FilterChip(
                    selected = !inEverything && chip == target,
                    onClick = {
                        chosen = chip.encode()
                        onTargetChange(chip)
                    },
                    label = { Text(chipLabel(chip, list, now, state.elements)) },
                    modifier = Modifier.testTag(findChipTag(chip)),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.accent,
                        selectedLabelColor = palette.onAccent,
                        labelColor = palette.text,
                    ),
                )
            }
        }

        if (inEverything) {
            // Every second, like the one target guidance places, because a
            // pass moves about a degree a second; off the main thread, because
            // it is every pattern's figure and every body at once.
            val items by produceState(
                emptyList<Pair<FindTarget, TargetFix>>(), now, available, list, state.elements, latitude, longitude,
            ) {
                val at = now
                value = withContext(Dispatchers.Default) {
                    FindTargets.everythingUp(available, list, state.elements, latitude, longitude, at)
                }
            }
            Everything(state, actions, source, items, list, latitude, longitude, now) { picked ->
                chosen = picked.encode()
                onTargetChange(picked)
            }
            return@Column
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
 * Everything mode: every offered target that is up, at once. A tap on one
 * hands it to [onPick], which selects its chip as tapping the chip does.
 *
 * Without a compass there is no view to draw it on, so it is a list of what
 * is up and where.
 */
@Composable
private fun Everything(
    state: MainUiState,
    actions: UiActions,
    source: CompassSource,
    items: List<Pair<FindTarget, TargetFix>>,
    list: TonightList,
    latitude: Double,
    longitude: Double,
    now: Long,
    onPick: (FindTarget) -> Unit,
) {
    val palette = LocalPalette.current
    if (!source.present) {
        Text(NO_COMPASS_LINE, style = MaterialTheme.typography.titleMedium)
        for ((target, fix) in items) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(target) }
                    .testTag(findUpTag(target))
                    .padding(vertical = 6.dp),
            ) {
                Text(chipLabel(target, list, now, state.elements), style = MaterialTheme.typography.titleSmall)
                whereLine(fix, null)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                }
            }
        }
        return
    }

    val reading = rememberCompassReadingState(source)
    val (pointing, roll) = rememberAim(reading, state, latitude, longitude, now)
    // The items are replaced every second; read through one state, so what
    // derives from them follows without the buzz below starting over.
    val current = rememberUpdatedState(items)
    val labels = remember(items, list, state.elements) {
        items.map { (target, _) -> chipLabel(target, list, now, state.elements) }
    }
    val labelOf = remember(items, labels) { items.map { it.first }.zip(labels).toMap() }
    val line by remember(labelOf) {
        derivedStateOf {
            pointing.value?.let { p ->
                inViewLine(EverythingView.inView(p, current.value, HALF_VIEW_DEGREES.toDouble()).mapNotNull { labelOf[it] })
            }
        }
    }
    val under = remember { derivedStateOf { pointing.value?.let { EverythingView.underReticle(it, current.value) } } }

    // Buzzes on coming onto any target, and again on one only after moving
    // well off it (AlignBuzz.nextAmong); a flow of what is under the reticle
    // and how far the last one buzzed for is, as guidance follows its one.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(haptics) {
        var buzz = AlignBuzz.AMONG_START
        snapshotFlow {
            val p = pointing.value
            // How far each target buzzed for is now, to know which re-arm; one
            // no longer up has no distance, and re-arms.
            val apart = buzz.disarmed.associateWith { target ->
                val at = current.value.firstOrNull { it.first == target }?.second?.position
                if (p != null && at != null) Guide.separation(p, at) else null
            }
            under.value to apart
        }.collect { (onTarget, apart) ->
            val (next, fire) = AlignBuzz.nextAmong(buzz, onTarget) { apart[it] }
            buzz = next
            if (fire) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        EverythingWindow(
            pointing = { pointing.value },
            roll = { roll.value },
            items = items,
            labels = labels,
            under = { under.value },
            onPick = onPick,
            palette = palette,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .testTag(FIND_VIEW_TAG)
                .clearAndSetSemantics { contentDescription = line ?: WAITING_FOR_COMPASS },
        )
    }
    Text(
        line ?: WAITING_FOR_COMPASS,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(FIND_IN_VIEW_TAG),
    )
    Text(
        "Tap a name to be guided to it.",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.muted,
    )
    CompassFooter(state, actions, reading, now)
}

/** Said until the compass has given its first reading. */
private const val WAITING_FOR_COMPASS = "Waiting for the compass."

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
    val reading = rememberCompassReadingState(source)
    // A reading arrives every frame or so. Everything that follows from one is
    // a derived state read only where a change should reach - the drawing, and
    // the words, which change far less often than the readings do - so a
    // reading redraws the view and does not recompose this function.
    val (pointing, roll) = rememberAim(reading, state, latitude, longitude, now)
    // Read through a state, as the aim is, so the guidance, the reticle and
    // the buzz below follow a newly chosen target and its movement.
    val position = rememberUpdatedState(fix?.position)
    val guidance = remember {
        derivedStateOf {
            val p = pointing.value
            val at = position.value
            if (p != null && at != null) Guide.of(p, at) else null
        }
    }
    val aligned = remember { derivedStateOf { guidance.value?.aligned == true } }

    // Buzzes on lining up and re-arms only once the phone has moved well off
    // the target (AlignBuzz), so a hand wavering at the edge of lined up does
    // not buzz on every crossing. Followed as a flow of the separation rather
    // than decided in composition, which must not perform effects.
    val separation = remember {
        derivedStateOf {
            val p = pointing.value
            val at = position.value
            if (p != null && at != null) Guide.separation(p, at) else null
        }
    }
    val haptics = LocalHapticFeedback.current
    // Started again for a different target, so it is armed for it.
    LaunchedEffect(haptics, fix?.label) {
        var armed = true
        snapshotFlow { separation.value }.collect {
            val step = AlignBuzz.next(armed, it)
            armed = step.armed
            if (step.fire) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val tonightKnown = findTonightKnown(state.tonight, state.tonightUpdating, state.elements)
    val instruction by remember(fix, tonightKnown) {
        derivedStateOf { findInstruction(fix, guidance.value, tonightKnown) }
    }
    val where by remember(fix) {
        derivedStateOf { fix?.let { whereLine(it, pointing.value) } }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        FindView(
            pointing = { pointing.value },
            roll = { roll.value },
            fix = fix,
            aligned = { aligned.value },
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

    CompassFooter(state, actions, reading, now)
}

/**
 * Where the phone points and how far to turn the drawing for its roll, as
 * derived states of the compass [reading]: read only where a change should
 * reach, so a reading redraws a view and recomposes nothing.
 */
@Composable
private fun rememberAim(
    reading: State<CompassReading?>,
    state: MainUiState,
    latitude: Double,
    longitude: Double,
    now: Long,
): Pair<State<AltAz?>, State<Float>> {
    val declination = remember(latitude, longitude, now / DAY_MILLIS) {
        magneticDeclination(latitude, longitude, now)
    }
    // The settings are read through states of their own, so the two states
    // returned are the same objects for the life of the screen: whatever has
    // captured them - an effect, a tap handler, another derived state - follows
    // a change of aim or a compass correction rather than keeping the old one.
    val offsetDegrees = rememberUpdatedState(state.settings.compassOffsetDegrees)
    val aim = rememberUpdatedState(state.settings.findAim)
    val declinationNow = rememberUpdatedState(declination)
    // The roll is from the device's top edge; the screen's up is a quarter
    // turn round from it for each quarter the display is rotated.
    val displayQuarters = rememberUpdatedState(LocalView.current.display?.rotation ?: 0)
    val pointing = remember(reading) {
        derivedStateOf {
            reading.value?.let { Pointing.direction(it.rotation, aim.value, declinationNow.value, offsetDegrees.value) }
        }
    }
    val roll = remember(reading) {
        derivedStateOf {
            val rotation = reading.value?.rotation
            if (rotation == null || aim.value != FindAim.LookThrough) {
                0f
            } else {
                (Pointing.roll(rotation, aim.value) - 90.0 * displayQuarters.value).toFloat()
            }
        }
    }
    return pointing to roll
}

/**
 * Below either mode's view: how good the compass reading is, and the button
 * to check it, with the check offered of itself when the reading is poor.
 */
@Composable
private fun CompassFooter(state: MainUiState, actions: UiActions, reading: State<CompassReading?>, now: Long) {
    val palette = LocalPalette.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetOffered by rememberSaveable { mutableStateOf(false) }
    // Offered at most once per visit as well as once a week, so a screen whose
    // settings have not caught up with the stamp yet does not offer it twice.
    var offeredThisVisit by remember { mutableStateOf(false) }
    val accuracy by remember { derivedStateOf { reading.value?.accuracy } }
    LaunchedEffect(accuracy) {
        val seen = accuracy
        if (seen != null && !offeredThisVisit &&
            CompassCheck.shouldOffer(seen, state.settings.compassOfferedAt, now)
        ) {
            offeredThisVisit = true
            sheetOffered = true
            sheetOpen = true
            actions.onCompassOffered(now)
        }
    }

    HorizontalDivider(color = palette.line)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            accuracy?.let(::accuracyLine) ?: WAITING_FOR_COMPASS,
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
            reading = reading.value,
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
internal fun rememberCompassReading(source: CompassSource): CompassReading? =
    rememberCompassReadingState(source).value

/**
 * [rememberCompassReading] as the state itself, for a caller that reads it
 * only where a change should reach: in a draw block or a derived value, and
 * not in composition, which a reading a frame would recompose every frame.
 */
@Composable
internal fun rememberCompassReadingState(source: CompassSource): State<CompassReading?> {
    val readings = remember(source) { source.readings() }
    return readings.collectAsStateWithLifecycle(
        initialValue = null,
        minActiveState = Lifecycle.State.RESUMED,
    )
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
 * The two ways of holding the phone, side by side, so either can be tried
 * where the sky is: neither is the obvious one to everybody.
 */
@Composable
private fun AimChoice(aim: FindAim, onChange: (FindAim) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FindAim.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == aim,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, FindAim.entries.size),
                modifier = Modifier.testTag(findAimTag(option)),
            ) { Text(option.label) }
        }
    }
}

/**
 * The view: sixty degrees of sky around where the phone points.
 *
 * The horizon is dashed, with the ground shaded beyond it. The target is
 * a ring with its name while it is inside the view; off the view, or not yet
 * risen, it is an arrow at the edge pointing the way to turn - the rise point
 * on the horizon for a target not up. A pattern is also drawn as its stick
 * figure, under the ring, so what to look for is on the screen as well as
 * where. The reticle is drawn thicker when lined up, so the state reads by
 * shape and not by colour alone, which night vision would take away.
 *
 * The ring, the figure and the horizon are placed by one projection,
 * [SkyProjection], so the ring always sits on the figure's centre and a star
 * low down is on the right side of the horizon at the edge of the view as
 * well as in the middle. The projection draws the horizon as a circle, which
 * is a straight line only looking level; it is drawn as one, as far as it
 * reaches into the view. The edge arrow points the way the projection does,
 * except for a target behind the viewer and low, where turning round beats
 * tipping over the top and [Guide.of] says which way to turn.
 *
 * [roll] turns the sky - horizon, ground, target and arrow - clockwise by so
 * many degrees, so that looked through, it stays level with the real one
 * however the phone is rolled. The reticle is the phone's own and stays put.
 */
@Composable
private fun FindView(
    pointing: () -> AltAz?,
    roll: () -> Float,
    fix: TargetFix?,
    aligned: () -> Boolean,
    palette: Palette,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(color = palette.text)
    val horizonStyle = MaterialTheme.typography.labelSmall.copy(color = palette.muted)

    // The path and the dash are made when the size changes and not on every
    // draw; a draw happens for each compass reading, and the lambdas below are
    // where a reading is read, so a reading redraws this and recomposes nothing.
    Spacer(modifier.graphicsLayer {}.drawWithCache {
        val backdrop = backdrop(measurer, horizonStyle)
        val targetLayout = fix?.let { measurer.measure(it.label, labelStyle) }
        val figure = fix?.figure
        val magnitudes = figure?.stars?.map { it.magnitude }.orEmpty()
        val figurePoints = MutableList<Offset?>(figure?.positions?.size ?: 0) { null }
        onDrawBehind {
            drawSky(
                pointing(), roll(), backdrop, aligned(), palette,
                clipped = { pointing, perDegree, radius ->
                    if (figure != null) {
                        figure.positions.forEachIndexed { i, star ->
                            figurePoints[i] = skyToScreen(pointing, star, center, perDegree)
                        }
                        drawFigure(figurePoints, magnitudes, figure.segments, palette)
                    }

                    val onScreen = fix?.position?.let { skyToScreen(pointing, it, center, perDegree) }
                    if (onScreen != null) {
                        val dx = onScreen.x - center.x
                        val dy = onScreen.y - center.y
                        val ring = 14.dp.toPx()
                        if (hypot(dx, dy) <= radius - ring) {
                            val at = Offset(center.x + dx, center.y + dy)
                            drawCircle(palette.text, ring, at, style = Stroke(2.dp.toPx()))
                            targetLayout?.let { drawLabel(it, Offset(at.x + ring + 4.dp.toPx(), at.y), above = false) }
                        }
                    }
                },
                turned = { pointing, perDegree, radius ->
                    edgeArrowDirection(pointing, fix, perDegree, radius - 14.dp.toPx())?.let { (dx, dy) ->
                        drawEdgeArrow(dx, dy, radius, palette)
                    }
                },
            )
        }
    })
}

/**
 * Everything mode's view: every target in [items] that falls in it, each
 * pattern as its figure and everything else as a dot, with its label from
 * [labels] where there is room for one (see [placeLabels]). The reticle
 * takes the lined-up look while [under] gives a target.
 *
 * A tap picks a target by [pickAt], from the layout of the frame last drawn,
 * so what is picked is what was on the screen; [onPick] gets it.
 */
@Composable
private fun EverythingWindow(
    pointing: () -> AltAz?,
    roll: () -> Float,
    items: List<Pair<FindTarget, TargetFix>>,
    labels: List<String>,
    under: () -> FindTarget?,
    onPick: (FindTarget) -> Unit,
    palette: Palette,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = palette.text)
    val horizonStyle = MaterialTheme.typography.labelSmall.copy(color = palette.muted)
    val drawn = remember { DrawnFrame() }
    val pick by rememberUpdatedState(onPick)
    val rollNow by rememberUpdatedState(roll)

    Spacer(
        modifier
            .graphicsLayer {}
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val layout = drawn.layout ?: return@detectTapGestures
                    val middle = Offset(size.width / 2f, size.height / 2f)
                    pickAt(layout, tap, rollNow(), middle, TAP_REACH.toPx())?.let(pick)
                }
            }
            .drawWithCache {
                val backdrop = backdrop(measurer, horizonStyle)
                val texts = labels.map { measurer.measure(it, labelStyle) }
                val sizes = texts.map { it.size }
                val targets = items.map { it.first }
                val anchors = MutableList<Offset?>(items.size) { null }
                val magnitudes = items.map { (_, fix) -> fix.figure?.stars?.map { it.magnitude }.orEmpty() }
                val figurePoints = items.map { (_, fix) -> MutableList<Offset?>(fix.figure?.positions?.size ?: 0) { null } }
                onDrawBehind {
                    val pointing = pointing()
                    if (pointing == null) drawn.layout = null
                    drawSky(
                        pointing, roll(), backdrop, under() != null, palette,
                        clipped = { at, perDegree, radius ->
                            items.forEachIndexed { i, (_, fix) ->
                                anchors[i] = fix.position?.let { skyToScreen(at, it, center, perDegree) }
                                val figure = fix.figure
                                if (figure != null) {
                                    figure.positions.forEachIndexed { j, star ->
                                        figurePoints[i][j] = skyToScreen(at, star, center, perDegree)
                                    }
                                    drawFigure(figurePoints[i], magnitudes[i], figure.segments, palette)
                                } else {
                                    anchors[i]?.let { drawCircle(palette.text, DOT_RADIUS.toPx(), it) }
                                }
                            }
                            val layout = placeLabels(targets, anchors.toList(), sizes, center, radius, LABEL_GAP.toPx())
                            drawn.layout = layout
                            layout.labels.forEachIndexed { i, box -> box?.let { drawText(texts[i], topLeft = it.topLeft) } }
                        },
                    )
                }
            },
    )
}

/** The layout of Everything's last frame, written by the draw and read by a tap; not state, so writing it redraws nothing. */
private class DrawnFrame {
    var layout: EverythingLayout? = null
}

/** How far from a target a tap still picks it. */
private val TAP_REACH = 32.dp

/** Everything mode's dot for a target that is not a pattern. */
private val DOT_RADIUS = 4.dp

/** Between a target and its label in Everything mode. */
private val LABEL_GAP = 6.dp

/** What both views' backdrop needs, made once per size: the view's circle, the horizon's dash and label, and a path for the ground. */
private class Backdrop(val circle: Path, val dash: PathEffect, val horizon: TextLayoutResult, val ground: Path)

private fun CacheDrawScope.backdrop(measurer: TextMeasurer, horizonStyle: TextStyle) = Backdrop(
    circle = Path().apply { addOval(Rect(Offset(size.width / 2f, size.height / 2f), size.minDimension / 2f)) },
    dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
    horizon = measurer.measure("Horizon", horizonStyle),
    ground = Path(),
)

/**
 * The view both modes share: the disc, then - once there is a reading - the
 * sky turned by [roll], with the horizon and [clipped] inside the circle and
 * [turned] turned with it but not clipped (an edge arrow overhangs the rim),
 * then the rim and the reticle, which is the phone's own and stays put. The
 * reticle is thicker and in the accent while [aligned], so the state reads by
 * shape and not by colour alone, which night vision would take away.
 */
private fun DrawScope.drawSky(
    pointing: AltAz?,
    roll: Float,
    backdrop: Backdrop,
    aligned: Boolean,
    palette: Palette,
    clipped: DrawScope.(pointing: AltAz, perDegree: Float, radius: Float) -> Unit,
    turned: DrawScope.(pointing: AltAz, perDegree: Float, radius: Float) -> Unit = { _, _, _ -> },
) {
    val radius = size.minDimension / 2f
    val perDegree = radius / HALF_VIEW_DEGREES

    drawCircle(palette.raised, radius, center)
    if (pointing != null) rotate(roll, center) {
        clipPath(backdrop.circle) {
            drawHorizon(pointing.altitude, perDegree, radius, backdrop.ground, backdrop.dash, backdrop.horizon, palette)
            clipped(pointing, perDegree, radius)
        }
        turned(pointing, perDegree, radius)
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

/**
 * The horizon and the ground beyond it, for a view centred at [altitude].
 *
 * Looking up the ground is outside the horizon's circle, looking down inside
 * it. Only the arc that reaches into the view is stroked: a circle hundreds
 * of views across, as it is looking nearly level, would otherwise be dashed
 * all the way round on every reading. [ground] is reused from draw to draw.
 */
private fun DrawScope.drawHorizon(
    altitude: Double,
    perDegree: Float,
    radius: Float,
    ground: Path,
    dash: PathEffect,
    label: TextLayoutResult,
    palette: Palette,
) {
    val stroke = Stroke(1.2.dp.toPx(), pathEffect = dash)
    val labelX = center.x - radius * 0.6f
    val circle = SkyProjection.horizon(altitude)
    if (circle == null) {
        // Level: the straight line through the middle, ground below it.
        // Wider than the view, so the ground still fills it when turned.
        val y = center.y + altitude.toFloat() * perDegree
        drawRect(palette.ground, topLeft = Offset(-size.width, y), size = Size(3 * size.width, 3 * size.height))
        drawLine(palette.muted, Offset(-size.width, y), Offset(2 * size.width, y), stroke.width, pathEffect = dash)
        drawLabel(label, Offset(labelX, y), above = true)
        return
    }

    val at = Offset(center.x + circle.centre.x.toFloat() * perDegree, center.y - circle.centre.y.toFloat() * perDegree)
    val r = circle.radius.toFloat() * perDegree
    ground.rewind()
    if (altitude > 0) {
        ground.addRect(Rect(-size.width, -size.height, 2 * size.width, 2 * size.height))
        ground.addOval(Rect(at, r))
        ground.fillType = PathFillType.EvenOdd
    } else {
        ground.addOval(Rect(at, r))
        ground.fillType = PathFillType.NonZero
    }
    drawPath(ground, palette.ground)

    // The arc within reach of the view, either side of the point of the
    // circle nearest the view's centre.
    val towards = Math.toDegrees(atan2((center.y - at.y).toDouble(), (center.x - at.x).toDouble())).toFloat()
    val half = if (r <= 1.5f * radius) 180f else Math.toDegrees(asin(1.5 * radius / r)).toFloat()
    drawArc(
        palette.muted,
        startAngle = towards - half,
        sweepAngle = 2 * half,
        useCenter = false,
        topLeft = Offset(at.x - r, at.y - r),
        size = Size(2 * r, 2 * r),
        style = stroke,
    )
    // On the side of the circle nearer the view: below its centre looking up.
    val across = labelX - at.x
    if (r > kotlin.math.abs(across)) {
        val rise = sqrt(r * r - across * across)
        drawLabel(label, Offset(labelX, if (altitude > 0) at.y + rise else at.y - rise), above = true)
    }
}

/**
 * Which way the edge arrow points, as a screen offset, or null for no arrow:
 * towards a target further than [inView] from the centre, or towards the rise
 * point of one below the horizon.
 */
internal fun edgeArrowDirection(pointing: AltAz, fix: TargetFix?, perDegree: Float, inView: Float): Pair<Float, Float>? {
    fix ?: return null
    val position = fix.position
    if (position != null) {
        // In view by the same projection the ring is drawn with, so the ring
        // and the arrow are never both shown or both missing.
        val flat = SkyProjection.around(pointing, position)
        if (flat != null && hypot(flat.x, flat.y).toFloat() * perDegree <= inView) return null
        return arrowTowards(pointing, position, inFront = flat != null)
    }
    val rise = AltAz(0.0, fix.riseAzimuth ?: return null)
    // Pointing straight at the rise point: down to the horizon it comes up from.
    if (Guide.separation(pointing, rise) < 0.5) return 0f to 1f
    return arrowTowards(pointing, rise, inFront = SkyProjection.around(pointing, rise) != null)
}

/**
 * In front, or looking nearly straight up, the way the projection points, so
 * the arrow says where the ring will come in. Behind and lower, the way
 * [Guide.of] says: a turn and a tilt, because somebody facing away from a
 * star low in the east turns round to it rather than tipping over the top.
 */
private fun arrowTowards(pointing: AltAz, target: AltAz, inFront: Boolean): Pair<Float, Float> {
    if (inFront || pointing.altitude >= Guide.ZENITH_DEGREES) {
        SkyProjection.towards(pointing, target)?.let { return it.x.toFloat() to -it.y.toFloat() }
    }
    val guidance = Guide.of(pointing, target)
    return guidance.turnDegrees.toFloat() to -guidance.tiltDegrees.toFloat()
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
    layout: TextLayoutResult,
    at: Offset,
    above: Boolean,
) {
    val y = if (above) at.y - layout.size.height - 2.dp.toPx() else at.y - layout.size.height / 2f
    drawText(layout, topLeft = Offset(at.x, y))
}
