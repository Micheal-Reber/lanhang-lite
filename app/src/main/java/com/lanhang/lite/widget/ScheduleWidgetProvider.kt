package com.lanhang.lite.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        ScheduleWidgetUpdater.updateAll(context)
        ScheduleWidgetUpdater.scheduleMidnight(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        ScheduleWidgetUpdater.updateWidget(context, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ScheduleWidgetUpdater.updateAll(context)
        ScheduleWidgetUpdater.scheduleMidnight(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ScheduleWidgetUpdater.cancelMidnight(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ScheduleWidgetUpdater.ACTION_MIDNIGHT -> ScheduleWidgetUpdater.updateAll(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                ScheduleWidgetUpdater.updateAll(context)
                ScheduleWidgetUpdater.scheduleMidnight(context)
            }
        }
    }
}
