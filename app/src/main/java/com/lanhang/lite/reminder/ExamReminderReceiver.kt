package com.lanhang.lite.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lanhang.lite.schedule.LocalExamStore
import java.time.LocalDate

/**
 * 考试提醒的投递端（2026-11-26）。
 *
 * 闹钟响 → 按 id 回查这场考试再弹通知。**必须回查**：用户可能在排期之后改了日期或删了这场，
 * 直接照搬排期时缓存的内容会弹出一条早已不存在的考试。
 */
class ExamReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExamReminderScheduler.ACTION_EXAM) return
        val id = intent.getStringExtra(ExamReminderScheduler.EXTRA_ID) ?: return
        val before = intent.getBooleanExtra(ExamReminderScheduler.EXTRA_BEFORE, false)
        val app = context.applicationContext
        ReminderNotify.ensureChannels(app)
        val exam = LocalExamStore.all().firstOrNull { it.id == id } ?: return
        // 日期被改到今天之后（或删了）就别响 —— 排期是几小时前定的，早就过期了
        val date = exam.localDate() ?: return
        if (date.isBefore(LocalDate.now())) return
        ReminderNotify.showExam(app, exam, before)
        ReminderLog.event("exam", "fired before=$before id=${id.take(6)}")
    }
}
