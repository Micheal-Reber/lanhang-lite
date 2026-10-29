package com.lanhang.lite.schedule

/**
 * 课表上一节课的展示项：课程本体 + 节次区间。
 *
 * 同一门课连上多节时，`ScheduleFragment.mergeCourses()` 会把它合并成一条，
 * 此时 `periodStart != periodEnd`，界面显示成「第 1-2 节」。
 */
data class CourseItem(
    val course: Course,
    val periodStart: Int,
    val periodEnd: Int,
    /** true = 用户自己添加的课（自建层），渲染时会加一圈中性描边区分 */
    val isLocal: Boolean = false,
    /** 自建课主键；学校课为 null。点击自建卡直接进编辑页 */
    val localId: String? = null
)
