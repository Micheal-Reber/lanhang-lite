package com.lanhang.lite.schedule

import com.lanhang.lite.session.Session
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
    private val periodTimes = listOf(
        "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
        "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45", "16:55" to "17:40",
        "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35"
    )
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
        val room: String
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

    fun build(data: ScheduleData, plan: Plan, weeks: Map<Int, ScheduleData>): ExportPayload? {
        val occurrences = collectOccurrences(weeks)
        if (occurrences.isEmpty()) return null
        val currentMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        return ExportPayload(
            ics = buildIcs(occurrences, plan.currentWeek, currentMonday),
            csv = buildCsv(occurrences, plan.currentWeek, currentMonday),
            text = buildText(data, occurrences, plan, currentMonday),
            baseFileName = "蓝航Lite课表_${LocalDate.now().format(fileDate)}",
            courseCount = occurrences.map { it.name }.distinct().size,
            currentWeek = plan.currentWeek,
            firstWeek = plan.weeks.first,
            lastWeek = plan.weeks.last
        )
    }

    private fun resolveCurrentWeek(data: ScheduleData, startWeek: Int, endWeek: Int): Int {
        val cachedWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: startWeek
        val todayMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val anchorAt = Session.scheduleWeekAnchorAt
        val anchorWeek = Session.scheduleWeekAnchorWeek
        val raw = if (anchorAt > 0L && anchorWeek > 0) {
            val anchorMonday = Instant.ofEpochMilli(anchorAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .with(DayOfWeek.MONDAY)
            anchorWeek + ChronoUnit.WEEKS.between(anchorMonday, todayMonday).toInt()
        } else {
            val modified = ScheduleRepository.cacheLastModified()
            if (modified > 0L) {
                val cacheMonday = Instant.ofEpochMilli(modified)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .with(DayOfWeek.MONDAY)
                cachedWeek + ChronoUnit.WEEKS.between(cacheMonday, todayMonday).toInt()
            } else {
                cachedWeek
            }
        }
        return raw.coerceIn(startWeek, endWeek)
    }

    private fun collectOccurrences(weeks: Map<Int, ScheduleData>): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        for ((week, data) in weeks.toSortedMap()) {
            for (dayIndex in dayNames.indices) {
                val day = data.schedule?.find { it.dayName == dayNames[dayIndex] } ?: continue
                val raw = mutableListOf<Occurrence>()
                for (period in day.periods.orEmpty()) {
                    val node = period.periodName?.trim()?.toIntOrNull() ?: continue
                    if (node !in 1..11) continue
                    for (course in period.courses.orEmpty()) {
                        raw.add(
                            Occurrence(
                                week = week,
                                day = dayIndex + 1,
                                start = node,
                                end = node,
                                name = course.name?.trim().orEmpty().ifEmpty { "未命名课程" },
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
        }
        return result.sortedWith(compareBy({ it.week }, { it.day }, { it.start }))
    }

    private fun buildIcs(
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
        line("X-WR-CALNAME:蓝航Lite课表")
        val stamp = Instant.now().atZone(ZoneOffset.UTC).format(icsStamp)
        for (item in occurrences) {
            val date = currentMonday
                .plusWeeks((item.week - currentWeek).toLong())
                .plusDays((item.day - 1).toLong())
            val startTime = periodTimes.getOrNull(item.start - 1)?.first ?: continue
            val endTime = periodTimes.getOrNull(item.end - 1)?.second ?: continue
            val description = buildList {
                add("第${item.start} - ${item.end}节")
                if (item.room.isNotEmpty()) add(item.room)
                if (item.teacher.isNotEmpty()) add("教师：${item.teacher}")
                add("第${item.week}周")
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

    /** CSV：每行一次实际开课；带 UTF-8 BOM 方便 Excel 识别中文 */
    private fun buildCsv(
        occurrences: List<Occurrence>,
        currentWeek: Int,
        currentMonday: LocalDate
    ): String {
        val sb = StringBuilder("\uFEFF")
        fun cell(value: String): String {
            val v = value.replace("\"", "\"\"")
            return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$v\"" else v
        }
        fun line(vararg cells: String) {
            sb.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        line("周次", "星期", "日期", "开始节", "结束节", "开始时间", "结束时间", "课程", "教师", "教室")
        for (item in occurrences) {
            val date = currentMonday
                .plusWeeks((item.week - currentWeek).toLong())
                .plusDays((item.day - 1).toLong())
            val startTime = periodTimes.getOrNull(item.start - 1)?.first ?: continue
            val endTime = periodTimes.getOrNull(item.end - 1)?.second ?: continue
            line(
                "${item.week}",
                dayNames[item.day - 1],
                "${date.year}-${"%02d".format(date.monthValue)}-${"%02d".format(date.dayOfMonth)}",
                "${item.start}",
                "${item.end}",
                startTime,
                endTime,
                item.name,
                item.teacher,
                item.room
            )
        }
        return sb.toString()
    }

    private fun buildText(
        data: ScheduleData,
        occurrences: List<Occurrence>,
        plan: Plan,
        currentMonday: LocalDate
    ): String {
        val lines = mutableListOf<String>()
        lines.add("蓝航Lite课表")
        data.termId?.trim()?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        lines.add(
            "第${plan.weeks.first}-${plan.weeks.last}周 · " +
                "共${occurrences.map { it.name }.distinct().size}门课程"
        )
        for (week in plan.weeks) {
            val weekOccurrences = occurrences.filter { it.week == week }
            if (weekOccurrences.isEmpty()) continue
            val monday = currentMonday.plusWeeks((week - plan.currentWeek).toLong())
            lines.add("")
            lines.add(
                "第${week}周（${displayDate.format(monday)} - " +
                    "${displayDate.format(monday.plusDays(6))}）"
            )
            for (day in 1..7) {
                val dayOccurrences = weekOccurrences.filter { it.day == day }
                if (dayOccurrences.isEmpty()) continue
                lines.add(dayNames[day - 1])
                for (item in dayOccurrences) {
                    val period = if (item.start == item.end) {
                        "第${item.start}节"
                    } else {
                        "第${item.start}-${item.end}节"
                    }
                    lines.add("  $period ${item.name}")
                    val meta = listOfNotNull(
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
