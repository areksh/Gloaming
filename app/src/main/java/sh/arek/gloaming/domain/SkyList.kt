package sh.arek.gloaming.domain

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * Everything up tonight, for the Sky tab: the complete list Tonight's
 * highlights are picked from.
 *
 * Tonight answers "what is worth going out for"; this answers "what is up
 * there", so nothing is cut. Three hundred passes is an ordinary night, which
 * is why they are grouped by the hour they are highest in rather than listed flat,
 * and why the ones already gone are folded away rather than leading.
 *
 * @property earlierPasses passes that have set, earliest first.
 * @property passHours the passes still to come or under way, each under the
 *   start of the local hour it is highest in, by when it is highest.
 * @property seasonal the patterns that rise and set, by when each is highest.
 * @property alwaysUp the patterns that never set from here, by name: the same
 *   every night, so the order of the night says nothing about them.
 * @property events tonight's events, comets and noctilucent prompt, by time.
 * @property filteredOut there are passes tonight, and the chosen channels hide
 *   every one - a different thing to say from a night with none.
 */
data class SkyList(
    val earlierPasses: List<SatellitePass>,
    val passHours: List<Pair<Long, List<SatellitePass>>>,
    val planets: List<PlanetTonight>,
    val seasonal: List<ConstellationTonight>,
    val alwaysUp: List<ConstellationTonight>,
    val events: List<SkyEvent>,
    val filteredOut: Boolean,
) {
    companion object {
        fun of(
            list: TonightList,
            nowMillis: Long,
            channels: Set<PassChannel>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): SkyList {
            val shown = list.passes.filter { it.channel in channels }.sortedBy { it.risesAt }
            val (gone, ahead) = shown.partition { it.setsAt <= nowMillis }
            // Under the hour each is highest in, since that is the time its
            // row says; by rise, one rising at 18:58 would read "19:03" under
            // a heading of 18:00.
            val hours = ahead
                .sortedBy { it.peaksAt }
                .groupBy { Instant.ofEpochMilli(it.peaksAt).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli() }
                .toList()
                .sortedBy { it.first }

            // A snapshot from before the full list was kept has only the
            // shortlist; that is still better than an empty section.
            val patterns = list.everyConstellation.ifEmpty { list.constellations }

            return SkyList(
                earlierPasses = gone,
                passHours = hours,
                planets = list.planets.sortedBy { it.peakAt },
                seasonal = patterns.filterNot { it.isCircumpolar }.sortedBy { it.peakAt },
                alwaysUp = patterns.filter { it.isCircumpolar }.sortedBy { it.constellation.name },
                events = (list.events + list.comets + listOfNotNull(list.noctilucent)).sortedBy { it.at },
                filteredOut = shown.isEmpty() && list.passes.isNotEmpty(),
            )
        }
    }
}
