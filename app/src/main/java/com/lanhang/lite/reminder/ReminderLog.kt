package com.lanhang.lite.reminder

import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.settings.Diagnostics

/**
 * 课程提醒链路埋点。
 *
 * 「不点开 App 就不响」是个黑盒：闹钟排没排、receiver 进没进、通知发没发、
 * 流体云起没起来，出问题时只能靠猜。这些事件逐条落进 `filesDir/diagnostics.log`
 * （诊断页「应用错误记录」块），把「猜」变成「看」。
 *
 * 两个约定：
 * - **不写 logcat**：诊断页采集的是 `logcat … *:W`，`Log.d` 采不到（`FluidCloudService`
 *   现有那条就是死的）；而且 logcat 会被系统冲掉，这个文件是持久的。
 * - **只记事实，不记内容**：课程名/教室/教师这类用户数据不落盘，只记数量、时间戳、
 *   成败与异常类名（`Diagnostics.redact` 再兜一层）。
 *
 * 全程 `runCatching`：埋点失败绝不能影响提醒本身。
 */
object ReminderLog {

    private const val AREA = "Reminder"

    /** 记一条事件；[detail] 用 `键=值` 拼，别写自由格式的一句话（不好检索）。 */
    fun event(step: String, detail: String = "") {
        runCatching {
            val text = if (detail.isEmpty()) step else "$step | $detail"
            Diagnostics.record(AppContext.instance, AREA, text)
        }
    }

    /** 记一条失败；异常类名与 message 由 [Diagnostics] 脱敏后落盘。 */
    fun error(step: String, error: Throwable) {
        runCatching {
            Diagnostics.record(
                AppContext.instance, AREA,
                "$step | ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
        }
    }
}
