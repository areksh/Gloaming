package sh.arek.gloaming.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import sh.arek.gloaming.domain.FindTarget

/**
 * Which label wins a place when two would overlap: the moon, then planets,
 * passes, aurora and patterns. Lower first. The moon and planets are points
 * somebody can mistake for each other; a pattern is a figure that reads
 * without its name.
 */
internal fun labelPriority(target: FindTarget): Int = when (target) {
    FindTarget.Moon -> 0
    is FindTarget.Planet -> 1
    is FindTarget.Pass -> 2
    FindTarget.Aurora -> 3
    is FindTarget.Pattern -> 4
}

/**
 * One frame of Everything mode as drawn, in the sky's frame (before the
 * roll): each target, where it is drawn, and where its label is, if it got
 * one. Kept from the draw for the tap, so what is tapped is what was drawn.
 */
internal data class EverythingLayout(val targets: List<FindTarget>, val anchors: List<Offset?>, val labels: List<Rect?>)

/**
 * Places each target's label, of [sizes], [gap] to the right of its anchor
 * and centred on it, in order of [labelPriority] and then as given. A label
 * is left out (null) for a target not drawn, where it would overlap a label
 * already placed, or where any corner would fall outside the view's circle
 * of [radius] about [centre].
 */
internal fun placeLabels(
    targets: List<FindTarget>,
    anchors: List<Offset?>,
    sizes: List<IntSize>,
    centre: Offset,
    radius: Float,
    gap: Float,
): EverythingLayout {
    val labels = MutableList<Rect?>(targets.size) { null }
    val placed = mutableListOf<Rect>()
    for (i in targets.indices.sortedBy { labelPriority(targets[it]) }) {
        val at = anchors[i] ?: continue
        val size = sizes[i]
        val box = Rect(at.x + gap, at.y - size.height / 2f, at.x + gap + size.width, at.y + size.height / 2f)
        val corners = listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight)
        if (corners.any { (it - centre).getDistance() > radius }) continue
        if (placed.any { it.overlaps(box) }) continue
        placed += box
        labels[i] = box
    }
    return EverythingLayout(targets, anchors, labels)
}

/**
 * The target a tap at [tap] picks: one whose label holds it, else the one
 * drawn nearest within [reach], else null.
 *
 * The view is drawn turned [rollDegrees] clockwise about [centre], and the
 * layout is in the sky's frame, so the tap is turned back first: on a phone
 * rolled a quarter turn, a target drawn right of the middle shows below it.
 */
internal fun pickAt(layout: EverythingLayout, tap: Offset, rollDegrees: Float, centre: Offset, reach: Float): FindTarget? {
    val turn = Math.toRadians(rollDegrees.toDouble())
    val dx = tap.x - centre.x
    val dy = tap.y - centre.y
    val sky = Offset(
        centre.x + (dx * cos(turn) + dy * sin(turn)).toFloat(),
        centre.y + (-dx * sin(turn) + dy * cos(turn)).toFloat(),
    )
    layout.labels.indexOfFirst { it?.contains(sky) == true }.takeIf { it >= 0 }?.let { return layout.targets[it] }
    return layout.anchors.indices
        .mapNotNull { i -> layout.anchors[i]?.let { i to hypot(it.x - sky.x, it.y - sky.y) } }
        .filter { it.second <= reach }
        .minByOrNull { it.second }
        ?.let { layout.targets[it.first] }
}
