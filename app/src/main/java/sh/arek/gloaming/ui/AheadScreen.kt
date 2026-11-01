package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.ui.theme.LocalPalette

/** The button that opens the kinds sheet, which says how many kinds are showing. */
const val KINDS_FILTER_TAG = "kinds-filter"

/** The chip that narrows the list to the events the reader has starred. */
const val STARRED_ONLY_TAG = "starred-only"

/** The sheet holding one chip per kind. */
const val KINDS_SHEET_TAG = "kinds-sheet"

/** A month's strip of nights, reached from a test. */
fun monthTag(year: Int, month: Int): String = "month:$year-$month"

/** A listed event, which opens its sheet. */
fun eventRowTag(event: SkyEvent): String = "event:${EventPlanner.starKey(event)}"

/**
 * The year ahead, month by month.
 *
 * Unlike everything else the app shows, this is not about tonight. An eclipse
 * three weeks away is the sort of thing people arrange a trip around, and a
 * shower peak is worth knowing about before the afternoon it happens - which
 * is the whole argument for a screen looking ahead existing separately from
 * an alert.
 *
 * Each month opens with a strip of its nights shaded by how much the moon
 * spoils them, then the events dated to it. Events that cannot be seen from
 * here are shown with the reason rather than hidden. A list that silently
 * omits the eclipse somebody read about in the paper reads as a broken app;
 * one that says the sun will already have set reads as an answer.
 *
 * Tapping an event opens the shared row sheet, which is where Share and Export
 * live; the star stays on the row, because starring is the quick thing.
 */
@Composable
fun AheadScreen(
    state: MainUiState,
    actions: UiActions,
    starredOnly: Boolean,
    onStarredOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val events = state.calendar
    val starred = state.settings.starredEvents
    val hiddenKinds = state.settings.hiddenEventKinds
    var opened by remember { mutableStateOf<SkyEvent?>(null) }
    var choosingKinds by rememberSaveable { mutableStateOf(false) }

    // A lazy list rather than loose children in the tab's scrolling column,
    // and this is the one screen where that is not a style preference. A year
    // ahead is around a hundred entries, every one of which a plain column
    // composes, measures and lays out whether or not it is anywhere near the
    // viewport. Measured on a Pixel 8a: switching to this tab cost a single
    // 1.3-second frame, and every frame of a scroll afterwards missed its
    // deadline - 20ms against the 6ms the Tonight tab manages. Composing only
    // what is on screen is the whole fix.
    //
    // Which is why this owns its scrolling where the other tabs do not: a lazy
    // list inside a scrolling column is measured with unbounded height, which
    // defeats the laziness and throws.
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.settings.hasLocation) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "The calendar needs a location",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Eclipses, showers and conjunctions all depend on where you are " +
                            "standing - which is why the app works them out here rather " +
                            "than downloading a list. Set a location under Settings and " +
                            "they will appear.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            return@LazyColumn
        }

        if (state.calendarLoading && events.isEmpty()) {
            item {
                Text("Working out the year ahead...", style = MaterialTheme.typography.bodyMedium)
            }
            return@LazyColumn
        }

        if (events.isEmpty()) {
            item {
                Text(
                    "Nothing in the year ahead, which should not happen - the moon alone " +
                        "guarantees an entry a week. Worth reporting.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LazyColumn
        }

        item(key = "help") { AheadHelp() }

        item(key = "filter") {
            FilterBar(
                showing = kindsShowing(hiddenKinds),
                starredOnly = starredOnly,
                onKinds = { choosingKinds = true },
                onStarredOnly = onStarredOnlyChange,
            )
        }

        val shown = events
            .filterNot { it.kind.name in hiddenKinds }
            .filter { !starredOnly || EventPlanner.starKey(it) in starred }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (starredOnly) {
                        "Nothing starred is showing. Star an event, or turn a kind back on above."
                    } else {
                        "Everything is filtered out. Turn a kind back on above."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Every month that has a strip, and any month an event falls in beyond
        // them, so a short strip list can never lose an event.
        val strips = state.months.associateBy { monthKey(it.year, it.month) }
        val byMonth = shown.groupBy { monthKey(it.at) }
        for (key in (strips.keys + byMonth.keys).sorted()) {
            val strip = strips[key]
            val inMonth = byMonth[key].orEmpty()
            if (strip == null && inMonth.isEmpty()) continue

            item(key = "month:$key") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        (strip?.let { "${it.name} ${it.year}" } ?: MONTH.format(Date(inMonth.first().at)))
                            .uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (strip != null) MonthStripView(strip)
                }
            }
            for (event in inMonth) {
                // Keyed, so that starring an entry re-composes that entry rather
                // than shuffling every row after it onto a new slot.
                item(key = EventPlanner.starKey(event)) {
                    EventRow(
                        event = event,
                        nowMillis = state.nowMillis,
                        starred = EventPlanner.starKey(event) in starred,
                        onToggleStar = { actions.onToggleStar(event) },
                        onOpen = { opened = event },
                    )
                }
            }
        }
    }

    opened?.let { event ->
        RowSheet(
            row = eventRow(event),
            state = state,
            actions = actions,
            onDismiss = { opened = null },
        )
    }

    if (choosingKinds) {
        KindsSheet(events, hiddenKinds, actions.onToggleKind, onDismiss = { choosingKinds = false })
    }
}

/**
 * What the shared row sheet is given for an event listed on Ahead.
 *
 * The time carries the date, because unlike a row on Tonight this one is not
 * about this evening. The detail is left to the sheet, which prints an event's
 * own.
 */
fun eventRow(event: SkyEvent): TimelineRow = TimelineRow(
    atMillis = event.at,
    time = SimpleDateFormat("EEE d MMMM, HH:mm", Locale.getDefault()).format(Date(event.at)),
    title = event.title,
    detail = null,
    tone = RowTone.Normal,
    subject = RowSubject.Event(event),
)

/** How many of the [EventKind]s are not hidden, ignoring names that are no longer kinds. */
fun kindsShowing(hiddenKinds: Set<String>): Int =
    EventKind.entries.count { it.name !in hiddenKinds }

/**
 * What an entry says when it is happening as you read it.
 *
 * One string, shared with the Tonight tab, because the two lists are the same
 * events seen at different ranges and a reader who learns the phrase on one
 * should meet the same phrase on the other.
 */
internal const val HAPPENING_NOW: String = "Happening now"

/**
 * The strip of a month's nights.
 *
 * Shaded from the darkest sky to the accent as the moon spoils the night, with
 * a row of dots beneath for the days that have an event. Neither carries the
 * meaning alone: the darkest run is written out underneath and the whole strip
 * has a description for a screen reader, because with night vision on there is
 * one colour to shade with and little to tell the shades apart.
 */
@Composable
private fun MonthStripView(strip: MonthStrip) {
    val palette = LocalPalette.current
    val night = palette.sky.last()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .testTag(monthTag(strip.year, strip.month))
            .semantics(mergeDescendants = true) { contentDescription = strip.description() },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp),
        ) {
            val width = size.width / strip.nights.size
            val gap = 1.dp.toPx()
            for ((index, cell) in strip.nights.withIndex()) {
                val left = index * width
                drawRect(
                    color = lerp(night, palette.accent, cell.moonlight.coerceIn(0.0, 1.0).toFloat()),
                    topLeft = Offset(left + gap / 2, 0f),
                    size = Size(width - gap, 24.dp.toPx()),
                )
                if (cell.hasEvent) {
                    drawCircle(
                        color = palette.text,
                        radius = (width / 2 - gap).coerceIn(1.5.dp.toPx(), 3.dp.toPx()),
                        center = Offset(left + width / 2, 31.dp.toPx()),
                    )
                }
            }
        }
        Text(
            strip.darkestWords(),
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted,
        )
    }
}

/**
 * One entry: when, what, and - when it matters - why it is not worth much.
 *
 * The date column is fixed-width so the titles line up down the page, which is
 * what makes a list of thirty scannable rather than something to be read.
 */
@Composable
private fun EventRow(
    event: SkyEvent,
    nowMillis: Long,
    starred: Boolean,
    onToggleStar: () -> Unit,
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    val underWay = event.isUnderWay(nowMillis)

    Row(
        Modifier
            .fillMaxWidth()
            // Outlined rather than filled differently, so the mark survives the
            // night-vision theme: that palette is a single red on black by
            // design, and a row told apart by its background would be told apart
            // by nothing at all once it was on.
            .then(
                if (underWay) {
                    Modifier.border(2.dp, palette.accent, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onOpen)
            .testTag(eventRowTag(event))
            .padding(12.dp),
    ) {
        Column(Modifier.width(56.dp)) {
            Text(
                DAY_NAME.format(Date(event.at)),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                DAY_NUMBER.format(Date(event.at)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            // In words as well as in colour. A border alone would say nothing
            // to somebody who cannot pick it out, and nothing at all to
            // somebody who has never seen the list without one.
            if (underWay) {
                Text(
                    HAPPENING_NOW,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent,
                )
            }
            Text(event.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${TIME.format(Date(event.at))}${dash(event)}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                when (val visibility = event.visibility) {
                    is Visibility.Marginal -> visibility.reason
                    is Visibility.NotFromHere -> visibility.reason
                    Visibility.Good -> "Good from here"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (event.visibility == Visibility.Good) null else FontWeight.Medium,
                color = if (event.visibility == Visibility.Good) palette.muted else palette.text,
            )
        }
        // Offered on everything, and that is a deliberate reversal. It used to
        // be offered only where a star could produce a notification - which
        // ruled out the moon phases, the solstices, the constellations and
        // anything the ground is in the way of - on the grounds that a control
        // which does nothing is worse than no control.
        //
        // The premise was wrong. A star is not only an opt-in to being
        // interrupted: it is what calendar sync writes to the phone's calendar,
        // and that path never looked at the channel at all. Starring the
        // December solstice, or an eclipse that misses this country, puts it in
        // your calendar - which for the eclipse may be the whole reason you are
        // looking at it. So which events are worth marking is the reader's
        // judgement rather than the app's.
        //
        // Nothing is at risk on the notification side: `worthAnnouncing`
        // already refuses NotFromHere whatever the star says, and an event with
        // no channel has nowhere to be announced on, so a star on either cannot
        // wake anybody.
        TextButton(
            onClick = onToggleStar,
            modifier = Modifier.testTag(starTag(event)),
        ) {
            Text(if (starred) "Starred" else "Star")
        }
    }
}

/** The two controls that narrow the list: which kinds, and starred only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(
    showing: Int,
    starredOnly: Boolean,
    onKinds: () -> Unit,
    onStarredOnly: (Boolean) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onKinds, modifier = Modifier.testTag(KINDS_FILTER_TAG)) {
            Text("Showing $showing of ${EventKind.entries.size} kinds")
        }
        FilterChip(
            selected = starredOnly,
            onClick = { onStarredOnly(!starredOnly) },
            label = { Text("Starred only") },
            modifier = Modifier.testTag(STARRED_ONLY_TAG),
        )
    }
}

/**
 * One chip per kind actually present, so the filter never offers to hide
 * something the year does not contain.
 *
 * Counts on the chips because the point of the control is volume: it is only
 * obvious that the moon's quarters are worth hiding once you can see there are
 * forty-seven of them. Behind a button rather than above the list because
 * fourteen chips pushed the first month a screen down.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun KindsSheet(
    events: List<SkyEvent>,
    hiddenKinds: Set<String>,
    onToggleKind: (EventKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val counts = events.groupingBy { it.kind }.eachCount()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.raised,
        contentColor = palette.text,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag(KINDS_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Which kinds to show", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (kind in EventKind.entries) {
                    val count = counts[kind] ?: continue
                    FilterChip(
                        selected = kind.name !in hiddenKinds,
                        onClick = { onToggleKind(kind) },
                        label = { Text("${labelFor(kind)} ($count)") },
                        modifier = Modifier.testTag(kindTag(kind)),
                    )
                }
            }
        }
    }
}

/**
 * The calendar's own vocabulary, behind one tap.
 *
 * Occultation, elongation, opposition and the rest are the app's densest run
 * of jargon, and unlike the Tonight tab there is no card here to hang an
 * Explain button off - Ahead is a year in one list. So it goes above the
 * filter, which is where somebody deciding what to hide is already looking,
 * and closed by default: a reader who knows the words should reach the first
 * month without scrolling past a glossary.
 */
@Composable
private fun AheadHelp() {
    var explaining by rememberSaveable { mutableStateOf(false) }

    TextButton(
        onClick = { explaining = !explaining },
        modifier = Modifier.testTag(helpTag(HelpTopic.Calendar)),
    ) {
        Text(if (explaining) "Hide" else "What these words mean")
    }

    if (explaining) HelpCard(HelpTopic.Calendar)
}

/** A month as one sortable number, from its numbers or from an instant in it. */
private fun monthKey(year: Int, month: Int): Int = year * 100 + month

private fun monthKey(atMillis: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = atMillis
    return monthKey(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
}

/** How a kind's chip is reached from a test. */
fun kindTag(kind: EventKind): String = "kind:${kind.name}"

/** What each kind is called where a user can see it. */
internal fun labelFor(kind: EventKind): String = when (kind) {
    EventKind.SolarEclipse -> "Solar eclipses"
    EventKind.LunarEclipse -> "Lunar eclipses"
    EventKind.MeteorShower -> "Meteor showers"
    EventKind.Conjunction -> "Conjunctions"
    EventKind.Occultation -> "Occultations"
    EventKind.Elongation -> "Mercury and Venus"
    EventKind.Opposition -> "Oppositions"
    EventKind.GreatestBrightness -> "Venus at its brightest"
    EventKind.MoonPhase -> "New and full moon"
    EventKind.MoonQuarter -> "Moon quarters"
    EventKind.Season -> "Equinoxes and solstices"
    EventKind.Noctilucent -> "Noctilucent clouds"
    EventKind.Comet -> "Comets"
    EventKind.Constellation -> "Constellations"
}

/** How a star is reached from a test, and kept stable across reworded titles. */
fun starTag(event: SkyEvent): String = "star:${EventPlanner.starKey(event)}"

/** The same, for the two ways an event leaves the app. */
fun shareTag(event: SkyEvent): String = "share:${EventPlanner.starKey(event)}"

fun exportTag(event: SkyEvent): String = "export:${EventPlanner.starKey(event)}"

/**
 * The span, for the things that have one.
 *
 * An eclipse runs for a couple of hours and a shower for a night, so the end
 * time is worth printing. A moon phase is an instant and printing a range for
 * it would invent a duration it does not have.
 */
private fun dash(event: SkyEvent): String = when {
    event.kind == EventKind.MoonPhase -> ""
    event.until <= event.from -> ""
    else -> " to ${TIME.format(Date(event.until))}"
}

/**
 * `SimpleDateFormat` rather than `java.time`, which needs core library
 * desugaring at `minSdk 24` and is not configured - the same reason the poller
 * uses `Calendar`. Device locale and time zone throughout: a calendar printed
 * in UTC would be wrong by an hour for half the year here and by more
 * elsewhere.
 */
private val MONTH = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
private val DAY_NAME = SimpleDateFormat("EEE", Locale.getDefault())
private val DAY_NUMBER = SimpleDateFormat("d", Locale.getDefault())
private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
