package com.lanhang.lite.schedule

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * 自己添加的课程（「自建层」）。
 *
 * 与学校下发的 [Course] **分开存**：学校数据每次刷新会整体重写 `schedule_cache.json`，
 * 自建课放另一个文件、走另一个仓库（[LocalCourseStore]），刷新永远碰不到它 ——
 * 这就是「自建在下、学校在上」的物理基础。
 *
 * 周次语义参考 NexioSchedule：`selectedWeeks` 非空时优先（任意周集合），
 * 否则用 `startWeek..endWeek + weekType(0 每周 / 1 单周 / 2 双周)`。
 */
data class LocalCourse(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    /** 所属学期；空串表示不区分（旧数据/未知） */
    @SerializedName("termId") val termId: String = "",
    /** 0 = 周一 … 6 = 周日 */
    @SerializedName("dayIndex") val dayIndex: Int = 0,
    @SerializedName("startPeriod") val startPeriod: Int = 1,
    @SerializedName("endPeriod") val endPeriod: Int = 1,
    @SerializedName("name") val name: String = "",
    @SerializedName("room") val room: String = "",
    @SerializedName("teacher") val teacher: String = "",
    @SerializedName("startWeek") val startWeek: Int = 1,
    @SerializedName("endWeek") val endWeek: Int = 20,
    /** 0 = 每周，1 = 单周，2 = 双周 */
    @SerializedName("weekType") val weekType: Int = 0,
    /** 非空时优先于 start/end/weekType，表示任意周集合 */
    @SerializedName("selectedWeeks") val selectedWeeks: List<Int> = emptyList(),
    @SerializedName("note") val note: String = "",
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis()
) {

    /** 这一周是否要上（与学校课同一套「周次 + 单双周」判定） */
    fun isActiveInWeek(week: Int): Boolean {
        if (selectedWeeks.isNotEmpty()) return week in selectedWeeks
        if (week < startWeek || week > endWeek) return false
        return when (weekType) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    /**
     * 转成学校模型，好让自建课直接复用现有的合并 / 渲染 / 提醒链路。
     *
     * `weekRange = null` 是刻意的：调用方已经用 [isActiveInWeek] 过滤过这一周，
     * 再让 `Course.isWeekValid` 判一次会重复、还容易两套口径走岔。
     */
    fun toCourse(): Course = Course(
        name = name,
        teacher = teacher,
        room = room,
        weekRange = null,
        oddEven = "0"
    )
}
