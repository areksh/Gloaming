package sh.arek.gloaming.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import sh.arek.gloaming.Graph
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.R
import sh.arek.gloaming.data.BootAware

/**
 * The home-screen widget: the local probability and how fresh it is.
 *
 * The highest value per line of code in the project, because it answers "is it
 * worth checking?" without interrupting anybody. A notification has to justify
 * waking someone; a widget only has to be glanced at, so it can say the quiet
 * things a notification never should.
 *
 * Plain `RemoteViews` rather than Glance. This is three pieces of text, and
 * Glance would bring a Compose runtime into the launcher's process for it - a
 * poor trade in an app that already vendors rather than depends where it can.
 *
 * It is never refreshed on a schedule of its own. The system's widget update
 * period has a 30-minute floor and wakes the device to honour it, which would
 * duplicate the adaptive schedule and undo its savings; instead each poll
 * redraws it, so the widget is exactly as fresh as the data behind it.
 */
class AuroraWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, render(context))
        }
    }

    companion object {

        private const val TAG = "AuroraWidget"

        /**
         * Redraws every instance of the widget.
         *
         * Never throws. This is called from the poll receiver's own path, and
         * a widget that fails to redraw must not take the schedule down with
         * it - the alerting matters more than the glanceable copy of it.
         */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, AuroraWidget::class.java),
                )
                if (ids.isEmpty()) return

                val views = render(context)
                for (id in ids) {
                    manager.updateAppWidget(id, views)
                }
            }.onFailure { Log.w(TAG, "could not redraw the widget", it) }
        }

        /** Internal so an instrumented test can inflate it for real. */
        internal fun render(context: Context): RemoteViews {
            // The same store the poll reads, so the widget and the alerting
            // never disagree about where the user is or what was last seen.
            val settings = BootAware.pollSettings(context).read()
            val state = Graph.alertState(context).read()
            val content = WidgetContent.of(settings, state, System.currentTimeMillis())

            return RemoteViews(context.packageName, R.layout.widget_aurora).apply {
                setTextViewText(R.id.widget_headline, content.headline)
                setTextViewText(
                    R.id.widget_label,
                    if (content.worthLooking) "worth a look" else "aurora here",
                )
                setTextViewText(R.id.widget_detail, content.detail)
                setOnClickPendingIntent(R.id.widget_root, openApp(context))
            }
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
