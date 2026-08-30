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
     * The name for a person, the build number for Android, the commit for
     * whoever has to reproduce it. They answer three different questions - the
     * name says which release, the number is what the package manager compares
     * to decide an update is an update, and the commit is the only one of the
     * three that identifies the source, since a version name does not change
     * between releases and every test build in a month carries the same one.
     */
    @Test
    fun `names the release, the build number and the commit behind it`() {
        assertEquals(
            "Gloaming 1.0.0-rc1 (build 1, e4898e3)",
            versionLine("1.0.0-rc1", 1, "e4898e3", modified = false, debug = false),
        )
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
            "Gloaming 1.0.0-rc1 (build 1, e4898e3, debug build)",
            versionLine("1.0.0-rc1", 1, "e4898e3", modified = false, debug = true),
        )
    }

    /**
     * A build from a tree with uncommitted changes in it corresponds to no
     * commit at all: nobody can rebuild it, including whoever built it, and the
     * commit named beside it is the one it is *not*. Saying so is the whole
     * value of carrying the commit, and it is the usual state of a build during
     * development, which is when this line is read most.
     */
    @Test
    fun `says when the tree had uncommitted changes in it`() {
        assertEquals(
            "Gloaming 1.0.0-rc1 (build 1, e4898e3 with changes, debug build)",
            versionLine("1.0.0-rc1", 1, "e4898e3", modified = true, debug = true),
        )
    }

    /**
     * A build made outside a checkout - from a source archive, or where git is
     * not installed - knows no commit, and says nothing rather than inventing a
     * word for it. "with changes" against no commit would be a claim about a
     * tree nothing was compared to.
     */
    @Test
    fun `leaves the commit out when the build knew of none`() {
        assertEquals(
            "Gloaming 1.0.0-rc1 (build 1)",
            versionLine("1.0.0-rc1", 1, "", modified = false, debug = false),
        )
        assertEquals(
            "Gloaming 1.0.0-rc1 (build 1)",
            versionLine("1.0.0-rc1", 1, "", modified = true, debug = false),
        )
    }

    /** Whatever the build declares, verbatim - it is the thing being reported. */
    @Test
    fun `quotes the version it is given rather than a version of its own`() {
        val line = versionLine("2.3.4", 17, "abc1234", modified = false, debug = false)

        assertTrue(line, line.contains("2.3.4"))
        assertTrue(line, line.contains("17"))
        assertTrue(line, line.contains("abc1234"))
    }
}
