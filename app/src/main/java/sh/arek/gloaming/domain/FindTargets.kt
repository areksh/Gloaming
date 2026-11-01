package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.Constellation
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyPositions
import sh.arek.gloaming.satellites.LiveTracker
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
 */
fun findTargetFrom(extra: String?, setupDone: Boolean): FindTarget? =
    if (extra == null || !setupDone) null else FindTarget.decode(extra)

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

    /** Where aurora is looked for: due north, low down. */
    private val AURORA_POSITION = AltAz(altitude = 10.0, azimuth = 0.0)

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

    /** Forgets the remembered rise; for tests. */
    internal fun forget() {
        remembered = null
        scans = 0
    }

    private const val SEARCH_STEP_MILLIS = 5 * 60_000L
    private const val SEARCH_SPAN_MILLIS = 24 * 3_600_000L
    private const val PASS_MATCH_MILLIS = 10 * 60_000L

    /**
     * Everything worth offering now, in the order the picker shows it.
     *
     * Planets and the moon, then the Plough and up to three more patterns,
     * then passes that have not finished (the next first), then aurora when
     * [auroraKnown] says there is a forecast to point at.
     */
    fun available(list: TonightList, auroraKnown: Boolean, nowMillis: Long): List<FindTarget> {
        val targets = mutableListOf<FindTarget>()
        list.planets.forEach { targets += FindTarget.Planet(it.body) }
        if ((list.moon?.upFraction ?: 0.0) > 0.0) targets += FindTarget.Moon
        targets += FindTarget.Pattern(PLOUGH)
        list.constellations
            .map { it.constellation.name }
            .filter { it != PLOUGH }
            .take(MORE_PATTERNS)
            .forEach { targets += FindTarget.Pattern(it) }
        list.passes
            .filter { it.setsAt > nowMillis }
            .sortedBy { it.risesAt }
            .forEach { targets += FindTarget.Pass(it.catalogue, it.risesAt) }
        if (auroraKnown) targets += FindTarget.Aurora
        return targets
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
        FindTarget.Aurora -> TargetFix(
            label = "Aurora",
            position = AURORA_POSITION,
            risesAt = null,
            riseAzimuth = null,
            recognise = "North, low down. A pale glow or a faint arc along the northern horizon; " +
                "a camera shows more of it than your eye will.",
        )
        is FindTarget.Pass -> passFix(target, list, elements, latitude, longitude, nowMillis)
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
        val pass: SatellitePass? = list.passes
            .filter { it.catalogue == target.catalogue }
            .filter { kotlin.math.abs(it.risesAt - target.risesAt) <= PASS_MATCH_MILLIS }
            .minByOrNull { kotlin.math.abs(it.risesAt - target.risesAt) }
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
