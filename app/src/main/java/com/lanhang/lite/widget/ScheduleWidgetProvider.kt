package com.lanhang.lite.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

open class ScheduleWidgetProvider : AppWidgetProvider() {

    /** 排版变体：false=全周课表（4×4），true=课表·小（2×2，今日列表紧凑档） */
    open val isSmall: Boolean = false

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
        // 本组件最后一个实例移除时，另一组件（课表·小）可能还有实例在用午夜闹钟
        if (ScheduleWidgetUpdater.activeCount(context) == 0) {
            ScheduleWidgetUpdater.cancelMidnight(context)
        }
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
