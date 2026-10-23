package com.lanhang.lite.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.lanhang.lite.R
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.LoginActivity
import com.lanhang.lite.ui.MainActivity
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

object ScheduleWidgetUpdater {

    const val ACTION_MIDNIGHT = "com.lanhang.lite.widget.ACTION_MIDNIGHT"

    private const val TAG = "ScheduleWidget"
    private const val ALARM_REQUEST = 4101
    private const val CLICK_REQUEST = 4200

    fun updateAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, ScheduleWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val snapshot = runCatching { ScheduleWidgetData.load() }.getOrNull()
        for (id in ids) {
            runCatching { updateWidget(app, manager, id, snapshot) }
                .onFailure { Log.e(TAG, "updateAll failed id=$id", it) }
        }
    }

    fun updateWidget(context: Context, appWidgetId: Int) {
        val app = context.applicationContext
        runCatching {
            val manager = AppWidgetManager.getInstance(app)
            val snapshot = runCatching { ScheduleWidgetData.load() }.getOrNull()
            updateWidget(app, manager, appWidgetId, snapshot)
        }.onFailure { Log.e(TAG, "updateWidget failed id=$appWidgetId", it) }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: ScheduleWidgetData.WeekSnapshot?
    ) {
        val (wDp, hDp) = sizeDp(manager.getAppWidgetOptions(appWidgetId))
        val bitmap = ScheduleWidgetRenderer.render(context, snapshot, wDp, hDp)
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_week)
        views.setImageViewBitmap(R.id.widget_bitmap, bitmap)
        views.setOnClickPendingIntent(R.id.widget_bitmap, clickPendingIntent(context, appWidgetId))
        views.setContentDescription(
            R.id.widget_bitmap,
            context.getString(R.string.widget_open_description)
        )
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun sizeDp(options: Bundle): Pair<Int, Int> {
        val hintW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val hintH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.getParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    SizeF::class.java
                )?.map { it.width to it.height }.orEmpty()
            } else {
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                (options.getParcelableArrayList<SizeF>(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES
                )?.map { it.width to it.height }).orEmpty()
            }
        } else {
            emptyList()
        }
        pickSize(sizes, hintW, hintH)?.let { picked ->
            return picked.first.roundToInt().coerceAtLeast(1) to
                picked.second.roundToInt().coerceAtLeast(1)
        }
        val width = hintW.takeIf { it > 0 }
            ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 }
            ?: 320
        val height = hintH.takeIf { it > 0 }
            ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }
            ?: 320
        return width to height
    }

    private fun pickSize(
        sizes: List<Pair<Float, Float>>,
        hintW: Int,
        hintH: Int
    ): Pair<Float, Float>? {
        val valid = sizes.filter { it.first > 0f && it.second > 0f }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first()
        val byWidth = valid.sortedByDescending { it.first }
        if (valid.size == 2 && abs(byWidth[0].first - byWidth[1].first) <= 2f) {
            return if (byWidth[0].second <= byWidth[1].second) byWidth[0] else byWidth[1]
        }
        if (hintW > 0 && hintH > 0) {
            return valid.minWithOrNull(
                compareBy<Pair<Float, Float>> {
                    abs(it.first - hintW) + abs(it.second - hintH)
                }.thenBy { it.first * it.second }
            )
        }
        return valid.minWithOrNull(
            compareBy<Pair<Float, Float>> { if (it.second >= it.first) 0 else 1 }
                .thenByDescending { it.first }
                .thenBy { it.second }
        )
    }

    fun scheduleMidnight(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            next.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            midnightPendingIntent(app)
        )
    }

    fun cancelMidnight(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(midnightPendingIntent(app))
    }

    private fun midnightPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleWidgetProvider::class.java)
            .setAction(ACTION_MIDNIGHT)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun clickPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = if (Session.isLoggedIn) {
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        } else {
            Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            CLICK_REQUEST + appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
