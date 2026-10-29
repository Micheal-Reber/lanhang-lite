package com.lanhang.lite.schedule

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.ui.MainActivity

/**
 * 上课提醒的**系统通知**（本地通知，不是推送）。
 *
 * 三条与工作圈提醒（[com.lanhang.lite.moments.MomentNotify]）刻意不同的地方：
 *
 * 1. **独立渠道** `class_reminder`：用户想关掉「每天好几条的上课提醒」但想留工作圈互动，
 *    必须能在系统设置里分开关。共用一个渠道就做不到了。
 * 2. **id 按节次分配**（`4800 + 起始节次`）：工作圈是「同类事件聚合成一条」，
 *    上课提醒是「不同课各自独立」。若用一个固定 id，8:00 的提醒会被 10:00 的顶掉，
 *    而用户还没出门就已经看不到第一条了。按节次分配后每节各留一条，能逐条滑掉。
 * 3. **不做全屏 Intent / 不用闹钟音频流**：那是叫醒服务做的事，会穿透静音；
 *    上课提醒只是提醒，打断课堂比漏响更糟（`USE_FULL_SCREEN_INTENT` 在 Android 14+
 *    也只发给闹钟/通话类应用）。
 */
object ClassReminderNotify {

    const val CHANNEL_ID = "class_reminder"

    private const val TAG = "ClassReminderNotify"

    /** 通知 id = 4800 + 节次，与工作圈的 4700 错开 */
    private const val NOTIFY_BASE = 4800

    /** 老版本「测试通知」的 id（功能已于 2026-10-29 删除，只用于把残留的那条撤掉） */
    private const val LEGACY_TEST_NOTIFY_ID = 4898

    private const val CLICK_REQUEST = 4815

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // 一次性清理：早期版本试过小米超级岛（私有 miui.focus.param），功能已删，但那个渠道
        // 会一直留在系统设置里，显示成一个永远不会有通知的「超级岛提醒」。删掉它（幂等，没有就空转）。
        runCatching { nm.deleteNotificationChannel("class_reminder_island") }
        // 渠道名只在创建那一刻落库，之后**不会**跟着 App 语言变（本机就留下过一个英文的
        // 「Class reminders」，系统设置里一直是英文）。所以名字对不上时重建一遍 ——
        // 重建只刷新名称/描述，用户自己调过的免打扰、声音等设置系统会保留。
        val text = LocaleHelper.wrap(context)
        val name = text.getString(R.string.class_reminder_channel)
        if (nm.getNotificationChannel(CHANNEL_ID)?.name == name) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = text.getString(R.string.class_reminder_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 能否弹通知：运行时权限、通知总开关、**渠道**三者都要通。
     *
     * 判定统一委托给 [NotifPermission]（工作圈互动用的是同一份），别在这里再写一遍 ——
     * 两份判定迟早不一致，而这里的漏判后果是「开关看着开着、提醒永远不出现」。
     */
    fun canNotify(context: Context): Boolean =
        NotifPermission.isGranted(context, CHANNEL_ID)

    /**
     * 弹一条课前提醒。
     *
     * @param minutesLeft 距离上课还有几分钟（按**当下**算，不是配置的提前量）：
     *   闹钟晚到几分钟时，标题会跟着显示真实剩余时间，而不是骗人的「还有 15 分钟」。
     */
    fun show(context: Context, slot: ClassReminderPlan.Slot, minutesLeft: Int) {
        if (slot.courses.isEmpty()) return
        if (!canNotify(context)) {
            Log.w(TAG, "skip class reminder period=${slot.period}: canNotify=false")
            return
        }
        ensureChannel(context)
        val text = LocaleHelper.wrap(context)
        val title = if (minutesLeft <= 0) {
            text.getString(R.string.class_reminder_title_now)
        } else {
            text.getString(R.string.class_reminder_title_minutes, minutesLeft)
        }
        val range = PeriodClock.rangeText(slot.period, slot.courses.maxOf { it.end })
        val lines = slot.courses.map { course ->
            if (course.room.isBlank()) {
                course.name
            } else {
                text.getString(R.string.class_reminder_course_line, course.name, course.room)
            }
        } + range
        val body = lines.filter { it.isNotBlank() }.joinToString("\n")
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            // 品牌蓝（#084168）：系统拿这个色当通知图标的**底色** —— 状态栏胶囊（Android 16
            // 实时动态 / 各家叫法不同）左上角那个图标圆底、以及通知栏里图标背后的色块，
            // 都取这里。不设的话系统给的是默认中性色，在深色胶囊上等于一块看不出品牌的灰底。
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setShowWhen(true)
            .setContentIntent(openSchedule(context))
        if (promotionAllowed(context)) {
            // Android 16「实时动态通知」：常驻 + 请求提升 → 系统把它画成状态栏芯片
            //（小米叫超级岛、原生叫实时活动、其他厂商叫法不同，位置是同一个）。
            // 芯片里的倒计时由系统按 `when` 自己算，不用逐秒重推。
            // **这条是常驻的**：上课那一刻的边界闹钟会把它收掉（见 ClassReminder.handleClassStarted）。
            builder.setOngoing(true)
                .setRequestPromotedOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(slot.startAt)
        } else {
            builder.setAutoCancel(true)
        }
        val notification = builder.build()
        // 不要 runCatching 静默吞掉：notify 抛异常（签名/权限/渠道）时，用户只看到
        // 「点了没反应」，排查时也拿不到任何线索。至少留下一条带原因的日志。
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFY_BASE + slot.period, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "notify period=${slot.period} failed", t)
        }
    }

    /** 撤掉某一节课那条通知（课前提醒 / 胶囊）。上课、下课、兜底清理三处都会调，幂等 */
    fun cancelSlot(context: Context, period: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFY_BASE + period) }
    }

    /**
     * 撤掉本功能发过的**全部**通知。
     *
     * 关提醒 / 退出登录时必须调：Android 16 上课前那条是 promoted **常驻**通知，
     * 用户滑不掉，如果不主动撤，界面关了开关、状态栏还挂着一条「还有 N 分钟上课」，
     * 而且永远不会自己消失（它是我们 setOngoing(true) 的）。
     */
    fun cancelAll(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        // 老版本的测试通知可能还挂在通知栏上（那时它不常驻、可滑掉，但没滑就一直留着）
        runCatching { nm.cancel(LEGACY_TEST_NOTIFY_ID) }
        for (period in 1..PeriodClock.count) {
            runCatching { nm.cancel(NOTIFY_BASE + period) }
        }
    }

    /**
     * 是否允许把通知提升成 Android 16「实时动态」（状态栏胶囊）。
     *
     * **判据必须是 [NotificationManager.canPostPromotedNotifications]**：它同时考虑
     * `POST_PROMOTED_NOTIFICATIONS`（普通权限，安装即授予）**和**用户在系统设置里对
     * 「实时活动 / 实时更新」的选择（底层是 appop，新装默认不放行）。
     *
     * 只查 `checkSelfPermission` 会恒为 true，于是 appop 未放行时仍走 promoted 路径 ——
     * 部分厂商（实测 HyperOS）会把这种「请求了提升、却没资格」的常驻通知整条丢掉，
     * 用户看到的就是「通知发不出来」。放不了就回退普通通知：**宁可没有胶囊，也不能不送达。**
     */
    fun promotionAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        // 官方判据（考虑用户对「实时活动」的设置）
        val apiOk = runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
        if (!apiOk) return false
        // 再加一道实测判据：本机（HyperOS 3）新装 appop 默认不放行，
        // 只有 `cmd appops set … allow` 之后胶囊才出现。两者都为真才请求提升。
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val op = AppOpsManager.permissionToOp("android.permission.POST_PROMOTED_NOTIFICATIONS")
            ?: return false
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 点通知进课表页。
     *
     * 用 [TaskStackBuilder] 而不是裸 `getActivity`：主界面之上没有任务栈时，
     * 按返回会直接退回桌面，用户会以为「点通知把 App 顶掉了」。
     */
    private fun openSchedule(context: Context) =
        TaskStackBuilder.create(context)
            .addNextIntent(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
            )
            .getPendingIntent(
                CLICK_REQUEST,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
}
