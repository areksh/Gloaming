package sh.arek.gloaming.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import sh.arek.gloaming.Graph
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.Verdict

/**
 * Takes the "was it worth it?" answer off a notification.
 *
 * `docs/sourcing and constraints.md` Sec. 15 has said from the beginning that
 * the default alert thresholds are the one open question that cannot be settled
 * by reading a policy or writing code, and prescribes logging activity against
 * a subjective judgement from Phase 1. Nothing did. Four open questions -
 * T_on/T_off, the polling escalation, storm-mode exit and the AuroraWatch level
 * mapping - have been waiting on data nobody was collecting, and a season of
 * real nights is the stated release gate.
 *
 * On the notification rather than in the app, and deliberately: the only moment
 * somebody knows whether an alert was worth it is the moment they come back
 * inside, and an app they would have to remember to open is an app that gets
 * one verdict in twenty. It costs no new notification at all - two actions on
 * one that was going out anyway.
 *
 * The conditions ride the action rather than being looked up here. Kp and Bz
 * come from a document replaced every few minutes and are persisted nowhere, so
 * by the time this runs the sky that prompted the alert exists only in the
 * extras - and `FLAG_IMMUTABLE`, required from Android 12, means they had to be
 * baked in when the notification was built.
 */
class WorthItReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext

        val answer = Answer.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ANSWER) }
        val conditions = Conditions.decode(intent.getStringExtra(EXTRA_CONDITIONS))
        if (answer == null || conditions == null) return

        record(application, answer, conditions)

        // The question has been answered, so the notification has nothing left
        // to say. Cancelled rather than left up: a prompt that stays after
        // being pressed reads as not having worked, and would collect a second
        // verdict about the same night.
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) {
            runCatching { NotificationManagerCompat.from(application).cancel(id) }
        }
    }

    private fun record(context: Context, answer: Answer, conditions: Conditions) {
        val now = System.currentTimeMillis()
        runCatching {
            Graph.verdicts(context).append(Verdict(now, answer, conditions))

            // Beside the probability, Kp, Bz and source that Sec. 15 asks for,
            // and in the log the user can already export and send. No position,
            // which is that log's standing contract.
            Graph.journal(context).append(now, TAG, line(answer, conditions))

            // Cleared whichever surface answered it, so the Tonight card and
            // the notification cannot both collect an answer about one night.
            val store = Graph.alertState(context)
            val held = store.read()
            if (Conditions.decode(held.pendingVerdict)?.atMillis == conditions.atMillis) {
                store.write(held.copy(pendingVerdict = null))
            }
        }.onFailure { Log.w(TAG, "could not record the verdict", it) }
    }

    private fun line(answer: Answer, conditions: Conditions): String = buildString {
        append("${conditions.channel} ${answer.name}")
        conditions.probability?.let { append(" probability=$it") }
        conditions.threshold?.let { append(" threshold=$it") }
        conditions.source?.let { append(" source=$it") }
        conditions.kp?.let { append(" kp=$it") }
        conditions.bz?.let { append(" bz=$it") }
        conditions.cloudTotal?.let { append(" cloud=$it") }
        conditions.cloudLow?.let { append(" cloudLow=$it") }
        conditions.cloudMid?.let { append(" cloudMid=$it") }
        conditions.cloudHigh?.let { append(" cloudHigh=$it") }
        conditions.peakElevation?.let { append(" elevation=${it.toInt()}") }
        conditions.magnitude?.let { append(" magnitude=$it") }
        conditions.moonInterference?.let { append(" moon=$it") }
    }

    companion object {
        const val EXTRA_ANSWER = "sh.arek.gloaming.WORTH_IT_ANSWER"
        const val EXTRA_CONDITIONS = "sh.arek.gloaming.WORTH_IT_CONDITIONS"
        const val EXTRA_NOTIFICATION_ID = "sh.arek.gloaming.WORTH_IT_NOTIFICATION"

        private const val TAG = "worth-it"
    }
}
