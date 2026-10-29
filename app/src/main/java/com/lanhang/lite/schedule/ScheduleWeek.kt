package com.lanhang.lite.schedule

import com.lanhang.lite.session.Session
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「实际当前周」的唯一推算口径。
 *
 * 为什么不能直接用服务端的 `weekInfo.currentWeek`：那个值是**拉取那一刻**的周次，
 * 随缓存一起落盘后就不再变化。缓存放上一周，读出来还是上一周的周次，
 * 表现就是「过了第 8 周不自动进第 9 周，必须手动下拉刷新」。
 *
 * 正确做法是以（锚点时间 → 锚点周次）为基准，按自然周推进到今天：
 * 锚点是每次 `refresh(null)`（不带周次拉元信息）成功时写入的。
 * 没有锚点的历史缓存，退化为「缓存文件写入时间 → 缓存内周次」同一算法。
 *
 * 课表页 / 桌面小组件 / 日历导出三处必须共用这一份实现，
 * 否则同一天会各显示一个周次。
 */
object ScheduleWeek {

    /**
     * @param cachedWeek 服务端返回、随缓存落盘的周次（不是当前真实周次）
     * @param startWeek  学期首周
     * @param endWeek    学期末周
     * @param now        当前时间，测试可注入
     */
    fun current(
        cachedWeek: Int,
        startWeek: Int,
        endWeek: Int,
        now: Long = System.currentTimeMillis()
    ): Int {
        val todayMonday = mondayOf(now)
        val anchorAt = Session.scheduleWeekAnchorAt
        val anchorWeek = Session.scheduleWeekAnchorWeek
        val raw = if (anchorAt > 0L && anchorWeek > 0) {
            anchorWeek + ChronoUnit.WEEKS.between(mondayOf(anchorAt), todayMonday).toInt()
        } else {
            val modified = ScheduleRepository.cacheLastModified()
            if (modified > 0L) {
                cachedWeek + ChronoUnit.WEEKS.between(mondayOf(modified), todayMonday).toInt()
            } else {
                cachedWeek
            }
        }
        val from = minOf(startWeek, endWeek)
        val to = maxOf(startWeek, endWeek)
        return raw.coerceIn(from, to)
    }

    /**
     * **不钳制**的真实周次：`null` = 这一天不在学期区间内（假期 / 开学前）。
     *
     * 为什么提醒必须用它、不能用 [current]：`current()` 末尾 `coerceIn(startWeek, endWeek)`
     * 是为「课表页总有周可显示」服务的，假期里它会稳定返回**末周**。课表页显示末周课表可以接受，
     * 但拿它排闹钟就是整个暑假每天早上 8 点响一次「还有 15 分钟上课」。所以这里把 `coerceIn`
     * 换成区间判断，学期外一律返回 null（→ 不排闹钟）。
     *
     * **不要改 [current] 的语义**去满足提醒：课表页、桌面小组件、日历导出三处都依赖「钳制后
     * 的可显示周次」，改了三处会一起坏。
     */
    fun rawWeekOf(
        date: LocalDate,
        cachedWeek: Int,
        startWeek: Int,
        endWeek: Int
    ): Int? {
        val targetMonday = date.with(DayOfWeek.MONDAY)
        val anchorAt = Session.scheduleWeekAnchorAt
        val anchorWeek = Session.scheduleWeekAnchorWeek
        val raw = if (anchorAt > 0L && anchorWeek > 0) {
            anchorWeek + ChronoUnit.WEEKS.between(mondayOf(anchorAt), targetMonday).toInt()
        } else {
            val modified = ScheduleRepository.cacheLastModified()
            if (modified > 0L) {
                cachedWeek + ChronoUnit.WEEKS.between(mondayOf(modified), targetMonday).toInt()
            } else {
                cachedWeek
            }
        }
        val from = minOf(startWeek, endWeek)
        val to = maxOf(startWeek, endWeek)
        return if (raw in from..to) raw else null
    }

    /** 距离下一个周一 00:00:05 的毫秒数：跨周自动跳转用 */    fun millisUntilNextWeek(now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val nowTime = Instant.ofEpochMilli(now).atZone(zone)
        val nextMonday = nowTime.toLocalDate()
            .with(DayOfWeek.MONDAY)
            .plusWeeks(1)
            .atStartOfDay(zone)
            .plusSeconds(5)
        return ChronoUnit.MILLIS.between(nowTime, nextMonday).coerceAtLeast(1_000L)
    }

    private fun mondayOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .with(DayOfWeek.MONDAY)
}
