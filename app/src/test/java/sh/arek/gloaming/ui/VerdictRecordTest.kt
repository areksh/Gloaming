package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.data.VerdictChannel

/**
 * The record of answers, as the settings screen has to say it back.
 *
 * The arithmetic is trivial and the wording is not. Two things here are worth
 * pinning: that an alert nobody answered is counted as its own thing rather
 * than as a failure, which is the whole reason [Answer.DidNotLook] exists, and
 * that a line says only what it was told - a pass alert records no Kp, and
 * inventing one would be worse than a shorter sentence.
 */
class VerdictRecordTest {

    private fun aurora(
        answer: Answer,
        at: Long = 1_800_000_000_000L,
        probability: Int = 54,
    ) = Verdict(
        answeredAtMillis = at,
        answer = answer,
        conditions = Conditions(
            atMillis = at - 100_000L,
            channel = VerdictChannel.Aurora,
            probability = probability,
            source = "AURORAWATCH",
            kp = 5.3,
            bz = -8.1,
            cloudTotal = 22,
            threshold = 30,
        ),
    )

    private fun pass(answer: Answer, at: Long = 1_800_000_000_000L) = Verdict(
        answeredAtMillis = at,
        answer = answer,
        conditions = Conditions(
            atMillis = at - 100_000L,
            channel = VerdictChannel.Pass,
            peakElevation = 41.2,
            subject = "ISS (ZARYA)",
        ),
    )

    @Test
    fun `counts every answer, by channel`() {
        val tallies = tallyVerdicts(
            listOf(
                aurora(Answer.Worth),
                aurora(Answer.NotWorth),
                aurora(Answer.DidNotLook),
                aurora(Answer.Worth),
                pass(Answer.Worth),
            ),
        )

        assertEquals(
            listOf(
                VerdictTally(VerdictChannel.Aurora, worth = 2, notWorth = 1, didNotLook = 1),
                VerdictTally(VerdictChannel.Pass, worth = 1, notWorth = 0, didNotLook = 0),
            ),
            tallies,
        )
    }

    @Test
    fun `leaves out a channel nothing has been answered about`() {
        val tallies = tallyVerdicts(listOf(pass(Answer.Worth)))

        assertEquals(listOf(VerdictChannel.Pass), tallies.map { it.channel })
    }

    @Test
    fun `an unanswered alert is not counted as a failure`() {
        val tally = tallyVerdicts(listOf(aurora(Answer.DidNotLook))).single()

        assertEquals(0, tally.notWorth)
        assertEquals(1, tally.didNotLook)
        assertEquals("1 alert: none worth going out for, 1 you did not look at", describe(tally))
    }

    @Test
    fun `says how a channel has gone in one line`() {
        val tally = VerdictTally(VerdictChannel.Aurora, worth = 3, notWorth = 1, didNotLook = 2)

        assertEquals("6 alerts: 3 worth going out for, 1 not, 2 you did not look at", describe(tally))
    }

    /**
     * The same words the rest of the settings screen uses for the same three
     * things. A record headed "Pass" beside a control headed "Satellite passes"
     * reads as two different features.
     */
    @Test
    fun `names each channel as the other cards name it`() {
        assertEquals("Aurora", name(VerdictChannel.Aurora))
        assertEquals("Satellite passes", name(VerdictChannel.Pass))
        assertEquals("Sky calendar", name(VerdictChannel.Event))
    }

    @Test
    fun `newest answer first`() {
        val older = aurora(Answer.Worth, at = 1_000L)
        val newer = aurora(Answer.NotWorth, at = 2_000L)

        assertEquals(listOf(newer, older), newestFirst(listOf(older, newer)))
    }

    @Test
    fun `keeps only as many as it was asked for, from the newest end`() {
        val verdicts = (1..5L).map { aurora(Answer.Worth, at = it * 1_000L) }

        assertEquals(
            listOf(5_000L, 4_000L, 3_000L),
            newestFirst(verdicts, limit = 3).map { it.answeredAtMillis },
        )
    }

    @Test
    fun `describes an aurora alert by what the sky was doing`() {
        assertEquals(
            "54% from AuroraWatch UK, Kp 5.3, Bz -8.1, 22% cloud, threshold 30",
            describe(aurora(Answer.Worth).conditions),
        )
    }

    @Test
    fun `names the global grid rather than its acronym`() {
        val fromGrid = aurora(Answer.Worth).conditions.copy(source = "OVATION")

        assertEquals(
            "54% from the global grid, Kp 5.3, Bz -8.1, 22% cloud, threshold 30",
            describe(fromGrid),
        )
    }

    @Test
    fun `describes a pass by what it was and how high it got`() {
        assertEquals("ISS (ZARYA), 41 degrees up", describe(pass(Answer.Worth).conditions))
    }

    @Test
    fun `says only what it was told`() {
        val bare = Conditions(atMillis = 0L, channel = VerdictChannel.Event, subject = "Perseids")

        assertEquals("Perseids", describe(bare))
    }

    @Test
    fun `has something to say about a record with nothing in it`() {
        val empty = Conditions(atMillis = 0L, channel = VerdictChannel.Event)

        assertEquals("Nothing was recorded about it", describe(empty))
    }
}
