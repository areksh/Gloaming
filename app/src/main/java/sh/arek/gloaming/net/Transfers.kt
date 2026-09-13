package sh.arek.gloaming.net

import java.util.concurrent.atomic.AtomicInteger
import sh.arek.gloaming.describe

/** Where one published object had got to, last anything looked. */
enum class TransferState {
    /** A request is open right now. */
    Fetching,

    /** A body arrived and replaced what was held. */
    Fresh,

    /** The server answered 304: what is held is still current. */
    Unchanged,

    /** The request did not produce a usable answer. */
    Failed,
}

/**
 * One published object's last transfer.
 *
 * @param atMillis when the state was reached: when the request opened while it
 *   is [TransferState.Fetching], and when it settled otherwise.
 * @param bytes the size of the body, on [TransferState.Fresh] alone.
 * @param error why it failed, read out of the whole cause chain.
 */
data class Transfer(
    val label: String,
    val state: TransferState,
    val atMillis: Long,
    val bytes: Int? = null,
    val error: String? = null,
)

/**
 * What the app has been downloading, so that a screen can say so.
 *
 * This exists because of a fortnight nobody noticed. The upstream elements
 * stopped being republished, every conditional GET went on answering 304, and
 * the app carried on predicting passes from a two-week-old catalogue without a
 * word - while each genuine failure elsewhere went into `Log.w` and a journal
 * file, neither of which anybody reads. From the screen a broken pipeline and a
 * quiet sky looked identical.
 *
 * Deliberately in memory and deliberately shallow: one row per object, the
 * latest attempt only, cleared when the process dies. It answers "what is
 * happening now, and what just went wrong", which is what somebody watching a
 * check wants to know. It is *not* how old the held copy is - that survives
 * restarts and belongs to the caches, which already record it
 * (`SatelliteRepository.cachedAgeMillis`). The screen shows both, because they
 * are different questions and the fortnight above needed the second one.
 *
 * Written from whichever thread a fetch happens on, and read from the main one,
 * so the map is synchronised and [version] moves on every change. No flows: the
 * app has none anywhere, and the screen already re-reads on a tick.
 */
object Transfers {

    private val transfers = LinkedHashMap<String, Transfer>()
    private val revision = AtomicInteger(0)

    fun started(url: String, atMillis: Long) =
        put(url, TransferState.Fetching, atMillis)

    fun fresh(url: String, bytes: Int, atMillis: Long) =
        put(url, TransferState.Fresh, atMillis, bytes = bytes)

    fun unchanged(url: String, atMillis: Long) =
        put(url, TransferState.Unchanged, atMillis)

    fun failed(url: String, cause: Throwable, atMillis: Long) =
        put(url, TransferState.Failed, atMillis, error = cause.describe())

    private fun put(
        url: String,
        state: TransferState,
        atMillis: Long,
        bytes: Int? = null,
        error: String? = null,
    ) {
        val label = labelOf(url)
        synchronized(transfers) {
            transfers[label] = Transfer(label, state, atMillis, bytes, error)
        }
        revision.incrementAndGet()
    }

    /**
     * Every object something has tried to fetch, in a settled order.
     *
     * Ordered by [ORDER] rather than by when each landed, so the card does not
     * reshuffle itself while a check runs - a list that reorders under the
     * reader is unreadable however correct each row is. Anything unrecognised
     * sorts to the end.
     */
    fun snapshot(): List<Transfer> = synchronized(transfers) { transfers.values.toList() }
        .sortedBy { orderOf(it.label) }

    /** The labels of everything with a request open, for the line beside the bar. */
    fun inFlight(): List<String> =
        snapshot().filter { it.state == TransferState.Fetching }.map { it.label }

    /**
     * Where a label sits in the settled order, for anything merging rows in.
     *
     * Past the end for a name this does not know, which keeps an unrecognised
     * endpoint at the bottom rather than dropping it.
     */
    fun orderOf(label: String): Int =
        ORDER.indexOf(label).takeIf { it >= 0 } ?: ORDER.size

    /** Moves on every change, so a screen reading on a tick can tell. */
    fun version(): Int = revision.get()

    /** Drops everything. For tests; nothing in the app clears this. */
    fun forget() {
        synchronized(transfers) { transfers.clear() }
        revision.incrementAndGet()
    }

    /**
     * What to call the thing at a URL.
     *
     * By file name rather than by manifest endpoint name, because this sits at
     * the HTTP boundary and the manifest is not in hand there - and because the
     * name a user should read is not the key the manifest happens to use. An
     * endpoint that moves to a filename this does not know still appears, under
     * that filename, which is worse writing and better than silence.
     */
    fun labelOf(url: String): String {
        val file = url.substringBefore('?').substringAfterLast('/')
        return NAMES[file] ?: file
    }

    private val NAMES = mapOf(
        "index.json" to "Endpoints",
        "status.json" to "Space weather",
        "oval.bin" to "Aurora grid",
        "sats.json" to "Satellite elements",
        "comets.json" to "Comets",
        "forecast" to "Cloud forecast",
    )

    /** The order a poll walks them, which is the order they are worth reading in. */
    private val ORDER = listOf(
        "Endpoints",
        "Space weather",
        "Aurora grid",
        "Satellite elements",
        "Comets",
        "Cloud forecast",
    )
}
