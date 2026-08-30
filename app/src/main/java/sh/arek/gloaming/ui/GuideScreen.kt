package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The guide, as a tab of its own.
 *
 * Emitted as loose children rather than wrapped in a column, like
 * [TonightContent] and [CalendarContent], so the tab owns the scrolling.
 *
 * A tab rather than a card buried in Settings, because nothing here is a
 * setting and the reader who needs it is the reader least likely to go
 * looking. It is also the only screen in the app that is the same every night,
 * which is why it can sit at the end of the row and be found when wanted
 * rather than competing with tonight.
 */
@Composable
fun GuideContent() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What to look for", style = MaterialTheme.typography.titleMedium)
            Text(
                "The rest of this app tells you what is up and where. This is the part " +
                    "that assumes you have never done any of it before: the landmarks " +
                    "every direction is measured from, what the words on the other tabs " +
                    "mean, and what actually helps once you are outside in the dark. " +
                    "Each card on the Tonight tab can explain its own words too - the " +
                    "Explain button beside its heading.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    for (section in Guide.SECTIONS) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                Text(section.blurb, style = MaterialTheme.typography.bodySmall)
                for (entry in section.entries) {
                    GuideEntryText(entry)
                }
            }
        }
    }
}

/**
 * What one card's words mean, under the card that used them.
 *
 * Opened in place rather than sending the reader to the Guide tab. The
 * question is asked while looking at the sentence that raised it, and an
 * answer that costs you your place on the screen is one nobody comes back
 * from.
 */
@Composable
internal fun HelpNotes(topic: HelpTopic) {
    HorizontalDivider()
    for (entry in Guide.forTopic(topic)) {
        GuideEntryText(entry)
    }
}

/**
 * The same words, where a whole screen rather than one card raised them.
 *
 * The calendar has no cards to hang an Explain button off - it is a year in
 * one list - so its entries arrive as a card of their own above the filter.
 */
@Composable
internal fun HelpCard(topic: HelpTopic) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (entry in Guide.forTopic(topic)) {
                GuideEntryText(entry)
            }
        }
    }
}

/** One entry, worded the same wherever it is read. */
@Composable
private fun GuideEntryText(entry: GuideEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            entry.term,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag(guideTag(entry)),
        )
        Text(entry.body, style = MaterialTheme.typography.bodySmall)
    }
}

/** How an entry is reached from a test, and kept stable across rewordings. */
fun guideTag(entry: GuideEntry): String = "guide:${entry.term}"

/** How a card's Explain button is reached from a test. */
fun helpTag(topic: HelpTopic): String = "help:${topic.name}"
