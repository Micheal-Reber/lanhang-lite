package com.lanhang.lite.reminder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 提醒重排枢纽（移植自 Sleepy `ReminderRescheduler`）。
 *
 * 编辑课表连续保存 / 提醒设置连点开关时，旧链路每次都直接 `scheduleAll()`（cancelAll + 重排 7 天闹钟），
 * 高频全量重排既浪费，又在 cancel/重排间隙留下「无闹钟」窗口。
 *
 * 契约：`request()` 幂等触发，静默 [DEBOUNCE_MS] 后才执行一次 —— 窗口内任意次 request 合并为一次。
 * 冷启动路径（BootReceiver 等）继续直调 `ReminderScheduler.scheduleAll()`，不经过本枢纽。
 */
object ReminderRescheduler {

    private const val DEBOUNCE_MS = 300L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    fun request() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            ReminderScheduler.scheduleAll()
        }
    }
}
