package sh.arek.gloaming.scenarios

import java.time.Instant
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.comets.ObservedMagnitude
import sh.arek.gloaming.comets.PublishedComet
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.satellites.SatelliteElements
import sh.arek.gloaming.satellites.SatellitesDocument
import sh.arek.gloaming.satellites.StarlinkTrain

/** Somewhere to stand, with the name the scenarios refer to it by. */
data class Place(val label: String, val latitude: Double, val longitude: Double)

/**
 * The places the catalogue uses.
 *
 * Chosen for latitude rather than sentiment: the app's behaviour changes at the
 * Arctic circle, at the noctilucent band, at the equator and in the southern
 * hemisphere, and each of those has to be somewhere real for the ephemeris to
 * mean anything.
 */
object Places {
    val edinburgh = Place("Edinburgh", 55.95, -3.19)
    val tromso = Place("Tromso", 69.65, 18.96)
    val reykjavik = Place("Reykjavik", 64.13, -21.90)
    val luxor = Place("Luxor", 25.69, 32.64)
    val quito = Place("Quito", -0.18, -78.47)
    val sydney = Place("Sydney", -33.87, 151.21)
    val ushuaia = Place("Ushuaia", -54.80, -68.30)
}

/** Reads an ISO-8601 instant. Every date in the catalogue is written in UTC. */
fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

/**
 * The parts of a night that are not decided by the sky.
 *
 * Cloud, satellite elements and the aurora reading all come from somewhere the
 * app cannot compute, so unlike everything else in the catalogue these really
 * are invented. They are built here, in one place, so it is always clear which
 * half of a scenario is arithmetic and which half is a story.
 */
object Fixtures {

    // --- satellites -------------------------------------------------------

    /**
     * The station's real element set, from Vallado's own test vectors.
     *
     * The same one [sh.arek.gloaming.satellites.PassFinderTest] uses, and the
     * reason the satellite scenarios are dated to November 2021: SGP4 is a
     * near-epoch model, and elements propagated months from their epoch give
     * positions nobody should put on a screen. A scenario that wants a real
     * station pass has to be dated to when these elements were real.
     */
    val stationElements = SatelliteElements(
        name = "ISS (ZARYA)",
        catalogue = 25544,
        epoch = 21320.51955234,
        epochMillis = at("2021-11-16T12:28:09.322Z"),
        meanMotion = 15.48582035,
        eccentricity = 0.0004694,
        inclination = 51.6447,
        raan = 309.4881,
        argPerigee = 203.6966,
        meanAnomaly = 299.8876,
        bstar = 0.000031985,
        meanMotionDot = 0.00001288,
    )

    /** The instant those elements describe. */
    val stationEpoch: Long = stationElements.epochMillis

    /**
     * The evening the satellite nights are set on: dusk on 20 November 2021,
     * four days after the elements' epoch.
     *
     * Four days rather than none, because a pass has to be *visible* as well as
     * overhead - the observer in darkness and the satellite still in sunlight -
     * and on the epoch itself the station crosses Edinburgh entirely inside the
     * Earth's shadow. Visible passes come in runs of a fortnight or so as the
     * orbital plane turns with respect to the sun, and this is the start of the
     * next run. Four days of propagation costs the station a few seconds of
     * timing, which nothing here turns on.
     */
    val stationNight: Long = at("2021-11-20T16:30:00Z")

    /**
     * The same orbit at a different epoch, and in a different place along it.
     *
     * Used to populate a night with more than one object. The result is a real
     * orbit and a real propagation, but not a real satellite - so nothing in
     * the catalogue claims one of these is a particular piece of hardware.
     *
     * @param aheadDegrees how far round the orbit from the station.
     * @param planeDegrees how far the orbital plane is rotated from it.
     * @param inclinationDegrees the orbit's own inclination. Lowering it below
     *   the observer's latitude is how a night is given elements that hold no
     *   pass at all: an equatorial orbit never comes near enough to Scotland to
     *   clear the horizon, which is a different thing from holding no elements.
     */
    fun objectInOrbit(
        name: String,
        catalogue: Int,
        atMillis: Long = stationEpoch,
        aheadDegrees: Double = 0.0,
        planeDegrees: Double = 0.0,
        inclinationDegrees: Double = stationElements.inclination,
    ): SatelliteElements = stationElements.copy(
        name = name,
        catalogue = catalogue,
        epoch = packedEpoch(atMillis),
        epochMillis = atMillis,
        inclination = inclinationDegrees,
        meanAnomaly = (stationElements.meanAnomaly + aheadDegrees).mod(360.0),
        raan = (stationElements.raan + planeDegrees).mod(360.0),
    )

    /**
     * A launch still bunched into a train, as the two ends the Worker
     * publishes.
     *
     * [spreadDegrees] is how far apart the front and the back are along the
     * orbit; two degrees is roughly the half-minute string that makes people
     * stop and film it.
     */
    fun train(
        launch: String,
        members: Int,
        leadCatalogue: Int,
        atMillis: Long = stationEpoch,
        spreadDegrees: Double = 2.0,
    ): Pair<List<SatelliteElements>, StarlinkTrain> {
        val tailCatalogue = leadCatalogue + 1
        val lead = objectInOrbit("STARLINK-$leadCatalogue", leadCatalogue, atMillis)
        val tail = objectInOrbit(
            "STARLINK-$tailCatalogue",
            tailCatalogue,
            atMillis,
            aheadDegrees = -spreadDegrees,
        )
        return listOf(lead, tail) to StarlinkTrain(launch, members, leadCatalogue, tailCatalogue)
    }

    fun satellites(
        elements: List<SatelliteElements>,
        trains: List<StarlinkTrain> = emptyList(),
    ) = SatellitesDocument(
        schema = 1,
        updated = "2021-11-16T12:00:00.000Z",
        satellites = elements,
        trains = trains,
    )

    /**
     * A satellite element epoch, packed as `YYDDD.dddddd`.
     *
     * The propagator reads the packed form rather than [SatelliteElements.epochMillis],
     * so moving an element set to another date means writing this field too -
     * an easy thing to forget and a silent one when forgotten, since a stale
     * epoch still propagates, just to the wrong place.
     */
    fun packedEpoch(atMillis: Long): Double {
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = atMillis
        val secondsIntoDay = calendar.get(Calendar.HOUR_OF_DAY) * 3600.0 +
            calendar.get(Calendar.MINUTE) * 60.0 +
            calendar.get(Calendar.SECOND) +
            calendar.get(Calendar.MILLISECOND) / 1000.0
        return (calendar.get(Calendar.YEAR) % 100) * 1000.0 +
            calendar.get(Calendar.DAY_OF_YEAR) +
            secondsIntoDay / 86400.0
    }

    // --- cloud ------------------------------------------------------------

    val clear = CloudCover(total = 4, low = 0, mid = 0, high = 4)
    val overcast = CloudCover(total = 96, low = 88, mid = 60, high = 20)
    val broken = CloudCover(total = 55, low = 30, mid = 25, high = 30)

    /**
     * Thin high cloud and nothing beneath it.
     *
     * The reading that makes the layer split worth keeping: 70 per cent of
     * cirrus still shows a strong aurora, and 70 per cent of stratus shows
     * nothing at all.
     */
    val cirrus = CloudCover(total = 70, low = 0, mid = 5, high = 70)

    /**
     * An hourly forecast, one percentage per hour from [fromMillis].
     *
     * Written as a list of numbers so a night reads as a shape - `90, 90, 20,
     * 20, 85` is a gap two hours wide and can be checked at a glance, which
     * matters when the thing under test is where the gap starts.
     */
    fun hourly(fromMillis: Long, vararg percentages: Int): List<HourlyCover> {
        val hour = 60 * 60 * 1000L
        val start = (fromMillis / hour) * hour
        return percentages.mapIndexed { index, total ->
            HourlyCover(atMillis = start + index * hour, total = total)
        }
    }

    // --- the aurora -------------------------------------------------------

    /**
     * A status document with a reading in it.
     *
     * The one document in the app with no offline answer: everything else is
     * computed, and this arrives from the Worker or not at all.
     */
    fun status(
        generated: String,
        activity: String = "high",
        maxProbability: Int = 60,
    ) = SkyStatus(
        schema = 1,
        generated = generated,
        globalActivity = activity,
        maxProbability = maxProbability,
    )

    // --- comets -----------------------------------------------------------

    /**
     * C/1995 O1 (Hale-Bopp), at its 1997 apparition, from the published
     * elements.
     *
     * The same comet the unit suite uses, for the same two reasons: it was
     * genuinely spectacular, so any sensible threshold admits it, and it is far
     * enough north to be up all night from Edinburgh. Where a scenario wants a
     * different geometry the elements are altered rather than a second real
     * comet introduced, so what a night turns on stays in the night.
     */
    fun haleBopp(
        magnitudeH: Double? = -1.0,
        slope: Double? = 4.0,
        observed: ObservedMagnitude? = null,
    ) = PublishedComet(
        key = "J95O010",
        name = "C/1995 O1 (Hale-Bopp)",
        q = 0.9141695106942098,
        e = 0.9951306139219294,
        i = 89.43017886941958,
        node = 282.470593486876,
        peri = 130.5872422543277,
        perihelionJdTt = 2450539.633093244,
        magnitudeH = magnitudeH,
        slope = slope,
        observed = observed,
    )

    fun cometsDocument(vararg comets: PublishedComet) =
        CometsDocument(schema = 1, updated = 0, comets = comets.toList())
}
