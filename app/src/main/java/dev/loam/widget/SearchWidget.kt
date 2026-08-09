package dev.loam.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.loam.MainActivity
import dev.loam.R

/**
 * A home-screen shortcut shaped like a search field.
 *
 * Deliberately holds nothing. A widget showing the note count would have to
 * read the encrypted index, which cannot be opened at all when index
 * protection is set to require authentication — so the one piece of data worth
 * putting here is the one it must not depend on. Holding nothing also means
 * `updatePeriodMillis` can be zero: there is no state to go stale and no
 * reason to ever wake the app to redraw a constant.
 *
 * What it does buy over the launcher icon is the focused field: tapping it
 * opens Loam on Search with the keyboard already up, which is the difference
 * between a shortcut and a second app icon.
 *
 * The receiver is registered `exported="false"`, which looks wrong for
 * something the system broadcasts to and is not. `APPWIDGET_UPDATE` is a
 * protected broadcast, so the system is the only sender and is exempt from the
 * export check. Confirmed against two working widgets installed on the test
 * device — one RemoteViews, one Glance — both declaring the same.
 */
class SearchWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_search).apply {
            setOnClickPendingIntent(R.id.widget_root, launchIntent(context))
        }
        manager.updateAppWidget(widgetIds, views)
    }

    private companion object {

        /**
         * FLAG_IMMUTABLE because nothing about this should be fillable by
         * whoever holds it: the launcher does not need to add extras, and a
         * mutable PendingIntent handed to another process is a way for it to
         * start our activity with arguments we never wrote. Required from
         * API 31 regardless, and correct well before that.
         */
        fun launchIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_FOCUS_SEARCH, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
