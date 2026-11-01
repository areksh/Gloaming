package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.notify.NOCTILUCENT_CHANNEL_DESCRIPTION

/**
 * The guide and the channel descriptions must hold wherever the reader is.
 * The app is used on both sides of the equator and in every country, so a
 * sentence that says "low in the north" or "from Britain" is simply wrong for
 * some of the people it is shown to.
 */
class GuideWordsTest {

    private val placeBound = listOf(
        "Britain",
        "British",
        "England",
        "Scotland",
        "UK",
        "low in the north",
        "northern horizon",
        "look north",
    )

    private fun offences(text: String) =
        placeBound.filter { text.contains(it, ignoreCase = true) }

    @Test
    fun `no guide entry assumes where the reader is`() {
        for (entry in Guide.TERMS) {
            assertEquals(entry.term, emptyList<String>(), offences(entry.term + " " + entry.body))
        }
        for (section in Guide.SECTIONS) {
            assertEquals(section.title, emptyList<String>(), offences(section.title + " " + section.blurb))
        }
    }

    /**
     * The sky turns over between hemispheres: Orion stands on his head from
     * Australia, and a pattern overhead in one place is low in another. So a
     * star-hop says what is next to what, never what is above, below, left or
     * right of it, nor where in the sky or in the year it stands.
     */
    @Test
    fun `no star-hop depends on which way up the sky is`() {
        val orientationBound = Regex(
            """\b(up|down)-(left|right)\b|\b(above|below|hanging|backwards|overhead|""" +
                """low in the|high in the|in the (north|south)|summer(?! Triangle)|winter|spring|autumn)\b""",
            RegexOption.IGNORE_CASE,
        )
        for (pattern in Constellations.WATCHED) {
            val found = orientationBound.findAll(pattern.starHop).map { it.value }.toList()
            assertEquals(pattern.name, emptyList<String>(), found)
        }
    }

    @Test
    fun `the noctilucent channel description assumes no hemisphere`() {
        assertEquals(emptyList<String>(), offences(NOCTILUCENT_CHANNEL_DESCRIPTION))
    }
}
