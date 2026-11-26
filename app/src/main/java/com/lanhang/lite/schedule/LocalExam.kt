package com.lanhang.lite.schedule

import com.google.gson.annotations.SerializedName
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * 自己添加的一场考试（2026-11-26）。
 *
 * 和 [LocalCourse] 是一对：课程按「周次 + 星期 + 节次」周期重复，考试是**一次性的绝对时刻**，
 * 所以这里直接存日期与钟点，不玩周次那套。
 *
 * 存储独立于学校课表（[LocalExamStore] 的 `local_exams.json`），学校刷新永远碰不到它。
 */
data class LocalExam(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    /** 所属学期，只作记录用（考试靠日期定位，不靠它过滤 —— 日期本身不会串学期） */
    @SerializedName("termId") val termId: String = "",
    /** `yyyy-MM-dd`（本地日期）。用字符串是为了 JSON 可读、导出好写 */
    @SerializedName("date") val date: String = "",
    /** 开始时间：从 0 点算的分钟数（540 = 09:00） */
    @SerializedName("startMinute") val startMinute: Int = 540,
    /** 结束时间：同上（660 = 11:00） */
    @SerializedName("endMinute") val endMinute: Int = 660,
    @SerializedName("subject") val subject: String = "",
    @SerializedName("location") val location: String = "",
    /** 座位号（考场排座有时单独给） */
    @SerializedName("seat") val seat: String = "",
    @SerializedName("note") val note: String = "",
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis()
) {

    fun localDate(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

    fun startTime(): LocalTime? = minuteToTime(startMinute)

    fun endTime(): LocalTime? = minuteToTime(endMinute)

    /**
     * 这场考试在课表网格里占哪几节。
     *
     * 考试时间是任意钟点（12:30、18:30 都可能），而网格只有 12 个节次格，所以：
     * ① 与节次区间有交集的，取首尾两节（9:00-11:00 → 第 1-3 节）；
     * ② 完全落在休息时段（午休 11:40-14:00）的，吸附到开始时刻之后最近的一节，
     *    否则这场考试会凭空消失 —— 「录了却看不见」比画得不准更糟。
     */
    fun periodRange(): IntRange {
        val s = startMinute.coerceIn(0, MINUTES_OF_DAY - 1)
        val e = endMinute.coerceIn(s + 1, MINUTES_OF_DAY)
        val hit = (1..PeriodClock.count).filter { p ->
            PeriodClock.startMinute(p) < e && PeriodClock.endMinute(p) > s
        }
        if (hit.isNotEmpty()) return hit.first()..hit.last()
        val next = (1..PeriodClock.count).firstOrNull { PeriodClock.startMinute(it) >= s }
        val prev = (PeriodClock.count downTo 1).firstOrNull { PeriodClock.endMinute(it) <= s }
        val p = next ?: prev ?: 1
        return p..p
    }

    /** 转成课程模型，好直接复用现有的网格 / 列表渲染（[CourseItem.examId] 负责换成考试样式） */
    fun toCourse(): Course = Course(
        name = subject,
        teacher = "",
        room = location,
        weekRange = null,
        oddEven = "0"
    )

    private fun minuteToTime(minute: Int): LocalTime? {
        val m = minute.takeIf { it in 0 until MINUTES_OF_DAY } ?: return null
        return LocalTime.of(m / 60, m % 60)
    }

    companion object {
        private const val MINUTES_OF_DAY = 24 * 60
    }
}
