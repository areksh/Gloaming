package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.astronomy.EventChannel
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
    starred: Set<String> = emptySet(),
    onToggleStar: (SkyEvent) -> Unit = {},
    hiddenKinds: Set<String> = emptySet(),
    onToggleKind: (EventKind) -> Unit = {},
) {
    if (!hasLocation) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("The calendar needs a location", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Eclipses, showers and conjunctions all depend on where you are standing " +
                        "- which is why the app works them out here rather than downloading a " +
                        "list. Set a location under Settings and they will appear.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        return
    }

    if (loading && events.isEmpty()) {
        Text("Working out the year ahead...", style = MaterialTheme.typography.bodyMedium)
        return
    }

    if (events.isEmpty()) {
        Text(
            "Nothing in the year ahead, which should not happen - the moon alone guarantees " +
                "an entry a week. Worth reporting.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    KindFilter(events, hiddenKinds, onToggleKind)

    val shown = events.filterNot { it.kind.name in hiddenKinds }
    if (shown.isEmpty()) {
        Text(
            "Everything is filtered out. Turn a kind back on above.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    var lastMonth = ""
    for (event in shown) {
        val month = MONTH.format(Date(event.at))
        if (month != lastMonth) {
            Text(
                month.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            lastMonth = month
        }
        EventRow(
            event = event,
            starred = EventPlanner.starKey(event) in starred,
            onToggleStar = { onToggleStar(event) },
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
private fun EventRow(event: SkyEvent, starred: Boolean, onToggleStar: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                // Offered only where it could do something. Starring an event
                // the ground is in the way of, or one that never interrupts
                // anyway, would be a control that quietly does nothing.
                if (event.visibility == Visibility.Good &&
                    EventChannel.of(event.kind) != null &&
                    EventPlanner.canBeStarred(event)
                ) {
                    TextButton(
                        onClick = onToggleStar,
                        modifier = Modifier.testTag(starTag(event)),
                    ) {
                        Text(if (starred) "Starred" else "Star")
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

/** How a kind's chip is reached from a test. */
fun kindTag(kind: EventKind): String = "kind:${kind.name}"

/** What each kind is called where a user can see it. */
internal fun labelFor(kind: EventKind): String = when (kind) {
    EventKind.SolarEclipse -> "Solar eclipses"
    EventKind.LunarEclipse -> "Lunar eclipses"
    EventKind.MeteorShower -> "Meteor showers"
    EventKind.Conjunction -> "Conjunctions"
    EventKind.Elongation -> "Mercury and Venus"
    EventKind.Opposition -> "Oppositions"
    EventKind.GreatestBrightness -> "Venus at its brightest"
    EventKind.MoonPhase -> "New and full moon"
    EventKind.MoonQuarter -> "Moon quarters"
    EventKind.Season -> "Equinoxes and solstices"
    EventKind.Noctilucent -> "Noctilucent clouds"
}

/** How a star is reached from a test, and kept stable across reworded titles. */
fun starTag(event: SkyEvent): String = "star:${EventPlanner.starKey(event)}"

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
