package sh.arek.gloaming.widget

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.arek.gloaming.R

/**
 * That the widget actually inflates.
 *
 * `RemoteViews` is applied in the launcher's process, not this app's, so a view
 * type the framework will not accept, or an id that does not exist, throws
 * where nothing here would ever see it - the widget just shows "Problem
 * loading widget" on somebody's home screen. Applying it here is the only way
 * to find that out before they do.
 */
@RunWith(AndroidJUnit4::class)
class AuroraWidgetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theWidgetInflatesAndCarriesItsText() {
        val views = AuroraWidget.render(context)

        val inflated = views.apply(context, FrameLayout(context))

        assertNotNull(inflated)
        val headline = inflated.findViewById<TextView>(R.id.widget_headline)
        assertNotNull("no headline in the inflated widget", headline)
        assertTrue("headline is empty", headline.text.isNotEmpty())
        assertTrue(
            "no detail in the inflated widget",
            inflated.findViewById<TextView>(R.id.widget_detail).text.isNotEmpty(),
        )
    }

    /** Tapping it has to open the app; a widget that does nothing is furniture. */
    @Test
    fun theWidgetOpensTheApp() {
        val inflated = AuroraWidget.render(context).apply(context, FrameLayout(context))

        assertEquals(true, inflated.findViewById<View>(R.id.widget_root).hasOnClickListeners())
    }
}
