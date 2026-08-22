package sh.arek.gloaming.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who the app owes credit to, and on what terms.
 *
 * These are obligations rather than courtesies. AuroraWatch UK's conditions
 * require attribution and forbid the word "official"; Open-Meteo ships under
 * CC BY 4.0; the vendored astronomy code is MIT, which requires its notice to
 * travel with every copy of the software - and an APK is a copy. Getting any of
 * them wrong is a licence breach, not a presentation bug, which is why the
 * wording is pinned here rather than left to whoever next edits the screen.
 */
class CreditsTest {

    private fun named(name: String, fromAuroraWatch: Boolean = false) =
        credits(fromAuroraWatch).firstOrNull { it.name == name }

    /**
     * Their terms tie the credit to the data. A global-grid reading is not
     * theirs, and crediting them for it would misattribute a number they had no
     * part in - which is its own kind of dishonesty, and the reverse of what
     * the requirement is for.
     */
    @Test
    fun `leaves AuroraWatch out unless their reading is the one on screen`() {
        assertEquals(null, named("AuroraWatch UK", fromAuroraWatch = false))
        assertNotNull(named("AuroraWatch UK", fromAuroraWatch = true))
    }

    /** Their sentence, not a paraphrase of it. */
    @Test
    fun `carries the AuroraWatch attribution word for word`() {
        val credit = named("AuroraWatch UK", fromAuroraWatch = true)!!

        assertEquals(
            "AuroraWatch UK alerts data is provided by Lancaster University, UK using " +
                "data from the SAMNET and/or AuroraWatchNet magnetometer networks.",
            credit.body,
        )
    }

    /**
     * Both the university and the alerts themselves have to be reachable from
     * the credit. The two magnetometer networks are named in the sentence but
     * not linked: the pages the API still advertises for them are gone, and a
     * link to a 404 credits nobody.
     */
    @Test
    fun `links AuroraWatch UK and Lancaster University`() {
        val links = named("AuroraWatch UK", fromAuroraWatch = true)!!.links

        assertEquals(
            listOf("AuroraWatch UK", "Lancaster University"),
            links.map { it.label },
        )
        assertTrue(links.all { it.url.startsWith("https://") })
    }

    /**
     * "Must not be called official" is one of their conditions, and it is the
     * kind of word that creeps into UI copy meaning nothing more than
     * "authoritative".
     */
    @Test
    fun `never describes anything as official`() {
        val everything = (credits(true) + credits(false))
            .joinToString(" ") { it.name + " " + it.body }

        assertFalse(everything.lowercase().contains("official"))
    }

    /** CC BY 4.0 wants the credit whether or not the cloud gate is on today. */
    @Test
    fun `credits Open-Meteo and NOAA whichever reading is on screen`() {
        for (fromAuroraWatch in listOf(true, false)) {
            assertNotNull(named("Open-Meteo", fromAuroraWatch))
            assertNotNull(named("NOAA Space Weather Prediction Center", fromAuroraWatch))
        }
    }

    /**
     * MIT requires the permission notice in all copies, so it has to be in the
     * APK - not merely in the repository beside the vendored source, which
     * nobody who installs the app ever sees.
     */
    @Test
    fun `ships the whole MIT notice for the vendored astronomy code`() {
        val credit = named("astronomy-engine")!!

        val licence = credit.licence!!
        assertTrue(licence.contains("Copyright (c) 2019-2023 Don Cross"))
        // The notice is wrapped as upstream wraps it, so match within a line.
        assertTrue(licence.contains("shall be included in all"))
    }

    /** Their usage policy asks for credit, and the elements are now in use. */
    @Test
    fun `credits CelesTrak for the orbital elements`() {
        val credit = named("CelesTrak")!!

        assertTrue(credit.links.any { it.url.startsWith("https://celestrak.org") })
    }

    /**
     * GPL-3 to GPL-3 vendoring needs no permission, only compliance: keep the
     * copyright notices and licence headers in the files, and credit the
     * project. Unlike MIT it does not require its text in every copy - it
     * requires the corresponding source, which the repository link provides.
     */
    @Test
    fun `credits Look4Sat for the vendored propagator`() {
        val credit = named("Look4Sat")!!

        assertTrue(credit.body.contains("GPL-3"))
        assertEquals(null, credit.licence)
        assertTrue(credit.links.any { it.url == "https://github.com/rt-bishop/Look4Sat" })
    }

    /**
     * The vendored file is replaced wholesale on an upgrade, and its licence
     * could change with it. Comparing the two catches a notice that has quietly
     * stopped describing the code it covers.
     */
    @Test
    fun `the shipped notice matches the vendored licence file`() {
        val vendored = listOf(
            "src/main/vendor/io/github/cosinekitty/astronomy/LICENSE",
            "app/src/main/vendor/io/github/cosinekitty/astronomy/LICENSE",
        ).map(::File).first { it.isFile }

        assertEquals(vendored.readText().trim(), named("astronomy-engine")!!.licence!!.trim())
    }
}
