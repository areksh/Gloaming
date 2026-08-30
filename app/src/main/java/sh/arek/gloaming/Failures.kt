package sh.arek.gloaming

/**
 * What went wrong, in one line, including the part underneath.
 *
 * A failure recorded by a poller is shown on the Tonight screen and written to
 * the journal, and `message` alone is often the least useful thing about it:
 * a name that will not resolve arrives as an exception whose whole message is
 * the hostname, and a timeout as one that says `timeout`. Neither says what
 * kind of fault it was, and the exception type - which does - is thrown away.
 *
 * So the chain is read out: the top in its own words, because that sentence is
 * usually the app's own, and each cause beneath it named by type as well as by
 * message. A cause the wrapper has already stated is left out rather than said
 * twice.
 */
fun Throwable.describe(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0

    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val message = current.message?.trim().orEmpty()
        val type = current.javaClass.simpleName

        // Stated already when a wrapper has written the reason onto the end of
        // its own message, which is what wrapping one looks like. Deliberately
        // not "appears anywhere in": a name that will not resolve is a cause
        // whose whole message is the host, and the host is in the URL above it.
        if (message.isEmpty()) {
            parts += type
        } else if (parts.none { it.endsWith(message) }) {
            parts += if (depth == 0) message else "$type: $message"
        }

        // A cause chained to itself would walk forever on a background wake;
        // the depth cap catches the longer loops.
        current = current.cause.takeIf { it !== current }
        depth++
    }

    return parts.joinToString(": ")
}

/** How far down a chain to read. Deeper than anything worth showing anybody. */
private const val MAX_CAUSE_DEPTH = 5
