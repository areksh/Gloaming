package sh.arek.gloaming.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.domain.FindTarget

/** Where Everything mode's labels go, and which target a tap picks. */
class EverythingLayoutTest {

    private val centre = Offset(160f, 160f)
    private val radius = 160f
    private val gap = 6f
    private val size = IntSize(60, 20)

    private val jupiter = FindTarget.Planet(SkyBody.Jupiter)
    private val orion = FindTarget.Pattern("Orion")

    private fun place(targets: List<FindTarget>, anchors: List<Offset?>) =
        placeLabels(targets, anchors, targets.map { size }, centre, radius, gap)

    @Test
    fun `places a lone label to the right of its target`() {
        val layout = place(listOf(jupiter), listOf(Offset(100f, 160f)))

        assertEquals(Rect(106f, 150f, 166f, 170f), layout.labels[0])
    }

    @Test
    fun `gives the planet the place over an overlapping pattern`() {
        val layout = place(listOf(orion, jupiter), listOf(Offset(100f, 160f), Offset(104f, 162f)))

        assertNull(layout.labels[0])
        assertEquals(Rect(110f, 152f, 170f, 172f), layout.labels[1])
    }

    @Test
    fun `drops a label that would leave the circle`() {
        assertNull(place(listOf(jupiter), listOf(Offset(290f, 160f))).labels[0])
    }

    @Test
    fun `drops the label of a target not drawn`() {
        assertNull(place(listOf(jupiter), listOf(null)).labels[0])
    }

    @Test
    fun `picks by label`() {
        val layout = place(listOf(jupiter), listOf(Offset(100f, 160f)))

        assertEquals(jupiter, pickAt(layout, Offset(150f, 165f), 0f, centre, radius, 32f))
    }

    @Test
    fun `picks the nearest target within reach`() {
        val layout = EverythingLayout(listOf(orion, jupiter), listOf(Offset(100f, 100f), Offset(130f, 100f)), listOf(null, null))

        assertEquals(jupiter, pickAt(layout, Offset(120f, 100f), 0f, centre, radius, 32f))
    }

    @Test
    fun `picks nothing out of reach`() {
        val layout = EverythingLayout(listOf(jupiter), listOf(Offset(100f, 100f)), listOf(null))

        assertNull(pickAt(layout, Offset(300f, 300f), 0f, centre, radius, 32f))
    }

    /** Turned a quarter clockwise, a target drawn right of centre shows below it, and is picked there. */
    @Test
    fun `picks through the roll`() {
        val layout = EverythingLayout(listOf(jupiter), listOf(Offset(210f, 160f)), listOf(null))

        assertEquals(jupiter, pickAt(layout, Offset(160f, 210f), 90f, centre, radius, 32f))
        assertNull(pickAt(layout, Offset(210f, 160f), 90f, centre, radius, 32f))
    }

    /** A target placed just outside the circle is clipped from the drawing, so a tap near the rim must not pick it. */
    @Test
    fun `picks nothing drawn outside the circle`() {
        val layout = EverythingLayout(listOf(orion, jupiter), listOf(Offset(325f, 160f), Offset(280f, 160f)), listOf(null, null))

        assertEquals(jupiter, pickAt(layout, Offset(310f, 160f), 0f, centre, radius, 32f))
    }
}
