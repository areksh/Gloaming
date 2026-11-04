package sh.arek.gloaming.domain

import kotlin.math.hypot
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.SkyProjection

/**
 * What Find's Everything mode has in view and under its reticle, from where
 * the phone points and the targets that are up.
 *
 * Pure, so it is tested on the JVM; the screen reads it as derived state of
 * the compass reading.
 */
object EverythingView {

    /**
     * The targets drawn within [radiusDegrees] of the middle of the view,
     * nearest first.
     *
     * Measured on the drawing ([SkyProjection.around]) and not on the sky, so
     * the names agree with what the circle shows: the projection stretches
     * outwards, and a target 29.9 degrees off is drawn just outside a 30
     * degree circle. A target behind the viewer, or with no position, is not
     * in view.
     */
    fun inView(pointing: AltAz, items: List<Pair<FindTarget, TargetFix>>, radiusDegrees: Double): List<FindTarget> =
        items.mapNotNull { (target, fix) ->
            val drawn = fix.position?.let { SkyProjection.around(pointing, it) } ?: return@mapNotNull null
            val off = hypot(drawn.x, drawn.y)
            if (off <= radiusDegrees) target to off else null
        }.sortedBy { it.second }.map { it.first }

    /**
     * The target nearest where the phone points, on the sky, if it is within
     * [Guide.ALIGNED_DEGREES]: what the reticle is on, for its lined-up look
     * and the buzz. Of two that close, the nearer.
     */
    fun underReticle(pointing: AltAz, items: List<Pair<FindTarget, TargetFix>>): FindTarget? =
        items.mapNotNull { (target, fix) -> fix.position?.let { target to Guide.separation(pointing, it) } }
            .filter { it.second <= Guide.ALIGNED_DEGREES }
            .minByOrNull { it.second }
            ?.first
}
