package sh.arek.gloaming.domain

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.data.LocationCadence

/**
 * What picking a cadence asks of Android, and what its answer means, decided
 * without Android.
 */
class CadenceRequestTest {

    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    private val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    // --- a pick ---

    @Test
    fun `stores a cadence that needs nothing from the background`() {
        assertEquals(
            CadencePick.Store,
            CadenceRequest.onPick(LocationCadence.OnOpen, inFlight = false, missing = listOf(background)),
        )
        assertEquals(
            CadencePick.Store,
            CadenceRequest.onPick(LocationCadence.Manual, inFlight = false, missing = listOf(background)),
        )
    }

    @Test
    fun `stores an interval once its grants are held`() {
        assertEquals(
            CadencePick.Store,
            CadenceRequest.onPick(LocationCadence.Every(6), inFlight = false, missing = emptyList()),
        )
    }

    @Test
    fun `asks for coarse first when coarse alone is missing`() {
        assertEquals(
            CadencePick.AskCoarseFirst(listOf(coarse)),
            CadenceRequest.onPick(LocationCadence.Every(6), inFlight = false, missing = listOf(coarse)),
        )
    }

    @Test
    fun `asks for whatever else is missing in one request`() {
        assertEquals(
            CadencePick.Ask(listOf(background)),
            CadenceRequest.onPick(LocationCadence.Every(6), inFlight = false, missing = listOf(background)),
        )
        assertEquals(
            CadencePick.Ask(listOf(coarse, background)),
            CadenceRequest.onPick(
                LocationCadence.Every(24),
                inFlight = false,
                missing = listOf(coarse, background),
            ),
        )
    }

    /**
     * A second request while Android still has the first is answered at once
     * with nothing granted, which would read as a refusal and throw away the
     * real answer to the first.
     */
    @Test
    fun `ignores any pick while a request is with Android`() {
        assertEquals(
            CadencePick.Ignore,
            CadenceRequest.onPick(LocationCadence.Every(12), inFlight = true, missing = listOf(background)),
        )
        assertEquals(
            CadencePick.Ignore,
            CadenceRequest.onPick(LocationCadence.OnOpen, inFlight = true, missing = emptyList()),
        )
    }

    // --- the answer ---

    @Test
    fun `granted once nothing is missing`() {
        assertEquals(CadenceAnswer.Granted, CadenceRequest.answer(missing = emptyList()))
    }

    @Test
    fun `a refused background grant is a background refusal`() {
        assertEquals(CadenceAnswer.BackgroundRefused, CadenceRequest.answer(missing = listOf(background)))
    }

    /** Without coarse the phone cannot be asked at all, which is the switch's refusal, not the cadence's. */
    @Test
    fun `a refused coarse grant is a coarse refusal whatever else is missing`() {
        assertEquals(CadenceAnswer.CoarseRefused, CadenceRequest.answer(missing = listOf(coarse)))
        assertEquals(
            CadenceAnswer.CoarseRefused,
            CadenceRequest.answer(missing = listOf(coarse, background)),
        )
    }
}
