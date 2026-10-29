package com.lanhang.lite.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 上课提醒的唯一的广播接收器：闹钟触发、开机、应用更新、改系统时间、精确闹钟授权，全走这里。
 *
 * 全部处理都是**同步本地操作**（读一个 JSON 文件、排几个闹钟，几十毫秒），
 * 所以不需要 `goAsync()` + 协程（工作圈那个是因为要联网）。
 *
 * 每个分支都必须容错到底：接收器里抛异常会被系统记成「应用崩溃」，
 * 而提醒挂掉的正确表现是「安静地少一条提醒」，不是闪退。
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        try {
            when (action) {
                ClassReminder.ACTION_ALARM -> {
                    ClassReminder.handleAlarm(app, intent.getStringExtra(ClassReminder.EXTRA_KEY))
                    // 每次响完都顺手重排：让窗口继续往前滚。
                    // 这条不是唯一保障（还有 00:05 的全量重同步），但能让「某天全都没响」更快自愈。
                    ClassReminder.rearm(app)
                }

                // 上下课边界：只服务「课中提醒」那条通知（上课换文案、下课撤掉）
                ClassReminder.ACTION_CLASS_STARTED ->
                    ClassReminder.handleClassStarted(app, intent.getStringExtra(ClassReminder.EXTRA_KEY))

                ClassReminder.ACTION_CLASS_ENDED ->
                    ClassReminder.handleClassEnded(app, intent.getStringExtra(ClassReminder.EXTRA_KEY))


                ClassReminder.ACTION_RESYNC -> ClassReminder.rearm(app)

                // 开机：闹钟不会被系统持久化，不重排就再也收不到
                Intent.ACTION_BOOT_COMPLETED,
                // 应用更新：**最容易漏的一条**。只监听开机的话，每次发版所有用户的提醒会静默消失，
                // 直到他们下次主动打开 App
                Intent.ACTION_MY_PACKAGE_REPLACED,
                // 用户改了时间 / 换了时区：之前按旧时区算出的触发时刻已经不对了
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                // 用户刚在系统设置里给了「闹钟和提醒」权限：原来降级的闹钟要升级成精确的
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
                -> ClassReminder.rearm(app)
            }
        } catch (e: Throwable) {
            Log.w("ClassReminderReceiver", "failed action=$action", e)
        }
    }
}
