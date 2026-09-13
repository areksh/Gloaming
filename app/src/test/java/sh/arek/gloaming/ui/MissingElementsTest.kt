package sh.arek.gloaming.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why the satellites card has nothing to list.
 *
 * Three situations arrive as the same empty list, and only one of them is a
 * fault. Saying "could not download, press Check now" to a phone that simply
 * has not run its first check yet reports a failure that has not happened, and
 * an app that cries wolf on a fresh install is one nobody believes later.
 */
class MissingElementsTest {

    @Test
    fun `a first run has not failed at anything`() {
        val note = describeMissingElements(everChecked = false, failure = null)

        assertFalse(note.alarming)
    }

    /** It says what will happen, rather than asking for something to be done. */
    @Test
    fun `a first run is told to expect it rather than to act`() {
        val note = describeMissingElements(everChecked = false, failure = null)

        assertTrue(note.text, note.text.contains("first check"))
        assertFalse(note.text, note.text.contains("Check now"))
    }

    /** A download that actually failed is a fault, and says why. */
    @Test
    fun `a failed download is reported as one, with its reason`() {
        val note = describeMissingElements(
            everChecked = true,
            failure = "GET https://sky.arek.sh/v1/sats.json returned 503",
        )

        assertTrue(note.alarming)
        assertTrue(note.text, note.text.contains("503"))
    }

    /**
     * Checked, no elements, and no reason to hand - which is what a background
     * poll that failed before the screen was ever opened looks like, the record
     * of it being in memory and this process being a new one. Still a fault,
     * just one that cannot explain itself.
     */
    @Test
    fun `a check that produced no elements is a fault even with no reason`() {
        val note = describeMissingElements(everChecked = true, failure = null)

        assertTrue(note.alarming)
        assertTrue(note.text, note.text.contains("Check now"))
    }

    /** Never a bare reason with no sentence around it. */
    @Test
    fun `always explains what the missing elements cost`() {
        for (note in listOf(
            describeMissingElements(false, null),
            describeMissingElements(true, null),
            describeMissingElements(true, "offline"),
        )) {
            assertTrue(note.text, note.text.contains("crossing"))
        }
    }
}
