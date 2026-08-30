package sh.arek.gloaming.scenarios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel

/**
 * That every night in the catalogue still shows what it says it shows.
 *
 * This is the test that makes the rest of the catalogue worth anything. A
 * screen test against a scenario that has quietly gone empty passes exactly as
 * happily as one against a full one - the card renders, nothing is asserted
 * about content, and the suite stays green while the app shows nothing. So each
 * scenario declares what it is for, and this proves it: the eclipse is really
 * there, the train really crosses, the gap in the cloud really opens after
 * midnight.
 *
 * It is also the only thing standing between the catalogue and rot. The dates
 * were chosen from a run of the real ephemeris; if a change to the ephemeris,
 * to the visibility rules or to a threshold moves one of them out of range,
 * this says which scenario and which claim, rather than leaving a screenshot
 * that silently means something else.
 */
class ScenariosTest {

    /**
     * Whether a rendered night demonstrates one claim.
     *
     * Kept as one function rather than a test each, so that adding a value to
     * [Shows] is a single decision about what it means and every scenario that
     * claims it is checked the same way.
     */
    private fun demonstrates(
        shows: Shows,
        tonight: TonightList,
        calendar: List<SkyEvent>,
        scenario: Scenario,
    ): Boolean = when (shows) {
        Shows.Events -> tonight.events.isNotEmpty()
        Shows.Caveat -> calendar.any { it.visibility is Visibility.Marginal }
        Shows.UnderWay -> tonight.events.any { it.isUnderWay(scenario.nowMillis) }
        Shows.NotVisibleHere -> calendar.any { it.visibility is Visibility.NotFromHere }
        Shows.Eclipse -> calendar.any {
            it.kind == EventKind.SolarEclipse || it.kind == EventKind.LunarEclipse
        }

        Shows.Occultation -> calendar.any { it.kind == EventKind.Occultation }
        Shows.Conjunction -> calendar.any { it.kind == EventKind.Conjunction }
        Shows.Shower -> calendar.any { it.kind == EventKind.MeteorShower }
        Shows.PlanetEvent -> calendar.any {
            it.kind == EventKind.Opposition ||
                it.kind == EventKind.Elongation ||
                it.kind == EventKind.GreatestBrightness
        }

        Shows.Comets -> tonight.comets.isNotEmpty()
        Shows.Passes -> tonight.passes.isNotEmpty()
        Shows.Train -> tonight.passes.any { it.channel == PassChannel.Trains }
        Shows.Planets -> tonight.planets.isNotEmpty()
        Shows.Constellations -> tonight.constellations.isNotEmpty()
        Shows.ConstellationAtItsBest -> tonight.constellations.any { it.isAtItsBest }
        Shows.Moon -> tonight.moon != null
        // Three tenths of the night's darkness lost. Below that the app says
        // the moon "spoils" some of it and the difference is arithmetic; above
        // it, it is the reason somebody stays in.
        Shows.Moonlight -> (tonight.moon?.interference ?: 0.0) >= 0.3
        Shows.AstronomicalNight -> tonight.darkestFrom != null
        Shows.NoAstronomicalNight -> tonight.darkFrom != null && tonight.darkestFrom == null
        Shows.NoNight -> tonight.darkFrom == null
        Shows.Cloud -> tonight.cloud != null
        Shows.ClearSpell -> tonight.clearSpell != null
        Shows.ClearSpellUnderway -> tonight.clearSpell?.underway == true
        Shows.Noctilucent -> tonight.noctilucent != null
        Shows.Aurora -> scenario.alertState.lastProbability != null
        Shows.PendingVerdict -> Conditions.decode(scenario.alertState.pendingVerdict) != null
        Shows.Nothing -> tonight.isEmpty
        Shows.NoLocation -> scenario.settings.latitude == null
    }

    @Test
    fun `every scenario shows what it claims to show`() {
        val failures = Scenarios.all.flatMap { scenario ->
            val tonight = Scenarios.tonight(scenario)
            val calendar = Scenarios.calendar(scenario)
            scenario.shows
                .filterNot { demonstrates(it, tonight, calendar, scenario) }
                .map { "${scenario.name} no longer shows $it" }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `every scenario is free of what it claims to be free of`() {
        val failures = Scenarios.all.flatMap { scenario ->
            val tonight = Scenarios.tonight(scenario)
            val calendar = Scenarios.calendar(scenario)
            scenario.absent
                .filter { demonstrates(it, tonight, calendar, scenario) }
                .map { "${scenario.name} unexpectedly shows $it" }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Names are how a failure is traced back to a night and how the screen
     * tests select one, so a duplicate would silently shadow a scenario.
     */
    @Test
    fun `scenario names are unique`() {
        val names = Scenarios.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    /** A scenario that claims nothing and forbids nothing is not a test of anything. */
    @Test
    fun `every scenario makes at least one claim`() {
        val silent = Scenarios.all.filter { it.shows.isEmpty() && it.absent.isEmpty() }
        assertTrue(silent.map { it.name }.toString(), silent.isEmpty())
    }

    /**
     * The catalogue exists to cover the app's states, so a state nothing
     * reaches is a gap in it - and one that is invisible unless something
     * counts.
     */
    @Test
    fun `the catalogue reaches every state a card can be in`() {
        val claimed = Scenarios.all.flatMap { it.shows + it.absent }.toSet()
        val unreached = Shows.entries.toSet() - claimed

        assertTrue("nothing in the catalogue reaches $unreached", unreached.isEmpty())
    }

    /**
     * Tonight never lists something that cannot be seen from where the observer
     * is standing, whatever the calendar says about it. The calendar explains;
     * the tonight list is for people who are about to go outside.
     */
    @Test
    fun `no scenario puts an invisible event on tonight's list`() {
        val wrong = Scenarios.all.flatMap { scenario ->
            Scenarios.tonight(scenario).events
                .filter { it.visibility is Visibility.NotFromHere }
                .map { "${scenario.name}: ${it.title}" }
        }

        assertTrue(wrong.toString(), wrong.isEmpty())
    }

    /**
     * A comet on the tonight list is worked out for tonight, and the calendar's
     * own entry for the same comet - which is about the best night of the year
     * - must not appear beside it saying something different.
     */
    @Test
    fun `no scenario lists the same comet twice`() {
        val wrong = Scenarios.all.filter { scenario ->
            Scenarios.tonight(scenario).events.any { it.kind == EventKind.Comet }
        }

        assertTrue(wrong.map { it.name }.toString(), wrong.isEmpty())
    }

    /** Passes are listed in time order, which is how somebody plans an evening. */
    @Test
    fun `passes come out in time order`() {
        Scenarios.all.forEach { scenario ->
            val passes = Scenarios.tonight(scenario).passes
            assertEquals(
                scenario.name,
                passes.map { it.risesAt }.sorted(),
                passes.map { it.risesAt },
            )
        }
    }

    /** A gap in the cloud is inside tonight's darkness or it is not a gap. */
    @Test
    fun `a clear spell falls inside the night it belongs to`() {
        Scenarios.all.forEach { scenario ->
            val tonight = Scenarios.tonight(scenario)
            val spell = tonight.clearSpell ?: return@forEach
            val darkUntil = tonight.darkUntil ?: return@forEach

            assertTrue(scenario.name, spell.until <= darkUntil)
            assertTrue(scenario.name, spell.from < spell.until)
            assertFalse(scenario.name, spell.underway && spell.from > scenario.nowMillis)
        }
    }
}
