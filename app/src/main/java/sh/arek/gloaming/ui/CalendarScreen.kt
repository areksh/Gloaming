package sh.arek.gloaming.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.EventPlanner

/**
 * The year ahead, grouped by month.
 *
 * Unlike everything else the app shows, this is not about tonight. An eclipse
 * three weeks away is the sort of thing people arrange a trip around, and a
 * shower peak is worth knowing about before the afternoon it happens - which
 * is the whole argument for a calendar existing separately from an alert.
 *
 * Events that cannot be seen from here are shown with the reason rather than
 * hidden. A list that silently omits the eclipse somebody read about in the
 * paper reads as a broken app; one that says the sun will already have set
 * reads as an answer.
 */
@Composable
fun CalendarContent(
    events: List<SkyEvent>,
    hasLocation: Boolean,
    loading: Boolean,
    /**
     * The moment the list is being read at, for marking what is already going
     * on. Passed in rather than read from the clock here so the screen stays a
     * plain function of the state it is given, and without a default because a
     * forgotten one would silently mean 1970 and mark nothing for ever.
     */
    nowMillis: Long,
    starred: Set<String> = emptySet(),
    hiddenKinds: Set<String> = emptySet(),
    actions: UiActions = UiActions(),
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
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
        state = state,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!hasLocation) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
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
            }
            return@LazyColumn
        }

        if (loading && events.isEmpty()) {
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

        item(key = "help") { CalendarHelp() }

        item(key = "filter") { KindFilter(events, hiddenKinds, actions.onToggleKind) }

        val shown = events.filterNot { it.kind.name in hiddenKinds }
        if (shown.isEmpty()) {
            item {
                Text(
                    "Everything is filtered out. Turn a kind back on above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LazyColumn
        }

        var lastMonth = ""
        for (event in shown) {
            val month = MONTH.format(Date(event.at))
            if (month != lastMonth) {
                item(key = "month:$month") {
                    Text(
                        month.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                lastMonth = month
            }
            // Keyed, so that starring an entry re-composes that entry rather
            // than shuffling every row after it onto a new slot.
            item(key = EventPlanner.starKey(event)) {
                EventRow(
                    event = event,
                    nowMillis = nowMillis,
                    starred = EventPlanner.starKey(event) in starred,
                    onToggleStar = { actions.onToggleStar(event) },
                    onShare = { actions.onShareEvent(event) },
                    onExport = { actions.onExportEvent(event) },
                )
            }
        }
    }
}

/**
 * What an entry says when it is happening as you read it.
 *
 * One string, shared with the Tonight tab, because the two lists are the same
 * events seen at different ranges and a reader who learns the phrase on one
 * should meet the same phrase on the other.
 */
internal const val HAPPENING_NOW: String = "Happening now"

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
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    val underWay = event.isUnderWay(nowMillis)

    Card(
        modifier = Modifier.fillMaxWidth(),
        // Outlined rather than filled differently, so the mark survives the
        // night-vision theme: that palette is a single red on black by design,
        // and a card told apart by its background would be told apart by
        // nothing at all once it was on.
        border = if (underWay) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(Modifier.padding(12.dp)) {
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
            Column(Modifier.fillMaxWidth()) {
                // In words as well as in colour. A border alone would say
                // nothing to somebody who cannot pick it out, and nothing at
                // all to somebody who has never seen the list without one.
                if (underWay) {
                    Text(
                        HAPPENING_NOW,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(event.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${TIME.format(Date(event.at))}${dash(event)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(event.detail, style = MaterialTheme.typography.bodySmall)
                when (val visibility = event.visibility) {
                    is Visibility.Marginal -> Reason(visibility.reason)
                    is Visibility.NotFromHere -> Reason(visibility.reason)
                    Visibility.Good -> Unit
                }
                // Offered on everything, and that is a deliberate reversal.
                // It used to be offered only where a star could produce a
                // notification - which ruled out the moon phases, the
                // solstices, the constellations and anything the ground is in
                // the way of - on the grounds that a control which does nothing
                // is worse than no control.
                //
                // The premise was wrong. A star is not only an opt-in to being
                // interrupted: it is what calendar sync writes to the phone's
                // calendar, and that path never looked at the channel at all.
                // Starring the December solstice, or an eclipse that misses
                // this country, puts it in your calendar - which for the
                // eclipse may be the whole reason you are looking at it. So
                // which events are worth marking is the reader's judgement
                // rather than the app's.
                //
                // Nothing is at risk on the notification side: `worthAnnouncing`
                // already refuses NotFromHere whatever the star says, and an
                // event with no channel has nowhere to be announced on, so a
                // star on either cannot wake anybody.
                //
                // FlowRow, so three buttons and a large system font do not
                // put the last one past the right edge of the card.
                FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onToggleStar,
                        modifier = Modifier.testTag(starTag(event)),
                    ) {
                        Text(if (starred) "Starred" else "Star")
                    }

                    // Offered on everything, including what cannot be seen
                    // from here: "the sun will already have set on this one"
                    // is a perfectly good thing to send somebody, and an
                    // eclipse that misses Edinburgh may well be the reason
                    // somebody is looking up flights.
                    TextButton(onClick = onShare, modifier = Modifier.testTag(shareTag(event))) {
                        Text("Share")
                    }
                    TextButton(onClick = onExport, modifier = Modifier.testTag(exportTag(event))) {
                        Text("Export")
                    }
                }
            }
        }
    }
}

@Composable
private fun Reason(reason: String) {
    Text(
        reason,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * One chip per kind actually present, so the filter never offers to hide
 * something the year does not contain.
 *
 * Counts on the chips because the point of the control is volume: it is only
 * obvious that the moon's quarters are worth hiding once you can see there are
 * forty-seven of them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindFilter(
    events: List<SkyEvent>,
    hiddenKinds: Set<String>,
    onToggleKind: (EventKind) -> Unit,
) {
    val counts = events.groupingBy { it.kind }.eachCount()
    if (counts.size < 2) return

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

/**
 * The calendar's own vocabulary, behind one tap.
 *
 * Occultation, elongation, opposition and the rest are the app's densest run
 * of jargon, and unlike the Tonight tab there is no card here to hang an
 * Explain button off - the calendar is a year in one list. So it goes above
 * the filter, which is where somebody deciding what to hide is already
 * looking, and closed by default: a reader who knows the words should reach
 * the first month without scrolling past a glossary.
 */
@Composable
private fun CalendarHelp() {
    var explaining by rememberSaveable { mutableStateOf(false) }

    TextButton(
        onClick = { explaining = !explaining },
        modifier = Modifier.testTag(helpTag(HelpTopic.Calendar)),
    ) {
        Text(if (explaining) "Hide" else "What these words mean")
    }

    if (explaining) HelpCard(HelpTopic.Calendar)
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
