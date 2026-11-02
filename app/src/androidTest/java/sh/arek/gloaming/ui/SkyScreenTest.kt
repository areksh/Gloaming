package sh.arek.gloaming.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * The Sky tab: every pass of the night, every pattern, each with a sheet.
 *
 * **These need the device unlocked**, for the reason recorded in [ScreensTest].
 */
class SkyScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val now = 1_793_642_400_000L // 2026-11-02 18:00 UTC

    private fun pass(catalogue: Int, risesIn: Long, channel: PassChannel = PassChannel.Bright) = SatellitePass(
        catalogue = catalogue,
        name = "OBJECT $catalogue",
        channel = channel,
        risesAt = now + risesIn,
        riseAzimuth = 10.0,
        peaksAt = now + risesIn + 5 * minute,
        peakElevation = 40.0,
        peakAzimuth = 280.0,
        setsAt = now + risesIn + 10 * minute,
        setAzimuth = 200.0,
        rangeAtPeakKm = 900.0,
        sunlitAtPeak = true,
        entersShadowAt = null,
        leavesShadowAt = null,
    )

    /** Twenty passes across three hours, two of them already gone, and two of them the station. */
    private val passes = (0 until 20).map { i ->
        pass(30000 + i, risesIn = (i - 2) * 9 * minute - 15 * minute, channel = if (i % 7 == 3) PassChannel.Iss else PassChannel.Bright)
    }
    private val gone = passes.filter { it.setsAt <= now }

    private fun pattern(name: String, circumpolar: Boolean) = ConstellationTonight(
        Constellations.WATCHED.first { it.name == name }, 50.0, now + 4 * 60 * minute, 180.0, circumpolar, false,
    )
    private val orion = pattern("Orion", circumpolar = false)
    private val ursaMajor = pattern("Ursa Major", circumpolar = true)

    private val night = TonightList(
        darkFrom = now - 40 * minute,
        darkUntil = now + 12 * 60 * minute,
        passes = passes,
        bestPasses = emptyList(),
        events = emptyList(),
        comets = emptyList(),
        noctilucent = null,
        planets = listOf(PlanetTonight(SkyBody.Saturn, 39.0, now + 3 * 60 * minute, 180.0, 0.9, null, null)),
        everyConstellation = listOf(orion, ursaMajor),
    )

    private val found = mutableListOf<FindTarget>()

    private fun show() {
        compose.setContent {
            val state = MainUiState(
                settings = UserSettings(latitude = 53.0, longitude = -3.0),
                alertState = AlertState(),
                latitudeInput = "53",
                longitudeInput = "-3",
                quietFromInput = "23:00",
                quietUntilInput = "07:00",
                batteryExempt = true,
                notificationsAllowed = true,
                nowMillis = now,
                tonight = night,
            )
            SkyScreen(
                state = state,
                actions = UiActions(),
                listState = rememberLazyListState(),
                onFindIt = { found += it },
                nowMillis = now,
            )
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(SKY_LIST_TAG).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun listsEveryPassStillToCome() {
        show()

        for (pass in passes - gone.toSet()) {
            val tag = skyRowTag(RowSubject.Pass(pass))
            scrollTo(tag)
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun foldsThePassesAlreadySet() {
        show()
        val tag = skyRowTag(RowSubject.Pass(gone.first()))

        assertEquals(0, compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)

        scrollTo(SKY_EARLIER_TAG)
        compose.onNodeWithTag(SKY_EARLIER_TAG).performClick()
        scrollTo(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test
    fun channelChipsHideAndShow() {
        show()
        val bright = passes.first { it.channel == PassChannel.Bright && it.setsAt > now }
        val tag = skyRowTag(RowSubject.Pass(bright))

        compose.onNodeWithTag(skyChannelTag(PassChannel.Bright)).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)

        compose.onNodeWithTag(skyChannelTag(PassChannel.Bright)).performClick()
        scrollTo(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test
    fun tappingAPassOpensItsSheetWithFindIt() {
        show()
        val pass = passes.last()
        val tag = skyRowTag(RowSubject.Pass(pass))

        scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(ROW_SHEET_TAG).assertIsDisplayed()
        compose.onNode(hasText("Highest at", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag(FIND_IT_TAG).performClick()

        assertEquals(listOf<FindTarget>(FindTarget.Pass(pass.catalogue, pass.risesAt)), found)
    }

    @Test
    fun anAlwaysUpPatternHasASheet() {
        show()
        val tag = skyRowTag(RowSubject.Constellation(ursaMajor))

        scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.onNode(hasText("Never sets from here.", substring = true)).assertIsDisplayed()
    }
}
