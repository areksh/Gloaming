package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which build the phone is actually running.
 *
 * Nothing else can answer that. The app is installed from an APK rather than a
 * store, so there is no listing to look the version up on and no automatic
 * update to assume it is current - a phone can sit on a build for months
 * without anything saying so. "The alert never came" is not a report anybody
 * can act on until it says which build it never came from, and the person
 * making it has only the screen in front of them.
 */
class VersionTest {

    /**
     * The name for a person and the build number for Android. They answer
     * different questions - the name says which release, the number is what the
     * package manager compares to decide an update is an update - and a report
     * quoting only one of them leaves the other to be guessed at.
     */
    @Test
    fun `names the release and the build number behind it`() {
        assertEquals("Gloaming 1.0.0-rc1 (build 1)", versionLine("1.0.0-rc1", 1, debug = false))
    }

    /**
     * A debug build is not the app anybody was given: it is unshrunk, signed
     * with a key that is not the project's, and behaves differently in ways
     * that matter to exactly the sort of question this line gets quoted into.
     * It says so rather than passing for the release of the same name.
     */
    @Test
    fun `says when the build is a debug one`() {
        assertEquals(
            "Gloaming 1.0.0-rc1 (build 1, debug build)",
            versionLine("1.0.0-rc1", 1, debug = true),
        )
    }

    /** Whatever the build declares, verbatim - it is the thing being reported. */
    @Test
    fun `quotes the version it is given rather than a version of its own`() {
        val line = versionLine("2.3.4", 17, debug = false)

        assertTrue(line, line.contains("2.3.4"))
        assertTrue(line, line.contains("17"))
    }
}
