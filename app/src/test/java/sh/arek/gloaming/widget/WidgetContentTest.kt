package sh.arek.gloaming.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings

class WidgetContentTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val located = UserSettings(latitude = 55.95, longitude = -3.19, threshold = 30)

    private fun content(
        settings: UserSettings = located,
        state: AlertState = AlertState(lastProbability = 42, lastPollAt = now - 10 * minute),
    ) = WidgetContent.of(settings, state, now)

    @Test
    fun `shows the local probability and how old it is`() {
        val content = content()

        assertEquals("42%", content.headline)
        assertTrue(content.detail, content.detail.contains("10 minutes ago"))
    }

    @Test
    fun `asks for a location before it has one`() {
        val content = content(settings = UserSettings())

        assertEquals(WidgetContent.NO_READING, content.headline)
        assertEquals("Tap to set your location", content.detail)
    }

    @Test
    fun `says so before the first reading`() {
        val content = content(state = AlertState())

        assertEquals(WidgetContent.NO_READING, content.headline)
        assertEquals("Not checked yet", content.detail)
    }

    /**
     * The whole point of the widget is answering "is it worth checking?" at a
     * glance, so it has to say when a reading crosses the user's own bar rather
     * than leaving them to compare two numbers.
     */
    @Test
    fun `marks a reading that has crossed the user's threshold`() {
        assertTrue(content(state = AlertState(lastProbability = 60, lastPollAt = now)).worthLooking)
        assertTrue(content(state = AlertState(lastProbability = 30, lastPollAt = now)).worthLooking)
    }

    @Test
    fun `does not mark a reading below the threshold`() {
        assertTrue(!content(state = AlertState(lastProbability = 29, lastPollAt = now)).worthLooking)
    }

    @Test
    fun `does not mark a reading it does not have`() {
        assertTrue(!content(state = AlertState()).worthLooking)
    }

    /**
     * A stale reading must not be presented as current. A phone that has been
     * offline for a week showing "5%" is worse than one showing nothing, since
     * the reassurance is what stops the user looking out of the window.
     */
    @Test
    fun `does not present a stale reading as current`() {
        val week = 7 * 24 * 60 * minute
        val content = content(state = AlertState(lastProbability = 42, lastPollAt = now - week))

        assertEquals(WidgetContent.NO_READING, content.headline)
        assertTrue(content.detail, content.detail.contains("7 days ago"))
        assertTrue(!content.worthLooking)
    }

    @Test
    fun `still shows a reading that is merely a few hours old`() {
        val state = AlertState(lastProbability = 42, lastPollAt = now - 3 * 60 * minute)

        assertEquals("42%", content(state = state).headline)
    }

    @Test
    fun `reports a failed check rather than hiding it`() {
        val state = AlertState(
            lastProbability = 42,
            lastPollAt = now - 5 * minute,
            lastError = "offline",
        )

        assertEquals("Last check failed", content(state = state).detail)
    }
}
