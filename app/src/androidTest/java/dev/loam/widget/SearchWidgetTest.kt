package dev.loam.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import dev.loam.MainActivity
import dev.loam.R
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the widget is actually offered by the installed package, and that it
 * stays the thing it claims to be.
 *
 * Queried through [AppWidgetManager] rather than read out of the manifest
 * source, for the same reason the share-sheet filters are: the source is not
 * what the system resolves against. A widget that fails to register does not
 * error — it is simply absent from the picker, which looks identical to not
 * having looked hard enough.
 */
class SearchWidgetTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun info(): AppWidgetProviderInfo? =
        AppWidgetManager.getInstance(context)
            .installedProviders
            .firstOrNull {
                it.provider == ComponentName(context, SearchWidget::class.java)
            }

    @Test
    fun theWidgetIsOfferedByThisPackage() {
        assertNotNull("SearchWidget is not in the widget picker", info())
    }

    @Test
    fun itNeverPollsBecauseItHoldsNothing() {
        // Zero on purpose. The widget carries no note count and no results, so
        // a refresh could only redraw a constant — and reading the count would
        // mean opening an index that authentication may have sealed.
        assertEquals(0, info()!!.updatePeriodMillis)
    }

    @Test
    fun itIsAHomeScreenWidgetAndResizable() {
        val widget = info()!!

        assertTrue(
            widget.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
        )
        assertTrue(
            widget.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        )
    }

    @Test
    fun theLayoutActuallyInflatesAsRemoteViews() {
        val views = RemoteViews(context.packageName, R.layout.widget_search)

        // RemoteViews accepts only a fixed set of view types, and a layout it
        // rejects does not throw anywhere a build or a normal Compose test
        // would see — it renders as "Problem loading widget" on the home
        // screen. Applying it here is the only cheap way to find that out.
        val root = views.apply(context, FrameLayout(context))

        assertNotNull(root)
        assertNotNull("widget_root is what the click listener attaches to",
            root.findViewById<View>(R.id.widget_root))
    }

    @Test
    fun theClickTargetSurvivesApplying() {
        val views = RemoteViews(context.packageName, R.layout.widget_search).apply {
            setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_FOCUS_SEARCH, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        val root = views.apply(context, FrameLayout(context))

        // Without a click listener the widget is decoration.
        assertTrue(root.findViewById<View>(R.id.widget_root).hasOnClickListeners())
    }

    @Test
    fun itDescribesItselfInThePicker() {
        val widget = info()!!
        val description = widget.loadDescription(context)?.toString()

        // The picker shows this next to the preview. Empty is legal and reads
        // as an unfinished app.
        assertTrue("no description: $description", !description.isNullOrBlank())
    }
}
