package sh.arek.gloaming.data

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What to ask Android for when somebody picks a background cadence.
 *
 * Three platform shapes: below API 29 the coarse grant is the whole of it, on
 * 29 both go in one request, and from 30 the background grant must be asked
 * for on its own after the coarse one, which Android answers with its settings
 * page.
 */
class BackgroundRequestTest {

    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    private val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    private fun request(sdk: Int, coarseGranted: Boolean, backgroundGranted: Boolean) =
        AndroidLocationSource.backgroundRequest(sdk, coarseGranted, backgroundGranted)

    @Test
    fun `asks for nothing once both are granted`() {
        assertEquals(emptyList<String>(), request(29, coarseGranted = true, backgroundGranted = true))
        assertEquals(emptyList<String>(), request(34, coarseGranted = true, backgroundGranted = true))
    }

    @Test
    fun `below Android 10 the coarse grant is all there is to ask`() {
        assertEquals(emptyList<String>(), request(28, coarseGranted = true, backgroundGranted = false))
        assertEquals(listOf(coarse), request(28, coarseGranted = false, backgroundGranted = false))
    }

    @Test
    fun `on Android 10 both are asked for in one request`() {
        assertEquals(
            listOf(coarse, background),
            request(29, coarseGranted = false, backgroundGranted = false),
        )
        assertEquals(
            listOf(coarse, background),
            request(29, coarseGranted = true, backgroundGranted = false),
        )
    }

    @Test
    fun `from Android 11 coarse comes first and background on its own`() {
        assertEquals(listOf(coarse), request(30, coarseGranted = false, backgroundGranted = false))
        assertEquals(listOf(background), request(30, coarseGranted = true, backgroundGranted = false))
        assertEquals(listOf(background), request(36, coarseGranted = true, backgroundGranted = false))
    }

    /** A background grant left over after coarse was withdrawn is no grant at all. */
    @Test
    fun `asks for coarse again when only the background grant is left`() {
        assertEquals(listOf(coarse), request(30, coarseGranted = false, backgroundGranted = true))
    }
}
