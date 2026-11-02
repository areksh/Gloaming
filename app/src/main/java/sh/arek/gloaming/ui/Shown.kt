package sh.arek.gloaming.ui

import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.FindTargets
import sh.arek.gloaming.domain.SubjectRef
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitesDocument

/** What a notification's tap lands on. */
sealed interface Shown {
    /** The subject's sheet, over Sky. */
    data class Sheet(val subject: RowSubject) : Shown

    /** Sky, with a line saying why the subject itself cannot be shown. */
    data class Finished(val note: String) : Shown

    /** An event on another night: Ahead, with that event open. */
    data class Later(val starKey: String) : Shown
}

/** Said when a tap lands before tonight has been worked out. */
const val STILL_WORKING_OUT = "Tonight is still being worked out, so that cannot be shown yet."

/**
 * Where [ref] lands, given tonight as it now stands.
 *
 * Never nothing. A tap that opened the app on whatever it was last showing is
 * how a pass alert came to point at a pass no screen listed; if the pass has
 * gone, or the list has lost it, the tap says so instead.
 *
 * A pass is matched the way Find matches one, within minutes of its rise, and
 * found in the [elements] where tonight's list has dropped it - a pass under
 * way when the list was worked out is only there.
 */
fun resolve(
    ref: SubjectRef,
    list: TonightList?,
    elements: SatellitesDocument?,
    latitude: Double?,
    longitude: Double?,
    nowMillis: Long,
): Shown {
    if (list == null || latitude == null || longitude == null) return Shown.Finished(STILL_WORKING_OUT)
    return when (ref) {
        is SubjectRef.Pass -> {
            val pass = FindTargets.resolvePass(
                FindTarget.Pass(ref.catalogue, ref.risesAt), list, elements, latitude, longitude,
            )
            when {
                pass == null -> Shown.Finished("That pass is no longer in tonight's list.")
                pass.setsAt <= nowMillis -> Shown.Finished("That pass finished at ${clock(pass.setsAt)}.")
                else -> Shown.Sheet(RowSubject.Pass(pass))
            }
        }
        is SubjectRef.Event -> (list.events + list.comets + listOfNotNull(list.noctilucent))
            .firstOrNull { EventPlanner.starKey(it) == ref.starKey }
            ?.let { Shown.Sheet(RowSubject.Event(it)) }
            ?: Shown.Later(ref.starKey)
    }
}
