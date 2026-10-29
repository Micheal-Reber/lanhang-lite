package com.lanhang.lite.widget

import com.lanhang.lite.schedule.DayCourses
import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.schedule.ScheduleWeek
import com.lanhang.lite.session.Session
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object ScheduleWidgetData {

    data class CourseBlock(
        val name: String,
        val room: String,
        val start: Int,
        val end: Int
    )

    data class DayInfo(
        val date: LocalDate,
        val courses: List<CourseBlock>
    )

    data class WeekSnapshot(
        val week: Int,
        val startWeek: Int,
        val endWeek: Int,
        val days: List<DayInfo>,
        val range: String
    )

    private val dateFormatter = DateTimeFormatter.ofPattern("M/d")

    /**
     * 生成小部件快照。
     *
     * @param targetDate 期望展示的日期（今日小部件翻天用）；null = 今天。
     *   会先钳制到学期范围（首周周一 ~ 末周周日），再换算成对应周次，
     *   跨周时取该周的按周缓存；无缓存则该周课程为空（不拿本周数据冒充）。
     */
    fun load(targetDate: LocalDate? = null): WeekSnapshot? {
        if (!Session.isLoggedIn) return null
        val base = ScheduleRepository.loadCached() ?: return null
        val data = base.data ?: return null
        val startWeek = data.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val endWeek = data.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: 1
        val baseWeek = resolveWeek(cachedWeek, startWeek, endWeek)
        val todayMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val week = resolveTargetWeek(baseWeek, startWeek, endWeek, todayMonday, targetDate)
        val monday = todayMonday.plusWeeks((week - baseWeek).toLong())
        val source = if (week == baseWeek) {
            ScheduleRepository.loadCachedWeek(week)?.data ?: data
        } else {
            ScheduleRepository.loadCachedWeek(week)?.data
        }
        val days = (0..6).map { index ->
            DayInfo(
                date = monday.plusDays(index.toLong()),
                courses = source?.let { coursesForDay(it, index, week) }.orEmpty()
            )
        }
        val sunday = monday.plusDays(6)
        return WeekSnapshot(
            week = week,
            startWeek = startWeek,
            endWeek = endWeek,
            days = days,
            range = "${dateFormatter.format(monday)} - ${dateFormatter.format(sunday)}"
        )
    }

    /**
     * 目标日期 → 周次：先钳制到学期区间（首周周一 ~ 末周周日），再按周差换算，
     * 最后夹在 start..end 之间。targetDate 为 null 时返回本周。
     */
    private fun resolveTargetWeek(
        baseWeek: Int,
        startWeek: Int,
        endWeek: Int,
        baseMonday: LocalDate,
        targetDate: LocalDate?
    ): Int {
        if (targetDate == null) return baseWeek
        val first = minOf(startWeek, endWeek)
        val last = maxOf(startWeek, endWeek)
        val semesterStart = baseMonday.plusWeeks((first - baseWeek).toLong())
        val semesterEnd = semesterStart.plusWeeks((last - first).toLong()).plusDays(6)
        val date = when {
            targetDate.isBefore(semesterStart) -> semesterStart
            targetDate.isAfter(semesterEnd) -> semesterEnd
            else -> targetDate
        }
        val deltaWeeks = ChronoUnit.WEEKS.between(baseMonday, date.with(DayOfWeek.MONDAY))
        return (baseWeek + deltaWeeks.toInt()).coerceIn(first, last)
    }

    /** 目标日期对应的周次（供导航时判断是否需要补拉该周缓存）；未登录/无缓存返回 null */
    fun targetWeekOf(targetDate: LocalDate): Int? {
        if (!Session.isLoggedIn) return null
        val data = ScheduleRepository.loadCached()?.data ?: return null
        val startWeek = data.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val endWeek = data.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: 1
        val baseWeek = resolveWeek(cachedWeek, startWeek, endWeek)
        return resolveTargetWeek(
            baseWeek, startWeek, endWeek, LocalDate.now().with(DayOfWeek.MONDAY), targetDate
        )
    }

    private fun resolveWeek(cachedWeek: Int, startWeek: Int, endWeek: Int): Int =
        ScheduleWeek.current(cachedWeek, startWeek, endWeek)

    /**
     * 数据变换在 [DayCourses] 里，这里只做一次模型转换。
     *
     * 为什么不用同一个模型：`CourseBlock` 是小组件渲染器的视图模型（1000+ 行代码都按它写的），
     * 而 [DayCourses] 要同时给「提醒调度」用。把渲染器的模型搬进 schedule 包会让两边互相牵扯，
     * 转换一行是更小的代价。
     */
    private fun coursesForDay(data: ScheduleData, dayIndex: Int, week: Int): List<CourseBlock> =
        DayCourses.of(data, dayIndex, week).map {
            CourseBlock(name = it.name, room = it.room, start = it.start, end = it.end)
        }
}
