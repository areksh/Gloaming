package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a notification says it is about, as text that survives an intent. */
class SubjectRefTest {

    @Test
    fun `round-trips a pass and an event`() {
        val refs = listOf(
            SubjectRef.Pass(28499, 1_793_644_084_410L),
            SubjectRef.Event("MeteorShower|1793700000000"),
            SubjectRef.Event("Comet|C/2026 A1: Something | odd"),
        )

        refs.forEach { assertEquals(it, SubjectRef.decode(it.encode())) }
        assertEquals("pass:28499:1793644084410", refs[0].encode())
    }

    @Test
    fun `decodes nothing from rubbish`() {
        listOf("", "pass:x:1", "pass:0:5", "pass:1", "pass:1:2:3", "event:", "planet:Mars", "moon", ":").forEach {
            assertNull("'$it' should not decode", SubjectRef.decode(it))
        }
    }

    @Test
    fun `takes nothing before setup is done or from a relaunch out of history`() {
        val text = SubjectRef.Pass(25544, 1L).encode()

        assertEquals(SubjectRef.Pass(25544, 1L), showSubjectFrom(text, setupDone = true, launchedFromHistory = false))
        assertNull(showSubjectFrom(text, setupDone = false, launchedFromHistory = false))
        assertNull(showSubjectFrom(text, setupDone = true, launchedFromHistory = true))
        assertNull(showSubjectFrom(null, setupDone = true, launchedFromHistory = false))
    }
}
