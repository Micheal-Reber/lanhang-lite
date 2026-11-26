package com.lanhang.lite.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lanhang.lite.schedule.LocalExamStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 考试提醒的排期（2026-11-26）。
 *
 * 每场考试排两条：**当天 08:00**（起床提醒）与**考前 1 小时**（收拾东西）。
 * 码段固定在 6100+，每次全量重扫：`cancelAll` 后重排未来 14 天内的考试。
 *
 * 为什么不做「7 天窗口 + 对账」那套（课前提醒是那么做的）：考试总量小（一学期十几场），
 * 全量取消 + 重排只有几十个 PendingIntent，开销可以忽略；而考试日期一改，旧的必须立刻撤，
 * 对账反而容易漏。**别把它接进课前提醒的码段** —— 两者周期完全不同（周循环 vs 一次性）。
 */
object ExamReminderScheduler {

    const val ACTION_EXAM = "com.lanhang.lite.EXAM_REMINDER"
    const val EXTRA_ID = "exam_id"
    const val EXTRA_BEFORE = "exam_before"

    private const val RC_BASE = 6100

    /** 槽位上限：40 场 × 2 条。超出的不排（一学期考不了这么多场） */
    private const val SLOT_COUNT = 80

    /** 只排未来 14 天：太远的精确闹钟在国产 ROM 上本来就不可靠，临近再排更稳 */
    private const val AHEAD_DAYS = 14L

    /** 离现在不到 1 分钟的跳过：闹钟设过去会被立刻触发，反而在大半夜弹一条 */
    private const val MIN_AHEAD_MS = 60_000L

    fun resync(context: Context) {
        val app = context.applicationContext
        cancelAll(app)
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val today = LocalDate.now()
        val limit = today.plusDays(AHEAD_DAYS)
        var slot = 0
        for (exam in LocalExamStore.upcoming(today)) {
            val date = exam.localDate() ?: continue
            if (date.isAfter(limit)) continue
            val start = exam.startTime() ?: continue
            if (slot + 2 > SLOT_COUNT) break
            // ① 当天早上 8:00
            setAlarm(am, app, slot++, date.atTime(8, 0), exam.id, before = false)
            // ② 考前 1 小时
            setAlarm(am, app, slot++, date.atTime(start).minusHours(1), exam.id, before = true)
        }
        ReminderLog.event("exam", "resync slots=$slot")
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        for (i in 0 until SLOT_COUNT) {
            runCatching { am.cancel(pending(app, i)) }
        }
    }

    private fun setAlarm(
        am: AlarmManager,
        context: Context,
        slot: Int,
        at: LocalDateTime,
        examId: String,
        before: Boolean
    ) {
        val epoch = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (epoch <= System.currentTimeMillis() + MIN_AHEAD_MS) return
        val pi = pending(context, slot, examId, before)
        runCatching {
            // 有精确闹钟权限就精确排；没有的话 setAndAllowWhileIdle 的窗口一小时起步，
            // 但考试提醒「晚一小时」仍然有意义（比课前提醒宽容）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pi)
            }
        }.onFailure { ReminderLog.event("exam", "set failed slot=$slot ${it.javaClass.simpleName}") }
    }

    /**
     * 同一个槽位对应同一个 PendingIntent。
     *
     * 取消时只传槽位（不带 extras）：`PendingIntent` 的匹配看 requestCode + Intent 的
     * action/component，extras 不参与 —— 所以取消和设置能用同一个函数。
     */
    private fun pending(
        context: Context,
        slot: Int,
        examId: String? = null,
        before: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, ExamReminderReceiver::class.java).apply {
            action = ACTION_EXAM
            examId?.let {
                putExtra(EXTRA_ID, it)
                putExtra(EXTRA_BEFORE, before)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            RC_BASE + slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
