package sh.arek.gloaming.domain

import sh.arek.gloaming.data.LocationCadence

/** What to do with a cadence somebody picked. */
sealed interface CadencePick {
    /** Android still has the last question; this pick is dropped, not queued. */
    data object Ignore : CadencePick

    /** Nothing to ask: store it. */
    data object Store : CadencePick

    /** Ask for coarse alone; the background grant is asked for once it is answered. */
    data class AskCoarseFirst(val permissions: List<String>) : CadencePick

    /** Ask for these in one request, and settle on the answer. */
    data class Ask(val permissions: List<String>) : CadencePick
}

/** What Android's last answer about a background cadence comes to. */
enum class CadenceAnswer { Granted, BackgroundRefused, CoarseRefused }

/**
 * The background-grant exchange behind the cadence choice, without Android.
 */
object CadenceRequest {

    /**
     * What picking [cadence] should do, given whether a request is already
     * [inFlight] and what is still [missing] (see `AndroidLocationSource.backgroundRequest`).
     *
     * A pick while a request is in flight is dropped. Android answers a second
     * request made before the first returns at once and with nothing granted,
     * which would be taken for a refusal and throw away the real answer.
     */
    fun onPick(cadence: LocationCadence, inFlight: Boolean, missing: List<String>): CadencePick =
        when {
            inFlight -> CadencePick.Ignore
            cadence !is LocationCadence.Every || missing.isEmpty() -> CadencePick.Store
            missing == listOf(COARSE) -> CadencePick.AskCoarseFirst(missing)
            else -> CadencePick.Ask(missing)
        }

    /**
     * What the grants still [missing] after Android's last answer mean.
     *
     * Coarse refused outranks everything: without it the phone cannot be asked
     * for a position at all, so it is the refusal the location switch already
     * handles, not a cadence that fell back.
     */
    fun answer(missing: List<String>): CadenceAnswer = when {
        COARSE in missing -> CadenceAnswer.CoarseRefused
        missing.isEmpty() -> CadenceAnswer.Granted
        else -> CadenceAnswer.BackgroundRefused
    }

    private const val COARSE = android.Manifest.permission.ACCESS_COARSE_LOCATION
}
