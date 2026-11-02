package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyPositions
import sh.arek.gloaming.data.CompassReading
import sh.arek.gloaming.data.FindAim
import sh.arek.gloaming.data.magneticDeclination
import sh.arek.gloaming.domain.CompassAccuracy
import sh.arek.gloaming.domain.CompassCheck
import sh.arek.gloaming.domain.Pointing
import sh.arek.gloaming.ui.theme.LocalPalette

/** The compass check sheet. */
const val COMPASS_SHEET_TAG = "compass-sheet"

/** Line up, which corrects the compass on the moon. */
const val LINE_UP_TAG = "line-up"

/** What Line up made of it: the correction, or why it refused. */
const val LINE_UP_RESULT_TAG = "line-up-result"

/** Done, which records the check. */
const val COMPASS_DONE_TAG = "compass-done"

/** The accuracy bar, heard as one sentence. */
const val COMPASS_ACCURACY_TAG = "compass-accuracy"

/**
 * Checking the compass: the figure of eight, the accuracy live as it moves,
 * then a fine-tune on the moon where it is up.
 *
 * Android has no call to recalibrate a compass. What it does is relearn north
 * as the phone is turned through every direction, and report how sure it is -
 * so this shows the motion and the accuracy, and the user sees it climb.
 *
 * The moon is the one target whose true bearing the app knows and the user
 * can point at without doubt, so Line up turns the gap between the two into a
 * standing correction ([CompassCheck.offsetFromMoon]). It is offered only
 * while the moon is up.
 *
 * @param reading the compass as it reads now, from the screen that opened this.
 * @param offered true when Find opened this on its own because the compass is
 *   unsure, which changes how it introduces itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassSheet(
    reading: CompassReading?,
    state: MainUiState,
    actions: UiActions,
    nowMillis: Long,
    offered: Boolean,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.raised,
        contentColor = palette.text,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag(COMPASS_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (offered) "Your compass may be off" else "Check the compass",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Phones lose track of north near metal and magnets, and after time in a bag. " +
                    "Putting it right takes about ten seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FigureOfEight()
                Text(
                    "Move the phone in a slow figure of eight three times, turning it as you go.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AccuracyBar(reading?.accuracy)

            HorizontalDivider(color = palette.line)
            LineUp(reading, state, actions, nowMillis)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                Button(
                    onClick = {
                        actions.onCompassChecked(nowMillis)
                        onDismiss()
                    },
                    modifier = Modifier.testTag(COMPASS_DONE_TAG),
                ) { Text("Done") }
            }
        }
    }
}

/**
 * The fine-tune on the moon, or a word on when it can be done.
 *
 * Its result stays on the sheet: a refusal says why in words that point at
 * the cause ([lineUpRefusal]), and a correction says how far out the compass
 * was.
 */
@Composable
private fun LineUp(reading: CompassReading?, state: MainUiState, actions: UiActions, nowMillis: Long) {
    val palette = LocalPalette.current
    val latitude = state.settings.latitude
    val longitude = state.settings.longitude
    // Where the moon is, and how far magnetic north is from true here.
    val sky = if (latitude != null && longitude != null) {
        remember(latitude, longitude, nowMillis) {
            SkyPositions.body(SkyBody.Moon, latitude, longitude, nowMillis) to
                magneticDeclination(latitude, longitude, nowMillis)
        }
    } else {
        null
    }
    var result by remember { mutableStateOf<String?>(null) }

    Text("Then fine-tune on the moon", style = MaterialTheme.typography.titleSmall)
    // Lined up the same way Find is aimed, so the correction is for the hold
    // it will be used with.
    val aim = state.settings.findAim
    if (sky == null || sky.first.altitude <= 0.0) {
        Text(
            "Once the moon is up, ${lineUpHold(aim, "it")} and tap Line up. Gloaming " +
                "knows exactly where it is, so it can correct what the compass gets wrong.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        return
    }
    Text(
        "${lineUpHold(aim, "the moon").replaceFirstChar { it.uppercase() }} and tap Line up. " +
            "Gloaming knows exactly where it is, so it can correct what the compass gets wrong.",
        style = MaterialTheme.typography.bodySmall,
        color = palette.muted,
    )
    val (moon, declination) = sky
    OutlinedButton(
        onClick = {
            val current = state.settings.compassOffsetDegrees
            val pointing = reading?.let { Pointing.direction(it.rotation, aim, declination, current) }
            val next = pointing?.let { CompassCheck.offsetFromMoon(it, moon, current) }
            result = if (next == null) {
                lineUpRefusal(moon)
            } else {
                actions.onCompassOffsetChange(next)
                lineUpResult(next - current)
            }
        },
        modifier = Modifier.testTag(LINE_UP_TAG),
    ) { Text("Line up") }
    result?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(LINE_UP_RESULT_TAG))
    }
}

/** How to hold the phone on [moon] for Line up, the way Find is aimed. */
internal fun lineUpHold(aim: FindAim, moon: String): String = when (aim) {
    FindAim.TopEdge -> "point the top of the phone at $moon"
    FindAim.LookThrough -> "hold the phone up in front of $moon, so it is straight behind the middle of the phone,"
}

/**
 * Low, medium, high as three segments, filled up to the level reached.
 *
 * An empty segment is drawn as an outline and a filled one solid, and the
 * level is named in bold beneath, so it reads without colour.
 */
@Composable
private fun AccuracyBar(accuracy: CompassAccuracy?) {
    val palette = LocalPalette.current
    val filled = when (accuracy) {
        CompassAccuracy.Low -> 1
        CompassAccuracy.Medium -> 2
        CompassAccuracy.High -> 3
        CompassAccuracy.Unreliable, null -> 0
    }
    val said = when (accuracy) {
        null -> "Accuracy: waiting for the compass"
        else -> "Accuracy: ${accuracy.name.lowercase()}"
    }
    Column(
        modifier = Modifier
            .testTag(COMPASS_ACCURACY_TAG)
            .clearAndSetSemantics { contentDescription = said },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (segment in 1..3) {
                val shape = RoundedCornerShape(4.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .then(
                            if (segment <= filled) {
                                Modifier.background(palette.accent, shape)
                            } else {
                                Modifier.border(1.dp, palette.line, shape)
                            },
                        ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((level, name) in listOf(CompassAccuracy.Low to "Low", CompassAccuracy.Medium to "Medium", CompassAccuracy.High to "High")) {
                val current = level == accuracy
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) palette.text else palette.muted,
                    fontWeight = if (current) FontWeight.Bold else null,
                )
            }
        }
        if (accuracy == CompassAccuracy.Unreliable) {
            Text("Unreliable", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** The motion, drawn: a dashed figure of eight. Decorative; the sentence beside it says the same. */
@Composable
private fun FigureOfEight() {
    val palette = LocalPalette.current
    Canvas(Modifier.size(width = 96.dp, height = 52.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2, h / 2)
            cubicTo(w * 0.37f, h * 0.12f, w * 0.08f, h * 0.12f, w * 0.08f, h / 2)
            cubicTo(w * 0.08f, h * 0.88f, w * 0.37f, h * 0.88f, w / 2, h / 2)
            cubicTo(w * 0.63f, h * 0.12f, w * 0.92f, h * 0.12f, w * 0.92f, h / 2)
            cubicTo(w * 0.92f, h * 0.88f, w * 0.63f, h * 0.88f, w / 2, h / 2)
        }
        drawPath(
            path,
            palette.muted,
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
            ),
        )
    }
}
