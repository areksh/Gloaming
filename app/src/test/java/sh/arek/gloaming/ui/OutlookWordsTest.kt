package sh.arek.gloaming.ui

import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.ClearSpell
import sh.arek.gloaming.domain.DarknessShortfall
import sh.arek.gloaming.domain.Excluded
import sh.arek.gloaming.domain.Gate
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.Outlooks
import sh.arek.gloaming.domain.Pick
import sh.arek.gloaming.domain.PickKind
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * The sentence is the product, so the copy is pinned word for word. The
 * outlooks are built by hand: the decision has its own tests, and these are
 * about what is said of each one.
 */
class OutlookWordsTest {

    private lateinit var zone: TimeZone

    @Before
    fun fixZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val now = at("2026-12-13T19:00:00Z")

    private fun report(
        outlook: Outlook,
        excluded: List<Excluded> = emptyList(),
        aurora: Int? = null,
        threshold: Int = 40,
    ) = OutlookReport(outlook, excluded, aurora, threshold)

    private fun planet(body: SkyBody, peakAt: Long, altitude: Double = 60.0, azimuth: Double = 180.0) =
        PlanetTonight(body, altitude, peakAt, azimuth, 0.5, null, null)

    private fun planetPick(body: SkyBody, time: String, altitude: Double = 60.0) =
        Pick(PickKind.Planet, body.label, at(time), planet = planet(body, at(time), altitude))

    private fun passPick(name: String, time: String) = Pick(
        PickKind.Pass,
        name,
        at(time),
        pass = SatellitePass(
            25544, name, PassChannel.Iss, at(time) - 240_000, 225.0, at(time), 60.0, 180.0,
            at(time) + 240_000, 45.0, 450.0, true, null, null,
        ),
    )

    private fun eventPick(kind: EventKind, title: String, time: String) = Pick(
        PickKind.Event,
        title,
        at(time),
        event = SkyEvent(kind, at(time), at(time), at(time), title, "", Visibility.Good),
    )

    private fun goOut(
        picks: List<Pick>,
        from: String? = null,
        until: String? = null,
        conditional: Boolean = false,
        underway: Boolean = false,
        allNight: Boolean = false,
    ) = Outlook.GoOut(
        picks, from?.let(::at), until?.let(::at), conditional, underway, allNight,
    )

    // ---- headline and follow

    @Test
    fun `no location says nothing`() {
        val r = report(Outlook.NoLocation)

        assertNull(OutlookWords.headline(r))
        assertNull(OutlookWords.follow(r, now))
    }

    @Test
    fun `a clear spell later in the night names when it starts and how long it lasts`() {
        val r = report(
            goOut(
                listOf(planetPick(SkyBody.Saturn, "2026-12-13T23:50:00Z"), passPick("Tiangong", "2026-12-14T00:10:00Z")),
                from = "2026-12-13T23:40:00Z",
                until = "2026-12-14T02:40:00Z",
            ),
            aurora = 12,
        )

        assertEquals(
            "Cloudy until 23:40, then three clear hours. Go out at midnight for Saturn and Tiangong.",
            OutlookWords.headline(r),
        )
        assertEquals("Aurora 12%, well under the 40% that would wake you.", OutlookWords.follow(r, now))
    }

    @Test
    fun `a one hour spell is an hour and an early spell starts from its time`() {
        val r = report(
            goOut(
                listOf(planetPick(SkyBody.Jupiter, "2026-12-13T21:30:00Z")),
                from = "2026-12-13T21:15:00Z",
                until = "2026-12-13T22:15:00Z",
            ),
        )

        assertEquals(
            "Cloudy until 21:15, then an hour of clear sky. Go out from 21:15 for Jupiter.",
            OutlookWords.headline(r),
        )
    }

    @Test
    fun `a spell under way says when it ends`() {
        val r = report(
            goOut(
                listOf(planetPick(SkyBody.Saturn, "2026-12-13T20:00:00Z")),
                from = "2026-12-13T19:00:00Z",
                until = "2026-12-14T01:30:00Z",
                underway = true,
            ),
        )

        assertEquals("Clear until 01:30. Go out at 20:00 for Saturn.", OutlookWords.headline(r))
    }

    @Test
    fun `a night clear throughout says so`() {
        val r = report(
            goOut(
                listOf(planetPick(SkyBody.Saturn, "2026-12-13T20:00:00Z")),
                from = "2026-12-13T19:00:00Z",
                until = "2026-12-14T07:50:00Z",
                underway = true,
                allNight = true,
            ),
        )

        assertEquals("Clear all night. Go out at 20:00 for Saturn.", OutlookWords.headline(r))
    }

    @Test
    fun `without a forecast the sentence is conditional`() {
        val r = report(goOut(listOf(planetPick(SkyBody.Saturn, "2026-12-13T22:20:00Z")), conditional = true))

        assertEquals("If it is clear, go out at 22:20 for Saturn.", OutlookWords.headline(r))
    }

    @Test
    fun `a pick outside the clear hours leaves the clear hours out`() {
        val r = report(goOut(listOf(eventPick(EventKind.SolarEclipse, "Partial eclipse of the sun", "2026-12-14T08:10:00Z"))))

        assertEquals("Go out at 08:10 for the partial eclipse of the sun.", OutlookWords.headline(r))
    }

    @Test
    fun `an occultation reads as the moon passing`() {
        val r = report(
            goOut(
                listOf(eventPick(EventKind.Occultation, "The moon passes in front of Saturn", "2026-12-13T21:00:00Z")),
                conditional = true,
            ),
        )

        assertEquals(
            "If it is clear, go out at 21:00 for the moon passing in front of Saturn.",
            OutlookWords.headline(r),
        )
    }

    @Test
    fun `a shower is named as the shower`() {
        val r = report(
            goOut(
                listOf(eventPick(EventKind.MeteorShower, "Geminids at their best", "2026-12-14T01:00:00Z")),
                conditional = true,
            ),
        )

        assertEquals("If it is clear, go out at 01:00 for the Geminids.", OutlookWords.headline(r))
    }

    @Test
    fun `a moon that hides a shower is the headline and the planet is the follow`() {
        val jupiter = planetPick(SkyBody.Jupiter, "2026-12-13T22:00:00Z", altitude = 62.0)
        val r = report(
            goOut(
                listOf(jupiter),
                from = "2026-12-13T19:00:00Z",
                until = "2026-12-14T07:50:00Z",
                underway = true,
                allNight = true,
            ),
            excluded = listOf(Excluded("Leonids at their best", at("2026-12-14T01:00:00Z"), Gate.Moon, "92")),
        )

        assertEquals(
            "Clear all night, but the moon will hide most of the Leonids: it is 92% lit and up.",
            OutlookWords.headline(r),
        )
        assertEquals(
            "Jupiter is still worth a look, high in the south around 22:00.",
            OutlookWords.follow(r, now),
        )
    }

    @Test
    fun `a planet judged away from its peak gets no direction`() {
        val clamped = Pick(
            PickKind.Planet, "Jupiter", at("2026-12-13T22:00:00Z"),
            planet = planet(SkyBody.Jupiter, at("2026-12-13T14:00:00Z")),
        )
        val r = report(
            goOut(listOf(clamped), conditional = true),
            excluded = listOf(Excluded("Leonids at their best", at("2026-12-14T01:00:00Z"), Gate.Moon, "92")),
        )

        assertEquals("Jupiter is still worth a look.", OutlookWords.follow(r, now))
    }

    @Test
    fun `an aurora held back by the moon is not blamed on a shower`() {
        val r = report(
            goOut(listOf(planetPick(SkyBody.Saturn, "2026-12-13T20:00:00Z")), conditional = true),
            excluded = listOf(Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Moon, "92")),
        )

        assertEquals("If it is clear, go out at 20:00 for Saturn.", OutlookWords.headline(r))
    }

    @Test
    fun `aurora now names the probability and how long it stays clear`() {
        val r = report(Outlook.AuroraNow(65, at("2026-12-14T01:30:00Z"), conditional = false))

        assertEquals(
            "Aurora likely now: 65% here. Look north, low down, while it stays clear until 01:30.",
            OutlookWords.headline(r),
        )
        assertEquals(
            "It can fade within the hour. Watch tonight keeps checking every 2 minutes.",
            OutlookWords.follow(r, now),
        )
    }

    @Test
    fun `aurora now without a forecast is conditional`() {
        val r = report(Outlook.AuroraNow(65, null, conditional = true))

        assertEquals(
            "If it is clear, aurora is likely now: 65% here. Look north, low down.",
            OutlookWords.headline(r),
        )
    }

    @Test
    fun `overcast names what the cloud cost and when it clears`() {
        val missed = eventPick(EventKind.MeteorShower, "Geminids at their best", "2026-12-14T01:00:00Z")
        val r = report(
            Outlook.Overcast(
                ClearSpell(at("2026-12-14T21:00:00Z"), at("2026-12-15T02:00:00Z"), underway = false, allNight = false),
                missed,
            ),
        )

        assertEquals("Overcast all night. Nothing worth going out for.", OutlookWords.headline(r))
        assertEquals(
            "The Geminids peak tonight. The next clear spell in the forecast is tomorrow from 21:00.",
            OutlookWords.follow(r, now),
        )
    }

    @Test
    fun `overcast does not claim a peak that was judged at some other moment`() {
        val shower = eventPick(EventKind.MeteorShower, "Geminids at their best", "2026-12-14T01:00:00Z")
        val moved = shower.copy(atMillis = at("2026-12-14T03:00:00Z"))
        val r = report(Outlook.Overcast(null, moved))

        assertEquals("Cloud will hide the Geminids tonight.", OutlookWords.follow(r, now))
    }

    @Test
    fun `overcast names a distant clear spell by its day`() {
        val r = report(
            Outlook.Overcast(
                ClearSpell(at("2026-12-16T21:00:00Z"), at("2026-12-17T02:00:00Z"), underway = false, allNight = false),
                null,
            ),
        )

        assertEquals(
            "The next clear spell in the forecast is on Wednesday from 21:00.",
            OutlookWords.follow(r, now),
        )
    }

    @Test
    fun `overcast with no spell in the forecast says so`() {
        val r = report(Outlook.Overcast(null, null))

        assertEquals("The forecast has no clear spell yet.", OutlookWords.follow(r, now))
    }

    @Test
    fun `a quiet night suggests something to learn`() {
        val r = report(Outlook.Quiet(at("2026-12-13T18:45:00Z"), "the Plough", conditional = false))

        assertEquals(
            "Clear and dark from 18:45, with nothing special on. A good night to learn the Plough.",
            OutlookWords.headline(r),
        )
        assertNull(OutlookWords.follow(r, now))
    }

    @Test
    fun `a quiet night without a forecast does not claim it is clear`() {
        val r = report(Outlook.Quiet(at("2026-12-13T18:45:00Z"), null, conditional = true))

        assertEquals("Dark from 18:45, with nothing special on.", OutlookWords.headline(r))
    }

    @Test
    fun `a quiet night with the moon hiding a shower says so`() {
        val r = report(
            Outlook.Quiet(at("2026-12-13T18:45:00Z"), null, conditional = false),
            excluded = listOf(Excluded("Leonids at their best", at("2026-12-14T01:00:00Z"), Gate.Moon, "92")),
            aurora = 12,
        )

        assertEquals(
            "Clear and dark from 18:45, but the moon will hide most of the Leonids: it is 92% lit and up.",
            OutlookWords.headline(r),
        )
        assertEquals("Aurora 12%, well under the 40% that would wake you.", OutlookWords.follow(r, now))
    }

    @Test
    fun `no night says only the moon and bright planets will show`() {
        val r = report(Outlook.NoNight(noctilucent = false))

        assertEquals(
            "It does not get properly dark tonight. Only the moon and the brightest planets will show.",
            OutlookWords.headline(r),
        )
        assertNull(OutlookWords.follow(r, now))
    }

    @Test
    fun `no night with noctilucent cloud possible mentions it`() {
        val r = report(Outlook.NoNight(noctilucent = true))

        assertEquals(
            "Noctilucent clouds are possible low in the north tonight.",
            OutlookWords.follow(r, now),
        )
    }

    @Test
    fun `without a forecast it will not guess`() {
        val r = report(Outlook.CannotTell(forecastHeld = false, elementsStale = false))

        assertEquals(
            "Gloaming cannot say what tonight holds: it has no recent forecast.",
            OutlookWords.headline(r),
        )
        assertEquals("Check now to fetch one.", OutlookWords.follow(r, now))
    }

    @Test
    fun `old satellite data alone says that, and advises fresh data`() {
        val r = report(Outlook.CannotTell(forecastHeld = true, elementsStale = true))

        assertEquals(
            "Gloaming cannot say when satellites pass: its satellite data is too old to trust.",
            OutlookWords.headline(r),
        )
        assertEquals("Check now to fetch fresh data.", OutlookWords.follow(r, now))
    }

    @Test
    fun `no forecast and old satellite data says both and advises fetching both`() {
        val r = report(Outlook.CannotTell(forecastHeld = false, elementsStale = true))

        assertEquals(
            "Gloaming cannot say what tonight holds: it has no recent forecast, " +
                "and its satellite data is too old to trust.",
            OutlookWords.headline(r),
        )
        assertEquals("Check now to fetch both.", OutlookWords.follow(r, now))
    }

    @Test
    fun `nothing missing still cannot tell, without blaming anything`() {
        val r = report(Outlook.CannotTell(forecastHeld = true, elementsStale = false))

        assertEquals("Gloaming cannot say what tonight holds.", OutlookWords.headline(r))
        assertEquals("Check now to refresh.", OutlookWords.follow(r, now))
    }

    @Test
    fun `two picks far apart in time each get their own`() {
        val r = report(
            goOut(
                listOf(planetPick(SkyBody.Saturn, "2026-12-13T23:00:00Z"), passPick("Tiangong", "2026-12-14T00:52:00Z")),
                conditional = true,
            ),
        )

        assertEquals(
            "If it is clear, go out at 23:00 for Saturn, and at 00:52 for Tiangong.",
            OutlookWords.headline(r),
        )
    }

    @Test
    fun `two picks within a quarter of an hour share one time`() {
        val r = report(
            goOut(
                listOf(planetPick(SkyBody.Saturn, "2026-12-13T22:00:00Z"), passPick("Tiangong", "2026-12-13T22:15:00Z")),
                conditional = true,
            ),
        )

        assertEquals("If it is clear, go out at 22:00 for Saturn and Tiangong.", OutlookWords.headline(r))
    }

    @Test
    fun `a name with its own and is not run into the next`() {
        val r = report(
            goOut(
                listOf(
                    eventPick(EventKind.Conjunction, "Venus and Jupiter at their closest", "2026-12-13T22:00:00Z"),
                    planetPick(SkyBody.Saturn, "2026-12-13T22:05:00Z"),
                ),
                conditional = true,
            ),
        )

        assertEquals(
            "If it is clear, go out at 22:00 for Venus and Jupiter at their closest, and Saturn.",
            OutlookWords.headline(r),
        )
    }

    @Test
    fun `a moon conjunction reads as a noun phrase everywhere`() {
        val moonVenus = eventPick(EventKind.Conjunction, "The moon passes Venus", "2026-12-13T22:00:00Z")

        assertEquals(
            "If it is clear, go out at 22:00 for the moon passing Venus.",
            OutlookWords.headline(report(goOut(listOf(moonVenus), conditional = true))),
        )
        assertEquals(
            "Cloud will hide the moon passing Venus tonight.",
            OutlookWords.follow(report(Outlook.Overcast(null, moonVenus)), now),
        )
        assertEquals(
            "The moon passing Venus is still worth a look.",
            OutlookWords.follow(
                report(
                    goOut(listOf(moonVenus), conditional = true),
                    excluded = listOf(Excluded("Leonids at their best", 0L, Gate.Moon, "92")),
                ),
                now,
            ),
        )
    }

    @Test
    fun `a planet conjunction keeps its planet's capital`() {
        val pair = eventPick(EventKind.Conjunction, "Venus and Jupiter at their closest", "2026-12-13T22:00:00Z")

        assertEquals(
            "If it is clear, go out at 22:00 for Venus and Jupiter at their closest.",
            OutlookWords.headline(report(goOut(listOf(pair), conditional = true))),
        )
    }

    @Test
    fun `a spell's hours are counted down`() {
        val r = report(
            goOut(
                listOf(planetPick(SkyBody.Saturn, "2026-12-13T21:30:00Z")),
                from = "2026-12-13T21:00:00Z",
                until = "2026-12-13T23:30:00Z",
            ),
        )

        assertEquals(
            "Cloudy until 21:00, then two clear hours. Go out from 21:00 for Saturn.",
            OutlookWords.headline(r),
        )
    }

    // ---- excluded

    private fun excluded(
        gate: Gate,
        note: String? = null,
        darkness: DarknessShortfall? = null,
        kind: PickKind? = null,
        atMillis: Long? = 0L,
    ) = OutlookWords.excluded(Excluded("x", atMillis, gate, note, darkness, kind), 40, now)

    @Test
    fun `each gate has its own reason`() {
        assertEquals("Forecast 80% cloud when it crosses.", excluded(Gate.Cloud, "80"))
        assertEquals("The moon is up and 70% lit, which washes it out.", excluded(Gate.Moon, "70"))
        assertEquals("In the earth's shadow, so it will not show.", excluded(Gate.Sunlit))
        assertEquals("Satellite times are days old, so they cannot be trusted.", excluded(Gate.Stale))
        assertEquals("The radiant is low.", excluded(Gate.Visibility, "The radiant is low."))
    }

    @Test
    fun `time says already over only for something that has gone`() {
        assertEquals("Already over.", excluded(Gate.Time, atMillis = now - 1))
        assertEquals("Not during tonight's darkness.", excluded(Gate.Time, atMillis = now + 1))
        assertEquals("Not during tonight's darkness.", excluded(Gate.Time, atMillis = null))
    }

    @Test
    fun `a cloud exclusion with no forecast hour says there is none`() {
        assertEquals(
            "No forecast for that hour, so it cannot be counted on.",
            excluded(Gate.Cloud, null),
        )
    }

    @Test
    fun `darkness says which way it fell short`() {
        assertEquals("It never gets dark enough tonight.", excluded(Gate.Darkness, darkness = DarknessShortfall.NeverDark))
        assertEquals("Only in twilight, which washes it out.", excluded(Gate.Darkness, darkness = DarknessShortfall.Twilight))
        assertEquals("It is not dark enough yet.", excluded(Gate.Darkness, darkness = DarknessShortfall.NotYet))
    }

    @Test
    fun `height says at best for a pass and by then for a planet`() {
        assertEquals("Only 14 degrees up at best.", excluded(Gate.Height, "14", kind = PickKind.Pass))
        assertEquals("Only 4 degrees up by then.", excluded(Gate.Height, "4", kind = PickKind.Planet))
    }

    @Test
    fun `a weak aurora gives its figure`() {
        val e = Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Aurora, "12")

        assertEquals("12%, too weak to see from here.", OutlookWords.excluded(e, 40, now))
    }

    @Test
    fun `an aurora above the threshold that was held back is an old reading`() {
        val e = Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Aurora, "65")

        assertEquals("65%, but the reading is too old to act on.", OutlookWords.excluded(e, 40, now))
    }

    // ---- alerts

    @Test
    fun `the alert is the aurora headline`() {
        assertEquals(
            "Aurora likely now: 65% here. Look north, low down, while it stays clear until 01:30.",
            OutlookWords.auroraAlert(65, at("2026-12-14T01:30:00Z")),
        )
        assertEquals("Aurora likely now: 65% here. Look north, low down.", OutlookWords.auroraAlert(65, null))
    }
}
