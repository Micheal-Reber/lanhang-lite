package com.lanhang.lite.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 撤销 2026-11-07 删除的旧版「上课提醒」（`schedule/ClassReminder*.kt`）还留在系统里的闹钟。
 *
 * 为什么旧闹钟还会在：AlarmManager 里排定的闹钟不随 APK 更新自动消失——旧接收器类
 * `com.lanhang.lite.schedule.ClassReminderReceiver` 已经从代码库移除，这些闹钟触发后
 * 没有人处理，只会白唤醒设备（排期没错，是唤醒纯空转）。
 *
 * 撤销不需要旧类：PendingIntent 的匹配只看 (requestCode, 组件名字符串, action)，
 * 与类是否还在对应的 APK 里无关。所以按原组件 + 原始 action 用 FLAG_NO_CREATE 构造，
 * 能命中就撤销，本来就没有就返回 null。
 *
 * 只做一次：首次启动打标 `lanhang_legacy_cleanup/class_reminder_alarms_v1`，
 * 之后直接返回（用户换设备/重装会重新跑一遍，无害且 idempotent）。
 */
object ReminderLegacyCleanup {

    private const val PREFS = "lanhang_legacy_cleanup"
    private const val KEY_V1 = "class_reminder_alarms_v1"

    const val ACTION_ALARM = "com.lanhang.lite.schedule.CLASS_REMINDER_ALARM"
    const val ACTION_RESYNC = "com.lanhang.lite.schedule.CLASS_REMINDER_RESYNC"
    const val ACTION_CLASS_STARTED = "com.lanhang.lite.schedule.CLASS_REMINDER_STARTED"
    const val ACTION_CLASS_ENDED = "com.lanhang.lite.schedule.CLASS_REMINDER_ENDED"
    const val ACTION_TEST = "com.lanhang.lite.schedule.CLASS_REMINDER_TEST"
    const val ACTION_NEXT_DAY = "com.lanhang.lite.schedule.CLASS_REMINDER_NEXT_DAY"
    const val LEGACY_RECEIVER = "com.lanhang.lite.schedule.ClassReminderReceiver"

    fun runOnce(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_V1, false)) return
        prefs.edit().putBoolean(KEY_V1, true).apply()

        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        fun intent(action: String) = Intent()
            .setComponent(ComponentName(app.packageName, LEGACY_RECEIVER))
            .setAction(action)

        fun cancel(requestCode: Int, action: String) {
            runCatching {
                PendingIntent.getBroadcast(
                    app, requestCode, intent(action),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )?.let { am.cancel(it) }
            }
        }

        // 课程槽位：RC_SLOT_BASE = 5000，64 个槽 (AGENTS.md 旧约定，勿与新码段 100+ 混用)
        for (rc in 5000..5063) cancel(rc, ACTION_ALARM)
        // 上下课边界：5300 + (节次-1)*2 + 0/1（节次 1..11）
        for (period in 1..11) {
            cancel(5300 + (period - 1) * 2, ACTION_CLASS_STARTED)
            cancel(5300 + (period - 1) * 2 + 1, ACTION_CLASS_ENDED)
        }
        cancel(4900, ACTION_RESYNC)
        cancel(4899, ACTION_TEST)
        cancel(5450, ACTION_NEXT_DAY)
    }
}
