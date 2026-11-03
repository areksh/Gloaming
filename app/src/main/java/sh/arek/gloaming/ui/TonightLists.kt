package sh.arek.gloaming.ui

import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.Excluded
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.TonightList

/**
 * One entry in a list under the timeline.
 *
 * @property title null for a line that stands on its own, such as the
 *   constellations that never set.
 * @property note why something is worth less than it sounds, where it is.
 * @property alarming something is wrong rather than merely absent, which the
 *   screen says in weight as well as colour.
 */
data class ListEntry(
    val title: String?,
    val detail: String?,
    val note: String? = null,
    val happeningNow: Boolean = false,
    val alarming: Boolean = false,
)

/**
 * The two lists under the timeline, worked out from the same night it draws.
 *
 * "Also up tonight" is everything up that the timeline does not already show
 * and the outlook did not set aside: the constellations and comets, which
 * have no row, and anything else the timeline could not place - all of it,
 * on a night with no sunset to draw. "Not worth it tonight" is each thing the
 * outlook set aside with the reason it gave, so nothing is said twice below
 * the timeline and nothing up tonight goes unsaid. Pure and clock-free.
 */
object TonightLists {

    fun alsoUp(list: TonightList, timeline: Timeline?, report: OutlookReport?, nowMillis: Long): List<ListEntry> {
        val rows = timeline?.rows.orEmpty().map { it.subject }
        val excluded = report?.excluded.orEmpty()

        fun drawn(subject: RowSubject) = subject in rows
        fun setAside(event: SkyEvent) = excluded.any { it.subject == event.title && it.atMillis == event.at }

        fun event(event: SkyEvent) = ListEntry(
            title = event.title,
            detail = event.detail,
            note = caveat(event),
            happeningNow = event.isUnderWay(nowMillis),
        )

        val entries = mutableListOf<ListEntry>()
        // Worked out for tonight rather than dated: a suggestion to look, not
        // something under way.
        list.noctilucent?.let { entries += event(it).copy(happeningNow = false) }
        list.events.filter { !drawn(RowSubject.Event(it)) && !setAside(it) }.forEach { entries += event(it) }
        list.comets.filter { !setAside(it) }.forEach { entries += event(it) }

        // Up and with no row to say so: up all night, or on a night with no timeline.
        list.moon?.takeIf { it.upFraction > 0.0 && !drawn(RowSubject.Moon) }?.let { moon ->
            val phase = "${describeMoonPhase(moon.phaseDegrees, moon.illumination)}, " +
                "${(moon.illumination * 100).roundToInt()}% lit."
            entries += ListEntry(
                "The moon",
                listOfNotNull(phase, describeMoonlight(moon), describeMoonTimes(moon)).joinToString(" "),
            )
        }

        list.planets
            .filter { planet -> !drawn(RowSubject.Planet(planet)) && excluded.none { it.subject == planet.body.label } }
            .forEach { entries += ListEntry(it.body.label, describePlanet(it)) }
        list.bestPasses
            .filter { pass ->
                !drawn(RowSubject.Pass(pass)) &&
                    excluded.none { it.subject == pass.name && it.atMillis == pass.peaksAt }
            }
            .forEach { entries += ListEntry(it.name, describePassLine(it)) }

        list.constellations
            .filter { !drawn(RowSubject.Constellation(it)) }
            .forEach { entries += ListEntry(it.constellation.name, describeConstellation(it)) }
        if (list.constellationsAlwaysUp.isNotEmpty()) {
            entries += ListEntry(null, describeAlwaysUp(list.constellationsAlwaysUp))
        }
        return entries
    }

    /**
     * Each thing the outlook set aside, and then the satellites where there
     * is nothing to list for them - which is two different sentences, because
     * "nothing crossing" and "no catalogue to look in" are the same empty
     * list and must never read the same.
     *
     * A planet is given no time: it is judged at a moment that moves with the
     * clock, and "Jupiter at 20:05" would name a time nothing happens at.
     */
    fun notWorth(
        list: TonightList,
        report: OutlookReport?,
        everChecked: Boolean,
        elementsFailure: String?,
        nowMillis: Long,
    ): List<ListEntry> {
        val planets = list.planets.map { it.body.label }.toSet()

        fun title(e: Excluded): String {
            val at = e.atMillis?.takeIf { e.subject !in planets } ?: return e.subject
            return "${e.subject} at ${clock(at)}"
        }

        val entries = mutableListOf<ListEntry>()
        report?.let { held ->
            held.excluded.forEach { entries += ListEntry(title(it), OutlookWords.excluded(it, held.threshold, nowMillis)) }
        }

        if (!list.elementsHeld) {
            val note = describeMissingElements(everChecked, elementsFailure)
            entries += ListEntry("Satellites", note.text, alarming = note.alarming)
        } else if (list.passes.isEmpty()) {
            entries += ListEntry("Satellites", "Nothing crossing tonight that clears the horizon in sunlight.")
        }
        return entries
    }

    private fun caveat(event: SkyEvent): String? = when (val visibility = event.visibility) {
        is Visibility.Marginal -> visibility.reason
        is Visibility.NotFromHere -> visibility.reason
        Visibility.Good -> null
    }
}

const val ALSO_UP_TAG = "also-up"

const val NOT_WORTH_TAG = "not-worth"
