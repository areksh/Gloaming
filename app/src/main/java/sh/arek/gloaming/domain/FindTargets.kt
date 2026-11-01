package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.Constellation
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyPositions
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.satellites.LiveTracker
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.satellites.SatellitesDocument

/**
 * Something the Find tab can point at.
 *
 * Saved by [encode] so the choice survives the screen being left, and read
 * back by [decode], which trusts nothing: a stored string can be stale, from a
 * newer build, or simply damaged, and the answer to all three is "no target".
 */
sealed interface FindTarget {
    /** A naked-eye planet. The sun is never one: pointing a phone at it is no help. */
    data class Planet(val body: SkyBody) : FindTarget

    data object Moon : FindTarget

    /** A constellation by its catalogue name, found at its catalogued centre. */
    data class Pattern(val name: String) : FindTarget

    /** One satellite pass, identified by the satellite and when it rises. */
    data class Pass(val catalogue: Int, val risesAt: Long) : FindTarget

    data object Aurora : FindTarget

    companion object
}

/** The target as text: `planet:Saturn`, `moon`, `pattern:Ursa Major`, `pass:25544:<rise>`, `aurora`. */
fun FindTarget.encode(): String = when (this) {
    is FindTarget.Planet -> "planet:${body.name}"
    FindTarget.Moon -> "moon"
    is FindTarget.Pattern -> "pattern:$name"
    is FindTarget.Pass -> "pass:$catalogue:$risesAt"
    FindTarget.Aurora -> "aurora"
}

/**
 * The target a notification's "Find it" asked for: null when there was none,
 * when it cannot be read, or when setup is not done - setup comes first and a
 * target handed in meanwhile is dropped rather than kept.
 *
 * Null too when [launchedFromHistory]: on Android 11 and below Recents
 * relaunches a task with the intent that created it, so a task begun by Find
 * it would hand its old target back on every return from Recents.
 */
fun findTargetFrom(extra: String?, setupDone: Boolean, launchedFromHistory: Boolean): FindTarget? =
    if (extra == null || !setupDone || launchedFromHistory) null else FindTarget.decode(extra)

/** The target [text] names, or null for anything [encode] could not have written. */
fun FindTarget.Companion.decode(text: String): FindTarget? {
    val kind = text.substringBefore(':')
    val rest = if (':' in text) text.substringAfter(':') else null
    return when {
        text == "moon" -> FindTarget.Moon
        text == "aurora" -> FindTarget.Aurora
        kind == "planet" && rest != null ->
            FindTargets.PLANETS.firstOrNull { it.name == rest }?.let { FindTarget.Planet(it) }
        kind == "pattern" && rest != null ->
            if (Constellations.WATCHED.any { it.name == rest }) FindTarget.Pattern(rest) else null
        kind == "pass" && rest != null -> {
            val parts = rest.split(':')
            val catalogue = parts.getOrNull(0)?.toIntOrNull()
            val risesAt = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && catalogue != null && catalogue > 0 && risesAt != null) {
                FindTarget.Pass(catalogue, risesAt)
            } else {
                null
            }
        }
        else -> null
    }
}

/**
 * Where a target is and how to tell it from its surroundings.
 *
 * @property position null while the target is below the horizon, or where it
 *   cannot be worked out (a pass whose elements the device does not hold).
 * @property risesAt when a target below the horizon next comes up, if known.
 * @property riseAzimuth degrees clockwise from north where it comes up.
 */
data class TargetFix(
    val label: String,
    val position: AltAz?,
    val risesAt: Long?,
    val riseAzimuth: Double?,
    val recognise: String,
)

/**
 * What the sky is doing, as far as deciding what Find offers goes.
 *
 * @property sunAltitude degrees above the horizon, negative below.
 * @property auroraNow whether a current reading is at or above the user's threshold.
 */
data class Sky(val sunAltitude: Double, val auroraNow: Boolean)

/**
 * What can be found, and where it is now.
 *
 * There is no star catalogue (ADR-0012), so a pattern is aimed at its
 * catalogued centre and nothing smaller than a constellation is offered.
 * Clock-free: the instant is a parameter.
 */
object FindTargets {

    /** The planets that can be a target; the sun and moon are not among them. */
    internal val PLANETS = listOf(
        SkyBody.Mercury, SkyBody.Venus, SkyBody.Mars, SkyBody.Jupiter, SkyBody.Saturn,
    )

    private const val PLOUGH = "Ursa Major"
    private const val MORE_PATTERNS = 3

    /** How high aurora is looked for: low down, over the poleward horizon. */
    private const val AURORA_ALTITUDE = 10.0

    /** How many rise searches have run; read by tests to show the memo works. */
    @Volatile
    internal var scans = 0

    /** How long "nothing rises within a day" is believed. */
    private const val NO_RISE_MEMO_MILLIS = 10 * 60_000L

    /**
     * The last rise worked out, so a screen asking every frame searches once.
     *
     * One immutable entry, replaced whole: a different target or place simply
     * displaces it, and a reader never sees half of an update.
     */
    private class Remembered(
        val key: String,
        val computedAt: Long,
        val rises: Long?,
        val riseAzimuth: Double?,
    )

    @Volatile
    private var remembered: Remembered? = null

    /** How many passes have been looked for in the elements; read by tests to show the memo works. */
    @Volatile
    internal var passSearches = 0

    /**
     * The last pass looked for in the elements, so a screen placing it every
     * second searches once. Keyed by the target, the place and the very
     * document searched, and replaced whole like [Remembered].
     */
    private class RememberedPass(
        val key: String,
        val elements: SatellitesDocument,
        val pass: SatellitePass?,
    )

    @Volatile
    private var rememberedPass: RememberedPass? = null

    /** Forgets the remembered rise and pass; for tests. */
    internal fun forget() {
        remembered = null
        rememberedPass = null
        scans = 0
        passSearches = 0
    }

    private const val SEARCH_STEP_MILLIS = 5 * 60_000L
    private const val SEARCH_SPAN_MILLIS = 24 * 3_600_000L

    /**
     * How far apart two rise times may be and still be the same pass.
     *
     * A notification's rise comes off the poller's own search grid and
     * Tonight's off another, so the two land a second or so apart; the next
     * pass of the same satellite is over an hour away.
     */
    const val PASS_MATCH_MILLIS: Long = 10 * 60_000L

    /** The most passes Find offers at once. */
    const val MAX_PASSES = 6

    /**
     * Whether [a] and [b] are the same target: equal, or the same satellite
     * rising within [PASS_MATCH_MILLIS] of each other.
     */
    fun same(a: FindTarget, b: FindTarget): Boolean =
        if (a is FindTarget.Pass && b is FindTarget.Pass) {
            a.catalogue == b.catalogue && kotlin.math.abs(a.risesAt - b.risesAt) <= PASS_MATCH_MILLIS
        } else {
            a == b
        }

    /**
     * The entry in [available] that is the same target as [target], or null
     * when none is - so a pass handed in by a notification selects Tonight's
     * own chip for it rather than standing beside it as a second one.
     */
    fun matching(target: FindTarget, available: List<FindTarget>): FindTarget? =
        available.filter { same(it, target) }.minByOrNull {
            if (it is FindTarget.Pass && target is FindTarget.Pass) kotlin.math.abs(it.risesAt - target.risesAt) else 0L
        }

    /** A pass is offered from this long before it rises. */
    const val PASS_LEAD_MILLIS = 30 * 60_000L

    /** The sun must be lower than this (degrees) for a planet to be offered. */
    const val PLANET_SUN_LIMIT = -6.0

    /** The sun must be lower than this (degrees) for a pattern or the aurora to be offered. */
    const val DARK_SUN_LIMIT = -12.0

    /** How far ahead [nextUp] looks, and how finely: no finer than the rise search. */
    private const val NEXT_UP_SPAN_MILLIS = SEARCH_SPAN_MILLIS
    private const val NEXT_UP_STEP_MILLIS = SEARCH_STEP_MILLIS

    /** Whether the sun is low enough for [target] to be seen. The moon is seen by day. */
    private fun sunAllows(target: FindTarget, sunAltitude: Double): Boolean = when (target) {
        FindTarget.Moon -> true
        is FindTarget.Planet -> sunAltitude < PLANET_SUN_LIMIT
        is FindTarget.Pattern -> sunAltitude < DARK_SUN_LIMIT
        FindTarget.Aurora -> sunAltitude < DARK_SUN_LIMIT
        is FindTarget.Pass -> true
    }

    /** Whether [target], at [altitude] degrees, is high enough to be worth pointing at. */
    private fun highEnough(target: FindTarget, altitude: Double): Boolean = when (target) {
        is FindTarget.Planet -> altitude >= Outlooks.PLANET_MIN_ALTITUDE
        FindTarget.Moon, is FindTarget.Pattern -> altitude > 0.0
        FindTarget.Aurora, is FindTarget.Pass -> true
    }

    /** The planets, the moon and the patterns that might be offered: everything with a place in the sky. */
    private fun skyCandidates(list: TonightList): List<FindTarget> {
        val patterns = list.constellations
            .map { it.constellation.name }
            .filter { it != PLOUGH }
            .map { FindTarget.Pattern(it) }
        return PLANETS.map { FindTarget.Planet(it) } + FindTarget.Moon +
            FindTarget.Pattern(PLOUGH) + patterns
    }

    /**
     * Everything that can be seen now or very soon, in the order the picker
     * shows it.
     *
     * Not what is merely up: the moon whenever it is above the horizon, since
     * it is seen by day and is what the compass check aims at; a planet from
     * [Outlooks.PLANET_MIN_ALTITUDE] once the sun is below [PLANET_SUN_LIMIT];
     * the Plough and up to three more patterns above the horizon once the sun
     * is below [DARK_SUN_LIMIT]; a pass under way or rising within
     * [PASS_LEAD_MILLIS], the soonest [MAX_PASSES] of Tonight's best; and
     * aurora when [Sky.auroraNow] and the sun is below [DARK_SUN_LIMIT].
     * Nothing is offered for rising later: [nextUp] says what that is.
     *
     * [fixOf] places a target now; null means it cannot be, which a pass
     * takes to mean it has set. A pass [handedIn] by a notification is always
     * offered, beyond the limit and ahead of its time if need be.
     */
    fun available(
        list: TonightList,
        nowMillis: Long,
        handedIn: FindTarget.Pass?,
        fixOf: (FindTarget) -> TargetFix?,
        sky: Sky,
    ): List<FindTarget> {
        val targets = mutableListOf<FindTarget>()
        var morePatterns = MORE_PATTERNS
        for (candidate in skyCandidates(list)) {
            if (!sunAllows(candidate, sky.sunAltitude)) continue
            val altitude = fixOf(candidate)?.position?.altitude ?: continue
            if (!highEnough(candidate, altitude)) continue
            if (candidate is FindTarget.Pattern && candidate.name != PLOUGH && morePatterns-- <= 0) continue
            targets += candidate
        }
        val coming = list.bestPasses
            .filter { it.setsAt > nowMillis }
            .sortedBy { it.risesAt }
            .map { FindTarget.Pass(it.catalogue, it.risesAt) }
        val offered = coming.filter { passSoon(fixOf(it), nowMillis) }.take(MAX_PASSES).toMutableList()
        if (handedIn != null && offered.none { same(it, handedIn) }) {
            // Tonight's own entry for it when it has one, so the chip carries
            // the rise the list uses; else the notification's.
            offered += coming.firstOrNull { same(it, handedIn) } ?: handedIn
        }
        targets += offered.sortedBy { it.risesAt }
        if (sky.auroraNow && sunAllows(FindTarget.Aurora, sky.sunAltitude)) targets += FindTarget.Aurora
        return targets
    }

    /** Whether a pass so placed is under way or about to be. */
    private fun passSoon(fix: TargetFix?, nowMillis: Long): Boolean {
        if (fix == null) return false
        if (fix.position != null) return true
        val rises = fix.risesAt ?: return false
        return rises - nowMillis <= PASS_LEAD_MILLIS
    }

    /**
     * The soonest thing that [available] would offer, with the moment it
     * would, within a day; null when nothing does.
     *
     * Aurora is never named: when it will be strong enough is not known ahead.
     * Bodies and patterns are stepped through at [altitudeAt] and [sunAt]
     * (coarse, like the rise search, so "from" is a few minutes late at worst);
     * a pass is named [PASS_LEAD_MILLIS] before it rises, or now if it is
     * already under way. On a tie a body wins.
     */
    fun nextUp(
        list: TonightList,
        nowMillis: Long,
        altitudeAt: (FindTarget, Long) -> Double,
        sunAt: (Long) -> Double,
    ): Pair<FindTarget, Long>? {
        val until = nowMillis + NEXT_UP_SPAN_MILLIS
        val candidates = skyCandidates(list)
        val passes = list.bestPasses
            .filter { it.setsAt > nowMillis }
            .map { FindTarget.Pass(it.catalogue, it.risesAt) to maxOf(nowMillis, it.risesAt - PASS_LEAD_MILLIS) }
            .filter { it.second <= until }
            .minByOrNull { it.second }

        var at = nowMillis
        while (at <= until && (passes == null || at < passes.second)) {
            val sun = sunAt(at)
            candidates.firstOrNull { sunAllows(it, sun) && highEnough(it, altitudeAt(it, at)) }
                ?.let { return it to at }
            at += NEXT_UP_STEP_MILLIS
        }
        return passes
    }

    /**
     * How high a planet, the moon or a pattern is at [atMillis], in degrees
     * (negative below the horizon); null for anything else.
     */
    fun altitudeAt(target: FindTarget, latitude: Double, longitude: Double, atMillis: Long): Double? =
        when (target) {
            is FindTarget.Planet -> SkyPositions.body(target.body, latitude, longitude, atMillis).altitude
            FindTarget.Moon -> SkyPositions.body(SkyBody.Moon, latitude, longitude, atMillis).altitude
            is FindTarget.Pattern -> Constellations.WATCHED.firstOrNull { it.name == target.name }
                ?.let { SkyPositions.fixed(it.raHours, it.dec, latitude, longitude, atMillis).altitude }
            else -> null
        }

    /**
     * The sky as [available] needs it at [nowMillis]: the sun's height, and
     * whether aurora is worth looking for. The reading counts only while
     * current (the same rule as the outlook's, [Outlooks.AURORA_READING_MAX_AGE_MILLIS])
     * and at or above the user's [threshold].
     */
    fun skyAt(
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
        probability: Int?,
        polledAt: Long?,
        threshold: Int,
    ): Sky {
        return Sky(
            sunAltitude = Twilight.sunAltitude(latitude, longitude, nowMillis),
            auroraNow = probability != null && polledAt != null &&
                nowMillis - polledAt < Outlooks.AURORA_READING_MAX_AGE_MILLIS &&
                probability >= threshold,
        )
    }

    /**
     * Where [target] is at [nowMillis], or null when it is no longer a target:
     * an unknown pattern, or a pass that has finished or is not known.
     */
    fun fix(
        target: FindTarget,
        list: TonightList,
        elements: SatellitesDocument?,
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
    ): TargetFix? = when (target) {
        is FindTarget.Planet -> bodyFix(
            label = target.body.label,
            recognise = planetWording(target.body),
            positionAt = { SkyPositions.body(target.body, latitude, longitude, it) },
            key = "${target.encode()}@$latitude,$longitude",
            nowMillis = nowMillis,
        )
        FindTarget.Moon -> bodyFix(
            label = "The moon",
            recognise = "The moon. You will not miss it.",
            positionAt = { SkyPositions.body(SkyBody.Moon, latitude, longitude, it) },
            key = "${target.encode()}@$latitude,$longitude",
            nowMillis = nowMillis,
        )
        is FindTarget.Pattern -> Constellations.WATCHED.firstOrNull { it.name == target.name }
            ?.let { patternFix(it, latitude, longitude, nowMillis) }
        FindTarget.Aurora -> auroraFix(latitude)
        is FindTarget.Pass -> passFix(target, list, elements, latitude, longitude, nowMillis)
    }

    /** Aurora is looked for low over the horizon of the nearer pole: due north, or due south below the equator. */
    private fun auroraFix(latitude: Double): TargetFix {
        val towards = Compass.polewardOf(latitude)
        val azimuth = if (towards == Compass.North) 0.0 else 180.0
        val side = if (towards == Compass.North) "northern" else "southern"
        return TargetFix(
            label = "Aurora",
            position = AltAz(altitude = AURORA_ALTITUDE, azimuth = azimuth),
            risesAt = null,
            riseAzimuth = null,
            recognise = "${towards.label.replaceFirstChar { it.uppercaseChar() }}, low down. " +
                "A pale glow or a faint arc along the $side horizon; " +
                "a camera shows more of it than your eye will.",
        )
    }

    private fun patternFix(c: Constellation, latitude: Double, longitude: Double, nowMillis: Long) =
        bodyFix(
            label = if (c.name == PLOUGH) "The Plough" else c.name,
            recognise = c.starHop,
            positionAt = { SkyPositions.fixed(c.raHours, c.dec, latitude, longitude, it) },
            key = "pattern:${c.name}@$latitude,$longitude",
            nowMillis = nowMillis,
        )

    private fun bodyFix(
        label: String,
        recognise: String,
        positionAt: (Long) -> AltAz,
        key: String,
        nowMillis: Long,
    ): TargetFix {
        val here = positionAt(nowMillis)
        if (here.altitude >= 0.0) return TargetFix(label, here, null, null, recognise)
        val known = remembered?.takeIf {
            it.key == key && nowMillis >= it.computedAt &&
                if (it.rises != null) it.rises > nowMillis else nowMillis - it.computedAt < NO_RISE_MEMO_MILLIS
        }
        if (known != null) return TargetFix(label, null, known.rises, known.riseAzimuth, recognise)

        val rises = nextRise(positionAt, nowMillis)
        val azimuth = rises?.let { positionAt(it).azimuth }
        remembered = Remembered(key, nowMillis, rises, azimuth)
        return TargetFix(label, null, rises, azimuth, recognise)
    }

    /**
     * When something below the horizon at [from] next clears it, within a day.
     *
     * Stepped, then bisected: the bodies and fixed points have no closed form
     * here, and a coarse step alone would put the rise minutes out.
     */
    private fun nextRise(positionAt: (Long) -> AltAz, from: Long): Long? {
        scans++
        var low = from
        var high = from + SEARCH_STEP_MILLIS
        while (high <= from + SEARCH_SPAN_MILLIS) {
            if (positionAt(high).altitude >= 0.0) {
                repeat(10) {
                    val middle = (low + high) / 2
                    if (positionAt(middle).altitude >= 0.0) high = middle else low = middle
                }
                return high
            }
            low = high
            high += SEARCH_STEP_MILLIS
        }
        return null
    }

    private fun passFix(
        target: FindTarget.Pass,
        list: TonightList,
        elements: SatellitesDocument?,
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
    ): TargetFix? {
        // Tonight's list first, and the elements where it has nothing: the
        // list is rebuilt from the start of the window onwards, which leaves
        // out a pass already under way, so once one has risen it is only in
        // the elements.
        val pass: SatellitePass? = list.passes
            .filter { same(FindTarget.Pass(it.catalogue, it.risesAt), target) }
            .minByOrNull { kotlin.math.abs(it.risesAt - target.risesAt) }
            ?: elements?.let { passFromElements(target, it, latitude, longitude) }
        if (pass == null || pass.setsAt <= nowMillis) return null

        val label = pass.name
        val wording = PASS_WORDING
        val held = elements?.satellites?.firstOrNull { it.catalogue == pass.catalogue }
            ?: return TargetFix(
                label, null, pass.risesAt.takeIf { it > nowMillis },
                pass.riseAzimuth.takeIf { pass.risesAt > nowMillis },
                "The orbital elements are missing, so it cannot be placed. $wording",
            )

        val fix = LiveTracker(held, latitude, longitude).fixAt(nowMillis)
        return if (nowMillis >= pass.risesAt && fix.elevation >= 0.0) {
            val shadow = if (fix.sunlit) "" else " It is in the Earth's shadow now, so it may be invisible."
            TargetFix(label, AltAz(fix.elevation, fix.azimuth), null, null, wording + shadow)
        } else {
            TargetFix(label, null, pass.risesAt, pass.riseAzimuth, wording)
        }
    }

    /**
     * The pass [target] names, worked out from the elements of its one
     * satellite over the [PASS_MATCH_MILLIS] either side of its rise.
     *
     * Twenty minutes of one satellite is a few hundred propagations, and the
     * answer is remembered, so the screen placing the target every second
     * searches once rather than every tick.
     */
    private fun passFromElements(
        target: FindTarget.Pass,
        elements: SatellitesDocument,
        latitude: Double,
        longitude: Double,
    ): SatellitePass? {
        val key = "${target.encode()}@$latitude,$longitude"
        rememberedPass?.takeIf { it.key == key && it.elements === elements }?.let { return it.pass }

        passSearches++
        val satellite = elements.satellites.firstOrNull { it.catalogue == target.catalogue }
        val pass = satellite?.let {
            PassFinder.passes(
                elements = it,
                latitude = latitude,
                longitude = longitude,
                fromMillis = target.risesAt - PASS_MATCH_MILLIS,
                untilMillis = target.risesAt + PASS_MATCH_MILLIS,
                // Any pass at all: the one asked for was already judged worth
                // pointing at by whoever offered it.
                minimumPeakElevation = 0.0,
            )
                .filter { found -> kotlin.math.abs(found.risesAt - target.risesAt) <= PASS_MATCH_MILLIS }
                .minByOrNull { found -> kotlin.math.abs(found.risesAt - target.risesAt) }
        }
        rememberedPass = RememberedPass(key, elements, pass)
        return pass
    }

    private const val PASS_WORDING =
        "A steady point of light drifting across the sky, with no blinking lights. " +
            "It fades out as it enters the Earth's shadow."

    private fun planetWording(body: SkyBody): String {
        val look = when (body) {
            SkyBody.Mercury -> "Small and white-ish, never far from the horizon."
            SkyBody.Venus -> "Dazzling white, the brightest point in the sky bar the moon."
            SkyBody.Mars -> "Orange-red, brighter than most stars."
            SkyBody.Jupiter -> "Brilliant creamy white, brighter than any star."
            SkyBody.Saturn -> "Pale yellow, dimmer than Jupiter."
            else -> ""
        }
        return "$look Planets shine steadily; stars twinkle."
    }
}
