package sh.arek.gloaming.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.ui.theme.LocalPalette

/**
 * The sections of the app, each one a place on the bottom bar.
 *
 * [tag] reaches the bar item from a test, because a label alone is ambiguous
 * with a heading of the same name on the screen above it.
 */
enum class Section(val label: String, val tag: String) {
    Tonight("Tonight", "nav-tonight"),
    Ahead("Ahead", "nav-ahead"),
    Settings("Settings", "nav-settings"),
}

/** Reached by tag from a test: the temporary way into the guide from Settings. */
const val GUIDE_ROW_TAG = "settings-guide"
const val GUIDE_BACK_TAG = "guide-back"

/**
 * The sections over one activity, behind a bottom bar.
 *
 * No navigation library: a handful of sections need no back stack, no deep
 * links and no new dependency, and `rememberSaveable` is enough to survive a
 * rotation.
 *
 * The guide is not a section. It reads the same every night, so it is a page
 * under Settings rather than a destination of its own; until Settings is an
 * index of pages, a "Guide to the words" row at the head of Settings shows it
 * in place.
 *
 * Each section owns its own scroll, which is why [TonightContent],
 * [CalendarContent], [GuideContent] and [SettingsContent] emit loose children
 * rather than columns of their own.
 */
@Composable
fun GloamingNav(
    state: MainUiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    val sections = Section.entries

    // One scroll position per section, remembered out here rather than inside
    // the branch that uses it, so moving away and back returns to where the
    // reader was.
    val tonightScroll = rememberScrollState()
    val guideScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val aheadScroll = rememberLazyListState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            val palette = LocalPalette.current
            NavigationBar(containerColor = palette.raised, contentColor = palette.text) {
                sections.forEachIndexed { index, section ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { SectionIcon(section) },
                        label = { Text(section.label) },
                        modifier = Modifier.testTag(section.tag),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.onAccent,
                            selectedTextColor = palette.accent,
                            indicatorColor = palette.accent,
                            unselectedIconColor = palette.muted,
                            unselectedTextColor = palette.muted,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        val content = Modifier.padding(inner)
        when (sections[selected]) {
            // The calendar brings its own scrolling: it is a lazy list, and a
            // lazy list inside a scrolling column is measured with unbounded
            // height, which defeats the point of it and throws.
            Section.Ahead -> CalendarContent(
                events = state.calendar,
                hasLocation = state.settings.hasLocation,
                loading = state.calendarLoading,
                nowMillis = state.nowMillis,
                starred = state.settings.starredEvents,
                hiddenKinds = state.settings.hiddenEventKinds,
                actions = actions,
                modifier = content.fillMaxSize(),
                state = aheadScroll,
            )

            Section.Tonight -> ScrollingSection(tonightScroll, content) {
                TonightContent(state = state, actions = actions)
            }

            Section.Settings -> if (showGuide) {
                ScrollingSection(guideScroll, content) {
                    TextButton(
                        onClick = { showGuide = false },
                        modifier = Modifier.testTag(GUIDE_BACK_TAG),
                    ) { Text("Back to Settings") }
                    GuideContent()
                }
            } else {
                ScrollingSection(settingsScroll, content) {
                    TextButton(
                        onClick = { showGuide = true },
                        modifier = Modifier.testTag(GUIDE_ROW_TAG),
                    ) { Text("Guide to the words") }
                    SettingsContent(state = state, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun SectionIcon(section: Section) {
    androidx.compose.material3.Icon(
        imageVector = when (section) {
            Section.Tonight -> Icons.moon
            Section.Ahead -> Icons.calendar
            Section.Settings -> Icons.sliders
        },
        contentDescription = null,
    )
}

/**
 * The three bar icons, drawn here as 24dp strokes so no icon library is needed.
 * Stroked in black; the bar tints them.
 */
private object Icons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }.build()

    val moon = icon("moon") {
        moveTo(20f, 14.5f)
        arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f)
        arcTo(6.5f, 6.5f, 0f, false, false, 20f, 14.5f)
        close()
    }

    val calendar = icon("calendar") {
        moveTo(5f, 6f)
        horizontalLineTo(19f)
        verticalLineTo(20f)
        horizontalLineTo(5f)
        close()
        moveTo(5f, 10f)
        horizontalLineTo(19f)
        moveTo(8f, 3f)
        verticalLineTo(7f)
        moveTo(16f, 3f)
        verticalLineTo(7f)
    }

    val sliders = icon("sliders") {
        moveTo(4f, 7f)
        horizontalLineTo(20f)
        moveTo(4f, 17f)
        horizontalLineTo(20f)
        moveTo(9f, 4f)
        verticalLineTo(10f)
        moveTo(15f, 14f)
        verticalLineTo(20f)
    }
}

/**
 * A section that scrolls as one column, which Tonight and Settings do.
 *
 * The padding and spacing live here so the sections look identical to each
 * other - the calendar applies the same figures as its own content padding.
 */
@Composable
private fun ScrollingSection(
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
