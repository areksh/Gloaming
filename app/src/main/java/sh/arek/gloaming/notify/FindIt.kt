package sh.arek.gloaming.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.encode

/**
 * "Find it" on a notification: opens the app on Find, aimed at whatever the
 * alert is about.
 *
 * Only the encoded target travels. The notification already says what the
 * alert is about, and a position has no business in an intent.
 */
internal object FindIt {
    const val EXTRA_FIND_TARGET = "sh.arek.gloaming.FIND"

    const val LABEL = "Find it"

    /**
     * The intent for [target].
     *
     * The target is also the intent's data. `PendingIntent` identity ignores
     * extras, so without it two alerts whose request codes happened to collide
     * would share one and the second would rewrite the first's target.
     */
    fun intent(context: Context, target: FindTarget): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.fromParts("gloaming-find", target.encode(), null))
            .putExtra(EXTRA_FIND_TARGET, target.encode())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

/**
 * Adds "Find it" for [target]. [requestCode] is the notification's own id, so
 * two alerts up at once each carry their own object.
 */
internal fun NotificationCompat.Builder.addFindIt(
    context: Context,
    target: FindTarget,
    requestCode: Int,
): NotificationCompat.Builder = addAction(
    0,
    FindIt.LABEL,
    PendingIntent.getActivity(
        context,
        requestCode,
        FindIt.intent(context, target),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ),
)
