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

    /**
     * A launch of Starlinks still bunched into a train.
     *
     * An addition to the design's channel taxonomy rather than an oversight in
     * it. A train crowded into [Bright] would compete for the same interval as
     * an ordinary rocket body and usually lose - and a train is visible for a
     * few nights after a launch and then never again, which is exactly the sort
     * of thing a rate limit built for nightly repeats should not swallow.
     */
    Trains("starlink-trains"),
    ;

    companion object {
        /** The station has its own channel: it is the one most people want. */
        const val ISS_CATALOGUE = 25544

        /**
         * The channel a catalogue number implies, which is never [Trains]: what
         * makes a satellite part of a train is the document it arrived in, not
         * its number, so a train pass is stamped where it is built.
         */
        fun of(catalogue: Int): PassChannel =
            if (catalogue == ISS_CATALOGUE) Iss else Bright
    }
}
