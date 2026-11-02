package studio.primeminutes.entry

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** The home-screen record button (see [RecordEntry]): red ● to start, ■ and the elapsed time while recording. */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        RecordEntry.refresh(context)
    }

    override fun onEnabled(context: Context) {
        RecordEntry.refresh(context)
    }
}
