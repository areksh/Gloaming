package sh.arek.gloaming.calendar

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.CalendarSync

/**
 * Against the real calendar provider, which is where the risk actually is.
 *
 * The deciding - what to write, what to remove, what to leave alone - is
 * ordinary arithmetic and is unit-tested without a device. What cannot be
 * checked that way is whether the provider accepts any of it: creating a local
 * calendar needs three account parameters repeated in the URI as well as in the
 * values, and the provider rejects the insert with an exception rather than a
 * null if they are missing. That is a seam, and this is the test for it.
 *
 * Everything written here goes into Gloaming's own local calendar and is
 * removed again afterwards. Nothing touches the device's real calendars.
 */
@RunWith(AndroidJUnit4::class)
class CalendarWriterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val writer = AndroidCalendarWriter(context)

    /** Rows this test created, taken back out however it ends. */
    private val written = mutableListOf<String>()

    /**
     * Granted through the shell rather than through `GrantPermissionRule`.
     *
     * The rule lives in `androidx.test:rules`, which this project does not
     * depend on, and one test is not a reason to take on a dependency the rest
     * of the suite has managed without. `pm grant` does the same job through
     * the instrumentation the suite already has.
     */
    @Before
    fun grantCalendarAccess() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            // Read to the end rather than closed. `executeShellCommand` starts
            // the command and returns; reading to EOF is what waits for it to
            // finish. Closing the descriptor instead let the first test in the
            // class run before the grant had landed, which failed as a write
            // the provider had refused - and passed on every later test.
            ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("pm grant ${context.packageName} $permission"),
            ).use { it.readBytes() }
        }

        assertTrue("calendar permission was not granted", writer.isPermitted())
    }

    @After
    fun removeWhatWasWritten() {
        written.forEach { writer.remove(it) }
    }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun event(
        kind: EventKind = EventKind.LunarEclipse,
        title: String = "Test lunar eclipse",
        from: String = "2028-12-31T01:00:00Z",
        visibility: Visibility = Visibility.Good,
    ) = SkyEvent(
        kind = kind,
        at = at(from),
        from = at(from),
        until = at(from) + 3_600_000L,
        title = title,
        detail = "Totality from 02:11 to 03:24.",
        visibility = visibility,
    )

    private fun write(event: SkyEvent, rowId: String? = null): String? =
        writer.write(event, CalendarSync.OwnCalendar, rowId)?.also {
            if (it !in written) written += it
        }

    /**
     * The one that cannot be unit-tested: the provider has to accept a
     * calendar created with no account behind it, and then accept an event
     * into it.
     */
    @Test
    fun createsItsOwnCalendarAndWritesIntoIt() {
        val rowId = write(event())

        assertNotNull("the provider refused the insert", rowId)
        assertEquals(AndroidCalendarWriter.CALENDAR_NAME, writer.destinationName(CalendarSync.OwnCalendar))
    }

    /** A second event does not create a second calendar. */
    @Test
    fun reusesTheCalendarItAlreadyMade() {
        val first = write(event(title = "First test eclipse"))
        val second = write(event(title = "Second test eclipse", from = "2029-06-26T01:00:00Z"))

        assertNotNull(first)
        assertNotNull(second)
        assertTrue("two events should be two rows", first != second)
    }

    /**
     * Updated in place rather than deleted and recreated, so a reminder the
     * user set on the row survives a comet's dates moving.
     */
    @Test
    fun updatesAnExistingRowRatherThanMakingASecond() {
        val comet = event(kind = EventKind.Comet, title = "Test comet 12P")
        val rowId = write(comet)!!

        val moved = write(comet.copy(from = at("2029-01-07T01:00:00Z")), rowId)

        assertEquals(rowId, moved)
    }

    @Test
    fun removesARowItWrote() {
        val rowId = write(event(title = "Eclipse to be removed"))!!

        writer.remove(rowId)
        written.remove(rowId)

        // Written again to prove the calendar still works after the delete -
        // the provider is quite capable of accepting a delete and then
        // refusing everything afterwards.
        assertNotNull(write(event(title = "Eclipse written after a removal")))
    }

    /** A row id from nowhere is ignored rather than throwing on a background wake. */
    @Test
    fun ignoresARowIdThatIsNotOne() {
        writer.remove("not-a-row")
        writer.remove("999999999")

        assertTrue(writer.isPermitted())
    }

    @Test
    fun writesNothingWhenTheDestinationIsOff() {
        assertNull(writer.write(event(), CalendarSync.Off))
        assertNull(writer.destinationName(CalendarSync.Off))
    }
}
