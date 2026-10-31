package sh.arek.gloaming.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.ui.theme.LocalPalette

/** Tonight's scrolling column, which is what pulling down to check acts on. */
const val TONIGHT_SCROLL_TAG = "tonight-scroll"

/**
 * Tonight as a whole: the night, scrolling, with the watch button pinned
 * under it.
 *
 * Pinned rather than at the end of the scroll, because it is the one thing on
 * the screen somebody acts on in a hurry - when the sky is doing something -
 * and that is the moment nobody should have to scroll past the night to reach
 * it. Pulling the night down runs a check, the same as Check now in the
 * checks sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonightScreen(
    state: MainUiState,
    actions: UiActions = UiActions(),
    scroll: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        PullToRefreshBox(
            isRefreshing = state.checking,
            onRefresh = actions.onPollNow,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TONIGHT_SCROLL_TAG)
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TonightContent(state, actions)
            }
        }
        HorizontalDivider(color = palette.line)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            WatchButton(state, actions)
        }
    }
}

/**
 * What the sky is doing tonight, under the line that says how fresh it is.
 *
 * Emitted as loose children rather than wrapped in a column of its own, so the
 * caller owns the scrolling. The watch button is not among them: it is pinned
 * by [TonightScreen] rather than scrolled.
 */
@Composable
fun TonightContent(
    state: MainUiState,
    actions: UiActions = UiActions(),
) {
    var showChecks by rememberSaveable { mutableStateOf(false) }

    WorthItCard(state, actions.onVerdict)

    TonightHeader(state, actions, onOpenChecks = { showChecks = true })

    // Titled cards rather than a single card called "Tonight". That card was
    // mostly satellite passes, so it read as a satellite card with a few other
    // lines stuck on top of it, and nothing on the screen said which part was
    // which. Each is absent rather than empty when it has nothing to say.
    val tonight = state.tonight?.takeIf { state.settings.hasLocation }
    if (state.settings.hasLocation) AuroraCard(state)
    if (tonight != null) {
        SkyNowCard(tonight, state.settings.cloudTolerance)
        DarknessCard(tonight)
        MoonCard(tonight)
        PlanetsCard(tonight)
        ConstellationsCard(tonight)
        EventsCard(tonight, state.nowMillis, actions.onShareEvent, actions.onExportEvent)
        CometsCard(tonight)
        SatellitesCard(tonight, state)
    }

    if (showChecks) ChecksSheet(state, actions, onDismiss = { showChecks = false })
}

/**
 * "Was it worth it?", for an alert that was never answered.
 *
 * The second half of the notification's two buttons, and the one that catches
 * the nights that actually matter: somebody woken at two in the morning swipes
 * the alert away and forms an opinion about it at nine, by which time the
 * buttons are gone. This is where that opinion lands.
 *
 * It carries the third answer the notification deliberately does not.
 * "Did not look" is the honest answer for a night somebody slept through, and
 * without it the alerts nobody answered would be counted as failures by any
 * naive reading of the record - but a third button on a notification is a
 * notification that gets dismissed unread. On a screen somebody is already
 * looking at, it costs nothing.
 *
 * At the top of the tab, above the aurora reading, because it is a question
 * rather than a statement and it is stale in a day. It appears only once the
 * alert is old enough to have an answer, and disappears the moment there is
 * one.
 */
@Composable
private fun WorthItCard(state: MainUiState, onVerdict: (Answer) -> Unit) {
    val pending = state.pendingVerdict ?: return
    if (state.nowMillis - pending.atMillis < WORTH_IT_DELAY_MILLIS) return

    SectionCard("Was it worth it?") {
        Text(
            "Gloaming alerted you at ${clock(pending.atMillis)} about " +
                "${pending.subject ?: "something in the sky"}. Saying whether it was worth " +
                "going out for is the one thing that lets it work out where your alert " +
                "settings should actually sit - and nothing about this leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (answer in Answer.entries) {
                OutlinedButton(
                    onClick = { onVerdict(answer) },
                    modifier = Modifier.testTag(verdictTag(answer)),
                ) { Text(answer.label) }
            }
        }
    }
}

/**
 * How long an alert has to have been out before it is worth asking about.
 *
 * An hour. Long enough for somebody to have gone out and come back, and short
 * enough that the question still arrives on the night it is about.
 */
private const val WORTH_IT_DELAY_MILLIS: Long = 60 * 60 * 1000L

/** How an answer is reached from a test. */
fun verdictTag(answer: Answer): String = "verdict:${answer.name}"

/**
 * The aurora reading, which is the one number the app exists for.
 *
 * Titled like every other card, and a size down from where it started. It was
 * `headlineSmall` and unlabelled, which made "30% aurora probability here" the
 * largest thing on the screen by some way - so it read as the app's headline
 * rather than as one of several things the sky is doing tonight, and the cards
 * under it looked like footnotes to it rather than answers to their own
 * questions. Its age, the schedule and the last error have moved to the status
 * line and the checks sheet above.
 */
@Composable
private fun AuroraCard(state: MainUiState) {
    SectionCard("Aurora", spacing = 4.dp, help = HelpTopic.Aurora) {
        val probability = state.alertState.lastProbability
        Text(
            text = if (probability == null) "No reading yet" else "$probability% probability here",
            style = MaterialTheme.typography.titleLarge,
        )

        // Beside the number, not at the foot of the screen. AuroraWatch's
        // conditions tie the credit to their data, and a reader deciding
        // whether to trust 55% should be able to see where it came from
        // without scrolling. The attribution they specify is carried in
        // full by the credits card below.
        if (state.alertState.lastFromAuroraWatch) {
            Text(
                "This reading came from AuroraWatch UK.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A titled card, which is what every part of this screen now is.
 *
 * The title is not decoration. Before it there was one card called "Tonight"
 * holding the darkness, the events, the comets and the passes in a single
 * unlabelled run of text, and a reader had no way to tell which sentence
 * belonged to which question.
 */
@Composable
private fun SectionCard(
    title: String,
    spacing: Dp = 8.dp,
    help: HelpTopic? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Keyed on the title so each card remembers its own answer, and keeps it
    // across a rotation - reading the explanation and losing it to a turn of
    // the phone is how somebody decides the button is not worth pressing.
    var explaining by rememberSaveable(title) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (help == null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = { explaining = !explaining },
                        modifier = Modifier.testTag(helpTag(help)),
                    ) { Text(if (explaining) "Hide" else "Explain") }
                }
            }

            content()

            // Under the card's own answer rather than above it. The words come
            // second: somebody who already knows them should not have to
            // scroll past a glossary to reach tonight.
            if (help != null && explaining) HelpNotes(help)
        }
    }
}

/**
 * The cloud, which the app has gated every alert on and never once shown.
 *
 * First because it is the question that decides everything under it: a pass at
 * 68 degrees and a comet at magnitude 8 are both worth nothing under total
 * overcast.
 *
 * Absent when the user has the cloud gate switched off, which is the same thing
 * as having switched off the one request that leaves the device.
 *
 * The clear-spell line is the point of the card. "80% cloud" is the wrong
 * summary of a night that is overcast until midnight and then clear for three
 * hours, and it is the second of those that decides whether anybody stays up.
 */
@Composable
private fun SkyNowCard(list: TonightList, tolerance: Int) {
    // Absent rather than guessing. With no reading and no forecast there is
    // nothing to say that is not either "unknown" or an invention.
    if (list.cloud == null && list.clearSpell == null) return

    SectionCard("Sky now", help = HelpTopic.SkyNow) {
        Text(
            describeCloud(list.cloud).replaceFirstChar { it.uppercase() } + " overhead.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // Only where there is a night to have a gap in. Outside darkness the
        // question is not "when does it clear" but "when does it get dark",
        // which the card below answers.
        if (list.darkFrom != null) {
            Text(
                describeClearSpell(list.clearSpell, tolerance),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // CC BY 4.0 requires attribution beside the data it covers, and this is
        // the first place in the app that shows a forecast rather than acting
        // on one behind the scenes.
        Text(
            buildAnnotatedString {
                append("Weather data by ")
                withLink(LinkAnnotation.Url("https://open-meteo.com/")) {
                    append("Open-Meteo.com")
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * When it is dark enough to be worth going outside.
 *
 * Both thresholds, not just the one the app has always shown. Civil twilight is
 * where the aurora starts to show and is the gate every alert passes; proper
 * astronomical darkness is a different night, and at these latitudes between
 * May and August it does not arrive at all - which is the difference between a
 * usable night and a bright one, and the reason a summer meteor shower
 * disappoints.
 */
@Composable
private fun DarknessCard(list: TonightList) {
    SectionCard("Darkness", help = HelpTopic.Darkness) {
        Text(
            if (list.darkFrom == null) {
                "It does not get properly dark here at this time of year."
            } else {
                "Dark from ${clock(list.darkFrom)} until ${clock(list.darkUntil!!)}."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            if (list.darkestFrom == null) {
                "It never gets fully dark here at this time of year - the sun stays " +
                    "too close to the horizon all night for a properly black sky."
            } else {
                "Fully dark from ${clock(list.darkestFrom)} until " +
                    "${clock(list.darkestUntil!!)}, which is when anything faint is " +
                    "worth looking for."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The moon, which the app has weighed against every comet and never mentioned.
 *
 * After the cloud and the darkness because it is the third gate on the same
 * question and the only one of the three known months ahead. What is reported
 * is how much of the night it spoils rather than how bright it is: a full moon
 * that has set by midnight costs far less than a half moon up all night, and
 * that product is what the comet gate has always used.
 */
@Composable
private fun MoonCard(list: TonightList) {
    val moon = list.moon ?: return

    SectionCard("The moon", help = HelpTopic.Moon) {
        Text(
            "${describeMoonPhase(moon.phaseDegrees, moon.illumination)}, " +
                "${(moon.illumination * 100).roundToInt()}% lit.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(describeMoonlight(moon), style = MaterialTheme.typography.bodySmall)
        describeMoonTimes(moon)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * What is up tonight, and where to look for it.
 *
 * The one card here whose answer is certain. Cloud is a forecast, an aurora is
 * a probability and a comet is usually a disappointment; a planet is where the
 * arithmetic says it is, months ahead, and will be there. It is also the
 * question the calendar structurally cannot answer - "Jupiter at opposition"
 * dates one night of the year, and this is the other three hundred and sixty
 * four.
 */
@Composable
private fun PlanetsCard(list: TonightList) {
    if (list.planets.isEmpty()) return

    SectionCard("Planets tonight", help = HelpTopic.Planets) {
        for (planet in list.planets) {
            Text(planet.body.label.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall)
            Text(describePlanet(planet), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The patterns up tonight, and how to pick each one out.
 *
 * Next to the planets rather than among the events, because it answers the
 * planets' question about different things: what is up, where to look, and
 * this time how to recognise it when you get there. A constellation's one
 * dated night is in the calendar, and the line here says when that night is
 * tonight so the same pattern is never named twice on one screen.
 */
@Composable
private fun ConstellationsCard(list: TonightList) {
    if (list.constellations.isEmpty()) return

    SectionCard("Constellations tonight", help = HelpTopic.Constellations) {
        for (seen in list.constellations) {
            Text(
                seen.constellation.name,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(describeConstellation(seen), style = MaterialTheme.typography.bodySmall)
        }
        if (list.constellationsAlwaysUp.isNotEmpty()) {
            Text(
                describeAlwaysUp(list.constellationsAlwaysUp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Dated things falling tonight, and the noctilucent prompt in season.
 *
 * Absent rather than empty when there are none. An "Events tonight" card
 * saying nothing is worse than no card at all - it takes up the same space and
 * answers the question with silence.
 */
@Composable
private fun EventsCard(
    list: TonightList,
    nowMillis: Long,
    onShareEvent: (SkyEvent) -> Unit,
    onExportEvent: (SkyEvent) -> Unit,
) {
    if (list.noctilucent == null && list.events.isEmpty()) return

    SectionCard("Events tonight", help = HelpTopic.Events) {
        // No buttons on this one. Noctilucent conditions are worked out for
        // tonight rather than dated, so there is no event to put in a calendar
        // - only a suggestion to look north-west after sunset.
        list.noctilucent?.let {
            Text(it.title, style = MaterialTheme.typography.titleSmall)
            Text(it.detail, style = MaterialTheme.typography.bodySmall)
            Caveat(it)
        }

        for (event in list.events) {
            // The same phrase the calendar uses, in the same colour. Most of
            // what is on this card is happening tonight; the point of the mark
            // is the narrower claim that it is happening as you read it, which
            // for an eclipse an hour long is the difference between going out
            // now and going out later.
            if (event.isUnderWay(nowMillis)) {
                Text(
                    HAPPENING_NOW,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(event.title, style = MaterialTheme.typography.titleSmall)
            Text(event.detail, style = MaterialTheme.typography.bodySmall)
            Caveat(event)
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onShareEvent(event) },
                    modifier = Modifier.testTag(shareTag(event)),
                ) { Text("Share") }
                TextButton(
                    onClick = { onExportEvent(event) },
                    modifier = Modifier.testTag(exportTag(event)),
                ) { Text("Export") }
            }
        }
    }
}

/**
 * Comets up tonight, worked out for tonight rather than taken from the
 * calendar.
 *
 * Their own card rather than folded in with the events, because the calendar's
 * comet entry answers a different question - the best night of the year, not
 * where to look now - and running the two together is what made the old card
 * unreadable. The reason is printed where there is one, because "washed out by
 * the moon" is the difference between a wasted walk and a good one.
 */
@Composable
private fun CometsCard(list: TonightList) {
    if (list.comets.isEmpty()) return

    // "Comets tonight", not "Comets": the calendar's comet entry is about the
    // best night of the year and the settings card is about which comets are
    // worth hearing about at all. Three cards called "Comets" answering three
    // different questions is the confusion this commit exists to remove.
    SectionCard("Comets tonight", help = HelpTopic.Comets) {
        for (comet in list.comets) {
            Text(comet.title, style = MaterialTheme.typography.titleSmall)
            Text(comet.detail, style = MaterialTheme.typography.bodySmall)
            Caveat(comet)
        }
    }
}

/**
 * Why an event is worth less than its headline suggests, where it is.
 *
 * The whole reason [sh.arek.gloaming.domain.Tonight] keeps marginal events
 * rather than dropping them: somebody reading this has already decided to go
 * out, and "the moon only reaches 8 degrees at maximum, in a sky that is not
 * fully dark" is what they need to plan around. Without it the card promises an
 * eclipse and delivers a disappointment - which is the calendar's own reasoning,
 * and this card had been quietly failing to carry it.
 *
 * Its own composable so the three cards cannot drift into saying it three
 * different ways, or - as happened here - two of them saying it and one not.
 */
@Composable
private fun Caveat(event: SkyEvent) {
    val reason = when (val visibility = event.visibility) {
        is Visibility.Marginal -> visibility.reason
        is Visibility.NotFromHere -> visibility.reason
        Visibility.Good -> return
    }
    Text(reason, style = MaterialTheme.typography.bodySmall)
}

/**
 * What crosses tonight, which is the one thing the calendar cannot hold.
 *
 * A year of eclipses fits in a list; a year of passes does not - a clear night
 * here has upwards of forty - so the split is by shape: dated and sparse in the
 * calendar, nightly and dense here. Unlike the cards above it is present even
 * when empty, because an empty sky is an answer and a missing card is not.
 */
@Composable
private fun SatellitesCard(list: TonightList, state: MainUiState) {
    SectionCard("Satellites", help = HelpTopic.Satellites) {
        if (!list.elementsHeld) {
            // "Nothing crossing" and "no catalogue to look in" are the same
            // empty list and must not be the same sentence. A device that has
            // never managed to download the elements was being told the sky was
            // quiet, which is the one thing this card must never say wrongly.
            //
            // And not every absence is a fault: a phone on its first run has
            // not failed at anything, so it is told what will happen rather
            // than accused of a download that never went wrong.
            val note = describeMissingElements(
                everChecked = state.alertState.lastPollAt != null,
                failure = state.elementsFailure,
            )
            Text(
                note.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (note.alarming) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else if (list.passes.isEmpty()) {
            Text(
                "Nothing crossing tonight that clears the horizon in sunlight.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                if (list.passes.size > list.bestPasses.size) {
                    "${list.passes.size} satellites cross tonight. The best of them:"
                } else {
                    "${list.passes.size} satellite ${plural2(list.passes.size)} tonight:"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            for (pass in list.bestPasses) {
                Text(describePassLine(pass), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun plural2(count: Int) = if (count == 1) "pass" else "passes"

/** One pass in one line: when, which way, and how high. */
internal fun describePassLine(pass: SatellitePass): String =
    "${clock(pass.risesAt)}  ${pass.name} - rises ${Compass.of(pass.riseAzimuth).label}, " +
        "highest ${pass.peakElevation.roundToInt()} degrees ${Compass.of(pass.peakAzimuth).label} " +
        "at ${clock(pass.peaksAt)}"
