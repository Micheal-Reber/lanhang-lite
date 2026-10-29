package com.lanhang.lite.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「接下来该在哪些时刻提醒」的计算 —— 纯逻辑，不碰 AlarmManager、不读写文件。
 *
 * 拆出来的理由：这一层最容易出错（学期边界、假期、单双周、连堂合并、同一节多门课），
 * 而它只依赖课表数据和一个时间戳，可以脱离 Android 运行时单独推演。
 * 调度（[ClassReminder]）只负责把这里算出的时刻落成闹钟。
 */
object ClassReminderPlan {

    /** 预排窗口：今天起 7 个日历天。跨周自然覆盖，不需要「周一重排」这种特判。 */
    const val WINDOW_DAYS = 7

    /** 窗口内最多排多少个闹钟（7 天 × 12 节 = 84 是理论上限） */
    const val MAX_SLOTS = 84

    /**
     * 一个提醒槽位 = **某天的某一节次**，而不是「一条课程记录」。
     *
     * 为什么按节次聚合：同一时刻可能有多门课（合班、重修、数据重复下发）。按课程排会连弹两条，
     * 而且通知 id 按节次分配，两条同 id 的通知会互相覆盖。聚合后一课一条，正文里列多门。
     */
    data class Slot(
        /** 提醒所属日期 */
        val date: LocalDate,
        /** 起始节次（1..12），同时决定通知 id */
        val period: Int,
        /** 上课时刻（毫秒） */
        val startAt: Long,
        /** 提醒时刻（毫秒）= 上课时刻 − 提前量 */
        val triggerAt: Long,
        /** 这一节要上的课（通常 1 门，合班时多门） */
        val courses: List<DayCourses.Block>
    ) {
        /** 稳定标识：`epochDay-节次`。跨进程（PendingIntent extras）也用这个 */
        val key: String get() = keyOf(date, period)
    }

    fun keyOf(date: LocalDate, period: Int): String = "${date.toEpochDay()}-$period"

    /** 反解 [key]；格式不对返回 null（脏 extras 不能崩在广播里） */
    fun parseKey(key: String): Pair<LocalDate, Int>? {
        val parts = key.split('-')
        if (parts.size != 2) return null
        val day = parts[0].toLongOrNull() ?: return null
        val period = parts[1].toIntOrNull() ?: return null
        if (period !in 1..PeriodClock.count) return null
        return LocalDate.ofEpochDay(day) to period
    }

    /**
     * 某一天是第几教学周；`null` = 不在学期内（假期），调用方据此**不排闹钟**。
     *
     * 这里刻意走 [ScheduleWeek.rawWeekOf]（不钳制）而不是 `current()`：见那个函数的注释，
     * `current()` 在假期里会稳定返回末周，用它排闹钟会让整个暑假天天响。
     */
    fun semesterWeekOf(data: ScheduleData, date: LocalDate): Int? {
        val info = data.weekInfo
        val start = info?.startWeek?.trim()?.toIntOrNull() ?: 1
        val end = info?.endWeek?.trim()?.toIntOrNull() ?: 20
        val cached = info?.currentWeek?.trim()?.toIntOrNull() ?: 1
        return ScheduleWeek.rawWeekOf(date, cached, start, end)
    }

    /**
     * 某一天全部槽位（**不做时间过滤**，供触发时二次校验用）。
     */
    fun slotsOn(data: ScheduleData, date: LocalDate, advanceMinutes: Int): List<Slot> {
        val week = semesterWeekOf(data, date) ?: return emptyList()
        val blocks = DayCourses.of(data, date.dayOfWeek.value - 1, week)
        if (blocks.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val advanceMs = advanceMinutes.coerceIn(0, 60) * 60_000L
        return blocks.groupBy { it.start }
            .mapNotNull { (period, courses) ->
                val startTime = PeriodClock.start(period) ?: return@mapNotNull null
                val startAt = date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                Slot(
                    date = date,
                    period = period,
                    startAt = startAt,
                    triggerAt = startAt - advanceMs,
                    courses = courses.sortedBy { it.name }
                )
            }
            .sortedBy { it.startAt }
    }

    /**
     * 从 [now] 起 [WINDOW_DAYS] 天内、**触发时刻还没到**的槽位，按时间升序。
     *
     * 只收 `triggerAt > now`：已经过去的提醒**不补发**（关机、没电、错过就是错过）。
     * 但「闹钟响晚了」是另一回事 —— 那次仍会在 [ClassReminder.handleAlarm] 里按
     * 「现在是否还没上课」再判一次，晚几分钟也照常提醒。
     */
    fun upcoming(
        data: ScheduleData,
        now: Long,
        advanceMinutes: Int
    ): List<Slot> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val result = mutableListOf<Slot>()
        for (offset in 0 until WINDOW_DAYS) {
            val date = today.plusDays(offset.toLong())
            result += slotsOn(data, date, advanceMinutes).filter { it.triggerAt > now }
            if (result.size >= MAX_SLOTS) break
        }
        return result.take(MAX_SLOTS)
    }

    /** 设置页上「下一次提醒」那一行；没有则 null */
    fun next(data: ScheduleData, now: Long, advanceMinutes: Int): Slot? =
        upcoming(data, now, advanceMinutes).firstOrNull()
}
