package sh.arek.gloaming

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That the vendored source is still byte-identical to what was vendored.
 *
 * `app/src/main/vendor/` is third-party code copied in rather than resolved as
 * a dependency, and the whole arrangement rests on one rule: nothing there is
 * ever edited, so an upgrade is a straight replacement instead of a merge. The
 * rule was written down in the vendor README, with a SHA-256 for every file,
 * and nothing checked it - an accidental edit would have surfaced years later
 * as a conflict during an upgrade, which is the worst moment to discover that
 * the file in the tree is not the file upstream published.
 *
 * The README is the record, so the README is what this reads. Each side is
 * checked against the other: a file whose hash is not written down has been
 * edited or vendored without being recorded, and a hash that belongs to no file
 * is a leftover from a file that has been renamed or removed.
 *
 * It matches hashes as a set rather than pairing each with the row that names
 * it, which is enough for the rule being protected - a set that agrees means no
 * byte in the directory has changed - and leaves the tables free to be laid out
 * however reads best.
 */
class VendorChecksumsTest {

    private fun vendorDirectory(): File =
        listOf("src/main/vendor", "app/src/main/vendor").map(::File).firstOrNull { it.isDirectory }
            ?: error("app/src/main/vendor is not where it should be; run from the project root")

    /** Every vendored file, README aside: it is the record, not part of the record. */
    private fun vendoredFiles(): List<File> =
        vendorDirectory().walkTopDown()
            .filter { it.isFile && it.name != "README.md" }
            .sortedBy { it.path }
            .toList()

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun recordedChecksums(): Set<String> =
        Regex("\\b[0-9a-f]{64}\\b")
            .findAll(File(vendorDirectory(), "README.md").readText())
            .map { it.value }
            .toSet()

    @Test
    fun `every vendored file has the checksum the README records`() {
        val recorded = recordedChecksums()
        val unrecorded = vendoredFiles()
            .filterNot { sha256(it) in recorded }
            .map { "${it.path} is ${sha256(it)}" }

        assertEquals(
            "these vendored files do not match any checksum in the vendor README, so they " +
                "have been edited, or vendored without being recorded. Nothing under vendor/ " +
                "is ever edited: replace the file from upstream, or record it",
            emptyList<String>(),
            unrecorded,
        )
    }

    @Test
    fun `every checksum the README records belongs to a vendored file`() {
        val present = vendoredFiles().map(::sha256).toSet()
        val orphaned = recordedChecksums().filterNot { it in present }

        assertEquals(
            "the vendor README records these checksums and no file under vendor/ has them, " +
                "so a file has been renamed, removed or upgraded without the README following",
            emptyList<String>(),
            orphaned,
        )
    }
}
