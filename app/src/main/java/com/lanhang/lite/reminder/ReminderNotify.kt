package com.lanhang.lite.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.color.MaterialColors
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.schedule.PeriodClock
import com.lanhang.lite.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 课程提醒的通知构建（渠道 / 每日摘要 / 课前横幅）。
 *
 * 三个渠道的重要性与 Sleepy 一致：每日摘要 DEFAULT、课前提醒 HIGH、流体云 HIGH
 * （厂商实时卡片要求渠道必须是 HIGH，否则不展示）。渠道 ID 也沿用 `sleepy_*` 的命名，
 * 只是换成 `lanhang_*` 前缀 —— 渠道一旦创建就不能改重要性，换名等于重新开一个。
 */
object ReminderNotify {

    const val CHANNEL_DAILY = "lanhang_daily"
    const val CHANNEL_BEFORE_CLASS = "lanhang_before_class"
    const val CHANNEL_FLUID = "lanhang_fluid_v2"

    const val NOTIFY_DAILY = 1001
    const val NOTIFY_TOMORROW_DAILY = 1002

    /** 课前提醒固定用这个 id（多条会互相替换）——这是 Sleepy 的行为，不要改成每课不同 id。 */
    const val NOTIFY_BEFORE_CLASS = 2000

    fun ensureChannels(context: Context) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val res = LocaleHelper.wrap(app)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DAILY,
                res.getString(R.string.notif_channel_daily),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = res.getString(R.string.notif_channel_daily_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BEFORE_CLASS,
                res.getString(R.string.notif_channel_before_class),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = res.getString(R.string.notif_channel_before_class_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FLUID,
                res.getString(R.string.notif_channel_fluid),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = res.getString(R.string.notif_channel_fluid_desc) }
        )
    }

    /** 主题主色（通知 `setColor` / 厂商卡片进度色）。与全 App 一样取 Material 属性，不写死 hex。 */
    fun accentColor(context: Context): Int =
        MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            0xFF084168.toInt()
        )

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 每日摘要 / 明日预告。
     *
     * @param targetDate 摘要指向的那一天
     * @param tomorrowPreview true = 明日预告（通知 id 与文案都走「明天」那一套）
     */
    fun sendDailySummary(context: Context, targetDate: LocalDate, tomorrowPreview: Boolean) {
        val app = context.applicationContext
        val res = LocaleHelper.wrap(app)
        val courses = ReminderData.coursesOn(targetDate)
        val notificationId = if (tomorrowPreview) NOTIFY_TOMORROW_DAILY else NOTIFY_DAILY
        val now = LocalDateTime.now()
        val relativeDay = ChronoUnit.DAYS.between(now.toLocalDate(), targetDate)

        val builder = NotificationCompat.Builder(app, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification_time)
            .setColor(accentColor(app))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setTimeoutAfter(millisUntilNextMidnight(now))
            .setContentIntent(openAppIntent(app))
            .setAutoCancel(true)

        // 标题里的「号」是**当月几号**（不是周几），与 Sleepy 1.0.61 一致：
        //   「今日 8 号，您有 1 节课」/「明日 9 号，您有 2 节课」
        val dayOfMonth = targetDate.dayOfMonth

        if (courses.isEmpty()) {
            val title = when (relativeDay) {
                0L -> res.getString(R.string.notif_daily_title_no_course, dayOfMonth)
                1L -> res.getString(R.string.notif_tomorrow_title_no_course, dayOfMonth)
                else -> res.getString(R.string.notif_date_no_course, targetDate.toString())
            }
            val text = res.getString(
                if (tomorrowPreview) R.string.notif_tomorrow_text_no_course
                else R.string.notif_daily_text_no_course
            )
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            post(app, notificationId, builder.build())
            return
        }

        val title = when (relativeDay) {
            0L -> res.getString(R.string.notif_daily_title, dayOfMonth, courses.size)
            1L -> res.getString(R.string.notif_tomorrow_title, dayOfMonth, courses.size)
            else -> res.getString(R.string.notif_date_title, targetDate.toString(), courses.size)
        }
        // 正文只报第一节（Sleepy 1.0.61 就没有展开列表，BigText 与正文同文）：
        //   「第一节课 台球文化与运动 于 14:00 在 台球馆 上课」
        val first = courses.first()
        val text = res.getString(
            R.string.notif_daily_text_first,
            first.name.ifBlank { res.getString(R.string.default_course_name) },
            first.startText.ifBlank { res.getString(R.string.agenda_time_unknown) },
            first.room.ifBlank { res.getString(R.string.notif_room_unknown) }
        )

        builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        post(app, notificationId, builder.build())
    }

    /** 课前普通横幅：标题=课程名，正文=`%1$s 开始 · %2$s`（时间 · 教室）。 */
    fun sendBeforeClassBanner(context: Context, course: ReminderCourse) {
        val app = context.applicationContext
        val res = LocaleHelper.wrap(app)
        val room = course.room.ifBlank { res.getString(R.string.notif_room_unknown) }
        val start = course.startText.ifBlank { res.getString(R.string.agenda_time_unknown) }
        val text = res.getString(R.string.notif_before_class_compact, start, room)
        val notif = NotificationCompat.Builder(app, CHANNEL_BEFORE_CLASS)
            .setSmallIcon(R.drawable.ic_notification_time)
            .setColor(accentColor(app))
            .setContentTitle(course.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setTicker(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(app))
            .setAutoCancel(true)
            .build()
        post(app, NOTIFY_BEFORE_CLASS, notif)
    }

    /** 退出登录：撤掉三条常驻通知（闹钟已由调度器撤销，这里清界面上残留的）。 */
    fun cancelAll(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(NOTIFY_DAILY)
        nm.cancel(NOTIFY_TOMORROW_DAILY)
        nm.cancel(NOTIFY_BEFORE_CLASS)
    }

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        // 权限被撤销时跳过：调用方（Receiver）已经判断过一次，这里防止设置页手动测试抛异常。
        // 成败都要留痕 —— 「通知发了没」是排查「不响」时第一个要确认的事实。
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onSuccess { ReminderLog.event("notify", "id=$id ok=true") }
            .onFailure { ReminderLog.error("notify-failed", it) }
    }

    private fun millisUntilNextMidnight(now: LocalDateTime): Long {
        val next = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault())
        return ChronoUnit.MILLIS.between(now.atZone(ZoneId.systemDefault()), next)
            .coerceAtLeast(60_000L)
    }

    /** 课程结束时刻（预览/勿扰用），供设置页复用。 */
    fun endTimeText(course: ReminderCourse): String =
        PeriodClock.endText(course.endPeriod).orEmpty()
}
