package sh.arek.gloaming.data

import android.location.LocationManager

/**
 * When to ask the system for a new approximate fix, and from whom.
 *
 * Pure so the decisions are tested on the JVM; [AndroidLocationSource] is the
 * thin glue that carries them out.
 */
object FixPolicy {

    /**
     * How old a last-known fix may be before the foreground asks for a new one.
     *
     * Ten minutes: inside it, a fix another app caused the system to record is
     * as good as one we would wait for, and costs nothing. Past it, the phone
     * may have moved, and the freshness gate in [LocationChoice] (half an hour)
     * is too loose to be the only test.
     */
    const val RECENT_MILLIS: Long = 600_000L

    /** How long the foreground waits for a new fix, shared by every provider tried. */
    const val FOREGROUND_TIMEOUT_MILLIS: Long = 20_000L

    /** The platform's own fused provider (API 31+); not Play Services. */
    private const val FUSED = "fused"

    /** True when there is no fix, or the best one is older than [RECENT_MILLIS]. */
    fun needsFresh(best: DeviceFix?, nowMillis: Long): Boolean =
        best == null || nowMillis - best.atMillis > RECENT_MILLIS

    /**
     * The providers to ask, in order: the platform fused provider where it
     * exists (API 31+), then network, then GPS - each only if it is [enabled].
     * Asking GPS is allowed because the phone may switch it on to answer; the
     * result is still coarsened by the coarse-only permission.
     */
    fun providers(sdk: Int, enabled: Set<String>): List<String> = buildList {
        if (sdk >= 31) add(FUSED)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }.filter { it in enabled }
}
