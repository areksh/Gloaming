package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a user beyond the station's reach is told so.
 *
 * Nothing else on the screen would tell them. The app has no pass list to show
 * empty - it only ever speaks through notifications - so from a user's side an
 * unreachable station and a broken app produce exactly the same experience,
 * which is nothing at all. This sentence is the entire difference.
 */
class IssCoverageNoteTest {

    @Test
    fun `says nothing to a user under the ground track`() {
        assertNull(describeIssCoverage(51.5))
        assertNull(describeIssCoverage(55.95))
    }

    @Test
    fun `tells a user beyond it that the orbit does not reach them`() {
        val text = describeIssCoverage(69.6)

        assertEquals(
            "The station's orbit reaches 51.6 degrees north and south, and you are at " +
                "69.6, so it never comes above your horizon. Other bright satellites are " +
                "unaffected - many of them are in polar orbits.",
            text,
        )
    }

    @Test
    fun `warns a user just past it without promising nothing`() {
        val text = describeIssCoverage(58.0)

        assertEquals(
            "The station's orbit reaches 51.6 degrees north and south, and you are at " +
                "58.0, so its passes here are low, brief and occasional.",
            text,
        )
    }

    @Test
    fun `says the same thing in the southern hemisphere`() {
        assertEquals(describeIssCoverage(69.6), describeIssCoverage(-69.65))
        assertEquals(describeIssCoverage(58.0), describeIssCoverage(-58.0))
    }

    @Test
    fun `states the latitude without a locale-dependent separator`() {
        // The sentence is British English throughout, so a machine set to a
        // decimal-comma locale must not render "69,6" in the middle of it.
        assertTrue(describeIssCoverage(69.6)!!.contains("69.6"))
        // Away from a rounding tie, which a double cannot represent anyway.
        assertTrue(describeIssCoverage(69.68)!!.contains("69.7"))
    }
}
