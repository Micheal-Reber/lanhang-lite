package com.lanhang.lite.schedule

import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper

/**
 * 「某天有哪些课」的唯一实现。
 *
 * 2026-10-27 从 `widget/ScheduleWidgetData` 抽出来：桌面小组件要它，上课提醒也要它。
 * 提醒不能去 import `widget` 包 —— 那是**渲染**层（Bitmap、RemoteViews、翻天导航），
 * 提醒是**调度**层，两者共用的只是这一步数据变换。抽到这里，谁都能调，且只有一份口径
 * （周次过滤、单双周、连堂合并、跨午休不合并全在里面）。
 *
 * 入参是 [ScheduleData]（整学期全量）而不是某个周的快照：服务端每条课程自带 `周次` 字段，
 * 任意一周都能本地算出来，不需要按周拉数据。
 */
object DayCourses {

    /** 合并后的一节课：[start]~[end] 是节次区间（连堂会合成一条） */
    data class Block(
        val name: String,
        val room: String,
        val start: Int,
        val end: Int
    )

    private val dayNames = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    )

    /**
     * @param dayIndex 0 = 周一 … 6 = 周日
     * @param week     教学周（用于过滤 `周次` 与单双周）
     * @return 按开始节次排序、已合并连堂的课程；当天没课返回空表
     */
    fun of(data: ScheduleData, dayIndex: Int, week: Int): List<Block> {
        val targetName = dayNames.getOrNull(dayIndex) ?: return emptyList()
        // 学校这天没课不代表没有自建课，因此这里不能提前 return
        val day = data.schedule?.find { it.dayName == targetName }
        val items = mutableListOf<Block>()
        for (period in day?.periods.orEmpty()) {
            val number = period.periodName?.trim()?.toIntOrNull() ?: continue
            // 作息表 12 节（第 12 节 21:45-22:30）；服务端偶尔下发 0 或越界节次，直接跳过
            if (number !in 1..PeriodClock.count) continue
            for (course in period.courses.orEmpty()) {
                if (!course.isWeekValid(week) || !course.isOddEvenValid(week)) continue
                val name = course.name?.trim().orEmpty().ifEmpty {
                    // 兜底文案跟着应用语言走（这里是 Application context），见 LocaleHelper
                    LocaleHelper.wrap(AppContext.instance)
                        .getString(R.string.schedule_unnamed_course)
                }
                items.add(
                    Block(
                        name = name,
                        room = course.room?.trim().orEmpty(),
                        start = number,
                        end = number
                    )
                )
            }
        }
        // 自建层：与网格同一套口径（1..PeriodClock.count 夹取、按周过滤），供小组件与上课提醒使用。
        // 与学校课同一时刻时，[merge] 的「相交即合并」只在同名同教室时生效，
        // 不同课仍各自成块 —— 提醒按节次聚合成一条、正文列多门（见 ClassReminderPlan.slotsOn）。
        LocalCourseStore.forDay(data.termId.orEmpty(), dayIndex, week).forEach { lc ->
            val start = lc.startPeriod.coerceIn(1, PeriodClock.count)
            val end = lc.endPeriod.coerceIn(start, PeriodClock.count)
            val name = lc.name.trim().ifEmpty {
                LocaleHelper.wrap(AppContext.instance).getString(R.string.schedule_unnamed_course)
            }
            items.add(Block(name = name, room = lc.room.trim(), start = start, end = end))
        }
        return merge(items.sortedBy { it.start })
    }

    /**
     * 合并同一门课的多条记录（与 `ScheduleFragment.mergeCourses` 同一套规则）：
     * 服务端可能把连堂课拆成「节次重叠」的多条（同一节重复下发、或 5-6 与 6-7 相交），
     * 只认严格首尾相接会让渲染出来的块互相压住，所以按「相交或相邻即合并」取并集，
     * 但不允许跨过午休 / 晚休（[PeriodClock.breakAfterPeriods]）。
     */
    fun merge(items: List<Block>): List<Block> {
        if (items.isEmpty()) return items
        val result = mutableListOf<Block>()
        for (item in items.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last == null) {
                result.add(item)
                continue
            }
            val sameCourse = last.name == item.name && last.room == item.room
            val mergedEnd = maxOf(last.end, item.end)
            val connects = item.start <= last.end + 1
            val crossesBreak = PeriodClock.breakAfterPeriods.any { it in last.start until mergedEnd }
            if (sameCourse && connects && !crossesBreak) {
                result[result.lastIndex] = last.copy(end = mergedEnd)
            } else {
                result.add(item)
            }
        }
        return result
    }
}
