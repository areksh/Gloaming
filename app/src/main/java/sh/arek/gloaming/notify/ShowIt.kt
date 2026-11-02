package sh.arek.gloaming.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.domain.SubjectRef
import sh.arek.gloaming.domain.encode

/**
 * A notification's tap: opens the app on what the alert was about - its sheet
 * over Sky, or the calendar for a later night - rather than on whatever tab it
 * was last left on.
 *
 * Beside [FindIt], not instead of it: the tap is "tell me about it", the
 * action is "point me at it".
 */
internal object ShowIt {
    const val EXTRA_SHOW_SUBJECT = "sh.arek.gloaming.SHOW"

    /**
     * The intent for [ref]. The reference is the intent's data too, with a
     * scheme of its own, for [FindIt.intent]'s reason: `PendingIntent`
     * identity ignores extras, so two taps differing only in theirs would
     * share one and the second would rewrite the first.
     */
    fun intent(context: Context, ref: SubjectRef): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.fromParts("gloaming-show", ref.encode(), null))
            .putExtra(EXTRA_SHOW_SUBJECT, ref.encode())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The tap for [ref]; [requestCode] is the notification's own id. */
    fun pendingIntent(context: Context, ref: SubjectRef, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent(context, ref),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
