package com.lanhang.lite.schedule

import android.content.Context
import com.lanhang.lite.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object ScheduleExporter {

    data class Plan(
        val currentWeek: Int,
        val termStart: Int,
        val termEnd: Int,
        val weeks: IntRange
    )

    data class ExportPayload(
        val ics: String,
        val csv: String,
        val text: String,
        /** 不含扩展名的基名，调用方自行拼 .ics / .csv */
        val baseFileName: String,
        val courseCount: Int,
        val currentWeek: Int,
        val firstWeek: Int,
        val lastWeek: Int
    )

    private val dayNames = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    )
    /** 第三方课表 App（Sleepy 等）只认「周一」或 1-7，不认「星期一」 */
    private val weekDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val icsDate = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val icsStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
    private val fileDate = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val displayDate = DateTimeFormatter.ofPattern("M/d", Locale.US)

    private data class Occurrence(
        val week: Int,
        val day: Int,
        val start: Int,
        val end: Int,
        val name: String,
        val teacher: String,
        val room: String,
        /** true = 自建课。只影响导出内容里的一句标注，不改课程名（避免污染第三方导入） */
        val isLocal: Boolean = false
    )

    /**
     * 一个「课程时段」= 星期 + 起止节 + 周次区间（不按周展开），
     * 用于生成兼容第三方课表 App（Sleepy / WakeUp 系）的 CSV。
     */
    private data class Slot(
        val day: Int,
        val start: Int,
        val end: Int,
        val name: String,
        val teacher: String,
        val room: String,
        /** 原始周次串，如 "6-15"、"1-16,18"；空表示整学期 */
        val weekRange: String,
        /** 原始单双周：0=每周 1=单周 2=双周 */
        val oddEven: String
    )

    fun plan(data: ScheduleData): Plan? {
        val termStart = data.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        val termEnd = data.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        val startWeek = minOf(termStart, termEnd)
        val endWeek = maxOf(termStart, termEnd)
        val currentWeek = resolveCurrentWeek(data, startWeek, endWeek)
        if (currentWeek > endWeek) return null
        return Plan(currentWeek, startWeek, endWeek, startWeek..endWeek)
    }

    fun build(
        context: Context,
        data: ScheduleData,
        plan: Plan,
        weeks: Map<Int, ScheduleData>
    ): ExportPayload? {
        val unnamed = context.getString(R.string.schedule_unnamed_course)
        val termId = data.termId.orEmpty()
        // 自建层与学校层在导出这一层合流：occurrences 给 ics/文本（按周展开），
        // slots 给 csv（保留周次区间，不按周展开）
        val occurrences = collectOccurrences(weeks, termId, unnamed)
        if (occurrences.isEmpty()) return null
        val slots = collectSlots(weeks, plan, termId, unnamed)
        val currentMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        return ExportPayload(
            ics = buildIcs(context, occurrences, plan.currentWeek, currentMonday),
            csv = buildCsv(slots),
            text = buildText(context, data, occurrences, plan, currentMonday),
            baseFileName = "${context.getString(R.string.schedule_export_file_prefix)}_" +
                LocalDate.now().format(fileDate),
            courseCount = occurrences.map { it.name }.distinct().size,
            currentWeek = plan.currentWeek,
            firstWeek = plan.weeks.first,
            lastWeek = plan.weeks.last
        )
    }

    private fun resolveCurrentWeek(data: ScheduleData, startWeek: Int, endWeek: Int): Int {
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: startWeek
        return ScheduleWeek.current(cachedWeek, startWeek, endWeek)
    }

    private fun collectOccurrences(
        weeks: Map<Int, ScheduleData>,
        termId: String,
        unnamed: String
    ): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        val locals = LocalCourseStore.forTerm(termId)
        for ((week, data) in weeks.toSortedMap()) {
            for (dayIndex in dayNames.indices) {
                val day = data.schedule?.find { it.dayName == dayNames[dayIndex] } ?: continue
                val raw = mutableListOf<Occurrence>()
                for (period in day.periods.orEmpty()) {
                    val node = period.periodName?.trim()?.toIntOrNull() ?: continue
                    if (node !in 1..PeriodClock.count) continue
                    for (course in period.courses.orEmpty()) {
                        raw.add(
                            Occurrence(
                                week = week,
                                day = dayIndex + 1,
                                start = node,
                                end = node,
                                name = course.name?.trim().orEmpty().ifEmpty { unnamed },
                                teacher = course.teacher?.trim().orEmpty(),
                                room = course.room?.trim().orEmpty()
                            )
                        )
                    }
                }
                raw.sortBy { it.start }
                var current: Occurrence? = null
                for (item in raw) {
                    val previous = current
                    val mergeable = previous != null &&
                        item.start == previous.end + 1 &&
                        previous.end != 4 &&
                        previous.end != 8 &&
                        item.name == previous.name &&
                        item.teacher == previous.teacher &&
                        item.room == previous.room
                    if (mergeable) {
                        current = previous.copy(end = item.end)
                    } else {
                        if (previous != null) result.add(previous)
                        current = item
                    }
                }
                if (current != null) result.add(current)
            }
            // 自建层：按「这一周是否生效」摊成事件。自建课本身就是完整时段，
            // 不再走学校那套「相邻节次合并」（避免和同名学校课黏成一整块）
            for (lc in locals) {
                if (!lc.isActiveInWeek(week)) continue
                val start = lc.startPeriod.coerceIn(1, PeriodClock.count)
                val end = lc.endPeriod.coerceIn(start, PeriodClock.count)
                result.add(
                    Occurrence(
                        week = week,
                        day = lc.dayIndex + 1,
                        start = start,
                        end = end,
                        name = lc.name.trim().ifEmpty { unnamed },
                        teacher = lc.teacher.trim(),
                        room = lc.room.trim(),
                        isLocal = true
                    )
                )
            }
        }
        return result.sortedWith(compareBy({ it.week }, { it.day }, { it.start }))
    }

    /**
     * 收集「课程时段」（含原始周次区间，不按周展开）。
     *
     * GetSchedule 按周返回数据，同一门课会在多周里重复出现，字段完全一致，
     * 因此合并相邻节次后按整条记录去重即可还原出原始周次串。
     */
    private fun collectSlots(
        weeks: Map<Int, ScheduleData>,
        plan: Plan,
        termId: String,
        unnamed: String
    ): List<Slot> {
        val all = LinkedHashSet<Slot>()
        for (data in weeks.toSortedMap().values) {
            for (dayIndex in dayNames.indices) {
                val day = data.schedule?.find { it.dayName == dayNames[dayIndex] } ?: continue
                val raw = mutableListOf<Slot>()
                for (period in day.periods.orEmpty()) {
                    val node = period.periodName?.trim()?.toIntOrNull() ?: continue
                    if (node !in 1..PeriodClock.count) continue
                    for (course in period.courses.orEmpty()) {
                        raw.add(
                            Slot(
                                day = dayIndex + 1,
                                start = node,
                                end = node,
                                name = course.name?.trim().orEmpty().ifEmpty { unnamed },
                                teacher = course.teacher?.trim().orEmpty(),
                                room = course.room?.trim().orEmpty(),
                                weekRange = course.weekRange?.trim().orEmpty(),
                                oddEven = course.oddEven?.trim().orEmpty()
                            )
                        )
                    }
                }
                raw.sortBy { it.start }
                var current: Slot? = null
                for (item in raw) {
                    val previous = current
                    val mergeable = previous != null &&
                        item.start == previous.end + 1 &&
                        previous.end != 4 &&
                        previous.end != 8 &&
                        item.name == previous.name &&
                        item.teacher == previous.teacher &&
                        item.room == previous.room &&
                        item.weekRange == previous.weekRange &&
                        item.oddEven == previous.oddEven
                    if (mergeable) {
                        current = previous.copy(end = item.end)
                    } else {
                        if (previous != null) all.add(previous)
                        current = item
                    }
                }
                if (current != null) all.add(current)
            }
        }
        // 周次串若在某些周次响应里缺失，优先保留有周次信息的那条，避免多出整学期重复行
        val bySpan = all.groupBy {
            "${it.day}|${it.start}|${it.end}|${it.name}|${it.teacher}|${it.room}"
        }
        val slots = mutableListOf<Slot>()
        for (group in bySpan.values) {
            val withWeek = group.filter { it.weekRange.isNotEmpty() }
            slots.addAll((withWeek.ifEmpty { group }).distinct())
        }
        // 自建课本身就是「星期 + 起止节 + 周次」的完整时段，直接进 slots：
        // 不按周展开、也不参与学校的跨周去重（那是为了还原服务端重复下发的同一门课）
        for (lc in LocalCourseStore.forTerm(termId)) {
            val start = lc.startPeriod.coerceIn(1, PeriodClock.count)
            val end = lc.endPeriod.coerceIn(start, PeriodClock.count)
            slots.add(
                Slot(
                    day = lc.dayIndex + 1,
                    start = start,
                    end = end,
                    name = lc.name.trim().ifEmpty { unnamed },
                    teacher = lc.teacher.trim(),
                    room = lc.room.trim(),
                    weekRange = localWeekRange(lc),
                    // 自定义周集合优先：此时不能再叠加单双周，否则第三方会再筛一遍
                    oddEven = if (lc.selectedWeeks.isNotEmpty()) "0" else lc.weekType.toString()
                )
            )
        }
        val fallbackWeeks = "${plan.weeks.first}-${plan.weeks.last}"
        return slots
            .map {
                val weeks = normalizeWeekRange(it.weekRange)
                it.copy(weekRange = weeks.ifEmpty { fallbackWeeks })
            }
            .sortedWith(compareBy({ it.day }, { it.start }, { it.name }))
    }

    private fun buildIcs(
        context: Context,
        occurrences: List<Occurrence>,
        currentWeek: Int,
        currentMonday: LocalDate
    ): String {
        val sb = StringBuilder()
        fun line(value: String) {
            sb.append(value).append("\r\n")
        }
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//LanhangLite//Schedule//ZH")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:${context.getString(R.string.schedule_export_file_prefix)}")
        val stamp = Instant.now().atZone(ZoneOffset.UTC).format(icsStamp)
        for (item in occurrences) {
            val date = currentMonday
                .plusWeeks((item.week - currentWeek).toLong())
                .plusDays((item.day - 1).toLong())
            val startTime = PeriodClock.startText(item.start) ?: continue
            val endTime = PeriodClock.endText(item.end) ?: continue
            val description = buildList {
                add(
                    if (item.start == item.end) {
                        context.getString(R.string.schedule_export_period_single, item.start)
                    } else {
                        context.getString(R.string.schedule_export_period_range, item.start, item.end)
                    }
                )
                if (item.isLocal) add(context.getString(R.string.local_course_tag))
                if (item.room.isNotEmpty()) add(item.room)
                if (item.teacher.isNotEmpty()) {
                    add(context.getString(R.string.course_label_teacher) + item.teacher)
                }
                add(context.getString(R.string.schedule_week_format, item.week))
            }.joinToString("\n")
            line("BEGIN:VEVENT")
            line(
                "UID:${item.week}-${item.day}-${item.start}-${item.end}-${item.name.hashCode()}" +
                    "-${item.teacher.hashCode()}@lanhang.lite"
            )
            line("DTSTAMP:$stamp")
            line("DTSTART:${icsDate.format(date)}T${compactTime(startTime)}")
            line("DTEND:${icsDate.format(date)}T${compactTime(endTime)}")
            line("SUMMARY:${escapeIcs(item.name)}")
            if (item.room.isNotEmpty()) line("LOCATION:${escapeIcs(item.room)}")
            line("DESCRIPTION:${escapeIcs(description)}")
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return sb.toString()
    }

    /**
     * CSV：一行 = 一个课程时段（星期 + 起止节 + 周次区间），带 UTF-8 BOM 方便 Excel 识别中文。
     *
     * 注意：本方法的列名与取值**刻意固定为中文，不随界面语言变化** ——
     * 第三方课表 App（Sleepy / WakeUp 系）按中文列名做包含匹配，英文界面导出英文表头会直接导入失败。
     *
     * 列名与取值刻意对齐第三方课表 App（Sleepy / WakeUp 系）的导入解析规则：
     * - 星期写「周一…周日」或 1-7：写「星期一」时 Sleepy 的星期解析失败，会**跳过整行**导致「未能解析任何课程」
     * - 节次拆成「开始节数 / 结束节数」：只写单列「开始节 / 结束节」会被当成“节次”单列，多节连上的课被压成 1 节
     * - 周次保留原始区间串（"1-16,18"）并单列「类型」（每周/单周/双周），不再按周展开成几十行重复课程
     * - 开始/结束时间为该时段的整体起止，对方 App 用它推导作息表
     */
    private fun buildCsv(slots: List<Slot>): String {
        val sb = StringBuilder("\uFEFF")
        fun cell(value: String): String {
            val v = value.replace("\"", "\"\"")
            return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$v\"" else v
        }
        fun line(vararg cells: String) {
            sb.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        line("课程", "星期", "开始节数", "结束节数", "周次", "类型", "开始时间", "结束时间", "教师", "教室")
        for (item in slots) {
            val startTime = PeriodClock.startText(item.start) ?: continue
            val endTime = PeriodClock.endText(item.end) ?: continue
            line(
                item.name,
                weekDayNames[item.day - 1],
                "${item.start}",
                "${item.end}",
                item.weekRange,
                oddEvenLabel(item.oddEven),
                startTime,
                endTime,
                item.teacher,
                item.room
            )
        }
        return sb.toString()
    }

    /** 自建课的周次串：自定义周集合优先（逗号分隔），否则用起止周 */
    private fun localWeekRange(lc: LocalCourse): String =
        if (lc.selectedWeeks.isNotEmpty()) {
            lc.selectedWeeks.sorted().joinToString(",")
        } else {
            "${lc.startWeek}-${lc.endWeek}"
        }

    /** 只保留数字与区间分隔符，"第6-15周" → "6-15" */
    private fun normalizeWeekRange(raw: String): String {
        val cleaned = raw
            .replace('—', '-')
            .replace('–', '-')
            .replace('－', '-')
            .replace('~', '-')
            .replace('，', ',')
            .replace('、', ',')
            .replace('；', ';')
        return cleaned.filter { it.isDigit() || it == '-' || it == ',' || it == ';' }
            .trim(',', ';', '-')
    }

    private fun oddEvenLabel(raw: String): String = when (raw) {
        "1" -> "单周"
        "2" -> "双周"
        else -> "每周"
    }

    private fun buildText(
        context: Context,
        data: ScheduleData,
        occurrences: List<Occurrence>,
        plan: Plan,
        currentMonday: LocalDate
    ): String {
        val lines = mutableListOf<String>()
        lines.add(context.getString(R.string.schedule_export_file_prefix))
        data.termId?.trim()?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        lines.add(
            context.getString(
                R.string.schedule_export_summary,
                plan.weeks.first,
                plan.weeks.last,
                occurrences.map { it.name }.distinct().size
            )
        )
        val displayDayNames = context.resources.getStringArray(R.array.schedule_day_names)
        for (week in plan.weeks) {
            val weekOccurrences = occurrences.filter { it.week == week }
            if (weekOccurrences.isEmpty()) continue
            val monday = currentMonday.plusWeeks((week - plan.currentWeek).toLong())
            lines.add("")
            lines.add(
                context.getString(
                    R.string.schedule_export_week_header,
                    week,
                    displayDate.format(monday),
                    displayDate.format(monday.plusDays(6))
                )
            )
            for (day in 1..7) {
                val dayOccurrences = weekOccurrences.filter { it.day == day }
                if (dayOccurrences.isEmpty()) continue
                lines.add(displayDayNames[day - 1])
                for (item in dayOccurrences) {
                    val period = if (item.start == item.end) {
                        context.getString(R.string.schedule_export_period_single, item.start)
                    } else {
                        context.getString(R.string.schedule_export_period_range, item.start, item.end)
                    }
                    lines.add("  $period ${item.name}")
                    val meta = listOfNotNull(
                        if (item.isLocal) context.getString(R.string.local_course_tag) else null,
                        item.teacher.takeIf { it.isNotEmpty() },
                        item.room.takeIf { it.isNotEmpty() }
                    )
                    if (meta.isNotEmpty()) lines.add("    ${meta.joinToString(" · ")}")
                }
            }
        }
        return lines.joinToString("\n").trimEnd()
    }

    private fun compactTime(value: String): String {
        val time = value.replace(":", "").trim()
        return if (time.length == 4) "${time}00" else time
    }

    private fun escapeIcs(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
    }
}
