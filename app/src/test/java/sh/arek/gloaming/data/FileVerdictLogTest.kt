package sh.arek.gloaming.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The record every calibrated default eventually rests on.
 *
 * Sec. 15 has said from the start that the alert thresholds cannot be settled
 * by reading a policy or writing code, and prescribes logging activity against
 * a subjective "was it worth it?". This is where those answers go.
 */
class FileVerdictLogTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun log(cap: Int = 500) = FileVerdictLog(temporaryFolder.root, cap)

    private fun verdict(
        at: Long = 1_800_000_000_000L,
        answer: Answer = Answer.Worth,
        probability: Int = 45,
    ) = Verdict(
        answeredAtMillis = at,
        answer = answer,
        conditions = Conditions(
            atMillis = at - 600_000L,
            channel = VerdictChannel.Aurora,
            probability = probability,
            source = "AURORAWATCH",
            kp = 5.33,
            bz = -12.4,
            cloudTotal = 20,
            cloudLow = 5,
            cloudMid = 0,
            cloudHigh = 20,
            threshold = 30,
        ),
    )

    @Test
    fun `returns nothing before anything has been recorded`() {
        assertEquals(emptyList<Verdict>(), log().read())
    }

    @Test
    fun `round-trips a verdict and the sky it was about`() {
        log().append(verdict())

        val held = log().read().single()
        assertEquals(Answer.Worth, held.answer)
        assertEquals(45, held.conditions.probability)
        assertEquals(5.33, held.conditions.kp!!, 1e-9)
        assertEquals(-12.4, held.conditions.bz!!, 1e-9)
        assertEquals(30, held.conditions.threshold)
        assertEquals(VerdictChannel.Aurora, held.conditions.channel)
    }

    @Test
    fun `keeps the verdicts in the order they were given`() {
        log().append(verdict(at = 1_000L, probability = 10))
        log().append(verdict(at = 2_000L, probability = 20))

        assertEquals(listOf(10, 20), log().read().map { it.conditions.probability })
    }

    /** Oldest dropped first, so a phone left running for years is bounded. */
    @Test
    fun `drops the oldest once the cap is reached`() {
        val log = log(cap = 3)
        for (index in 1..5) log.append(verdict(at = index.toLong(), probability = index))

        assertEquals(listOf(3, 4, 5), log.read().map { it.conditions.probability })
    }

    /**
     * A truncated write - the device losing power as a verdict is pressed -
     * reads back as no verdicts rather than throwing inside a broadcast
     * receiver with nobody to see it.
     */
    @Test
    fun `returns nothing when the file is unreadable`() {
        log().append(verdict())
        File(temporaryFolder.root, FileVerdictLog.FILE_NAME).writeText("{ truncated")

        assertEquals(emptyList<Verdict>(), log().read())
    }

    @Test
    fun `clears everything on request`() {
        log().append(verdict())
        log().clear()

        assertEquals(emptyList<Verdict>(), log().read())
    }

    // --- the conditions, which travel in a notification rather than in a file ---

    /**
     * Kp and Bz are read from a document replaced every few minutes and are
     * persisted nowhere, so by the time somebody answers "was it worth it?" the
     * conditions that prompted the alert exist only here. FLAG_IMMUTABLE means
     * they have to be baked in when the notification is built.
     */
    @Test
    fun `round-trips the conditions through the string an action can carry`() {
        val conditions = verdict().conditions

        assertEquals(conditions, Conditions.decode(conditions.encode()))
    }

    @Test
    fun `reads nothing rather than failing on a malformed action`() {
        assertNull(Conditions.decode("not json"))
        assertNull(Conditions.decode(null))
    }

    /**
     * An action built by an older version and left sitting in the shade for a
     * week must still be answerable - the fields it does not carry are simply
     * unknown.
     */
    @Test
    fun `reads an encoding that predates a field`() {
        val old = """{"atMillis":1000,"channel":"Aurora","probability":40}"""

        val conditions = Conditions.decode(old)!!
        assertEquals(40, conditions.probability)
        assertNull(conditions.kp)
    }

    @Test
    fun `ignores a field a later version added`() {
        val newer = """{"atMillis":1000,"channel":"Aurora","somethingElse":7}"""

        assertTrue(Conditions.decode(newer) != null)
    }
}
