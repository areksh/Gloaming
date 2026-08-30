package sh.arek.gloaming.astronomy

import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.ui.labelFor

/**
 * That every kind is accounted for everywhere a kind has to be.
 *
 * Three exhaustive `when` blocks catch a new kind at compile time, and until
 * now nothing caught the rest. A kind can reach the calendar with no chip
 * label, no scenario and no channel decision, compile cleanly and pass every
 * suite - and a kind with no chip is one nobody can hide, which by
 * [sh.arek.gloaming.domain.EventPlanner] also makes it one nobody can silence.
 *
 * The same shape as the scenario catalogue's own coverage test: collect every
 * failure and name them together, so one run says what is missing rather than
 * stopping at the first.
 */
class EventKindTest {

    @Test
    fun `gives every kind a filter label`() {
        val missing = EventKind.entries.filter { labelFor(it).isBlank() }

        assertTrue("no chip label for $missing", missing.isEmpty())
    }

    /** Two kinds sharing a label would draw two chips nobody could tell apart. */
    @Test
    fun `gives every kind its own filter label`() {
        val labels = EventKind.entries.map { labelFor(it) }

        assertTrue(labels.toString(), labels.size == labels.toSet().size)
    }

    /** Plain ASCII, like every other string the app shows. */
    @Test
    fun `writes plain ascii filter labels`() {
        for (kind in EventKind.entries) {
            assertTrue(labelFor(kind), labelFor(kind).all { it.code in 32..126 })
        }
    }
}
