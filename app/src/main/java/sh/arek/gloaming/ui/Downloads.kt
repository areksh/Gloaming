package sh.arek.gloaming.ui

import sh.arek.gloaming.net.Transfer
import sh.arek.gloaming.net.TransferState
import sh.arek.gloaming.net.Transfers

/**
 * One published object as the downloads card shows it.
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
 * @param elementsAgeMillis how old the held satellite elements are, or null.
 * @param cometsAgeMillis the same for the comet elements.
 */
fun downloadRows(
    transfers: List<Transfer>,
    elementsAgeMillis: Long?,
    cometsAgeMillis: Long?,
    now: Long,
): List<DownloadRow> {
    val held = mapOf(
        ELEMENTS to (elementsAgeMillis to isStale(elementsAgeMillis)),
        COMETS to (cometsAgeMillis to isStale(cometsAgeMillis, COMETS_STALE_AFTER_MILLIS)),
    )
    val attempts = transfers.associateBy { it.label }

    val labels = (held.keys + attempts.keys).sortedBy {
        Transfers.orderOf(it)
    }

    return labels.map { label ->
        val copy = held[label]
        val attempt = attempts[label]
        DownloadRow(
            label = label,
            lines = listOfNotNull(
                copy?.let { describeHeldCopy(it.first) },
                attempt?.let { describeTransfer(it, now) },
            ),
            alarming = copy?.second == true || attempt?.state == TransferState.Failed,
        )
    }
}

/** The two documents with a cache of their own, named as [Transfers] names them. */
private const val ELEMENTS = "Satellite elements"
private const val COMETS = "Comets"

/**
 * How old a comet document has to be before it is worth mentioning.
 *
 * A month, against three days for the elements, and the difference is real
 * rather than a hedge: SGP4 is a near-epoch model and a fortnight ruins a pass
 * prediction, while a comet's orbit is good for months and only the observed
 * magnitudes go off. Holding both to the elements' threshold would put a mark
 * on an ordinary week and teach the reader to ignore every mark.
 */
private const val COMETS_STALE_AFTER_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
