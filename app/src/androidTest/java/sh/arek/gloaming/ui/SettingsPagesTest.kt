package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings

/**
 * The three Settings pages that are about whether the app can do its job,
 * which have no control of their own to set: they report, and where Android
 * holds the grant they ask for it.
 *
 * Rendered one at a time here because their sentences and tags repeat what
 * Tonight and its checks sheet say, which would make [ScreensTest]'s stacked
 * column ambiguous. **These need the device unlocked**, for the reason recorded
 * there.
 */
class SettingsPagesTest {

    @get:Rule
    val compose = createComposeRule()

    private var exemptionsAsked = 0
    private var notificationsAsked = 0

    private fun show(
        page: SettingsPage,
        batteryExempt: Boolean = true,
        notificationsAllowed: Boolean = true,
        downloads: List<DownloadRow> = emptyList(),
    ) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SettingsPageContent(
                    page = page,
                    state = MainUiState(
                        settings = UserSettings(latitude = 55.95, longitude = -3.19),
                        alertState = AlertState(),
                        latitudeInput = "55.95",
                        longitudeInput = "-3.19",
                        quietFromInput = "23:00",
                        quietUntilInput = "07:00",
                        batteryExempt = batteryExempt,
                        notificationsAllowed = notificationsAllowed,
                        nowMillis = 1_800_000_000_000L,
                        downloads = downloads,
                    ),
                    actions = UiActions(
                        onRequestBatteryExemption = { exemptionsAsked++ },
                        onRequestNotifications = { notificationsAsked++ },
                    ),
                )
            }
        }
    }

    @Test
    fun backgroundChecksAskForTheExemptionWhenItIsMissing() {
        show(SettingsPage.BackgroundChecks, batteryExempt = false)

        compose.onNodeWithText("Not allowed.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALLOW_BACKGROUND_CHECKS_TAG).performScrollTo().performClick()

        assertEquals(1, exemptionsAsked)
    }

    @Test
    fun backgroundChecksOfferNothingToPressOnceAllowed() {
        show(SettingsPage.BackgroundChecks, batteryExempt = true)

        compose.onNodeWithText("Allowed.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALLOW_BACKGROUND_CHECKS_TAG).assertDoesNotExist()
    }

    @Test
    fun notificationsAskForPermissionWhenItIsMissing() {
        show(SettingsPage.Notifications, notificationsAllowed = false)

        compose.onNodeWithTag(ALLOW_NOTIFICATIONS_TAG).performScrollTo().performClick()

        assertEquals(1, notificationsAsked)
    }

    @Test
    fun notificationsOfferNothingToPressOnceAllowed() {
        show(SettingsPage.Notifications, notificationsAllowed = true)

        compose.onNodeWithText("On.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALLOW_NOTIFICATIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun downloadsShowEveryDocumentEvenWhenNoneIsWrong() {
        show(
            SettingsPage.Downloads,
            downloads = listOf(
                DownloadRow("Satellite elements", listOf("Held copy is current"), false),
            ),
        )

        compose.onNodeWithTag(DOWNLOADS_PAGE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Satellite elements").assertIsDisplayed()
        compose.onNodeWithText("Held copy is current").assertIsDisplayed()
    }

    @Test
    fun downloadsSayWhenThereAreNone() {
        show(SettingsPage.Downloads)

        compose.onNodeWithText("Nothing downloaded yet", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(DOWNLOADS_PAGE_TAG).assertDoesNotExist()
    }
}
