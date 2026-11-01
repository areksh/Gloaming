package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.QuietHours
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.data.VerdictChannel

/**
 * What each row of the Settings index says about its page, and which of
 * those say something is wrong.
 */
class SettingsSummaryTest {

    private fun state(
        settings: UserSettings = UserSettings(),
        batteryExempt: Boolean = true,
        notificationsAllowed: Boolean = true,
        downloads: List<DownloadRow> = emptyList(),
        alertState: AlertState = AlertState(),
        logSizeBytes: Long = 0L,
        verdicts: List<Verdict> = emptyList(),
    ) = MainUiState(
        settings = settings,
        alertState = alertState,
        latitudeInput = "",
        longitudeInput = "",
        quietFromInput = "23:00",
        quietUntilInput = "07:00",
        batteryExempt = batteryExempt,
        notificationsAllowed = notificationsAllowed,
        nowMillis = 1_800_000_000_000L,
        downloads = downloads,
        logSizeBytes = logSizeBytes,
        verdicts = verdicts,
    )

    private val verdict = Verdict(
        answeredAtMillis = 1_799_000_000_000L,
        answer = Answer.Worth,
        conditions = Conditions(
            atMillis = 1_798_990_000_000L,
            channel = VerdictChannel.Aurora,
            probability = 40,
            threshold = 30,
        ),
    )

    // --- the structure ---

    @Test
    fun `groups the pages by question in the order the spec gives`() {
        assertEquals(
            listOf(
                "Where you are",
                "What is worth waking for",
                "When to leave you alone",
                "Is it working",
                "Learning from your answers",
                "Screen and reference",
            ),
            SettingsGroup.entries.map { it.title },
        )
    }

    @Test
    fun `lists each group's pages in the spec's order`() {
        fun titles(group: SettingsGroup) =
            SettingsPage.entries.filter { it.group == group }.map { it.title }

        assertEquals(listOf("Location", "Before first unlock"), titles(SettingsGroup.WhereYouAre))
        assertEquals(
            listOf("Aurora", "Satellite passes", "Dated events", "Comets", "Cloud", "Constellations"),
            titles(SettingsGroup.WorthWakingFor),
        )
        assertEquals(listOf("Quiet hours"), titles(SettingsGroup.LeaveYouAlone))
        assertEquals(
            listOf("Background checks", "Notifications", "Downloads", "Log"),
            titles(SettingsGroup.IsItWorking),
        )
        assertEquals(
            listOf("Automatic settings", "Your answers"),
            titles(SettingsGroup.LearningFromAnswers),
        )
        assertEquals(
            listOf("Night vision", "Guide to the words", "Calendar sync", "Credits and licences"),
            titles(SettingsGroup.ScreenAndReference),
        )
    }

    @Test
    fun `tags each row by its page`() {
        assertEquals("settings:Aurora", settingsRowTag(SettingsPage.Aurora))
    }

    @Test
    fun `gives every page a summary`() {
        for (page in SettingsPage.entries) {
            assertTrue(page.name, summary(page, state()).isNotBlank())
        }
    }

    // --- one summary per page ---

    @Test
    fun `says the aurora bar as a chance`() {
        assertEquals(
            "40% chance or more",
            summary(SettingsPage.Aurora, state(UserSettings(threshold = 40))),
        )
    }

    @Test
    fun `says aurora alerts are off when they are`() {
        assertEquals(
            "Alerts off",
            summary(SettingsPage.Aurora, state(UserSettings(alertsEnabled = false))),
        )
    }

    @Test
    fun `says a located phone is approximate and how often it updates`() {
        val settings = UserSettings(
            locationMode = LocationMode.Automatic,
            locationCadence = LocationCadence.OnOpen,
        )
        assertEquals(
            "Approximate, from the phone. Each time you open Gloaming",
            summary(SettingsPage.Location, state(settings)),
        )
    }

    @Test
    fun `says a typed position as its coordinates`() {
        val settings = UserSettings(latitude = 55.95, longitude = -3.19)
        assertEquals(
            "Typed in: 55.950 N, 3.190 W",
            summary(SettingsPage.Location, state(settings)),
        )
    }

    @Test
    fun `says when no position has been typed`() {
        assertEquals("Not set yet", summary(SettingsPage.Location, state()))
    }

    @Test
    fun `says the exact copy before first unlock only when it is on`() {
        assertEquals("Off", summary(SettingsPage.BeforeFirstUnlock, state()))
        assertEquals(
            "On, rounded to the whole degree",
            summary(SettingsPage.BeforeFirstUnlock, state(UserSettings(alertsBeforeUnlock = true))),
        )
        assertEquals(
            "On, with your exact location",
            summary(
                SettingsPage.BeforeFirstUnlock,
                state(
                    UserSettings(alertsBeforeUnlock = true, preciseLocationBeforeUnlock = true),
                ),
            ),
        )
    }

    @Test
    fun `says satellite passes are off or how low they are ignored`() {
        assertEquals(
            "Off",
            summary(SettingsPage.SatellitePasses, state(UserSettings(passAlertsEnabled = false))),
        )
        assertEquals(
            "Ignoring passes below 10 degrees",
            summary(SettingsPage.SatellitePasses, state()),
        )
    }

    @Test
    fun `says whether dated events tell you about all or only starred`() {
        assertEquals(
            "Off",
            summary(SettingsPage.DatedEvents, state(UserSettings(eventAlertsEnabled = false))),
        )
        assertEquals(
            "Only the ones you star",
            summary(
                SettingsPage.DatedEvents,
                state(UserSettings(eventAlertsEnabled = true, notifyAboutEveryEvent = false)),
            ),
        )
        assertEquals(
            "Everything visible from here",
            summary(
                SettingsPage.DatedEvents,
                state(UserSettings(eventAlertsEnabled = true, notifyAboutEveryEvent = true)),
            ),
        )
    }

    @Test
    fun `says the faintest comet worth hearing about`() {
        assertEquals(
            "Down to magnitude 7",
            summary(SettingsPage.Comets, state(UserSettings(cometMagnitudeThreshold = 7))),
        )
    }

    @Test
    fun `says the cloud bar or that it is not checked`() {
        assertEquals(
            "Held back above 70 per cent cloud",
            summary(
                SettingsPage.Cloud,
                state(UserSettings(cloudGateEnabled = true, cloudTolerance = 70)),
            ),
        )
        assertEquals(
            "Not checked",
            summary(SettingsPage.Cloud, state(UserSettings(cloudGateEnabled = false))),
        )
    }

    @Test
    fun `says how many constellations are named`() {
        assertEquals(
            "The handful worth leading with",
            summary(SettingsPage.Constellations, state(UserSettings(showEveryConstellation = false))),
        )
        assertEquals(
            "Every one above the horizon",
            summary(SettingsPage.Constellations, state(UserSettings(showEveryConstellation = true))),
        )
    }

    @Test
    fun `says the quiet hours or that there are none`() {
        assertEquals("Off", summary(SettingsPage.QuietHours, state()))
        assertEquals(
            "23:00 to 07:00",
            summary(
                SettingsPage.QuietHours,
                state(UserSettings(quietHours = QuietHours(23 * 60, 7 * 60))),
            ),
        )
    }

    @Test
    fun `says background checks are allowed`() {
        assertEquals("Allowed", summary(SettingsPage.BackgroundChecks, state(batteryExempt = true)))
        assertFalse(summaryAlarming(SettingsPage.BackgroundChecks, state(batteryExempt = true)))
    }

    @Test
    fun `says plainly that background checks without the exemption arrive late`() {
        val withoutExemption = state(batteryExempt = false)

        assertEquals(
            "Not allowed. Overnight alerts will arrive hours late",
            summary(SettingsPage.BackgroundChecks, withoutExemption),
        )
        assertTrue(summaryAlarming(SettingsPage.BackgroundChecks, withoutExemption))
    }

    @Test
    fun `says notifications are on or off and flags off`() {
        assertEquals("On", summary(SettingsPage.Notifications, state(notificationsAllowed = true)))
        assertFalse(summaryAlarming(SettingsPage.Notifications, state(notificationsAllowed = true)))

        val off = state(notificationsAllowed = false)
        assertEquals(
            "Off. Gloaming cannot tell you anything",
            summary(SettingsPage.Notifications, off),
        )
        assertTrue(summaryAlarming(SettingsPage.Notifications, off))
    }

    @Test
    fun `says nothing has been downloaded before anything has`() {
        assertEquals("Nothing downloaded yet", summary(SettingsPage.Downloads, state()))
        assertFalse(summaryAlarming(SettingsPage.Downloads, state()))
    }

    @Test
    fun `says downloads are fine when none is marked`() {
        val fine = state(
            downloads = listOf(DownloadRow("Satellite elements", listOf("Held copy is current"), false)),
        )

        assertEquals("Nothing wrong", summary(SettingsPage.Downloads, fine))
        assertFalse(summaryAlarming(SettingsPage.Downloads, fine))
    }

    @Test
    fun `names what is wrong with the downloads and flags it`() {
        val stale = state(
            downloads = listOf(DownloadRow("Satellite elements", listOf("Held copy is 14 days old"), true)),
        )

        assertEquals(
            "Something is wrong with the satellite elements.",
            summary(SettingsPage.Downloads, stale),
        )
        assertTrue(summaryAlarming(SettingsPage.Downloads, stale))
    }

    @Test
    fun `says the log's size and how long it is kept`() {
        assertEquals(
            "212 KB, kept for 30 days",
            summary(
                SettingsPage.Log,
                state(UserSettings(logRetentionDays = 30), logSizeBytes = 217_088L),
            ),
        )
    }

    @Test
    fun `says the log is empty or off`() {
        assertEquals(
            "Empty, kept for 7 days",
            summary(SettingsPage.Log, state(UserSettings(logRetentionDays = 7))),
        )
        assertEquals(
            "Off",
            summary(SettingsPage.Log, state(UserSettings(logRetentionDays = 0))),
        )
    }

    @Test
    fun `says automatic settings are on or off`() {
        assertEquals("Off", summary(SettingsPage.AutomaticSettings, state()))
        assertEquals(
            "On",
            summary(SettingsPage.AutomaticSettings, state(UserSettings(automaticSettings = true))),
        )
    }

    @Test
    fun `counts the answers given`() {
        assertEquals("None yet", summary(SettingsPage.YourAnswers, state()))
        assertEquals("1 answer", summary(SettingsPage.YourAnswers, state(verdicts = listOf(verdict))))
        assertEquals(
            "2 answers",
            summary(SettingsPage.YourAnswers, state(verdicts = listOf(verdict, verdict))),
        )
    }

    @Test
    fun `says night vision is on or off`() {
        assertEquals("Off", summary(SettingsPage.NightVision, state()))
        assertEquals(
            "On",
            summary(SettingsPage.NightVision, state(UserSettings(nightVision = true))),
        )
    }

    @Test
    fun `says where starred events are written`() {
        assertEquals("Off", summary(SettingsPage.CalendarSync, state()))
        assertEquals(
            CalendarSync.OwnCalendar.label,
            summary(
                SettingsPage.CalendarSync,
                state(UserSettings(calendarSync = CalendarSync.OwnCalendar)),
            ),
        )
    }

    // --- which of them say something is wrong ---

    @Test
    fun `flags a location whose background reads Android has revoked`() {
        val revoked = state(
            UserSettings(
                locationMode = LocationMode.Automatic,
                locationCadence = LocationCadence.Every(6),
                backgroundLocationRevoked = true,
            ),
        )

        assertTrue(summaryAlarming(SettingsPage.Location, revoked))
        assertEquals(
            "Approximate, from the phone. Background updates revoked; each time you open Gloaming",
            summary(SettingsPage.Location, revoked),
        )
    }

    @Test
    fun `flags nothing on a healthy phone`() {
        val healthy = state(UserSettings(latitude = 55.95, longitude = -3.19))

        assertEquals(emptyList<SettingsPage>(), SettingsPage.entries.filter { summaryAlarming(it, healthy) })
    }
}
