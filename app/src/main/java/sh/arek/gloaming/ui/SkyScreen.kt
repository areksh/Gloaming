package sh.arek.gloaming.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.SkyList
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.ui.theme.LocalPalette

/** The Sky tab's list, reached by tag from a test. */
const val SKY_LIST_TAG = "sky-list"

/** The folded row of passes that have already set. */
const val SKY_EARLIER_TAG = "sky-earlier"

/** A line Sky was opened with, such as a notification's pass having finished. */
const val SKY_NOTE_TAG = "sky-note"

/** One row on Sky, by what it is about. */
fun skyRowTag(subject: RowSubject): String = when (subject) {
    is RowSubject.Pass -> "sky-pass:${subject.pass.catalogue}:${subject.pass.risesAt}"
    is RowSubject.Planet -> "sky-planet:${subject.planet.body.name}"
    is RowSubject.Constellation -> "sky-pattern:${subject.seen.constellation.name}"
    is RowSubject.Event -> "sky-event:${EventPlanner.starKey(subject.event)}"
    RowSubject.Moon -> "sky-moon"
    RowSubject.Twilight -> "sky-twilight"
    RowSubject.Clear -> "sky-clear"
}

/** The chip that shows or hides one kind of pass. */
fun skyChannelTag(channel: PassChannel): String = "sky-channel:${channel.name}"

/** What a channel's chip says. */
private fun channelLabel(channel: PassChannel): String = when (channel) {
    PassChannel.Iss -> "Space station"
    PassChannel.Bright -> "Bright"
    PassChannel.Trains -> "Starlink trains"
}

/**
 * Everything up tonight: the complete list Tonight's highlights are picked
 * from, so nothing the app mentions is out of reach.
 *
 * A lazy list for Ahead's reason, and more so: a night of three hundred
 * passes is three times the rows of a year of events. Passes come first,
 * under the hour they are highest in, with the ones already gone folded into one row
 * so the list opens on what is still to come.
 *
 * Every row opens the same sheet Tonight's rows do, with Find it on anything
 * that can be pointed at.
 *
 * @param openSubject a subject to open the sheet on, from a notification;
 *   [onSubjectShown] is called once it is open, so the caller can forget it.
 * @param note a line to say at the top, from a notification whose subject
 *   could not be shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SkyScreen(
    state: MainUiState,
    actions: UiActions,
    listState: LazyListState,
    onFindIt: (FindTarget) -> Unit,
    modifier: Modifier = Modifier,
    openSubject: RowSubject? = null,
    onSubjectShown: () -> Unit = {},
    note: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val palette = LocalPalette.current
    // The channels switched off, as names joined by commas so the choice
    // saves as plain text.
    var hiddenText by rememberSaveable { mutableStateOf("") }
    val hidden = hiddenText.split(',').filter { it.isNotEmpty() }
    var showEarlier by rememberSaveable { mutableStateOf(false) }
    // By tag, so a sheet opened from the list survives a rotation; a subject
    // handed in is held as itself, since it may be a pass the list never had.
    var openedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var handed by remember { mutableStateOf<RowSubject?>(null) }

    LaunchedEffect(openSubject) {
        if (openSubject != null) {
            handed = openSubject
            onSubjectShown()
        }
    }

    val list = state.tonight
    val channels = PassChannel.entries.filterNot { it.name in hidden }.toSet()
    val sky = remember(list, nowMillis / MINUTE_MILLIS, channels) {
        list?.let { SkyList.of(it, nowMillis, channels) }
    }

    LazyColumn(
        modifier = modifier.testTag(SKY_LIST_TAG),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        note?.let { line ->
            item(key = "note") {
                Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(SKY_NOTE_TAG))
            }
        }

        if (!state.settings.hasLocation) {
            item(key = "no-location") {
                Text(
                    "Set where you are in Settings first, so Gloaming knows what is in your sky.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@LazyColumn
        }
        if (list == null || sky == null) {
            item(key = "working") {
                Text(WORKING_OUT_TONIGHT, style = MaterialTheme.typography.bodyLarge, color = palette.muted)
            }
            return@LazyColumn
        }

        heading("passes", "Passes")
        item(key = "channels") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (channel in PassChannel.entries) {
                    FilterChip(
                        selected = channel in channels,
                        onClick = {
                            hiddenText = (if (channel.name in hidden) hidden - channel.name else hidden + channel.name)
                                .joinToString(",")
                        },
                        label = { Text(channelLabel(channel)) },
                        modifier = Modifier.testTag(skyChannelTag(channel)),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.accent,
                            selectedLabelColor = palette.onAccent,
                            labelColor = palette.text,
                        ),
                    )
                }
            }
        }
        passesNote(list, sky, state)?.let { line ->
            item(key = "passes-note") { Text(line, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
        }
        if (sky.earlierPasses.isNotEmpty()) {
            item(key = SKY_EARLIER_TAG) {
                Text(
                    if (showEarlier) "Earlier tonight - hide" else "Earlier tonight (${sky.earlierPasses.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showEarlier = !showEarlier }
                        .padding(vertical = 6.dp)
                        .testTag(SKY_EARLIER_TAG),
                )
            }
            if (showEarlier) rows(sky.earlierPasses.map { RowSubject.Pass(it) }) { openedTag = it }
        }
        for ((hour, passes) in sky.passHours) {
            stickyHeader(key = "hour:$hour") {
                Text(
                    clock(hour),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.ground)
                        .padding(vertical = 4.dp),
                )
            }
            rows(passes.map { RowSubject.Pass(it) }) { openedTag = it }
        }

        if (sky.planets.isNotEmpty()) {
            heading("planets", "Planets")
            rows(sky.planets.map { RowSubject.Planet(it) }) { openedTag = it }
        }
        if (sky.seasonal.isNotEmpty()) {
            heading("patterns", "Constellations")
            rows(sky.seasonal.map { RowSubject.Constellation(it) }) { openedTag = it }
        }
        if (sky.alwaysUp.isNotEmpty()) {
            heading("always-up", "Up all night, every night")
            rows(sky.alwaysUp.map { RowSubject.Constellation(it) }) { openedTag = it }
        }
        val others = listOfNotNull(list.moon?.let { RowSubject.Moon }) + sky.events.map { RowSubject.Event(it) }
        if (others.isNotEmpty()) {
            heading("others", "Moon, events and comets")
            rows(others) { openedTag = it }
        }
    }

    val shown = handed ?: openedTag?.let { tag -> sky?.let { subjectsOf(it, list).firstOrNull { s -> skyRowTag(s) == tag } } }
    shown?.let { subject ->
        val dismiss = {
            handed = null
            openedTag = null
        }
        val target = findTarget(subject)
        SubjectSheet(
            heading = heading(subject) ?: SheetHeading("", "", null),
            subject = subject,
            state = state,
            actions = actions,
            onDismiss = dismiss,
            findIt = target?.let {
                {
                    FindItButton {
                        dismiss()
                        onFindIt(it)
                    }
                }
            },
        )
    }
}

/** Every subject on the list, to find the open one again by its tag. */
private fun subjectsOf(sky: SkyList, list: TonightList?): List<RowSubject> =
    (sky.earlierPasses + sky.passHours.flatMap { it.second }).map { RowSubject.Pass(it) } +
        sky.planets.map { RowSubject.Planet(it) } +
        (sky.seasonal + sky.alwaysUp).map { RowSubject.Constellation(it) } +
        listOfNotNull(list?.moon?.let { RowSubject.Moon }) +
        sky.events.map { RowSubject.Event(it) }

/** Why there are no passes under the chips, where there are none to show. */
private fun passesNote(list: TonightList, sky: SkyList, state: MainUiState): String? = when {
    !list.elementsHeld -> describeMissingElements(state.alertState.lastPollAt != null, state.elementsFailure).text
    sky.filteredOut -> "Every kind of pass is switched off above."
    list.passes.isEmpty() -> "Nothing crossing tonight that clears the horizon in sunlight."
    else -> null
}

private fun LazyListScope.heading(key: String, text: String) {
    item(key = "heading:$key") {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun LazyListScope.rows(subjects: List<RowSubject>, onOpen: (String) -> Unit) {
    for (subject in subjects) {
        val tag = skyRowTag(subject)
        item(key = tag) { SkyRow(subject, onOpen = { onOpen(tag) }) }
    }
}

/**
 * One row: when, what, and the line under it, worded as the sheet heads it.
 * A pass in the earth's shadow at its highest says so, since that is what
 * decides whether it is worth looking up for.
 */
@Composable
private fun SkyRow(subject: RowSubject, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val heading = heading(subject) ?: return
    val shadow = (subject as? RowSubject.Pass)?.pass?.sunlitAtPeak == false
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp)
            .testTag(skyRowTag(subject)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${heading.time}  ${heading.title}",
            style = MaterialTheme.typography.bodyLarge,
            color = if (shadow) palette.muted else palette.text,
        )
        val detail = listOfNotNull(heading.detail, if (shadow) "in shadow at its highest" else null)
        if (detail.isNotEmpty()) {
            Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
    }
}

private const val MINUTE_MILLIS = 60_000L
