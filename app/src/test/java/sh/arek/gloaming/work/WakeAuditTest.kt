package sh.arek.gloaming.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.FakeSharedPreferences

/**
 * The two questions the journal could not answer about itself.
 *
 * Both come from real nights. A poll killed mid-flight wrote its first stage
 * and stopped, which reads exactly like a quiet sky; and a wake that asked for
 * half an hour arrived after nearly three, which nothing recorded at all.
 */
class WakeAuditTest {

    private val minute = 60_000L
    private val boot = 1_700_000_000_000L

    private fun record(
        atMillis: Long = 0L,
        askedMinutes: Int = 30,
        complete: Boolean = true,
        bootInstantMillis: Long = boot,
    ) = WakeRecord(atMillis, askedMinutes, complete, bootInstantMillis)

    @Test
    fun `says nothing about a wake that arrived within the batching window`() {
        val previous = record(atMillis = 0L, askedMinutes = 30)

        // 1.93x: the worst ordinary delivery either test phone has produced.
        val findings = WakeAudit.findings(previous, 58 * minute, boot)

        assertEquals(emptyList<String>(), findings)
    }

    @Test
    fun `reports a wake far outside the window`() {
        val previous = record(atMillis = 0L, askedMinutes = 30)

        // The 10a, 2026-09-16: asked at 02:56, delivered at 05:43.
        val findings = WakeAudit.findings(previous, 167 * minute, boot)

        assertEquals(1, findings.size)
        assertTrue(findings[0], findings[0].contains("asked for 30m"))
        assertTrue(findings[0], findings[0].contains("167m"))
    }

    @Test
    fun `reports a poll that never reached its reschedule`() {
        val previous = record(atMillis = 0L, askedMinutes = 30, complete = false)

        val findings = WakeAudit.findings(previous, 28 * minute, boot)

        assertEquals(1, findings.size)
        assertTrue(findings[0], findings[0].contains("never reached its reschedule"))
    }

    @Test
    fun `reports both when a killed poll is also followed by a late wake`() {
        val previous = record(atMillis = 0L, askedMinutes = 30, complete = false)

        assertEquals(2, WakeAudit.findings(previous, 167 * minute, boot).size)
    }

    /**
     * The approach to a pass alert asks for a minute at a time, and the window
     * on a wake that short is seconds. Twice over is the normal case there.
     */
    @Test
    fun `holds its tongue about a short wake that doubled`() {
        val previous = record(atMillis = 0L, askedMinutes = 1)

        assertEquals(emptyList<String>(), WakeAudit.findings(previous, 3 * minute, boot))
    }

    /**
     * The shortest interval the policy asks for is five minutes, and those
     * were measured arriving at nine to ten. Reporting that as a fault would
     * put a line or two in the journal every time the wind turned southward.
     */
    @Test
    fun `holds its tongue about an elevated wake arriving at the measured worst`() {
        val previous = record(atMillis = 0L, askedMinutes = 5)

        assertEquals(emptyList<String>(), WakeAudit.findings(previous, 10 * minute, boot))
    }

    /**
     * Elapsed realtime restarts at a reboot, so nothing spanning one can be
     * measured - and a poll cut short by a shutdown was not killed in any sense
     * worth a line. BootReceiver already accounts for the gap.
     */
    @Test
    fun `says nothing across a reboot`() {
        val previous = record(atMillis = 0L, askedMinutes = 30, complete = false)

        val findings = WakeAudit.findings(previous, 500 * minute, bootInstantMillis = boot + 400 * minute)

        assertEquals(emptyList<String>(), findings)
    }

    /** A clock correction of a few seconds is not a reboot. */
    @Test
    fun `tolerates a small clock correction`() {
        val previous = record(atMillis = 0L, askedMinutes = 30, complete = false)

        val findings = WakeAudit.findings(previous, 28 * minute, bootInstantMillis = boot + 4_000L)

        assertEquals(1, findings.size)
    }

    @Test
    fun `says nothing when there is no previous record`() {
        assertEquals(emptyList<String>(), WakeAudit.findings(null, 99 * minute, boot))
    }

    @Test
    fun `survives a clock that went backwards`() {
        val previous = record(atMillis = 10 * minute, askedMinutes = 30)

        assertEquals(emptyList<String>(), WakeAudit.findings(previous, 0L, boot))
    }

    @Test
    fun `remembers what was armed across a read`() {
        val store = WakeRecordStore(FakeSharedPreferences())
        val written = WakeRecord(
            atMillis = 1_789_000_000_000L,
            askedMinutes = 42,
            complete = false,
            bootInstantMillis = boot,
        )

        store.write(written)

        assertEquals(written, store.read())
    }

    /** Nothing written yet is not the same as a poll that finished. */
    @Test
    fun `reads nothing back from an empty store`() {
        assertEquals(null, WakeRecordStore(FakeSharedPreferences()).read())
    }
}
