package sh.arek.gloaming

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.Journal
import sh.arek.gloaming.data.PreferencesSettingsStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.credentialPreferences
import sh.arek.gloaming.data.devicePreferences

/**
 * That the journal consults the settings a poll actually runs under.
 *
 * The seam a JVM test cannot reach: which of the two preference stores is
 * authoritative is a property of the Android contexts. It went wrong silently
 * - Notes read the credential-encrypted store, which before first unlock does
 * not merely disagree with the mirror, it throws - so every journal line from
 * a locked poll was swallowed by the catch around it, and the journal was
 * empty for exactly the window it exists to explain.
 *
 * An instrumented test runs unlocked, where both stores are readable, so the
 * throw itself cannot be reproduced here. What can be pinned is the choice of
 * store: with before-unlock alerting on, the device-encrypted mirror is the
 * one the poll obeys, so it must be the one the journal obeys too.
 */
@RunWith(AndroidJUnit4::class)
class NotesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var savedCredential: Map<String, Any?> = emptyMap()
    private var savedDevice: Map<String, Any?> = emptyMap()
    private var savedJournal: String = ""

    private val journalFile: File
        get() = File(
            File(BootAware.deviceContext(context).filesDir, BootAware.CACHE_DIRECTORY),
            Journal.FILE_NAME,
        )

    /** Real stores and a real journal: saved here and handed back afterwards. */
    @Before
    fun saveRealState() {
        savedCredential = credentialPreferences(context).all.toMap()
        savedDevice = devicePreferences(context).all.toMap()
        savedJournal = if (journalFile.exists()) journalFile.readText() else ""
        credentialPreferences(context).edit().clear().commit()
        devicePreferences(context).edit().clear().commit()
    }

    @After
    fun putRealStateBack() {
        restore(credentialPreferences(context), savedCredential)
        restore(devicePreferences(context), savedDevice)
        if (savedJournal.isEmpty()) {
            journalFile.delete()
        } else {
            journalFile.writeText(savedJournal)
        }
    }

    @Test
    fun honoursTheMirrorsRetentionWhenBeforeUnlockAlertingIsOn() {
        // The mirror a locked poll reads says keep no log; the settings the
        // user sees say keep one. The poll's store is the one that counts.
        PreferencesSettingsStore(devicePreferences(context))
            .write(UserSettings(alertsBeforeUnlock = true, logRetentionDays = 0))
        PreferencesSettingsStore(credentialPreferences(context))
            .write(UserSettings(logRetentionDays = 7))
        val marker = "notes-test-${System.nanoTime()}"

        Notes.i(context, "NotesTest", marker)

        assertFalse(
            "the journal obeyed the credential store, not the poll's own",
            Graph.journal(context).read().contains(marker),
        )
    }

    @Test
    fun writesWhenThePollSettingsKeepALog() {
        PreferencesSettingsStore(devicePreferences(context))
            .write(UserSettings(alertsBeforeUnlock = true, logRetentionDays = 7))
        PreferencesSettingsStore(credentialPreferences(context))
            .write(UserSettings(logRetentionDays = 0))
        val marker = "notes-test-${System.nanoTime()}"

        Notes.i(context, "NotesTest", marker)

        assertTrue(
            "nothing was journalled under the settings a poll runs with",
            Graph.journal(context).read().contains(marker),
        )
    }

    private fun restore(preferences: SharedPreferences, saved: Map<String, Any?>) {
        preferences.edit().clear().apply {
            for ((key, value) in saved) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }
}
