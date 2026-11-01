package sh.arek.gloaming.ui

import kotlin.math.abs
import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.domain.CompassAccuracy
import sh.arek.gloaming.domain.CompassCheck
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.FindTargets
import sh.arek.gloaming.domain.Guidance
import sh.arek.gloaming.domain.Guide
import sh.arek.gloaming.domain.GuideWords
import sh.arek.gloaming.domain.TargetFix
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitesDocument

/**
 * What a row of the night can be pointed at, or null where there is nothing
 * to point at: twilight, the clear spell, and events with no body to find.
 *
 * An event says which body it is about only in its words, so only the ones
 * that are always about the moon map to it.
 */
fun findTarget(subject: RowSubject): FindTarget? = when (subject) {
    RowSubject.Twilight, RowSubject.Clear -> null
    RowSubject.Moon -> FindTarget.Moon
    is RowSubject.Pass -> FindTarget.Pass(subject.pass.catalogue, subject.pass.risesAt)
    is RowSubject.Planet -> FindTarget.Planet(subject.planet.body)
    is RowSubject.Event -> findTarget(subject.event)
}

/**
 * What an event can be pointed at, shared by its row and its notification so
 * the two never disagree about whether there is anything to find.
 */
fun findTarget(event: SkyEvent): FindTarget? = when (event.kind) {
    EventKind.LunarEclipse, EventKind.Occultation, EventKind.MoonPhase, EventKind.MoonQuarter ->
        FindTarget.Moon
    else -> null
}

/**
 * Where the target is in words, then which way the phone is facing:
 * "South-east, 24 degrees up. You are facing south, 10 degrees up."
 *
 * A target below the horizon says where and when it comes up instead. Null
 * where there is nothing true to say - a pass whose elements are missing.
 * The facing half is left off until the compass has given a reading.
 */
fun whereLine(fix: TargetFix, pointing: AltAz?): String? {
    val facing = pointing
        ?.let { " You are facing ${Compass.of(it.azimuth).label}, ${degreesUp(it.altitude)}." }
        .orEmpty()
    val position = fix.position
    val risesAt = fix.risesAt
    val where = when {
        position != null ->
            "${Compass.of(position.azimuth).label.replaceFirstChar { it.uppercaseChar() }}, " +
                "${degreesUp(position.altitude)}."
        risesAt != null -> fix.riseAzimuth
            ?.let { "Rises in the ${Compass.of(it).label} at ${clock(risesAt)}." }
            ?: "Rises at ${clock(risesAt)}."
        else -> return null
    }
    return where + facing
}

/** "24 degrees up", "1 degree up", "12 degrees down": rounded to the whole degree. */
private fun degreesUp(altitude: Double): String {
    val degrees = altitude.roundToInt()
    val size = abs(degrees)
    val unit = if (size == 1) "degree" else "degrees"
    return if (degrees < 0) "$size $unit down" else "$size $unit up"
}

/**
 * Whether tonight is known well enough for Find to say a target has gone.
 *
 * Not while it is being worked out again with no satellite elements held: a
 * night opened from the last one kept has its list but not the elements, so a
 * pass that has risen since is missing from the list and cannot be placed, and
 * the fresh run is what brings both.
 */
fun findTonightKnown(tonight: TonightList?, updating: Boolean, elements: SatellitesDocument?): Boolean =
    tonight != null && !(updating && elements == null)

/**
 * The one line that tells somebody what to do with the phone.
 *
 * [fix] null means the target cannot be placed. Once tonight is known
 * ([tonightKnown]) that is because it has gone - a pass that has set; before
 * then, as on a cold start from a pass alert, it is because the elements and
 * the night are still being read, and saying "gone" would be false. [guidance]
 * null means the compass has not given a reading yet. A target below the
 * horizon gives its rise time whatever the compass says, because no amount of
 * turning will find it.
 */
fun findInstruction(fix: TargetFix?, guidance: Guidance?, tonightKnown: Boolean): String {
    if (fix == null) return if (tonightKnown) "That one has gone for tonight." else WORKING_OUT_TONIGHT
    if (fix.position == null) {
        return fix.risesAt?.let { "Not up until ${clock(it)}." } ?: "Cannot be pointed at now."
    }
    return guidance?.let(GuideWords::instruction) ?: "Waiting for the compass."
}

/**
 * How far to trust the direction, in a sentence.
 *
 * Android publishes the accuracy as one of four levels and no figure; the
 * degrees here are what a level is worth in practice, not a promise.
 */
fun accuracyLine(accuracy: CompassAccuracy): String = when (accuracy) {
    CompassAccuracy.Unreliable -> "The compass is unreliable right now, so this could point anywhere."
    CompassAccuracy.Low -> "Compass accuracy is low, so this could be 20 degrees out."
    CompassAccuracy.Medium -> "Compass accuracy is medium, so this could be a few degrees out."
    CompassAccuracy.High -> "Compass accuracy is high."
}

/**
 * Why Line up did nothing, for a [CompassCheck.offsetFromMoon] that refused.
 *
 * The moon's own height decides two of the causes, and those are worth naming
 * because no amount of pointing will fix them. Anything else - a tilt that
 * does not match the moon's, or a correction too large to be the compass's -
 * means the phone was not on the moon.
 */
fun lineUpRefusal(moon: AltAz): String = when {
    moon.altitude < CompassCheck.MIN_MOON_ALTITUDE -> "The moon is too low to line up on."
    moon.altitude >= Guide.ZENITH_DEGREES -> "The moon is too high to line up on."
    else -> "Point at the moon first."
}

/** What Line up did, given the change it made to the correction, in degrees. */
fun lineUpResult(correctionDegrees: Double): String {
    val degrees = abs(correctionDegrees).roundToInt()
    return when (degrees) {
        0 -> "Lined up. The compass was already right."
        1 -> "Lined up. The compass was 1 degree out."
        else -> "Lined up. The compass was $degrees degrees out."
    }
}

/**
 * The name on a target's chip, with the time it comes up where that is still
 * ahead: "Saturn", "The moon, 20:40", "ISS (ZARYA), 00:52".
 *
 * Read from tonight's list rather than worked out, so a row of chips costs
 * nothing to draw. A pass is matched to the list's entry for it as
 * [FindTargets.same] matches it, a few seconds either way; one the list no
 * longer holds - it has risen, so the list rebuilt since leaves it out - is
 * named from [elements] where they know the satellite, and by its catalogue
 * number where they do not.
 */
fun chipLabel(
    target: FindTarget,
    list: TonightList,
    nowMillis: Long,
    elements: SatellitesDocument? = null,
): String {
    fun withRise(name: String, risesAt: Long?) =
        if (risesAt != null && risesAt > nowMillis) "$name, ${clock(risesAt)}" else name

    return when (target) {
        is FindTarget.Planet ->
            withRise(target.body.label, list.planets.firstOrNull { it.body == target.body }?.risesAt)
        FindTarget.Moon -> withRise("The moon", list.moon?.risesAt)
        is FindTarget.Pattern -> FindTargets.patternLabel(target.name)
        is FindTarget.Pass -> list.passes
            .filter { FindTargets.same(FindTarget.Pass(it.catalogue, it.risesAt), target) }
            .minByOrNull { abs(it.risesAt - target.risesAt) }
            ?.let { withRise(it.name, it.risesAt) }
            ?: elements?.satellites?.firstOrNull { it.catalogue == target.catalogue }
                ?.let { withRise(it.name, target.risesAt) }
            ?: "Satellite ${target.catalogue}"
        FindTarget.Aurora -> "Aurora"
    }
}

/**
 * What Find says when nothing is up to point at.
 *
 * [next] is the name of the soonest thing that will be, with the moment it
 * qualifies; null when nothing comes within the search.
 */
fun nothingToPointAt(next: Pair<String, Long>?): String =
    if (next == null) {
        "Nothing to point at right now."
    } else {
        "Nothing to point at right now. Next up: ${next.first}, from ${clock(next.second)}."
    }
