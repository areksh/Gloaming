package sh.arek.gloaming

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the user's position is not copied off the phone by the backup system.
 *
 * The whole storage design rests on one promise: the precise position stays in
 * credential-encrypted storage and the weaker device-encrypted mirror only ever
 * holds a rounded grid cell. Android's backup system goes around that entirely
 * - it copies application data wholesale - and it is on by default with
 * include-everything semantics when no rules are given. On GrapheneOS this is
 * not hypothetical: Seedvault honours these declarations.
 *
 * Cloud backup and device-to-device transfer are separate switches, and turning
 * off the first does not turn off the second. Both are checked from the source
 * files, so the promise cannot quietly lapse in a manifest edit.
 */
class BackupRulesTest {

    private fun source(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.isFile }
            ?: error("none of ${candidates.toList()} exists; run from the project root")

    private fun manifest() =
        source("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()

    private fun extractionRules() = source(
        "src/main/res/xml/data_extraction_rules.xml",
        "app/src/main/res/xml/data_extraction_rules.xml",
    ).readText()

    @Test
    fun `backup is switched off`() {
        assertTrue(
            "android:allowBackup must be false; the settings hold a precise position",
            manifest().contains("android:allowBackup=\"false\""),
        )
    }

    /**
     * `allowBackup="false"` does not cover this. Android's own documentation is
     * explicit that device-to-device transfer still runs for an app that has
     * opted out of cloud backup, so it needs its own exclusion.
     */
    @Test
    fun `both cloud backup and device transfer exclude everything`() {
        val rules = extractionRules()

        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue("<$section> is missing from the extraction rules", body != rules)
            for (domain in DOMAINS) {
                assertTrue(
                    "<$section> does not exclude the $domain domain",
                    body.contains("domain=\"$domain\""),
                )
            }
        }
    }

    private companion object {
        /** Every domain the backup system can reach, both stores. */
        val DOMAINS = listOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
    }
}
