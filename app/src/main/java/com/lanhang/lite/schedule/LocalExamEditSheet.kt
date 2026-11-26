package com.lanhang.lite.schedule

import com.lanhang.lite.core.toast
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.databinding.SheetLocalExamEditBinding
import com.lanhang.lite.reminder.ReminderRescheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 自建考试的新增 / 编辑弹层（2026-11-26）。
 *
 * 入口：课表顶栏「＋ → 添加考试」、长按空白格菜单、「我的考试」页的添加按钮、
 * 以及直接点击课表上的考试块（编辑）。
 *
 * 比 [LocalCourseEditSheet] 简单：考试是一次性事件，没有星期、节次、单双周。
 * 日期和时间都用系统选择器，不让用户手打 —— 手打 `2026/1/3` 这类格式迟早会解析失败。
 */
class LocalExamEditSheet : BottomSheetDialogFragment() {

    private var _binding: SheetLocalExamEditBinding? = null
    private val binding get() = _binding!!

    private var editing: LocalExam? = null
    private var date: LocalDate = LocalDate.now()
    private var startMinute = 9 * 60
    private var endMinute = 11 * 60

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetLocalExamEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog as? BottomSheetDialog ?: return
        // 全高展开 + 禁止拖动：表单里有输入，往下拖很容易误关
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.behavior.isDraggable = false
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 全高弹层在 edge-to-edge 下会顶到状态栏：补一条状态栏的上内边距
        val baseTop = binding.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }

        val id = arguments?.getString(ARG_ID).orEmpty()
        editing = LocalExamStore.all().firstOrNull { it.id == id }
        editing?.let { exam ->
            date = exam.localDate() ?: LocalDate.now()
            startMinute = exam.startMinute
            endMinute = exam.endMinute
            binding.etSubject.setText(exam.subject)
            binding.etLocation.setText(exam.location)
            binding.etSeat.setText(exam.seat)
            binding.etNote.setText(exam.note)
            binding.tvTitle.setText(R.string.local_exam_edit_title)
            binding.btnDelete.isVisible = true
        } ?: run {
            arguments?.getString(ARG_DATE)?.let { raw ->
                runCatching { LocalDate.parse(raw) }.getOrNull()?.let { date = it }
            }
        }

        renderDateAndTime()

        binding.cardDate.setOnClickListener { pickDate() }
        binding.cardStart.setOnClickListener { pickTime(isStart = true) }
        binding.cardEnd.setOnClickListener { pickTime(isStart = false) }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderDateAndTime() {
        binding.tvDate.text = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        binding.tvStart.text = clockText(startMinute)
        binding.tvEnd.text = clockText(endMinute)
    }

    private fun clockText(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)

    private fun pickDate() {
        // 选择器返回的是 UTC 当天 0 点，必须按 UTC 还原日期，否则东八区会差一天
        val selection = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.local_exam_date)
            .setSelection(selection)
            // 按钮文案同样别交给 Material 的默认资源（理由见 pickTime）
            .setPositiveButtonText(R.string.common_ok)
            .setNegativeButtonText(R.string.common_cancel)
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            renderDateAndTime()
        }
        picker.show(parentFragmentManager, "exam_date")
    }

    /**
     * 时间选择：**用 NumberPicker 自己拼**，不用 `MaterialTimePicker`。
     *
     * 2026-11-26 用户反馈「外面都是中文，只有这个弹窗是英文」——MaterialTimePicker 的
     * Hour/Minute/Cancel/OK 走的是 Material 库自带的 `m3c_time_picker_*` 文案，
     * 在纯中文环境下这几条没有中文翻译，且我们 App 用的是自己的 locale 覆盖，
     * 没法让它们跟着变。自己拼的年代感虽然朴素，但语言、字号、按钮文案全部可控。
     * （课程编辑的节次选择也是这么做的，两者风格一致。）
     */
    private fun pickTime(isStart: Boolean) {
        val ctx = requireContext()
        val current = if (isStart) startMinute else endMinute
        val density = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        fun column(label: Int, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            col.addView(TextView(ctx).apply {
                text = getString(label)
                setTextAppearance(R.style.TextAppearance_Lanhang_LabelMedium)
            })
            col.addView(NumberPicker(ctx).apply {
                minValue = min
                maxValue = max
                this.value = value
                wrapSelectorWheel = true
                // 两位数显示：选「5」时不至于看起来像「05」和「5」混着
                setFormatter { "%02d".format(it) }
                setOnValueChangedListener { _, _, newVal -> onChange(newVal) }
                // NumberPicker 默认编辑态会在「输入」和「滚轮」之间切换，这里固定成滚轮
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            })
            container.addView(col)
        }

        var hour = current / 60
        var minute = current % 60
        column(R.string.local_exam_hour, 0, 23, hour) { hour = it }
        column(R.string.local_exam_minute, 0, 59, minute) { minute = it }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isStart) R.string.local_exam_start_time else R.string.local_exam_end_time)
            .setView(container)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val total = hour * 60 + minute
                if (isStart) startMinute = total else endMinute = total
                renderDateAndTime()
            }
            .show()
    }

    private fun save() {
        val subject = binding.etSubject.text?.toString()?.trim().orEmpty()
        if (subject.isEmpty()) {
            binding.etSubject.requestFocus()
            toast(R.string.local_exam_subject_required)
            return
        }
        if (endMinute <= startMinute) {
            toast(R.string.local_exam_time_invalid)
            return
        }
        val termId = ScheduleRepository.loadCached()?.data?.termId.orEmpty()
        val exam = (editing ?: LocalExam(termId = termId)).copy(
            termId = termId,
            date = date.toString(),
            startMinute = startMinute,
            endMinute = endMinute,
            subject = subject,
            location = binding.etLocation.text?.toString()?.trim().orEmpty(),
            seat = binding.etSeat.text?.toString()?.trim().orEmpty(),
            note = binding.etNote.text?.toString()?.trim().orEmpty()
        )
        if (!LocalExamStore.save(exam)) {
            toast(R.string.local_exam_save_failed)
            return
        }
        toast(R.string.local_exam_saved)
        notifyChanged()
        dismiss()
    }

    private fun confirmDelete() {
        val exam = editing ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.local_exam_delete_confirm, exam.subject))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                if (!LocalExamStore.delete(exam.id)) {
                    toast(R.string.local_exam_delete_failed)
                    return@setPositiveButton
                }
                toast(R.string.local_exam_deleted)
                notifyChanged()
                dismiss()
            }
            .show()
    }

    /**
     * 让课表页重建当前周。
     *
     * 复用 [LocalCourseEditSheet.RESULT_KEY]：课表页已经在这个 key 上挂了重建监听，
     * 再造一个 key 等于两套通知 —— 而且考试和自建课本来就是同一张网格。
     */
    private fun notifyChanged() {
        parentFragmentManager.setFragmentResult(LocalCourseEditSheet.RESULT_KEY, bundleOf())
        // 考试也参与课前提醒窗口的重排（提醒侧自己读 LocalExamStore，这里只需踢一下）
        runCatching { ReminderRescheduler.request() }
        // 开着「同步考试到系统日历」就跟着重写一遍：改期 / 删除都必须反映到系统日历，
        // 否则系统里会留着一条已经不存在的考试。contentResolver 调用走 IO，别卡 UI
        // 先取 context 再进协程：dismiss() 之后 requireContext() 可能已经抛异常
        val appContext = context?.applicationContext
        if (appContext != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { ExamCalendarSync.sync(appContext) }
            }
        }
    }

    companion object {
        const val TAG = "LocalExamEditSheet"

        private const val ARG_ID = "exam_id"
        private const val ARG_DATE = "exam_date"

        /** 从课表某天新增：日期先填成那天 */
        fun newAdd(date: LocalDate? = null): LocalExamEditSheet =
            LocalExamEditSheet().apply {
                arguments = bundleOf(ARG_DATE to (date ?: LocalDate.now()).toString())
            }

        fun newEdit(id: String): LocalExamEditSheet =
            LocalExamEditSheet().apply { arguments = bundleOf(ARG_ID to id) }
    }
}
