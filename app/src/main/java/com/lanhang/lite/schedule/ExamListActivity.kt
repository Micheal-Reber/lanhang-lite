package com.lanhang.lite.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.databinding.ActivityExamListBinding
import com.lanhang.lite.databinding.ItemExamRowBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 「我的考试」列表（2026-11-26）。
 *
 * 按日期升序排（过去的也在，标灰 + 「已结束」），因为用户往往会回来核对考完的那场；
 * 右上角倒计时用 [ChronoUnit.DAYS] 算「今天 / 明天 / 还有 N 天」。
 *
 * 刷新靠 [LocalCourseEditSheet.RESULT_KEY]：考试编辑弹层用的是同一个 key
 * （它通知课表页重建，这里顺便重读列表，一套通知两处消费）。
 */
class ExamListActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ExamListActivity::class.java))
        }
    }

    private lateinit var binding: ActivityExamListBinding
    private val adapter = ExamAdapter()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val baseBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0,
            )
            binding.recycler.setPadding(
                binding.recycler.paddingLeft,
                binding.recycler.paddingTop,
                binding.recycler.paddingRight,
                baseBottom + bars.bottom,
            )
            insets
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener {
            LocalExamEditSheet.newAdd().show(supportFragmentManager, LocalExamEditSheet.TAG)
        }

        // 编辑 / 删除后重读（弹层走的是 FragmentResult，Activity 这边也得挂一份）
        supportFragmentManager.setFragmentResultListener(
            LocalCourseEditSheet.RESULT_KEY,
            this,
        ) { _, _ -> load() }
    }

    override fun onResume() {
        super.onResume()
        // 从别处改过（课表页长按新增）再回来也要是最新的
        load()
    }

    private fun load() {
        val today = LocalDate.now()
        val exams = LocalExamStore.all()
            .map { it to (it.localDate() ?: LocalDate.MAX) }
            .sortedWith(compareBy({ it.second }, { it.first.startMinute }))
            .map { it.first }
        adapter.submit(exams, today)
        binding.tvEmpty.isVisible = exams.isEmpty()
    }

    private inner class ExamAdapter : RecyclerView.Adapter<ExamAdapter.VH>() {

        private val items = mutableListOf<LocalExam>()
        private var today: LocalDate = LocalDate.now()

        private val dayFmt = DateTimeFormatter.ofPattern("d")
        private val monthFmt = DateTimeFormatter.ofPattern("M月")

        fun submit(list: List<LocalExam>, today: LocalDate) {
            this.today = today
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemExamRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemExamRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(exam: LocalExam) {
                val date = exam.localDate()
                if (date != null) {
                    b.tvDay.text = date.format(dayFmt)
                    b.tvMonth.text = date.format(monthFmt)
                } else {
                    b.tvDay.text = "?"
                    b.tvMonth.text = ""
                }
                b.tvSubject.text = exam.subject
                b.tvTime.text = listOfNotNull(
                    exam.startTime()?.toString(),
                    exam.endTime()?.toString()
                ).joinToString(" - ")
                val place = listOfNotNull(
                    exam.location.takeIf { it.isNotBlank() },
                    exam.seat.takeIf { it.isNotBlank() }?.let {
                        getString(R.string.local_exam_seat_value, it)
                    }
                ).joinToString(" · ")
                b.tvPlace.text = place
                b.tvPlace.isVisible = place.isNotBlank()

                // 倒计时：过去的写「已结束」并整体压暗，让当前/未来的排在视觉前面
                val days = date?.let { ChronoUnit.DAYS.between(today, it) }
                when {
                    days == null || days < 0 -> {
                        b.tvCountdown.setText(R.string.local_exam_past)
                        b.root.alpha = 0.55f
                    }

                    days == 0L -> {
                        b.tvCountdown.setText(R.string.local_exam_today)
                        b.root.alpha = 1f
                    }

                    days == 1L -> {
                        b.tvCountdown.setText(R.string.local_exam_tomorrow)
                        b.root.alpha = 1f
                    }

                    else -> {
                        b.tvCountdown.text = getString(R.string.local_exam_days_left, days)
                        b.root.alpha = 1f
                    }
                }

                b.root.setOnClickListener {
                    LocalExamEditSheet.newEdit(exam.id)
                        .show(supportFragmentManager, LocalExamEditSheet.TAG)
                }
            }
        }
    }
}
