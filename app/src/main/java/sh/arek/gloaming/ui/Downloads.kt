package sh.arek.gloaming.ui

import sh.arek.gloaming.net.Transfer
import sh.arek.gloaming.net.TransferState
import sh.arek.gloaming.net.Transfers

/**
 * One published object as the downloads report shows it.
 *
 * @param lines what is known about it, held copy first and last attempt after.
 * @param alarming whether this row is the reason something is not working.
 */
data class DownloadRow(
    val label: String,
    val lines: List<String>,
    val alarming: Boolean,
)

/**
 * What the app has been downloading, merged from the two things that know.
 *
 * The caches know what the device is working from and survive a restart; the
 * transfer record knows what happened the last time anything tried and does
 * not. Neither alone would have caught the fortnight that prompted this: the
 * fetches were succeeding - answering 304 against an object nobody upstream was
 * republishing any more - so only the age of the held copy had anything to say.
 *
 * @param held how old each document on the device is, by the label [Transfers]
 *   knows it under. A null value means the document has never been downloaded;
 *   a label absent from the map means this device keeps no copy of it to age.
 */
fun downloadRows(
    transfers: List<Transfer>,
    held: Map<String, Long?>,
    now: Long,
): List<DownloadRow> {
    val attempts = transfers.associateBy { it.label }

    return (held.keys + attempts.keys)
        .sortedBy { Transfers.orderOf(it) }
        .map { label ->
            val attempt = attempts[label]
            DownloadRow(
                label = label,
                lines = listOfNotNull(
                    if (label in held) describeHeldCopy(held[label]) else null,
                    attempt?.let { describeTransfer(it, now) },
                ),
                alarming = isStale(held[label], staleAfterMillis(label)) ||
                    attempt?.state == TransferState.Failed,
            )
        }
}

/**
 * Whether a report is worth showing at all when nobody has asked for one.
 *
 * Quiet by default, which is the whole point of it not being a card any more:
 * on an ordinary evening this is six lines saying everything is fine, and six
 * lines saying everything is fine is furniture. It speaks while a check is
 * running, after one has been run, and - regardless of either - when something
 * is actually wrong, because a fortnight of stale elements that says nothing
 * until you happen to press a button is the failure this was built for.
 */
fun worthReporting(rows: List<DownloadRow>, checking: Boolean): Boolean =
    checking || rows.any { it.alarming || it.lines.size > 1 }

/**
 * How old a document has to be before its age is worth marking.
 *
 * Only the two whose staleness silently corrupts an answer are marked. The
 * satellite elements get three days because SGP4 is a near-epoch model and a
 * fortnight puts a pass minutes and degrees from where it really is. Comets get
 * a month, their orbits being good for months and only the observed magnitudes
 * going off.
 *
 * Everything else shows its age plainly and is never marked. An old status
 * document is not a silent wrong answer - the aurora card carries its own age
 * and its own error in far more prominent type - and marking things that are
 * merely old teaches the reader to ignore every mark.
 */
private fun staleAfterMillis(label: String): Long = when (label) {
    ELEMENTS -> STALE_AFTER_MILLIS
    COMETS -> COMETS_STALE_AFTER_MILLIS
    else -> NEVER
}

/** The documents held to a threshold, named as [Transfers] names them. */
private const val ELEMENTS = "Satellite elements"
private const val COMETS = "Comets"

private const val COMETS_STALE_AFTER_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/** No age reaches this, which is how a row opts out of being marked. */
private const val NEVER: Long = Long.MAX_VALUE
