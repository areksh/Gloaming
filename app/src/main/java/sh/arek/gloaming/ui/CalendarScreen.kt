package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility

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

    var lastMonth = ""
    for (event in events) {
        val month = MONTH.format(Date(event.at))
        if (month != lastMonth) {
            Text(
                month.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            lastMonth = month
        }
        EventRow(event)
    }
}

/**
 * One entry: when, what, and - when it matters - why it is not worth much.
 *
 * The date column is fixed-width so the titles line up down the page, which is
 * what makes a list of thirty scannable rather than something to be read.
 */
@Composable
private fun EventRow(event: SkyEvent) {
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
