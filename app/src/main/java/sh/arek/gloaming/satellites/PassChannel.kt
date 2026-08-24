package sh.arek.gloaming.satellites

/**
 * Which notification channel a pass belongs to, so either can be silenced alone.
 *
 * Lives beside the passes rather than in the planner because it is a property
 * of the pass itself: a [SatellitePass] carries the channel it belongs to, set
 * where the pass is built. [of] is the usual way to work that out, but not the
 * only one - what a satellite is can depend on the document it arrived in and
 * not only on its catalogue number.
 */
enum class PassChannel(val id: String) {
    Iss("iss-passes"),
    Bright("bright-satellites"),
    ;

    companion object {
        /** The station has its own channel: it is the one most people want. */
        const val ISS_CATALOGUE = 25544

        fun of(catalogue: Int): PassChannel =
            if (catalogue == ISS_CATALOGUE) Iss else Bright
    }
}
