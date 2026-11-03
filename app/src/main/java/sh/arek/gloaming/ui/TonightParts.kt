package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.ui.theme.LocalPalette
import sh.arek.gloaming.ui.theme.Palette

/** A picked row, reached by its moment and title so a rotation keeps its sheet open. */
fun timelineRowTag(row: TimelineRow): String = "row:${row.atMillis}:${row.title}"

/** The short list of what the outlook picked. */
const val WORTH_TAG = "worth"

/** What is next, standing in for the headline once the night is under way. */
const val NEXT_UP_TAG = "next-up"

/** The card that says when an overcast night next clears. */
const val NEXT_CLEAR_TAG = "next-clear"

/** How long until the trip out. */
const val COUNTDOWN_TAG = "countdown"

/** The row that opens and closes the folded list tagged [listTag]. */
fun disclosureTag(listTag: String): String = "$listTag-toggle"

/**
 * What the outlook picked, in time order: "Worth going out for" before the
 * night and "Still to come" once it is under way, with what is over folded
 * into one line. Nothing at all where nothing was picked - an aurora is a
 * reading rather than a moment, and a heading over nothing reads as a fault.
 */
@Composable
internal fun WorthList(timeline: Timeline, nowMillis: Long, palette: Palette, onRow: (TimelineRow) -> Unit) {
    val worth = TonightSections.worth(timeline)
    val earlier = TonightSections.earlierLine(worth.earlier)
    if (worth.upcoming.isEmpty()) {
        // Everything picked is over: said in one line rather than dropped.
        earlier?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
        return
    }

    Column(Modifier.fillMaxWidth().testTag(WORTH_TAG)) {
        Text(
            if (timeline.nowMillis == null) "Worth going out for" else "Still to come",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        worth.upcoming.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = palette.line)
            WorthRow(row, nowMillis, palette, onClick = { onRow(row) })
        }
        earlier?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * One picked row: its time, what it is, a line of detail, and a chevron for
 * the sheet behind it. The trip's own picks lead in the accent and in weight,
 * never hue alone.
 */
@Composable
private fun WorthRow(row: TimelineRow, nowMillis: Long, palette: Palette, onClick: () -> Unit) {
    val trip = row.pick == PickTag.Go
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(timelineRowTag(row))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.time,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (trip) FontWeight.Bold else FontWeight.Normal,
            color = if (trip) palette.accent else palette.muted,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall)
            if (row.isHappening(nowMillis)) {
                Text(
                    HAPPENING_NOW,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent,
                )
            }
            row.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
        }
        Chevron(palette.muted, open = null)
    }
}

/**
 * A folded list: a row saying what it is, how much it holds and the first of
 * it, which opens onto the entries. Closed until asked for, and absent with
 * nothing in it - except that a list holding something gone wrong (a broken
 * download, say) starts open: a fault folded away is a fault nobody sees.
 */
@Composable
internal fun Disclosure(title: String, entries: List<ListEntry>, listTag: String) {
    if (entries.isEmpty()) return
    val palette = LocalPalette.current
    var open by rememberSaveable(listTag) { mutableStateOf(entries.any { it.alarming }) }

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = palette.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .testTag(disclosureTag(listTag))
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val heading = TonightSections.heading(title, entries)
                Text(
                    buildAnnotatedString {
                        append(title)
                        withStyle(SpanStyle(color = palette.muted, fontWeight = FontWeight.Normal)) {
                            append(heading.removePrefix(title))
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                TonightSections.preview(entries)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                }
            }
            Chevron(palette.muted, open = open)
        }
        if (open) EntryBody(entries, listTag)
    }
}

/** The entries of a folded list once it is open. */
@Composable
private fun EntryBody(entries: List<ListEntry>, tag: String) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().testTag(tag).padding(top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        for (entry in entries) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                // In words and weight as well as the accent, for night vision.
                if (entry.happeningNow) {
                    Text(
                        HAPPENING_NOW,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent,
                    )
                }
                entry.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (entry.alarming) FontWeight.Bold else null,
                        color = if (entry.alarming) palette.alarm else palette.muted,
                    )
                }
                entry.note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                }
            }
        }
    }
}

/** ">" for a row with a sheet behind it ([open] null), or "v" / "^" for a folded list. */
@Composable
private fun Chevron(colour: Color, open: Boolean?) {
    val turn = when (open) {
        null -> 0f
        false -> 90f
        true -> -90f
    }
    Canvas(Modifier.size(20.dp).rotate(turn)) {
        val stroke = 2.dp.toPx()
        val w = size.width
        drawLine(colour, Offset(w * 0.375f, w * 0.25f), Offset(w * 0.625f, w * 0.5f), stroke, StrokeCap.Round)
        drawLine(colour, Offset(w * 0.625f, w * 0.5f), Offset(w * 0.375f, w * 0.75f), stroke, StrokeCap.Round)
    }
}
