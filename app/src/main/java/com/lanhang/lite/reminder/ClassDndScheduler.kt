package com.lanhang.lite.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课自动勿扰（移植自 Sleepy `ClassDndScheduler`，原实现参照 shiguang 的 DndSchedulerWorker）。
 *
 * 契约：
 *  - 枚举未来 [WINDOW_DAYS] 天的课程区间，只排「下一次上课开始」与「下一次下课结束」
 *    两颗配对闹钟（各自 cancel + 重排 → 幂等）。
 *  - 每次闹钟触发（[ClassDndReceiver]）先校准当下状态，再自续重排 —— 即使某颗闹钟丢失，
 *    下一颗触发也会恢复正确状态，不会永久滞留勿扰。
 *  - 勿扰切换需要系统「通知策略访问」权限；未授权时只跳过切换，闹钟链不断
 *    （用户随时可以去系统页授权）。
 *
 * 边界计算是纯函数（入参 intervals / now），不依赖 Android。
 */
class ClassDndScheduler(private val context: Context) {

    /** 下一次课程区间边界；null = 窗口内没有未来边界。 */
    data class Boundaries(val nextStart: LocalDateTime?, val nextEnd: LocalDateTime?)

    companion object {
        const val RC_START = 7701
        const val RC_END = 7702
        internal const val WINDOW_DAYS = 7L

        /**
         * 用户进入勿扰前的 filter 快照，离开时恢复。
         *
         * 用 companion 字段而不是实例字段：[ClassDndScheduler] 每次 new 实例
         * （Receiver / syncFromPrefs），实例字段会被新实例覆盖导致状态丢失。
         * 默认 ALL（系统初始值）。
         */
        @Volatile
        internal var savedFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL

        private const val DND_STATE_FILE = "lanhang_class_dnd_state"
        private const val KEY_DND_OWNED = "entered_by_app"
        private const val KEY_DND_SAVED_FILTER = "saved_filter"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val rebuildLock = Mutex()

        fun launchRebuildFromReceiver(appContext: Context) {
            scope.launch { ClassDndScheduler(appContext).rebuildAlarms() }
        }

        /** 当前时刻是否处于某节课区间 `[start, end)` 内。 */
        internal fun isCurrentlyInClass(
            intervals: List<Pair<LocalDateTime, LocalDateTime>>,
            now: LocalDateTime,
        ): Boolean = intervals.any { (s, e) -> !now.isBefore(s) && now.isBefore(e) }

        /** 未来边界：只取仍未来的最早开始 / 最早结束。 */
        internal fun nextBoundaries(
            intervals: List<Pair<LocalDateTime, LocalDateTime>>,
            now: LocalDateTime,
        ): Boundaries = Boundaries(
            nextStart = intervals.map { it.first }.filter { it.isAfter(now) }.minOrNull(),
            nextEnd = intervals.map { it.second }.filter { it.isAfter(now) }.minOrNull(),
        )
    }

    /** 开关关 / 无课表 → 直接返回；否则校准当下状态后排下一对边界闹钟。 */
    suspend fun rebuildAlarms() = rebuildLock.withLock {
        if (!ReminderPrefs.isClassDndEnabled(context)) {
            cancelAndRestore()
            return@withLock
        }
        val intervals = buildIntervals()
        if (intervals.isEmpty() && !ReminderData.hasSchedule()) {
            cancelAndRestore()
            return@withLock
        }
        val now = LocalDateTime.now()
        applyDnd(enter = isCurrentlyInClass(intervals, now))
        val b = nextBoundaries(intervals, now)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        b.nextStart?.let { setExact(am, RC_START, it) }
            ?: setExact(am, RC_START, now.plusDays(WINDOW_DAYS))
        b.nextEnd?.let { setExact(am, RC_END, it) } ?: cancelSlot(am, RC_END)
    }

    /** 开关入口（设置页 / BootReceiver 调）：开 → 重排；关 → 取消并恢复。 */
    fun syncFromPrefs() {
        if (ReminderPrefs.isClassDndEnabled(context)) {
            // 用 companion scope 而不是每次 new SupervisorJob，减少并发竞态与 GC 压力
            scope.launch { rebuildAlarms() }
        } else {
            disableAndRestore()
        }
    }

    /** 关闭开关：取消闹钟并恢复勿扰现场。 */
    fun disableAndRestore() {
        cancelAndRestore()
    }

    private fun cancelAndRestore() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelSlot(am, RC_START)
        cancelSlot(am, RC_END)
        applyDnd(enter = false)
    }

    private fun buildIntervals(): List<Pair<LocalDateTime, LocalDateTime>> {
        val today = LocalDate.now()
        val result = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        for (offsetDays in 0 until WINDOW_DAYS) {
            val date = today.plusDays(offsetDays)
            ReminderData.coursesOn(date).forEach { course ->
                val start = course.startTime ?: return@forEach
                val end = course.endTime ?: return@forEach
                val s = date.atTime(start)
                val e = date.atTime(end)
                // 跨天课罕见，保守跳过；结束不晚于开始的脏数据同样跳过
                if (e.isAfter(s)) result += s to e
            }
        }
        return result
    }

    private fun setExact(am: AlarmManager, rc: Int, at: LocalDateTime) {
        val pi = PendingIntent.getBroadcast(
            context, rc, Intent(context, ClassDndReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val epoch = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pi)
        }
    }

    private fun cancelSlot(am: AlarmManager, rc: Int) {
        val pi = PendingIntent.getBroadcast(
            context, rc, Intent(context, ClassDndReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { am.cancel(it) }
    }

    /**
     * 勿扰切换；需要「通知策略访问」权限，未授权静默跳过。
     *
     * 进入前快照用户 filter，离开时恢复 saved —— 保护 PRIORITY / ALARMS 等非 ALL 档位
     * 不被「下课恢复成 ALL」误覆盖。离开时若勿扰已不在（用户手动改走），不抢方向盘。
     */
    fun applyDnd(enter: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        if (enter) {
            if (isDndOwned(context)) return
            val current = nm.currentInterruptionFilter
            if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return
            savedFilter = current
            saveDndState(context, owned = true, saved = current)
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else if (isDndOwned(context) &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        ) {
            nm.setInterruptionFilter(loadSavedFilter(context))
            saveDndState(
                context, owned = false, saved = NotificationManager.INTERRUPTION_FILTER_ALL
            )
        } else if (isDndOwned(context)) {
            // 用户手动改了勿扰：只清归属，不覆盖他的选择
            saveDndState(
                context, owned = false, saved = NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
    }

    private fun dndPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(DND_STATE_FILE, Context.MODE_PRIVATE)

    private fun isDndOwned(context: Context): Boolean =
        dndPrefs(context).getBoolean(KEY_DND_OWNED, false)

    private fun loadSavedFilter(context: Context): Int =
        dndPrefs(context).getInt(KEY_DND_SAVED_FILTER, savedFilter)

    private fun saveDndState(context: Context, owned: Boolean, saved: Int) {
        savedFilter = saved
        dndPrefs(context).edit()
            .putBoolean(KEY_DND_OWNED, owned)
            .putInt(KEY_DND_SAVED_FILTER, saved)
            .apply()
    }
}

/**
 * 勿扰边界闹钟接收器 —— 先校准当下状态，再自续重排。
 * requestCode 只用于区分语义（日志），实际动作由「现在还在不在课内」决定。
 */
class ClassDndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderPrefs.isClassDndEnabled(context)) return
        // 校准 + 重排查库挪出主线程防 ANR；复用 companion 的单例 scope
        ClassDndScheduler.launchRebuildFromReceiver(context.applicationContext)
    }
}
