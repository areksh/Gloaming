package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.ui.theme.LocalPalette

/** The row sheet, opened by tapping a row on the timeline. */
const val ROW_SHEET_TAG = "row-sheet"

/** Find it, on a row whose subject can be pointed at. */
const val FIND_IT_TAG = "find-it"

/**
 * Which words a row offers to explain: the ones the card it replaced offered
 * behind its Explain button, so a term is still explained beside the thing
 * that used it. Nullable so a row with nothing worth explaining can say so.
 */
fun helpTopic(subject: RowSubject): HelpTopic? = when (subject) {
    RowSubject.Twilight -> HelpTopic.Darkness
    RowSubject.Clear -> HelpTopic.SkyNow
    RowSubject.Moon -> HelpTopic.Moon
    is RowSubject.Pass -> HelpTopic.Satellites
    is RowSubject.Planet -> HelpTopic.Planets
    is RowSubject.Event -> if (subject.event.kind == EventKind.Comet) HelpTopic.Comets else HelpTopic.Events
    is RowSubject.Constellation -> HelpTopic.Constellations
}

/** What a sheet is headed with: when, what, and the line under it. */
data class SheetHeading(val time: String, val title: String, val detail: String?)

/**
 * A subject's heading wherever its sheet is opened from, worded as its
 * timeline row is so the two never read differently. Null for the twilight
 * and the clear spell, which are only ever opened from a row of their own.
 */
fun heading(subject: RowSubject): SheetHeading? = when (subject) {
    RowSubject.Twilight, RowSubject.Clear -> null
    RowSubject.Moon -> SheetHeading(TONIGHT_TIME, "The moon", null)
    is RowSubject.Pass -> SheetHeading(clock(subject.pass.peaksAt), passTitle(subject.pass), passEnds(subject.pass))
    is RowSubject.Planet -> SheetHeading(
        clock(subject.planet.peakAt), planetPeakTitle(subject.planet), planetPeakDetail(subject.planet),
    )
    is RowSubject.Event -> SheetHeading(clock(subject.event.at), subject.event.title, null)
    is RowSubject.Constellation -> SheetHeading(clock(subject.seen.peakAt), subject.seen.constellation.name, null)
}

/** The moon has no one moment; its sheet is about the night. */
private const val TONIGHT_TIME = "Tonight"

/**
 * One row, said in full: what it is, the detail the row had no room for, why
 * it may be worth less than it sounds, and the words it uses.
 *
 * This is where each old card's prose went - the darkness thresholds behind a
 * twilight row, the moon's phase and what it costs the night behind a moon
 * row, the cloud and the gap in it behind the clear spell - and where its
 * Explain button went: the notes are always here, under the answer, because
 * somebody who opened the sheet has already asked. A dated event keeps Share
 * and Export.
 *
 * @param findIt the way to point the phone at this row's subject, where there
 *   is one: a [FindItButton].
 */
@Composable
fun RowSheet(
    row: TimelineRow,
    state: MainUiState,
    actions: UiActions,
    onDismiss: () -> Unit,
    findIt: (@Composable () -> Unit)? = null,
) = SubjectSheet(SheetHeading(row.time, row.title, row.detail), row.subject, state, actions, onDismiss, findIt)

/**
 * A subject said in full, wherever it was opened from: a row on Tonight, a
 * row on Sky, or a notification. [RowSheet] is this with a timeline row's own
 * heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectSheet(
    heading: SheetHeading,
    subject: RowSubject,
    state: MainUiState,
    actions: UiActions,
    onDismiss: () -> Unit,
    findIt: (@Composable () -> Unit)? = null,
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
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag(ROW_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(heading.time, style = MaterialTheme.typography.labelLarge, color = palette.muted)
                Text(heading.title, style = MaterialTheme.typography.titleLarge)
            }
            heading.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            val tonight = state.tonight
            when (subject) {
                RowSubject.Twilight -> tonight?.let { DarknessLines(it) }
                RowSubject.Clear -> tonight?.let { Cloud(it, state.settings.cloudTolerance) }
                RowSubject.Moon -> tonight?.moon?.let { moon ->
                    Said(
                        "${describeMoonPhase(moon.phaseDegrees, moon.illumination)}, " +
                            "${(moon.illumination * 100).roundToInt()}% lit.",
                    )
                    Said(describeMoonlight(moon))
                    describeMoonTimes(moon)?.let { Said(it) }
                }
                is RowSubject.Pass -> describePassDetail(subject.pass).forEach { Said(it) }
                is RowSubject.Planet -> Said(describePlanet(subject.planet))
                is RowSubject.Event -> Event(subject.event, state.nowMillis, actions)
                is RowSubject.Constellation -> Said(describeConstellation(subject.seen))
            }

            findIt?.invoke()

            helpTopic(subject)?.let { HelpNotes(it) }
        }
    }
}

/** Opens Find aimed at a row's subject. */
@Composable
internal fun FindItButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(FIND_IT_TAG)) { Text("Find it") }
}

@Composable
private fun Said(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/**
 * Both thresholds. Civil twilight is where an aurora starts to show;
 * astronomical darkness is a different night, and at these latitudes between
 * May and August it does not arrive at all - which is the difference between
 * a usable night and a bright one, and why a summer meteor shower disappoints.
 *
 * Said behind a twilight row, and on Tonight itself where there is no night
 * to draw because the sun neither sets nor rises.
 */
@Composable
internal fun DarknessLines(list: TonightList) {
    Said(
        if (list.darkFrom == null || list.darkUntil == null) {
            "It does not get properly dark here at this time of year."
        } else {
            "Dark from ${clock(list.darkFrom)} until ${clock(list.darkUntil)}."
        },
    )
    Said(
        if (list.darkestFrom == null || list.darkestUntil == null) {
            "It never gets fully dark here at this time of year - the sun stays " +
                "too close to the horizon all night for a properly black sky."
        } else {
            "Fully dark from ${clock(list.darkestFrom)} until ${clock(list.darkestUntil)}, " +
                "which is when anything faint is worth looking for."
        },
    )
}

/**
 * The cloud now and the gap in it. "80% cloud" alone is the wrong summary of
 * a night that is overcast until midnight and then clear for three hours.
 */
@Composable
private fun Cloud(list: TonightList, tolerance: Int) {
    list.cloud?.let { Said(describeCloud(it).replaceFirstChar { c -> c.uppercase() } + " overhead.") }
    if (list.darkFrom != null) Said(describeClearSpell(list.clearSpell, tolerance))
}

@Composable
private fun Event(event: SkyEvent, nowMillis: Long, actions: UiActions) {
    val palette = LocalPalette.current
    // The same phrase the calendar uses. Most of the night is "tonight"; this
    // is the narrower claim that it is happening as you read it.
    if (event.isUnderWay(nowMillis)) {
        Text(
            HAPPENING_NOW,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = palette.accent,
        )
    }
    Said(event.detail)
    Caveat(event)
    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { actions.onShareEvent(event) },
            modifier = Modifier.testTag(shareTag(event)),
        ) { Text("Share") }
        TextButton(
            onClick = { actions.onExportEvent(event) },
            modifier = Modifier.testTag(exportTag(event)),
        ) { Text("Export") }
    }
}

/**
 * Why an event is worth less than its headline suggests, where it is.
 *
 * The whole reason [sh.arek.gloaming.domain.Tonight] keeps marginal events
 * rather than dropping them: somebody reading this has already decided to go
 * out, and "the moon only reaches 8 degrees at maximum, in a sky that is not
 * fully dark" is what they need to plan around.
 */
@Composable
private fun Caveat(event: SkyEvent) {
    val reason = when (val visibility = event.visibility) {
        is Visibility.Marginal -> visibility.reason
        is Visibility.NotFromHere -> visibility.reason
        Visibility.Good -> return
    }
    Text(reason, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted)
}
