package sh.arek.gloaming

import android.content.Context
import android.util.Log

/**
 * Says something, to logcat and to the app's own log at once.
 *
 * Two audiences. Logcat is for whoever has the phone plugged into a laptop
 * right now; the journal is for the morning after, when logcat's ring buffer
 * has long since rolled past the interesting part. Everything the background
 * work has to say goes to both, so neither has half the story.
 *
 * **Nothing here may carry the user's position.** The journal is written to be
 * exported and sent to somebody, and it lives in device-encrypted storage so a
 * poll before first unlock can write to it. What belongs in it is what the app
 * decided and why - outcomes, catalogue numbers, times, wake reasons - and
 * never where it was standing when it decided.
 */
object Notes {

    fun i(context: Context, tag: String, message: String) {
        Log.i(tag, message)
        record(context, tag, message)
    }

    fun w(context: Context, tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
        record(context, tag, if (error == null) message else "$message: ${error.message}")
    }

    fun e(context: Context, tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
        record(context, tag, if (error == null) message else "$message: ${error.message}")
    }

    /**
     * Honours the user's retention setting, including their turning it off.
     *
     * Reading the setting costs a preference lookup per entry, which is far
     * cheaper than the alternative: keeping a log somebody has explicitly said
     * they do not want.
     */
    private fun record(context: Context, tag: String, message: String) {
        runCatching {
            val application = context.applicationContext
            if (!Graph.settings(application).read().keepsLog) return
            Graph.journal(application).append(System.currentTimeMillis(), tag, message)
        }
    }
}
