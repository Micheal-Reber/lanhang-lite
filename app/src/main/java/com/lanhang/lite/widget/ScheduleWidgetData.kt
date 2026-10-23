package com.lanhang.lite.widget

import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.Session
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    private val dayNames = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("M/d")

    fun load(): WeekSnapshot? {
        if (!Session.isLoggedIn) return null
        val base = ScheduleRepository.loadCached() ?: return null
        val data = base.data ?: return null
        val startWeek = data.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val endWeek = data.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: 1
        val week = resolveWeek(cachedWeek, startWeek, endWeek)
        val source = ScheduleRepository.loadCachedWeek(week)?.data ?: data
        val monday = LocalDate.now().with(DayOfWeek.MONDAY)
        val days = (0..6).map { index ->
            DayInfo(
                date = monday.plusDays(index.toLong()),
                courses = coursesForDay(source, index, week)
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

    private fun resolveWeek(cachedWeek: Int, startWeek: Int, endWeek: Int): Int {
        val todayMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val anchorAt = Session.scheduleWeekAnchorAt
        val anchorWeek = Session.scheduleWeekAnchorWeek
        val raw = if (anchorAt > 0L && anchorWeek > 0) {
            val anchorDate = Instant.ofEpochMilli(anchorAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val anchorMonday = anchorDate.with(DayOfWeek.MONDAY)
            anchorWeek + ChronoUnit.WEEKS.between(anchorMonday, todayMonday).toInt()
        } else {
            val modified = ScheduleRepository.cacheLastModified()
            if (modified > 0L) {
                val cacheDate = Instant.ofEpochMilli(modified)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                val cacheMonday = cacheDate.with(DayOfWeek.MONDAY)
                cachedWeek + ChronoUnit.WEEKS.between(cacheMonday, todayMonday).toInt()
            } else {
                cachedWeek
            }
        }
        val from = minOf(startWeek, endWeek)
        val to = maxOf(startWeek, endWeek)
        return raw.coerceIn(from, to)
    }

    private fun coursesForDay(data: ScheduleData, dayIndex: Int, week: Int): List<CourseBlock> {
        val targetName = dayNames.getOrNull(dayIndex) ?: return emptyList()
        val day = data.schedule?.find { it.dayName == targetName } ?: return emptyList()
        val items = mutableListOf<CourseBlock>()
        for (period in day.periods.orEmpty()) {
            val number = period.periodName?.trim()?.toIntOrNull() ?: continue
            if (number !in 1..11) continue
            for (course in period.courses.orEmpty()) {
                if (!course.isWeekValid(week) || !course.isOddEvenValid(week)) continue
                val name = course.name?.trim().orEmpty().ifEmpty { "未命名课程" }
                items.add(
                    CourseBlock(
                        name = name,
                        room = course.room?.trim().orEmpty(),
                        start = number,
                        end = number
                    )
                )
            }
        }
        return merge(items.sortedBy { it.start })
    }

    private fun merge(items: List<CourseBlock>): List<CourseBlock> {
        if (items.isEmpty()) return items
        val result = mutableListOf<CourseBlock>()
        var current = items[0]
        for (index in 1 until items.size) {
            val next = items[index]
            val contiguous = next.start == current.end + 1
            val sameBreak = current.end != 4 && current.end != 8
            if (next.name == current.name && next.room == current.room && contiguous && sameBreak) {
                current = current.copy(end = next.end)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}
