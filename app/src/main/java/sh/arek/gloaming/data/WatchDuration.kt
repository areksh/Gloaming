package sh.arek.gloaming.data

/**
 * How long a watch the user started runs before stopping itself.
 *
 * This was a constant - four hours, described in the code as "a safety net, not
 * a feature". It is honestly both, and the two halves belong to different
 * people. How long somebody means to stand outside is their call, the same
 * reasoning that made the interruption intervals settings rather than
 * constants; and a forgotten watch must still end, because it polls every two
 * minutes and holds a wake lock the whole time.
 *
 * So the span is the user's and the ceiling is not.
 */
enum class WatchDuration(val label: String, private val spanMillis: Long?) {
    OneHour("1 hour", 60 * 60_000L),
    TwoHours("2 hours", 2 * 60 * 60_000L),
    FourHours("4 hours", 4 * 60 * 60_000L),
    EightHours("8 hours", 8 * 60 * 60_000L),

    /**
     * Until astronomical dawn where the observer is standing.
     *
     * The natural end of an aurora watch - there is genuinely nothing to see
     * after it - and an option almost no other app could offer, because this one
     * already computes the dark-sky window for the alerting rules.
     */
    UntilLight("Until it gets light", null),
    ;

    /**
     * How long to run from [now], given when darkness ends.
     *
     * [darknessEndsAt] is null where the sun never gets far enough below the
     * horizon, which is most of a high-latitude summer. That is not an edge case
     * to be surprised by later: it is the normal state of the sky this app is
     * built for, in the months it has least to say.
     */
    fun capMillis(now: Long, darknessEndsAt: Long?): Long {
        val wanted = spanMillis
            ?: darknessEndsAt?.minus(now)?.takeIf { it > 0L }
            ?: MAXIMUM_MILLIS
        return wanted.coerceAtMost(MAXIMUM_MILLIS)
    }

    companion object {
        /**
         * The longest any watch may run, whatever was asked for.
         *
         * Long enough for a real winter night's watch, short enough to bound a
         * bug holding a wake lock. Deliberately not a setting: an unbounded
         * watch is a flat battery by morning, and it lands on exactly the person
         * who tapped the switch at eleven and fell asleep.
         */
        const val MAXIMUM_MILLIS: Long = 8 * 60 * 60_000L

        val DEFAULT: WatchDuration = FourHours

        /** Tolerant of a stored value from a version that had different ones. */
        fun of(name: String?): WatchDuration =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
