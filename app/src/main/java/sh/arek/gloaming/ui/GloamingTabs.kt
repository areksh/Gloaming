package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.WatchDuration

/** The tabs, reached by tag because a label alone is ambiguous with a heading. */
const val TAB_TONIGHT = "tab-tonight"
const val TAB_CALENDAR = "tab-calendar"
const val TAB_GUIDE = "tab-guide"
const val TAB_SETTINGS = "tab-settings"

/**
 * Four tabs over one activity.
 *
 * No navigation library: a handful of tabs need no back stack, no deep links
 * and no new dependency, and `rememberSaveable` is enough to survive a
 * rotation. The app has been a single screen since Phase 1 and this is the
 * smallest thing that stops being one.
 *
 * Guide sits third, between what is happening and how the app is configured.
 * It is the one tab that reads the same every night, so it neither competes
 * with tonight nor gets buried under the settings - and unlike the Explain
 * button on each card, it can be found by somebody who does not yet know
 * which word they are stuck on.
 *
 * Each tab owns its own scroll, which is why [TonightContent],
 * [CalendarContent], [GuideContent] and [SettingsContent] emit loose children
 * rather than columns of their own.
 */
@Composable
fun GloamingTabs(
    state: MainUiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selected) {
            Tab(
                selected = selected == 0,
                onClick = { selected = 0 },
                text = { Text("Tonight") },
                modifier = Modifier.testTag(TAB_TONIGHT),
            )
            Tab(
                selected = selected == 1,
                onClick = { selected = 1 },
                text = { Text("Calendar") },
                modifier = Modifier.testTag(TAB_CALENDAR),
            )
            Tab(
                selected = selected == 2,
                onClick = { selected = 2 },
                text = { Text("Guide") },
                modifier = Modifier.testTag(TAB_GUIDE),
            )
            Tab(
                selected = selected == 3,
                onClick = { selected = 3 },
                text = { Text("Settings") },
                modifier = Modifier.testTag(TAB_SETTINGS),
            )
        }

        // One scroll position per tab, remembered out here rather than inside
        // the branch that uses it, so tabbing away and back returns to where
        // the reader was. They used to share a single one, which meant opening
        // the calendar half way down because that was where Tonight had been
        // left.
        val tonightScroll = rememberScrollState()
        val guideScroll = rememberScrollState()
        val settingsScroll = rememberScrollState()
        val calendarScroll = rememberLazyListState()

        when (selected) {
            // The calendar brings its own scrolling: it is a lazy list, and a
            // lazy list inside a scrolling column is measured with unbounded
            // height, which defeats the point of it and throws.
            1 -> CalendarContent(
                events = state.calendar,
                hasLocation = state.settings.hasLocation,
                loading = state.calendarLoading,
                nowMillis = state.nowMillis,
                starred = state.settings.starredEvents,
                                hiddenKinds = state.settings.hiddenEventKinds,
                actions = actions,
                modifier = Modifier.fillMaxSize(),
                state = calendarScroll,
            )

            else -> ScrollingTab(
                scroll = when (selected) {
                    0 -> tonightScroll
                    2 -> guideScroll
                    else -> settingsScroll
                },
            ) {
                when (selected) {
                    0 -> TonightContent(state = state, actions = actions)

                    2 -> GuideContent()

                    else -> SettingsContent(state = state, actions = actions)
                }
            }
        }
    }
}

/**
 * A tab that scrolls as one column, which three of the four do.
 *
 * The padding and spacing that used to live on the single shared column, kept
 * here so the tabs still look identical to each other - the calendar applies
 * the same figures as its own content padding.
 */
@Composable
private fun ScrollingTab(
    scroll: ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
