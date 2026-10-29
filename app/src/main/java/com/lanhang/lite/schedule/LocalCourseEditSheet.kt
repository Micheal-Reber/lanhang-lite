package com.lanhang.lite.schedule

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.databinding.SheetLocalCourseEditBinding
import com.lanhang.lite.widget.ScheduleWidgetUpdater
import java.time.LocalDate

/**
 * 自建课程的新增 / 编辑弹层（结构对齐 NexioSchedule 的 AddCourseDialog）。
 *
 * 入口是**长按课表格子**（不是 FAB）：长按位置决定默认星期 + 节次，默认周次取当前浏览周。
 * 表单是「卡片 + 内联行」：基本信息三行、星期按钮行、节次行（点开 NumberPicker 弹窗）、
 * 周次（全部/单周/双周 + 周次格子）。保存后重排闹钟、刷小组件，并把结果告知课表页重建。
 *
 * 刻意不做颜色选择：本项目的课程卡是 M3 tonal，容器色/文字色由课名哈希推导；
 * 塞一个任意色会破坏对比度规则，所以颜色仍由课名决定。
 */
class LocalCourseEditSheet : BottomSheetDialogFragment() {

    private var _binding: SheetLocalCourseEditBinding? = null
    private val binding get() = _binding!!

    private var editing: LocalCourse? = null
    private var dayIndex = 0
    private var startPeriod = 1
    private var endPeriod = 1
    private var weekFrom = 1
    private var weekTo = 20

    private lateinit var dayNames: Array<String>
    private val dayButtons = IntArray(7)
    private val selectedWeeks = sortedSetOf<Int>()

    /** 程序化改周次勾选时置 true，避免监听器互相递归 */
    private var updatingWeeks = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetLocalCourseEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog as? BottomSheetDialog ?: return
        // 全高展开 + 禁止拖动：表单里有输入，向下拖很容易误关
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.behavior.isDraggable = false
        // 键盘弹出时收缩弹层，滚动区自己滚（不要用 adjustPan，会把顶栏顶飞）
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 全高弹层在 edge-to-edge 下会顶到状态栏：给根布局补一条状态栏高度的上内边距
        val baseTop = binding.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }

        dayNames = resources.getStringArray(R.array.schedule_day_names)
        dayButtons[0] = R.id.day0
        dayButtons[1] = R.id.day1
        dayButtons[2] = R.id.day2
        dayButtons[3] = R.id.day3
        dayButtons[4] = R.id.day4
        dayButtons[5] = R.id.day5
        dayButtons[6] = R.id.day6
        dayButtons.forEachIndexed { index, id ->
            view.findViewById<TextView>(id).text = dayNames[index]
        }

        val cached = ScheduleRepository.loadCached()?.data
        weekFrom = cached?.weekInfo?.startWeek?.trim()?.toIntOrNull() ?: 1
        weekTo = cached?.weekInfo?.endWeek?.trim()?.toIntOrNull() ?: 20
        if (weekTo < weekFrom) {
            weekFrom = 1
            weekTo = 20
        }

        editing = arguments?.getString(ARG_ID)?.let { LocalCourseStore.find(it) }

        buildWeekChips()

        if (editing != null) fillForm(editing!!) else initDefaults()

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener { save() }
        binding.cardSection.setOnClickListener { showSectionDialog() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        binding.toggleDay.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val index = dayButtons.indexOf(checkedId)
            if (index >= 0) dayIndex = index
        }

        // 三个快捷勾选：全部 / 单周 / 双周（与参考项目一致，勾选会整体替换当前周次集合）
        binding.cbAll.setOnCheckedChangeListener { _, c ->
            if (!updatingWeeks) setWeeks(if (c) allWeeks() else emptySet())
        }
        binding.cbOdd.setOnCheckedChangeListener { _, c ->
            if (!updatingWeeks) setWeeks(if (c) oddWeeks() else emptySet())
        }
        binding.cbEven.setOnCheckedChangeListener { _, c ->
            if (!updatingWeeks) setWeeks(if (c) evenWeeks() else emptySet())
        }

        binding.chipWeeks.setOnCheckedStateChangeListener { group, checkedIds ->
            if (updatingWeeks) return@setOnCheckedStateChangeListener
            selectedWeeks.clear()
            for (cid in checkedIds) {
                val chip = group.findViewById<View>(cid) as? Chip ?: continue
                chip.text?.toString()?.toIntOrNull()?.let { selectedWeeks.add(it) }
            }
            syncWeekBoxes()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------ 初始化

    private fun initDefaults() {
        val args = arguments
        dayIndex = args?.getInt(ARG_DAY, -1) ?: -1
        if (dayIndex !in 0..6) dayIndex = LocalDate.now().dayOfWeek.value - 1

        val p = args?.getInt(ARG_PERIOD, 0) ?: 0
        startPeriod = p.takeIf { it in 1..PeriodClock.count } ?: 1
        endPeriod = startPeriod

        val cachedWeek = ScheduleRepository.loadCached()?.data
            ?.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: weekFrom
        val w = args?.getInt(ARG_WEEK, 0) ?: 0
        val defaultWeek = (w.takeIf { it in weekFrom..weekTo } ?: cachedWeek)
            .coerceIn(weekFrom, weekTo)

        binding.toggleDay.check(dayButtons[dayIndex])
        updateSectionValue()
        // 空白格新增：默认只勾当前浏览周（要整学期就点「全部」），与参考项目 defaultWeeks 同义
        setWeeks(setOf(defaultWeek))
        binding.btnDelete.isVisible = false
        binding.tvTitle.text = getString(R.string.local_course_add)
    }

    private fun fillForm(c: LocalCourse) {
        binding.tvTitle.text = getString(R.string.local_course_edit)
        binding.btnDelete.isVisible = true

        binding.etName.setText(c.name)
        binding.etRoom.setText(c.room)
        binding.etTeacher.setText(c.teacher)

        dayIndex = c.dayIndex.coerceIn(0, 6)
        startPeriod = c.startPeriod.coerceIn(1, PeriodClock.count)
        endPeriod = c.endPeriod.coerceIn(startPeriod, PeriodClock.count)
        binding.toggleDay.check(dayButtons[dayIndex])
        updateSectionValue()

        val weeks = when {
            c.selectedWeeks.isNotEmpty() -> c.selectedWeeks.filter { it in weekFrom..weekTo }.toSet()
            c.weekType == 1 -> oddWeeks()
            c.weekType == 2 -> evenWeeks()
            else -> (c.startWeek..c.endWeek).filter { it in weekFrom..weekTo }.toSet()
        }
        setWeeks(weeks)
    }

    // ------------------------------------------------------------ 周次

    private fun allWeeks(): Set<Int> = (weekFrom..weekTo).toSet()
    private fun oddWeeks(): Set<Int> = allWeeks().filter { it % 2 == 1 }.toSet()
    private fun evenWeeks(): Set<Int> = allWeeks().filter { it % 2 == 0 }.toSet()

    private fun buildWeekChips() {
        binding.chipWeeks.removeAllViews()
        for (week in weekFrom..weekTo) {
            val chip = Chip(requireContext()).apply {
                text = week.toString()
                isCheckable = true
                isCheckedIconVisible = false
                chipMinHeight = dp(32).toFloat()
                textSize = 13f
            }
            binding.chipWeeks.addView(chip)
        }
    }

    /** 整体替换选中周次（勾选格子 / 快捷框都走这里） */
    private fun setWeeks(weeks: Set<Int>) {
        updatingWeeks = true
        for (i in 0 until binding.chipWeeks.childCount) {
            val chip = binding.chipWeeks.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.text?.toString()?.toIntOrNull() in weeks
        }
        updatingWeeks = false
        selectedWeeks.clear()
        selectedWeeks.addAll(weeks)
        syncWeekBoxes()
    }

    private fun syncWeekBoxes() {
        updatingWeeks = true
        val chosen = selectedWeeks.toSet()
        binding.cbAll.isChecked = chosen.isNotEmpty() && chosen == allWeeks()
        binding.cbOdd.isChecked = chosen.isNotEmpty() && chosen == oddWeeks()
        binding.cbEven.isChecked = chosen.isNotEmpty() && chosen == evenWeeks()
        updatingWeeks = false
    }

    // ------------------------------------------------------------ 节次

    private fun updateSectionValue() {
        binding.tvSectionValue.text = if (startPeriod == endPeriod) {
            getString(R.string.local_course_period_single, startPeriod)
        } else {
            getString(R.string.local_course_period_range, startPeriod, endPeriod)
        }
    }

    /**
     * 节次选择：两个 NumberPicker（开始 / 结束），结束自动跟随开始下界。
     * 与参考项目一致 —— 不用下拉框，滚轮在这种 1..12 的小范围里更好点。
     */
    private fun showSectionDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        var tempStart = startPeriod
        var tempEnd = endPeriod

        fun addColumn(labelRes: Int, value: Int, onChange: (Int) -> Unit) {
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            column.addView(TextView(ctx).apply {
                setText(labelRes)
                gravity = Gravity.CENTER
            })
            val picker = NumberPicker(ctx).apply {
                minValue = 1
                maxValue = PeriodClock.count
                this.value = value
                wrapSelectorWheel = false
                setOnValueChangedListener { _, _, newVal -> onChange(newVal) }
            }
            column.addView(picker)
            container.addView(column)
        }

        addColumn(R.string.local_course_start, startPeriod) { v ->
            tempStart = v
            if (tempEnd < v) tempEnd = v
        }
        addColumn(R.string.local_course_end, endPeriod) { v ->
            tempEnd = v
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.local_course_select_section)
            .setView(container)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                startPeriod = tempStart.coerceIn(1, PeriodClock.count)
                endPeriod = tempEnd.coerceIn(startPeriod, PeriodClock.count)
                updateSectionValue()
            }
            .show()
    }

    // ------------------------------------------------------------ 保存 / 删除

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.etName.requestFocus()
            toast(R.string.local_course_name_required)
            return
        }
        if (selectedWeeks.isEmpty()) {
            toast(R.string.local_course_select_weeks)
            return
        }
        if (endPeriod < startPeriod) {
            toast(R.string.local_course_period_invalid)
            return
        }

        // 与参考项目同一套归一化：连续整段 → WEEK_TYPE_ALL；纯单/双周 → 1/2；其余存具体周集合
        val sorted = selectedWeeks.sorted()
        val minWeek = sorted.first()
        val maxWeek = sorted.last()
        val range = (minWeek..maxWeek).toSet()
        val chosen = selectedWeeks.toSet()
        val weekType = when (chosen) {
            range -> 0
            range.filter { it % 2 == 1 }.toSet() -> 1
            range.filter { it % 2 == 0 }.toSet() -> 2
            else -> 0
        }
        // 单/双周交给 weekType 表达；连续整段也只存起止周。其余才存具体周集合
        val contiguous = sorted.size == (maxWeek - minWeek + 1)
        val weeksToSave = if (weekType != 0 || contiguous) emptyList() else sorted

        val termId = ScheduleRepository.loadCached()?.data?.termId.orEmpty()
        val course = (editing ?: LocalCourse(termId = termId)).copy(
            termId = termId,
            dayIndex = dayIndex,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            name = name,
            room = binding.etRoom.text?.toString()?.trim().orEmpty(),
            teacher = binding.etTeacher.text?.toString()?.trim().orEmpty(),
            startWeek = minWeek,
            endWeek = maxWeek,
            weekType = weekType,
            selectedWeeks = weeksToSave
        )

        if (!LocalCourseStore.save(course)) {
            toast(R.string.local_course_save_failed)
            return
        }
        afterChange()
        toast(R.string.local_course_saved)
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf())
        dismiss()
    }

    private fun confirmDelete() {
        val course = editing ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.local_course_delete_confirm_title)
            .setMessage(R.string.local_course_delete_confirm_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.local_course_delete) { _, _ ->
                LocalCourseStore.delete(course.id)
                afterChange()
                toast(R.string.local_course_deleted)
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf())
                dismiss()
            }
            .show()
    }

    /** 自建课变了 → 提醒重排 + 小组件刷新；顺序反了会先刷出旧数据 */
    private fun afterChange() {
        val app = requireContext().applicationContext
        ClassReminder.rearm(app)
        ScheduleWidgetUpdater.updateAll(app)
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val RESULT_KEY = "local_course_changed"
        const val TAG = "local_course_edit"

        private const val ARG_ID = "local_course_id"
        private const val ARG_DAY = "local_course_day"
        private const val ARG_PERIOD = "local_course_period"
        private const val ARG_WEEK = "local_course_week"

        /** 长按空白格新增：星期/节次/当前浏览周都带过去 */
        fun newAdd(dayIndex: Int, period: Int, week: Int): LocalCourseEditSheet =
            LocalCourseEditSheet().apply {
                arguments = bundleOf(
                    ARG_DAY to dayIndex,
                    ARG_PERIOD to period,
                    ARG_WEEK to week
                )
            }

        /** 点自建课卡编辑 */
        fun newEdit(id: String): LocalCourseEditSheet =
            LocalCourseEditSheet().apply { arguments = bundleOf(ARG_ID to id) }
    }
}
