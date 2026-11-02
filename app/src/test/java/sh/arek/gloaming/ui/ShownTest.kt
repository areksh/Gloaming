package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.domain.SubjectRef
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * Where a notification's tap lands: the sheet of what it was about, a line
 * saying why that cannot be shown, or the calendar for a later night. Never
 * nothing - which is what a tap did for a pass outside Tonight's best.
 */
class ShownTest {

    private val minute = 60_000L
    private val now = 1_793_643_000_000L
    private val latitude = 53.0
    private val longitude = -3.0

    private val ariane = SatellitePass(
        28499, "ARIANE 5 R/B", PassChannel.Bright, now + 5 * minute, 13.0, now + 11 * minute, 75.0, 288.0,
        now + 18 * minute, 202.0, 697.0, true, null, null,
    )

    private val shower = SkyEvent(
        kind = EventKind.MeteorShower,
        at = now + 6 * 60 * minute,
        from = now,
        until = now + 10 * 60 * minute,
        title = "Taurids at their best",
        detail = "A few an hour.",
        visibility = Visibility.Good,
    )

    private val night = TonightList(
        darkFrom = now - 60 * minute,
        darkUntil = now + 11 * 60 * minute,
        passes = listOf(ariane),
        bestPasses = emptyList(),
        events = listOf(shower),
        comets = emptyList(),
        noctilucent = null,
    )

    private fun shown(ref: SubjectRef, at: Long = now, list: TonightList? = night) =
        resolve(ref, list, elements = null, latitude = latitude, longitude = longitude, nowMillis = at)

    @Test
    fun `a pass in tonight's list opens its sheet`() {
        assertEquals(Shown.Sheet(RowSubject.Pass(ariane)), shown(SubjectRef.Pass(28499, ariane.risesAt)))
    }

    /** The poller's search grid lands a little off Tonight's, and fresher elements move it further. */
    @Test
    fun `a pass whose rise moved by minutes still opens its sheet`() {
        assertEquals(Shown.Sheet(RowSubject.Pass(ariane)), shown(SubjectRef.Pass(28499, ariane.risesAt + 5 * minute)))
    }

    @Test
    fun `a pass that has set says when it finished`() {
        assertEquals(
            Shown.Finished("That pass finished at ${clock(ariane.setsAt)}."),
            shown(SubjectRef.Pass(28499, ariane.risesAt), at = ariane.setsAt + minute),
        )
    }

    @Test
    fun `a pass nobody knows says so`() {
        assertEquals(
            Shown.Finished("That pass is no longer in tonight's list."),
            shown(SubjectRef.Pass(99999, ariane.risesAt)),
        )
    }

    @Test
    fun `an event tonight opens its sheet`() {
        assertEquals(Shown.Sheet(RowSubject.Event(shower)), shown(SubjectRef.Event(EventPlanner.starKey(shower))))
    }

    @Test
    fun `an event on a later night opens the calendar`() {
        val key = "Opposition|1800000000000"

        assertEquals(Shown.Later(key), shown(SubjectRef.Event(key)))
    }

    @Test
    fun `nothing worked out yet says so`() {
        assertEquals(Shown.Finished(STILL_WORKING_OUT), shown(SubjectRef.Pass(28499, ariane.risesAt), list = null))
    }
}
