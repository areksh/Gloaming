package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.Constellations

/**
 * The guide, which is the only part of the app whose job is to be understood
 * rather than to be right.
 *
 * Everything else here reports a number. This explains the words those numbers
 * are printed in, and it is worth pinning because the failure mode is silent:
 * a card can go on offering help about a term nobody ever wrote, or a star-hop
 * can send somebody to a landmark the guide has never heard of, and both look
 * perfectly healthy from the outside.
 */
class GuideTest {

    private fun term(name: String) = Guide.TERMS.firstOrNull { it.term == name }

    // --- what the cards ask for ---

    /**
     * The topic map names its terms as strings, so a typo in one is a term
     * that quietly does not appear. [Guide.forTopic] drops what it cannot find
     * rather than throwing - a help button is not worth crashing a screen over
     * - which is exactly why the mistake has to be caught here instead.
     */
    @Test
    fun `every card's help names terms the guide actually holds`() {
        for ((topic, wanted) in Guide.TOPIC_TERMS) {
            val found = Guide.forTopic(topic).map { it.term }
            assertEquals("$topic", wanted, found)
        }
    }

    /** A button that opens an empty sheet is worse than no button. */
    @Test
    fun `every topic explains something`() {
        for (topic in HelpTopic.entries) {
            assertTrue("$topic", Guide.forTopic(topic).isNotEmpty())
        }
    }

    // --- what the sky sends you to ---

    /**
     * The star-hops are the app's oldest piece of teaching and the one that
     * gives out first: "on the far side of Polaris from the Plough" helps
     * nobody who does not already know either. Every landmark a hop names has
     * to be a landmark the guide explains, so a constellation added later
     * cannot quietly reintroduce the problem.
     *
     * Matched on "the Something", which is how a hop names a landmark and is
     * not how it names a star - "orange Betelgeuse", "brilliant Capella" - or
     * a part of the pattern, which is always lower case: "the belt", "the
     * heart", "the tail".
     */
    @Test
    fun `explains every landmark the star-hops send you to`() {
        val landmark = Regex("""\bthe ([A-Z][a-z]+(?: of [A-Z][a-z]+| [A-Z][a-z]+)*)""")

        val sent = Constellations.WATCHED
            .flatMap { landmark.findAll(it.starHop).map { match -> match.groupValues[1] } }
            .toSortedSet()

        assertTrue("no landmarks found - has the regex or the wording changed?", sent.size >= 5)

        for (name in sent) {
            assertNotNull(
                "a star-hop sends you to \"the $name\", which the guide does not explain",
                term("The $name"),
            )
        }
    }

    /**
     * Polaris is the one landmark the hops name without an article, and the
     * one the whole northern sky is signposted from. It is here rather than in
     * the sweep above for that reason alone.
     */
    @Test
    fun `explains Polaris, which the hops name without an article`() {
        assertNotNull(term("Polaris"))
        assertTrue(Constellations.WATCHED.any { it.starHop.contains("Polaris") })
    }

    // --- the guide as a thing to read ---

    /** Two entries under one name is one of them nobody will ever find. */
    @Test
    fun `explains each term once`() {
        val names = Guide.TERMS.map { it.term }

        assertEquals(names.sorted(), names.toSet().sorted())
    }

    /** Every entry is reachable by reading the tab top to bottom. */
    @Test
    fun `puts every term in a section`() {
        assertEquals(Guide.TERMS.size, Guide.SECTIONS.sumOf { it.entries.size })
    }

    /**
     * Thirty-odd entries of hand-written prose is where a smart quote or a
     * degree symbol gets in, and the app writes "degrees" in words everywhere
     * else precisely so it never has to.
     */
    @Test
    fun `stays in plain ASCII, like the rest of the app's writing`() {
        for (entry in Guide.TERMS) {
            val offending = (entry.term + entry.body).filter { it.code > 127 }
            assertEquals("${entry.term} carries \"$offending\"", "", offending)
        }
    }

    /** A heading with nothing under it is a section somebody forgot to fill. */
    @Test
    fun `gives every section something to say`() {
        for (section in Guide.SECTIONS) {
            assertTrue(section.title, section.entries.isNotEmpty())
            assertTrue(section.title, section.blurb.isNotEmpty())
        }
    }
}
