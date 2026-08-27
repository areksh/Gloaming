package sh.arek.gloaming.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.work.WorthItReceiver

/**
 * The two buttons that turn a night into calibration data.
 *
 * Shared by all three notifiers rather than written three times, because the
 * fiddly part is identical and getting it subtly different per channel is how
 * an aurora verdict and a pass verdict end up meaning different things.
 *
 * **Two buttons, not three.** Sec. 9.5 argues that attention is the scarcest
 * thing the app spends, and a notification with three actions on it is a
 * notification somebody dismisses without reading. "Did not look" is the honest
 * third answer and it is the one nobody presses in the dark - so it lives on
 * the Tonight card instead, where somebody is already looking at a screen.
 *
 * Every field of [Conditions] is baked in here rather than looked up when the
 * button is pressed. `FLAG_IMMUTABLE` is required from Android 12 and forbids
 * the receiver filling anything in, and in any case Kp and Bz are read from a
 * document replaced every few minutes and persisted nowhere at all - by the
 * time somebody answers, the sky that prompted the alert exists only here.
 */
internal fun NotificationCompat.Builder.addWorthItActions(
    context: Context,
    conditions: Conditions,
    notificationId: Int,
): NotificationCompat.Builder {
    val encoded = conditions.encode()
    addAction(0, Answer.Worth.label, pending(context, encoded, Answer.Worth, notificationId))
    addAction(0, Answer.NotWorth.label, pending(context, encoded, Answer.NotWorth, notificationId))
    return this
}

/**
 * A request code unique to this answer about this alert.
 *
 * Two notifications on different channels would otherwise share a
 * `PendingIntent` under `FLAG_UPDATE_CURRENT`, and the second would silently
 * rewrite the first's extras - so answering a pass alert would record a verdict
 * about an aurora.
 */
private fun pending(
    context: Context,
    encoded: String,
    answer: Answer,
    notificationId: Int,
): PendingIntent = PendingIntent.getBroadcast(
    context,
    REQUEST_BASE + notificationId * Answer.entries.size + answer.ordinal,
    Intent(context, WorthItReceiver::class.java)
        .putExtra(WorthItReceiver.EXTRA_ANSWER, answer.name)
        .putExtra(WorthItReceiver.EXTRA_CONDITIONS, encoded)
        .putExtra(WorthItReceiver.EXTRA_NOTIFICATION_ID, notificationId),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Clear of the request codes the other actions in the app already use. */
private const val REQUEST_BASE = 9000
