package sh.arek.gloaming.scenarios

import kotlin.reflect.KClass
import sh.arek.gloaming.astronomy.Planets
import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookInput
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.Outlooks
import sh.arek.gloaming.domain.Tonight
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitesDocument
import sh.arek.gloaming.ui.MainUiState
import sh.arek.gloaming.ui.NightTimeline

/**
 * One night, chosen because it shows something the app would otherwise have to
 * wait years to show.
 *
 * **Nothing here is fake astronomy.** A scenario supplies arguments - where you
 * are standing, what the time is, and the documents the device would already
 * hold - and everything else is the shipped code doing its real work. The total
 * lunar eclipse of December 2029 is in the catalogue because it really happens
 * then and really is 40 degrees up over Edinburgh, not because a fixture says
 * so. That distinction is what makes the catalogue worth having: a canned
 * `TonightList` would go on agreeing with itself for ever while the engine
 * underneath it rotted.
 *
 * What is genuinely invented is the small amount the sky does not decide - the
 * cloud forecast, the satellite elements, and the aurora reading. Those are
 * marked as such where they are built.
 *
 * @param name a stable slug. Tests select by it and failures print it, so it is
 *   part of the contract rather than a label.
 * @param about what this night is for, in a sentence. This is the reason the
 *   scenario exists and the first thing to read when one of them fails.
 * @param shows the things the app must actually have to say about this night.
 *   Asserted in the JVM suite, so a scenario cannot quietly stop demonstrating
 *   what it claims - the failure mode that would otherwise make the whole
 *   catalogue worthless, because a screen test against an empty night passes
 *   just as happily as one against a full one.
 */
data class Scenario(
    val name: String,
    val about: String,
    val shows: Set<Shows>,
    /**
     * What must *not* appear.
     *
     * Half the interesting states of this app are absences - a comet below the
     * threshold, a night with no elements held, a forecast with no gap in it -
     * and an absence asserted nowhere is indistinguishable from a scenario that
     * has quietly broken.
     */
    val absent: Set<Shows> = emptySet(),
    /** The moment the user is looking at the screen. */
    val nowMillis: Long,
    /** Where they are, and how they have the app set up. */
    val settings: UserSettings,
    /** The elements the device last fetched, or null when it holds none. */
    val satellites: SatellitesDocument? = null,
    /** The comet document it last fetched, or null. */
    val comets: CometsDocument? = null,
    /** The last cloud reading held for here. Null means unknown, and is said so. */
    val cloud: CloudCover? = null,
    /** The hours ahead from that same reading. */
    val hourly: List<HourlyCover> = emptyList(),
    /** What the aurora side of the app knows, for the status and verdict cards. */
    val alertState: AlertState = AlertState(),
    val status: SkyStatus? = null,
    /**
     * How far ahead the calendar is worked out.
     *
     * Three days by default, because tonight is all the tonight list reads and
     * a year costs a quarter of a second on a desktop and a good deal more on a
     * phone. Scenarios built for the calendar screen rather than the tonight
     * tab raise it.
     */
    val calendarDays: Int = 3,
    /**
     * The opening sentence this night must come out as, or null where the
     * night is not about it. Checked against [Scenarios.outlook] in the JVM
     * suite, like [shows].
     */
    val outlook: KClass<out Outlook>? = null,
) {
    val latitude: Double? get() = settings.latitude
    val longitude: Double? get() = settings.longitude

    override fun toString(): String = "$name ($about)"
}

/**
 * What a scenario is supposed to demonstrate.
 *
 * Deliberately about what appears on the screen rather than about the sky: the
 * point of a scenario is to put a card in a state somebody can look at, and a
 * shower that is genuinely happening but has been filtered out by a setting has
 * failed at that job even though the astronomy was right.
 *
 * Some are about the calendar and some about tonight, and they are not the same
 * question. A solar eclipse happens in daylight, so it is a calendar entry and
 * never a tonight entry; an event below the horizon is on the calendar saying
 * why and is dropped from tonight entirely. Each value below says which it
 * asks about.
 */
enum class Shows {
    /** Tonight's list has at least one calendar event in it. */
    Events,

    /** Something in the calendar is visible from here, but with a caveat. */
    Caveat,

    /**
     * Something on tonight's list is happening as the screen is read, rather
     * than later tonight - the state the calendar could not reach at all until
     * it started keeping an entry while any part of it was still ahead.
     */
    UnderWay,

    /** Something in the calendar cannot be seen from here at all. */
    NotVisibleHere,

    /** The calendar holds an eclipse of either kind. */
    Eclipse,

    /** The calendar holds the moon passing in front of a planet. */
    Occultation,

    /** The calendar holds two bodies close together. */
    Conjunction,

    /** The calendar holds a meteor shower at its peak. */
    Shower,

    /** The calendar holds an opposition, a greatest elongation or a greatest brilliancy. */
    PlanetEvent,

    /** At least one comet up tonight. */
    Comets,

    /** At least one satellite pass worth listing. */
    Passes,

    /** One of those passes is a Starlink train. */
    Train,

    /** At least one naked-eye planet above the bar. */
    Planets,

    /** There is a moon to describe. */
    Moon,

    /** The moon is bright enough and up long enough to spoil the night. */
    Moonlight,

    /** The sun gets 18 degrees down: a properly dark night. */
    AstronomicalNight,

    /** Civil darkness, but the sun never reaches 18 degrees. */
    NoAstronomicalNight,

    /** The sun does not even reach 6 degrees down: no night at all. */
    NoNight,

    /** A cloud reading is held for here. */
    Cloud,

    /** The forecast has a gap in the cloud later tonight. */
    ClearSpell,

    /** That gap is open now. */
    ClearSpellUnderway,

    /** At least one constellation is up and above the bar. */
    Constellations,

    /** One of them has its best night of the year tonight. */
    ConstellationAtItsBest,

    /** Some are held back for never setting, and named in a line instead. */
    ConstellationsAlwaysUp,

    /** The noctilucent prompt is in season and at the right latitude. */
    Noctilucent,

    /** An aurora reading to put on the status card. */
    Aurora,

    /** An alert nobody has answered yet, for the worth-it card. */
    PendingVerdict,

    /** Nothing to see: the list is empty and the tab has to say so. */
    Nothing,

    /** No position set, which is the state a fresh install is in. */
    NoLocation,
}

/**
 * The catalogue, and the two functions that turn a scenario into what the
 * screen would show.
 */
object Scenarios {

    /** Every night in the catalogue, in the order the groups are declared. */
    val all: List<Scenario> = Nights.all

    fun byName(name: String): Scenario =
        all.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("no scenario named $name")

    /**
     * The calendar as the app would have computed it that night.
     *
     * The real [SkyCalendar], from the scenario's own moment - so an eclipse
     * appears because the ephemeris says it does.
     */
    fun calendar(scenario: Scenario, horizonDays: Int = scenario.calendarDays): List<SkyEvent> {
        val latitude = scenario.latitude ?: return emptyList()
        val longitude = scenario.longitude ?: return emptyList()
        return SkyCalendar.events(latitude, longitude, scenario.nowMillis, horizonDays)
    }

    /**
     * Tonight, as the tab would show it.
     *
     * [Tonight.of] unchanged, over the real calendar and the scenario's own
     * documents. Nothing between this and what the app does on the night except
     * the arguments.
     */
    fun tonight(scenario: Scenario): TonightList = Tonight.of(
        satellites = scenario.satellites,
        comets = scenario.comets,
        calendar = calendar(scenario),
        settings = scenario.settings,
        nowMillis = scenario.nowMillis,
        cloud = scenario.cloud,
        hourly = scenario.hourly,
    )

    /**
     * Tonight's outlook, from [Outlooks.of] over [tonight] and the scenario's
     * own readings.
     *
     * The elements' age is how long before now the satellite document says it
     * was published, which is all a device knows about them; with no document
     * there is no age, and nothing for it to judge.
     */
    fun outlook(scenario: Scenario): OutlookReport {
        val now = scenario.nowMillis
        // Without a position the outlook stops at NoLocation before it asks
        // where anything is, so these are never read for a real answer.
        val latitude = scenario.latitude ?: 0.0
        val longitude = scenario.longitude ?: 0.0
        val probability = scenario.alertState.lastProbability
        val lastPollAt = scenario.alertState.lastPollAt
        return Outlooks.of(
            tonight(scenario),
            OutlookInput(
                settings = scenario.settings,
                nowMillis = now,
                hourly = scenario.hourly,
                auroraProbability = probability,
                auroraAgeMillis = lastPollAt?.takeIf { probability != null }?.let { now - it },
                elementsAgeMillis = scenario.satellites?.let { now - at(it.updated) },
                sunAltitude = { Twilight.sunAltitude(latitude, longitude, it) },
                moonAltitude = { Twilight.moonAltitude(latitude, longitude, it) },
                planetAltitude = { body, t -> Planets.altitude(body, latitude, longitude, t) },
            ),
        )
    }

    /**
     * The whole screen state for that night.
     *
     * Built here rather than in the instrumented suite so that both suites see
     * the same screen: a test that renders one thing while another asserts
     * about a different one is worse than either alone.
     *
     * The permissions are granted and the phone is exempt, because a scenario
     * is about the sky rather than about the two warning cards - the states
     * where they are missing have their own tests already.
     */
    fun uiState(scenario: Scenario): MainUiState {
        val latitude = scenario.latitude
        val longitude = scenario.longitude
        val tonight = tonight(scenario)
        val outlook = outlook(scenario)
        return MainUiState(
            settings = scenario.settings,
            alertState = scenario.alertState,
            latitudeInput = latitude?.toString().orEmpty(),
            longitudeInput = longitude?.toString().orEmpty(),
            quietFromInput = "23:00",
            quietUntilInput = "07:00",
            batteryExempt = true,
            notificationsAllowed = true,
            nowMillis = scenario.nowMillis,
            darkness = if (latitude != null && longitude != null) {
                Twilight.darkness(latitude, longitude, scenario.nowMillis)
            } else {
                null
            },
            calendar = calendar(scenario),
            tonight = tonight,
            outlook = outlook,
            hourly = scenario.hourly,
            timeline = NightTimeline.of(tonight, outlook, scenario.hourly, scenario.settings, scenario.nowMillis),
            pendingVerdict = Conditions.decode(scenario.alertState.pendingVerdict),
        )
    }
}
