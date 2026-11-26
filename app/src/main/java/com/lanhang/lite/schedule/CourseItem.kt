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
    val localId: String? = null,
    /**
     * 自建考试主键；null = 普通课程。
     *
     * 考试复用同一条渲染链路（网格/列表都走 [ScheduleFragment.getCoursesForDay]），
     * 只是配色和点击行为不同：examId 非空时画成醒目的考试色、点开进考试编辑页。
     */
    val examId: String? = null,
    /**
     * 考试的时间文案（`09:00-11:00`），仅 [examId] 非空时有值。
     *
     * 不在这里回查 [LocalExamStore]：网格/列表每次布局都查一遍文件太浪费，
     * 构建 [CourseItem] 时顺手格式化好传下来。
     */
    val examTimeLabel: String? = null,
    /**
     * 考试的真实起止（从 0 点算的分钟），网格用它**按分钟精确摆放**。
     *
     * 只用 [periodStart]/[periodEnd] 的话，考试会被对齐到整节格子：9:00 开始的考试从
     * 8:00（第 1 节顶部）画起，结束时间同理偏一截 —— 用户 2026-11-26 反馈「时间对不上」。
     */
    val examStartMinute: Int? = null,
    val examEndMinute: Int? = null
)
