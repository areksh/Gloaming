package sh.arek.gloaming.scenarios

import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.VerdictChannel
import sh.arek.gloaming.scenarios.Fixtures.cometsDocument
import sh.arek.gloaming.scenarios.Fixtures.haleBopp
import sh.arek.gloaming.scenarios.Fixtures.hourly
import sh.arek.gloaming.scenarios.Fixtures.objectInOrbit
import sh.arek.gloaming.scenarios.Fixtures.satellites
import sh.arek.gloaming.scenarios.Fixtures.stationElements
import sh.arek.gloaming.scenarios.Fixtures.train

/**
 * The catalogue: every night the app can be looked at on.
 *
 * Grouped by what each group is about rather than by date, because that is how
 * somebody looking for a state to reproduce will search it. Within a group the
 * order is the order the cases are worth reading in - the ordinary one first,
 * then the awkward ones.
 *
 * **Every date here was found by running the shipped calendar, not by
 * remembering one.** The eclipse dates, the occultation dates, the conjunction
 * separations and the moon phases all came out of [sh.arek.gloaming.astronomy.SkyCalendar]
 * over a decade of sky; what is written below is which of its answers were
 * interesting. So a scenario cannot flatter the code that produced it, and if
 * the ephemeris ever disagrees with these dates the suite says so.
 */
internal object Nights {

    private fun at(place: Place) =
        UserSettings(latitude = place.latitude, longitude = place.longitude)

    // ---------------------------------------------------------------- eclipses

    private val eclipses = listOf(
        Scenario(
            name = "total-lunar-eclipse",
            about = "The total lunar eclipse of 20 December 2029, high over Edinburgh " +
                "on the longest nights of the year",
            shows = setOf(
                Shows.Events,
                Shows.Eclipse,
                Shows.Moon,
                Shows.Moonlight,
                Shows.AstronomicalNight,
            ),
            absent = setOf(Shows.UnderWay),
            nowMillis = at("2029-12-20T20:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "an-eclipse-already-under-way",
            about = "The same eclipse forty minutes from totality, with the app " +
                "opened while it is going on rather than before it starts",
            shows = setOf(Shows.Events, Shows.Eclipse, Shows.UnderWay, Shows.Moon),
            nowMillis = at("2029-12-20T22:30:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "partial-lunar-eclipse-low-in-twilight",
            about = "The partial eclipse of 28 August 2026: 97 per cent covered, but " +
                "only 7 degrees up and in a sky that never gets properly dark",
            shows = setOf(Shows.Events, Shows.Eclipse, Shows.Caveat),
            nowMillis = at("2026-08-28T01:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "lunar-eclipse-below-the-horizon",
            about = "The total eclipse of 3 March 2026, which from Edinburgh happens " +
                "entirely under the horizon: on the calendar saying why, off tonight's list",
            shows = setOf(Shows.Eclipse, Shows.NotVisibleHere),
            absent = setOf(Shows.Events),
            nowMillis = at("2026-03-03T09:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "deep-partial-solar-eclipse",
            about = "12 August 2026 from Edinburgh: 91 per cent of the sun covered, " +
                "the deepest partial these islands get for a generation",
            shows = setOf(Shows.Eclipse),
            nowMillis = at("2026-08-12T09:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "total-solar-eclipse",
            about = "The same eclipse from Reykjavik, where it is total",
            shows = setOf(Shows.Eclipse),
            nowMillis = at("2026-08-12T09:00:00Z"),
            settings = at(Places.reykjavik),
        ),
        Scenario(
            name = "total-solar-eclipse-in-the-desert",
            about = "2 August 2027 over Luxor: the long totality, with the sun " +
                "near the top of the sky",
            shows = setOf(Shows.Eclipse),
            nowMillis = at("2027-08-02T04:00:00Z"),
            settings = at(Places.luxor),
        ),
        Scenario(
            name = "total-solar-eclipse-in-the-south",
            about = "22 July 2028 over Sydney, a total eclipse from a southern city",
            shows = setOf(Shows.Eclipse),
            nowMillis = at("2028-07-22T00:00:00Z"),
            settings = at(Places.sydney),
        ),
        Scenario(
            name = "solar-eclipse-that-the-sunset-interrupts",
            about = "26 January 2028: the sun sets over Edinburgh part-way through, " +
                "so only the first half of it happens in the sky",
            shows = setOf(Shows.Eclipse, Shows.Caveat),
            nowMillis = at("2028-01-26T12:00:00Z"),
            settings = at(Places.edinburgh),
        ),
    )

    // ------------------------------------------- occultations and conjunctions

    private val pairs = listOf(
        Scenario(
            name = "occultation-of-jupiter",
            about = "19 March 2027 over Sydney: Jupiter goes out at the moon's edge " +
                "and comes back an hour later, 30 degrees up",
            shows = setOf(Shows.Events, Shows.Occultation, Shows.Moon),
            nowMillis = at("2027-03-19T08:30:00Z"),
            settings = at(Places.sydney),
        ),
        Scenario(
            name = "occultation-of-venus-in-a-low-sky",
            about = "10 January 2032 from Edinburgh: the same event, but with the " +
                "pair only 6 degrees up and needing a clear horizon",
            shows = setOf(Shows.Events, Shows.Occultation, Shows.Caveat),
            nowMillis = at("2032-01-10T05:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "the-moon-passes-mars",
            about = "5 October 2026: 0.6 degrees apart - about a moon's width - and " +
                "47 degrees up in the south-east",
            shows = setOf(Shows.Events, Shows.Conjunction, Shows.Moon),
            nowMillis = at("2026-10-05T03:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "venus-and-jupiter-together",
            about = "10 November 2028: the two brightest planets 0.7 degrees apart " +
                "in the morning sky",
            shows = setOf(Shows.Events, Shows.Conjunction),
            nowMillis = at("2028-11-09T22:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "a-close-pair-too-low-to-watch",
            about = "24 November 2027: Venus and Mars closer still, at 0.4 degrees, " +
                "and never more than 3 degrees above a Scottish horizon",
            shows = setOf(Shows.Conjunction, Shows.Caveat),
            nowMillis = at("2027-11-24T15:00:00Z"),
            settings = at(Places.edinburgh),
        ),
    )

    // ------------------------------------------------------------------ showers

    private val showers = listOf(
        Scenario(
            name = "geminids-under-a-dark-sky",
            about = "14 December 2026: the best shower of the year, at its peak, " +
                "with no moon to spoil it",
            shows = setOf(Shows.Events, Shows.Shower, Shows.AstronomicalNight),
            nowMillis = at("2026-12-14T00:30:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "perseids-washed-out-by-the-moon",
            about = "12 August 2028: the summer shower everybody knows, under a " +
                "moon 63 per cent lit and up most of the night",
            shows = setOf(
                Shows.Events,
                Shows.Shower,
                Shows.Caveat,
                Shows.Moon,
                Shows.Moonlight,
            ),
            nowMillis = at("2028-08-12T02:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "quadrantids-under-a-full-moon",
            about = "4 January 2026: a hundred an hour on paper, and a full moon " +
                "up all night to hide them",
            shows = setOf(Shows.Shower, Shows.Caveat, Shows.Moon, Shows.Moonlight),
            nowMillis = at("2026-01-02T20:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "a-shower-whose-radiant-stays-low",
            about = "The eta Aquariids of May 2026, whose radiant never clears 12 " +
                "degrees from Scotland so most of the shower happens out of sight",
            shows = setOf(Shows.Shower, Shows.Caveat),
            nowMillis = at("2026-05-05T18:00:00Z"),
            settings = at(Places.edinburgh),
        ),
    )

    // ------------------------------------------------------------------ planets

    private val planets = listOf(
        Scenario(
            name = "jupiter-at-opposition",
            about = "January 2026: Jupiter opposite the sun, up all night and as " +
                "bright as it gets this time round",
            shows = setOf(Shows.Events, Shows.PlanetEvent, Shows.Planets),
            nowMillis = at("2026-01-09T20:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "mars-at-opposition",
            about = "February 2027: Mars at magnitude -1.2, the once-in-two-years night",
            shows = setOf(Shows.Events, Shows.PlanetEvent, Shows.Planets),
            nowMillis = at("2027-02-18T20:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "venus-at-its-brightest",
            about = "November 2026: magnitude -4.9, bright enough to cast a shadow",
            shows = setOf(Shows.PlanetEvent, Shows.Planets),
            nowMillis = at("2026-11-26T18:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "mercury-at-its-furthest-from-the-sun",
            about = "November 2026: the one apparition of the year Mercury gets 9 " +
                "degrees up from Scotland, which still needs a clear horizon",
            shows = setOf(Shows.PlanetEvent, Shows.Caveat),
            nowMillis = at("2026-11-20T06:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "mercury-that-never-clears-the-horizon",
            about = "October 2026: a greatest elongation that from this latitude " +
                "happens entirely in daylight, and says so rather than vanishing",
            shows = setOf(Shows.PlanetEvent, Shows.NotVisibleHere),
            nowMillis = at("2026-10-11T20:00:00Z"),
            settings = at(Places.edinburgh),
        ),
    )

    // ------------------------------------------------------------------- comets

    private val comets = listOf(
        Scenario(
            name = "a-great-comet-under-a-new-moon",
            about = "Hale-Bopp on 6 April 1997, days after perihelion, with the " +
                "moon out of the way - the best comet night in living memory",
            shows = setOf(Shows.Comets),
            nowMillis = at("1997-04-06T21:00:00Z"),
            settings = at(Places.edinburgh),
            comets = cometsDocument(haleBopp()),
        ),
        Scenario(
            name = "a-comet-under-a-full-moon",
            about = "The same comet a fortnight earlier, under a full moon: still " +
                "there, and the app has to say what that costs",
            shows = setOf(Shows.Comets, Shows.Moon, Shows.Moonlight),
            nowMillis = at("1997-03-23T22:00:00Z"),
            settings = at(Places.edinburgh),
            comets = cometsDocument(haleBopp()),
        ),
        Scenario(
            name = "a-comet-too-faint-to-mention",
            about = "A comet four magnitudes fainter than Hale-Bopp, against the " +
                "default threshold: held, judged, and left off the screen",
            shows = emptySet(),
            absent = setOf(Shows.Comets),
            nowMillis = at("1997-04-06T21:00:00Z"),
            settings = at(Places.edinburgh),
            comets = cometsDocument(haleBopp(magnitudeH = 12.0)),
        ),
        Scenario(
            name = "a-comet-nobody-has-measured",
            about = "The same comet with no magnitude published at all, which is " +
                "the ordinary case for a new discovery",
            shows = emptySet(),
            absent = setOf(Shows.Comets),
            nowMillis = at("1997-04-06T21:00:00Z"),
            settings = at(Places.edinburgh),
            comets = cometsDocument(haleBopp(magnitudeH = null, slope = null)),
        ),
        Scenario(
            name = "two-comets-at-once",
            about = "Two bright comets up the same night, which has happened and " +
                "which the card has to lay out rather than assume away",
            shows = setOf(Shows.Comets),
            nowMillis = at("1997-04-06T21:00:00Z"),
            settings = at(Places.edinburgh),
            comets = cometsDocument(
                haleBopp(),
                haleBopp(magnitudeH = 1.0).copy(
                    key = "J96B020",
                    name = "C/1996 B2 (Hyakutake)",
                    node = 250.0,
                ),
            ),
        ),
    )

    // --------------------------------------------------------------- satellites

    /**
     * Dated to November 2021, and not for nostalgia: these are the station's
     * real elements at their real epoch, and SGP4 is only worth trusting near
     * one. A satellite night has to be dated to when its elements were true.
     */
    private val satelliteNights = listOf(
        Scenario(
            name = "a-station-pass",
            about = "The ISS over Edinburgh on the evening of 20 November 2021, from " +
                "its own published elements",
            shows = setOf(Shows.Passes),
            nowMillis = Fixtures.stationNight,
            settings = at(Places.edinburgh),
            satellites = satellites(listOf(stationElements)),
        ),
        Scenario(
            name = "a-starlink-train",
            about = "A launch still bunched into a train, which is one thing to " +
                "look at rather than twenty-two",
            shows = setOf(Shows.Passes, Shows.Train),
            nowMillis = Fixtures.stationNight,
            settings = at(Places.edinburgh),
            satellites = train("2021-089", members = 22, leadCatalogue = 101000).let {
                satellites(it.first, listOf(it.second))
            },
        ),
        Scenario(
            name = "a-busy-night-of-passes",
            about = "The station, a train and half a dozen other objects on one " +
                "night, which is what a real document looks like",
            shows = setOf(Shows.Passes, Shows.Train),
            nowMillis = Fixtures.stationNight,
            settings = at(Places.edinburgh),
            satellites = train("2021-089", members = 22, leadCatalogue = 101000).let { train ->
                satellites(
                    listOf(stationElements) + train.first + (1..6).map {
                        objectInOrbit(
                            name = "OBJECT ${'A' + it - 1}",
                            catalogue = 90000 + it,
                            aheadDegrees = it * 47.0,
                            planeDegrees = it * 31.0,
                        )
                    },
                    listOf(train.second),
                )
            },
        ),
        Scenario(
            name = "no-satellite-elements-held",
            about = "A device that has never reached the satellite document, which " +
                "is a different state from a night with no passes in it",
            shows = emptySet(),
            absent = setOf(Shows.Passes),
            nowMillis = Fixtures.stationNight,
            settings = at(Places.edinburgh),
            satellites = null,
        ),
        Scenario(
            name = "elements-held-but-nothing-crosses",
            about = "An equatorial orbit, which never comes near enough to Scotland " +
                "to clear the horizon: elements held, and nothing to say",
            shows = emptySet(),
            absent = setOf(Shows.Passes),
            nowMillis = Fixtures.stationNight,
            settings = at(Places.edinburgh),
            satellites = satellites(
                listOf(
                    objectInOrbit(
                        name = "EQUATORIAL OBJECT",
                        catalogue = 90100,
                        inclinationDegrees = 4.0,
                    ),
                ),
            ),
        ),
    )

    // ------------------------------------------------------------------ weather

    /**
     * Cloud is the one thing on the tab that is genuinely invented. It has no
     * offline answer, so a scenario has to say what the forecast held - and
     * these six are the shapes that change what the card says.
     */
    private val weather = run {
        val night = at("2026-12-14T00:30:00Z")
        val place = at(Places.edinburgh)
        val evening = at("2026-12-13T16:00:00Z")
        listOf(
            Scenario(
                name = "a-clear-night",
                about = "Four per cent cloud and a forecast that stays that way: " +
                        "the gap is now and runs to dawn",
                shows = setOf(Shows.Cloud, Shows.ClearSpell, Shows.ClearSpellUnderway),
                nowMillis = night,
                settings = place,
                cloud = Fixtures.clear,
                hourly = hourly(evening, 5, 5, 8, 4, 6, 5, 10, 8, 5, 4, 6, 9, 7, 5, 6, 8),
            ),
            Scenario(
                name = "overcast-with-no-gap",
                about = "Solid low cloud from dusk to dawn, which is most of a " +
                        "Scottish winter and has to read as a plain no",
                shows = setOf(Shows.Cloud),
                absent = setOf(Shows.ClearSpell),
                nowMillis = night,
                settings = place,
                cloud = Fixtures.overcast,
                hourly = hourly(
                    evening,
                    96, 98, 97, 95, 99, 100, 98, 96, 97, 99, 95, 96, 98, 97, 99, 96,
                ),
            ),
            Scenario(
                name = "a-gap-in-the-cloud-at-one",
                about = "Overcast now, clearing for three hours after midnight: the " +
                        "single question that decides a marginal night",
                shows = setOf(Shows.Cloud, Shows.ClearSpell),
                absent = setOf(Shows.ClearSpellUnderway),
                nowMillis = at("2026-12-13T21:00:00Z"),
                settings = place,
                cloud = Fixtures.overcast,
                hourly = hourly(
                    evening,
                    95, 97, 96, 94, 92, 90, 88, 85, 20, 15, 18, 80, 92, 95, 96, 97,
                ),
            ),
            Scenario(
                name = "a-gap-that-has-already-opened",
                about = "The same night an hour later, with the gap open now - which " +
                        "is a different sentence from one that opens at one",
                shows = setOf(Shows.Cloud, Shows.ClearSpell, Shows.ClearSpellUnderway),
                nowMillis = at("2026-12-14T01:00:00Z"),
                settings = place,
                cloud = Fixtures.broken,
                hourly = hourly(
                    evening,
                    95, 97, 96, 94, 92, 90, 88, 85, 20, 15, 18, 80, 92, 95, 96, 97,
                ),
            ),
            Scenario(
                name = "thin-high-cloud-only",
                about = "Seventy per cent cover, all of it cirrus: the reading that " +
                        "makes keeping the layers apart worth the trouble",
                shows = setOf(Shows.Cloud),
                nowMillis = night,
                settings = place,
                cloud = Fixtures.cirrus,
                hourly = hourly(
                    evening,
                    68, 70, 72, 70, 66, 70, 74, 70, 68, 70, 71, 69, 70, 72, 70, 68,
                ),
            ),
            Scenario(
                name = "no-cloud-reading-at-all",
                about = "Nothing held for here, which the card says outright rather " +
                        "than implying a clear sky",
                shows = emptySet(),
                absent = setOf(Shows.Cloud, Shows.ClearSpell),
                nowMillis = night,
                settings = place,
                cloud = null,
            ),
        )
    }

    // ------------------------------------------------- latitudes and the seasons

    private val latitudes = listOf(
        Scenario(
            name = "midsummer-with-no-real-darkness",
            about = "Edinburgh at the June solstice: civil twilight all night, the " +
                "sun never 18 degrees down, and noctilucent clouds in season",
            shows = setOf(Shows.NoAstronomicalNight, Shows.Noctilucent),
            nowMillis = at("2026-06-21T22:00:00Z"),
            settings = at(Places.edinburgh),
        ),
        Scenario(
            name = "midwinter-inside-the-arctic-circle",
            about = "Tromso at the December solstice, where the sun does not rise " +
                "at all and the dark runs through the working day",
            shows = setOf(Shows.AstronomicalNight),
            absent = setOf(Shows.Noctilucent),
            nowMillis = at("2026-12-21T12:00:00Z"),
            settings = at(Places.tromso),
        ),
        Scenario(
            name = "the-midnight-sun",
            about = "Tromso at the June solstice: no darkness of any kind, which is " +
                "an answer the tab has to give rather than an empty screen",
            shows = setOf(Shows.NoNight),
            nowMillis = at("2027-06-21T22:00:00Z"),
            settings = at(Places.tromso),
        ),
        Scenario(
            name = "twilight-at-the-equator",
            about = "Quito at the March equinox, where the sun drops vertically and " +
                "twilight is over in twenty minutes",
            shows = setOf(Shows.AstronomicalNight, Shows.Planets),
            absent = setOf(Shows.Noctilucent),
            nowMillis = at("2026-03-21T02:00:00Z"),
            settings = at(Places.quito),
        ),
        Scenario(
            name = "a-southern-summer-night",
            about = "Sydney in December, with the sky the other way up and the " +
                "seasons the other way round",
            shows = setOf(Shows.AstronomicalNight, Shows.Planets),
            nowMillis = at("2027-12-15T11:00:00Z"),
            settings = at(Places.sydney),
        ),
        Scenario(
            name = "the-far-south-in-winter",
            about = "Ushuaia at the June solstice: the southern equivalent of a " +
                "Scottish midwinter, and the longest nights in an inhabited place",
            shows = setOf(Shows.AstronomicalNight),
            nowMillis = at("2027-06-21T04:00:00Z"),
            settings = at(Places.ushuaia),
        ),
        Scenario(
            name = "noctilucent-season-in-the-south",
            about = "Ushuaia at the December solstice, where the noctilucent band " +
                "and the summer twilight are the same as Scotland's, six months out",
            shows = setOf(Shows.NoAstronomicalNight, Shows.Noctilucent),
            nowMillis = at("2027-12-21T03:00:00Z"),
            settings = at(Places.ushuaia),
        ),
    )

    // ---------------------------------------------------- the aurora, and verdicts

    private val aurora = run {
        val night = at("2026-12-14T00:30:00Z")
        val place = at(Places.edinburgh)
        listOf(
            Scenario(
                name = "an-aurora-alert-tonight",
                about = "A reading of 72 per cent from the UK magnetometers, twenty " +
                        "minutes ago, on a clear December night",
                shows = setOf(Shows.Aurora, Shows.Cloud),
                nowMillis = night,
                settings = place,
                cloud = Fixtures.clear,
                alertState = AlertState(
                    lastAlertAt = night - 20 * 60 * 1000L,
                    lastAlertProbability = 72,
                    lastPollAt = night - 3 * 60 * 1000L,
                    lastProbability = 72,
                    lastFromAuroraWatch = true,
                    armed = false,
                ),
                status = Fixtures.status("2026-12-14T00:27:00Z", maxProbability = 80),
            ),
            Scenario(
                name = "a-quiet-aurora-night",
                about = "Eight per cent and nothing doing, which is what the status " +
                        "card shows on almost every night of the year",
                shows = setOf(Shows.Aurora),
                nowMillis = night,
                settings = place,
                alertState = AlertState(
                    lastPollAt = night - 4 * 60 * 1000L,
                    lastProbability = 8,
                ),
                status = Fixtures.status(
                    "2026-12-14T00:26:00Z",
                    activity = "none",
                    maxProbability = 12,
                ),
            ),
            Scenario(
                name = "an-alert-nobody-answered",
                about = "An alert from two hours ago with no verdict against it, " +
                        "which is the state the worth-it card exists for",
                shows = setOf(Shows.Aurora, Shows.PendingVerdict),
                nowMillis = night,
                settings = place,
                alertState = AlertState(
                    lastAlertAt = night - 2 * 60 * 60 * 1000L,
                    lastAlertProbability = 64,
                    lastPollAt = night - 5 * 60 * 1000L,
                    lastProbability = 31,
                    armed = false,
                    pendingVerdict = Conditions(
                        atMillis = night - 2 * 60 * 60 * 1000L,
                        channel = VerdictChannel.Aurora,
                        probability = 64,
                        source = "AURORAWATCH",
                        kp = 5.3,
                        bz = -8.4,
                        cloudTotal = 12,
                        cloudLow = 0,
                        cloudMid = 4,
                        cloudHigh = 12,
                        threshold = 50,
                        subject = "Aurora",
                    ).encode(),
                ),
                status = Fixtures.status("2026-12-14T00:25:00Z"),
            ),
            Scenario(
                name = "a-poll-that-could-not-reach-anything",
                about = "The network down and the last reading stale, which has to " +
                        "read as unknown rather than as quiet",
                shows = emptySet(),
                absent = setOf(Shows.Aurora),
                nowMillis = night,
                settings = place,
                alertState = AlertState(
                    lastPollAt = night - 90 * 60 * 1000L,
                    lastError = "Unable to resolve host",
                ),
            ),
        )
    }

    // ------------------------------------------------------------ empty nights

    private val quiet = listOf(
        Scenario(
            name = "a-fresh-install",
            about = "No position set, which is what the app looks like before " +
                "anybody has told it where they are",
            shows = setOf(Shows.NoLocation, Shows.Nothing),
            absent = setOf(Shows.Events, Shows.Passes, Shows.Comets, Shows.Planets),
            nowMillis = at("2026-12-14T00:30:00Z"),
            settings = UserSettings(),
        ),
        Scenario(
            name = "a-quiet-night",
            about = "A real place on a real night with nothing above the horizon " +
                "worth naming: the ordinary case, and the one most easily broken",
            shows = setOf(Shows.Nothing),
            absent = setOf(Shows.Events, Shows.Passes, Shows.Comets),
            nowMillis = at("2026-02-24T22:00:00Z"),
            settings = at(Places.edinburgh),
        ),
    )

    val all: List<Scenario> =
        eclipses + pairs + showers + planets + comets + satelliteNights + weather +
            latitudes + aurora + quiet
}
