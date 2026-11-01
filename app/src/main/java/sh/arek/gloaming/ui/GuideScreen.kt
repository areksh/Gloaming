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
 * The guide, a page under Settings.
 *
 * Emitted as loose children rather than wrapped in a column, like
 * [TonightContent] and [CalendarContent], so the section owns the scrolling.
 *
 * Not a section of the bottom bar, because nothing here is a setting or
 * happening tonight: it is the one screen in the app that is the same every
 * night, so it is found when wanted rather than competing with tonight.
 */
@Composable
fun GuideContent() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What to look for", style = MaterialTheme.typography.titleMedium)
            Text(
                "The rest of this app tells you what is up and where. This is the part " +
                    "that assumes you have never done any of it before: the landmarks " +
                    "every direction is measured from, what the words used elsewhere in the app " +
                    "mean, and what actually helps once you are outside in the dark. " +
                    "Each row on Tonight explains its own words too - tap it.",
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
 * What one row's words mean, in the sheet that row opens.
 *
 * Opened in place rather than sending the reader to the guide. The question
 * is asked while looking at the sentence that raised it, and an answer that
 * costs you your place on the screen is one nobody comes back from.
 */
@Composable
internal fun HelpNotes(topic: HelpTopic) {
    HorizontalDivider()
    for (entry in Guide.forTopic(topic)) {
        GuideEntryText(entry)
    }
}

/**
 * The same words, where a whole screen rather than one row raised them.
 *
 * The calendar has no rows to open - it is a year in one list - so its
 * entries arrive as a card of their own above the filter.
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

/** How Ahead's "What these words mean" button is reached from a test. */
fun helpTag(topic: HelpTopic): String = "help:${topic.name}"
