package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test
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

    @Test
    fun `the noctilucent channel description assumes no hemisphere`() {
        assertEquals(emptyList<String>(), offences(NOCTILUCENT_CHANNEL_DESCRIPTION))
    }
}
