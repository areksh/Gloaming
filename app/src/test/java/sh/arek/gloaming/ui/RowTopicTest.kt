package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * Which words a timeline row offers to explain: the same ones the card it
 * replaced offered behind its Explain button.
 */
class RowTopicTest {

    private fun event(kind: EventKind = EventKind.Conjunction) = SkyEvent(
        kind = kind,
        at = 0L,
        from = 0L,
        until = 3_600_000L,
        title = "The moon passes Mars",
        detail = "",
        visibility = Visibility.Good,
    )

    @Test
    fun `explains darkness on a twilight row`() {
        assertEquals(HelpTopic.Darkness, helpTopic(RowSubject.Twilight))
    }

    @Test
    fun `explains the cloud on the clear spell's rows`() {
        assertEquals(HelpTopic.SkyNow, helpTopic(RowSubject.Clear))
    }

    @Test
    fun `explains moonlight on a moon row`() {
        assertEquals(HelpTopic.Moon, helpTopic(RowSubject.Moon))
    }

    @Test
    fun `explains a pass on a pass row`() {
        val pass = SatellitePass(
            25544, "ISS (ZARYA)", PassChannel.Iss, 0L, 225.0, 240_000L, 68.0, 180.0,
            480_000L, 135.0, 420.0, true, null, null,
        )

        assertEquals(HelpTopic.Satellites, helpTopic(RowSubject.Pass(pass)))
    }

    @Test
    fun `explains magnitude and the rest on a planet row`() {
        val planet = PlanetTonight(SkyBody.Jupiter, 46.0, 0L, 178.0, -2.4, null, null)

        assertEquals(HelpTopic.Planets, helpTopic(RowSubject.Planet(planet)))
    }

    @Test
    fun `explains an event's words on an event row`() {
        assertEquals(HelpTopic.Events, helpTopic(RowSubject.Event(event())))
    }

    @Test
    fun `explains a comet's words on a comet row`() {
        assertEquals(HelpTopic.Comets, helpTopic(RowSubject.Event(event(EventKind.Comet))))
    }
}
