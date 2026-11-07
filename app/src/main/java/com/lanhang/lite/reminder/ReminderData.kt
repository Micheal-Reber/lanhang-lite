package com.lanhang.lite.reminder

import com.lanhang.lite.schedule.DayCourses
import com.lanhang.lite.schedule.PeriodClock
import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.schedule.ScheduleWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 提醒侧的一节课：已经换成钟点与日期，调度器不再需要碰节次表。
 *
 * 时间一律取自 [PeriodClock]（全 App 唯一时间表），不要在这里另写一份映射。
 */
data class ReminderCourse(
    val date: LocalDate,
    val name: String,
    val room: String,
    val teacher: String,
    val startPeriod: Int,
    val endPeriod: Int
) {
    val startTime: LocalTime? get() = PeriodClock.start(startPeriod)
    val endTime: LocalTime? get() = PeriodClock.end(endPeriod)

    /** "08:00"；节次越界返回空串（调用方据此跳过） */
    val startText: String get() = PeriodClock.startText(startPeriod).orEmpty()

    /** "09:40"；节次越界返回空串 */
    val endText: String get() = PeriodClock.endText(endPeriod).orEmpty()

    val startEpoch: Long
        get() = startTime?.let { date.atTime(it).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            ?: 0L

    val endEpoch: Long
        get() = endTime?.let { date.atTime(it).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            ?: 0L
}

/**
 * 提醒数据源：把整学期缓存换算成「某天有哪些课」。
 *
 * 与课表页/小组件共用 [DayCourses] 的过滤与合并口径（周次、单双周、连堂、午休不合并），
 * 提醒不再自己遍历一次 `schedule[]` —— 那样两处迟早会给出不同的课程条数。
 *
 * **学期外一律无课**：用 [ScheduleWeek.rawWeekOf] 而不是 `current()`，假期里后者会钳到末周，
 * 排出来的闹钟会在暑假每天早上响一次。
 */
object ReminderData {

    /** 学期边界；null = 还没有课表缓存（此时不排任何闹钟） */
    private data class TermBounds(val cachedWeek: Int, val startWeek: Int, val endWeek: Int)

    private fun bounds(data: ScheduleData): TermBounds? {
        val info = data.weekInfo ?: return null
        val cached = info.currentWeek?.trim()?.toIntOrNull() ?: return null
        val start = info.startWeek?.trim()?.toIntOrNull() ?: return null
        val end = info.endWeek?.trim()?.toIntOrNull() ?: return null
        if (cached <= 0) return null
        return TermBounds(cached, start, end)
    }

    fun hasSchedule(): Boolean = ScheduleRepository.loadCached()?.data != null

    /** 这一天属于第几周；null = 学期外或没有缓存 */
    fun weekOf(date: LocalDate): Int? {
        val data = ScheduleRepository.loadCached()?.data ?: return null
        val b = bounds(data) ?: return null
        return ScheduleWeek.rawWeekOf(date, b.cachedWeek, b.startWeek, b.endWeek)
    }

    /** 某天的课，按开始节次排序。学期外/无缓存返回空表。 */
    fun coursesOn(date: LocalDate): List<ReminderCourse> {
        val base = ScheduleRepository.loadCached()?.data ?: return emptyList()
        return coursesOn(base, date)
    }

    private fun coursesOn(base: ScheduleData, date: LocalDate): List<ReminderCourse> {
        val b = bounds(base) ?: return emptyList()
        val week = ScheduleWeek.rawWeekOf(date, b.cachedWeek, b.startWeek, b.endWeek) ?: return emptyList()
        val data = dataForWeek(base, b, week) ?: return emptyList()
        val dayIndex = date.dayOfWeek.value - 1
        return DayCourses.of(data, dayIndex, week).map { block ->
            ReminderCourse(
                date = date,
                name = block.name,
                room = block.room,
                teacher = block.teacher,
                startPeriod = block.start,
                endPeriod = block.end
            )
        }.sortedBy { it.startPeriod }
    }

    /**
     * 某一周该用哪份数据。
     *
     * 服务端 `GetSchedule&week=N` 只返回**那一周展开的课**（见 AGENTS.md §1.2），
     * 主缓存 `schedule_cache.json` 因此只代表「落盘那一刻的那一周」：
     * 拿它去算下一周，会把「下周才开始上的课」整门漏掉，还会把本周的课当成下周的课。
     * 所以跨周必须用该周的 `schedule_wN.json`；没有就只允许「实际本周」退回主缓存，
     * 别的周宁可没有提醒，也不要按错误的课表提醒。
     */
    private fun dataForWeek(base: ScheduleData, b: TermBounds, week: Int): ScheduleData? {
        if (week == b.cachedWeek) return base
        ScheduleRepository.loadCachedWeek(week)?.data?.let { return it }
        val current = ScheduleWeek.current(b.cachedWeek, b.startWeek, b.endWeek)
        return if (week == current) base else null
    }

    /**
     * 补齐提醒窗口覆盖到的按周数据（没有就拉一次）。
     *
     * 只在「该周没有缓存、且不是主缓存本身代表的那一周」时联网，一个 7 天窗口最多两次请求；
     * 拉不到（离线/会话失效）就静默跳过，调度继续按已有数据走。
     */
    suspend fun ensureWeeksCovered(days: Int = 7) {
        val base = ScheduleRepository.loadCached()?.data ?: return
        val b = bounds(base) ?: return
        val today = LocalDate.now()
        val weeks = LinkedHashSet<Int>()
        for (offset in 0 until days) {
            val week = ScheduleWeek.rawWeekOf(
                today.plusDays(offset.toLong()), b.cachedWeek, b.startWeek, b.endWeek
            ) ?: continue
            weeks += week
        }
        weeks.filter { it != b.cachedWeek }.forEach { week ->
            if (ScheduleRepository.loadCachedWeek(week) != null) return@forEach
            runCatching {
                ScheduleRepository.refresh(week, updateWidget = false, reschedule = false)
            }
        }
    }

    /**
     * 从 [from] 起逐天找第一节还没开始的课，最多找 [days] 天。
     * `from` 当天只算「开始时间晚于 now」的课（与 Sleepy 的提醒预览一致）。
     */
    fun nextCourse(
        now: java.time.LocalDateTime = java.time.LocalDateTime.now(),
        days: Int = 60
    ): ReminderCourse? {
        val data = ScheduleRepository.loadCached()?.data ?: return null
        for (offset in 0 until days) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val candidate = coursesOn(data, date).firstOrNull { course ->
                val start = course.startTime ?: return@firstOrNull false
                if (offset == 0) date.atTime(start).isAfter(now) else true
            }
            if (candidate != null) return candidate
        }
        return null
    }

    /** 全量课程 key：提醒请求码用「日期 + 起始节次」派生，见 ReminderScheduler。 */
    fun armedKey(date: LocalDate, startPeriod: Int): Int =
        100 + ((date.toEpochDay() % 100000L) * 20 + startPeriod).toInt()
}
