package com.lanhang.lite.schedule

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lanhang.lite.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 课表按天分组显示的 Adapter
 * data: 一周内每天的课程列表
 */
class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.DayViewHolder>() {

    /** 一天的课程展示项 */
    data class DayItem(
        val dayIndex: Int,       // 0=周一 ... 6=周日
        val date: LocalDate?,
        val courses: List<CourseItem>
    )

    data class CourseItem(
        val course: Course,
        val periodStart: Int,
        val periodEnd: Int
    )

    private var items: List<DayItem> = emptyList()

    // 课程配色（Material You 风格的柔和色调）
    private val courseColors = intArrayOf(
        Color.parseColor("#2A6F97"),  // blue
        Color.parseColor("#7D5260"),  // rose
        Color.parseColor("#9C4146"),  // red
        Color.parseColor("#B3261E"),  // error red
        Color.parseColor("#795548"),  // brown
        Color.parseColor("#546E7A"),  // blue grey
        Color.parseColor("#455A64"),  // dark blue grey
        Color.parseColor("#00695C"),  // teal
        Color.parseColor("#2E7D32"),  // green
        Color.parseColor("#558B2F"),  // light green
        Color.parseColor("#F57F17"),  // yellow
        Color.parseColor("#E65100"),  // orange
    )

    fun submitList(newItems: List<DayItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_day_schedule, parent, false)
        return DayViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDayHeader: TextView = itemView.findViewById(R.id.tvDayHeader)
        private val courseContainer: LinearLayout = itemView.findViewById(R.id.courseContainer)

        fun bind(item: DayItem) {
            val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            val dayName = dayNames.getOrElse(item.dayIndex) { "周?" }
            val dateStr = item.date?.format(DateTimeFormatter.ofPattern("M/d", Locale.CHINA)) ?: ""
            tvDayHeader.text = if (dateStr.isNotEmpty()) "$dayName $dateStr" else dayName

            courseContainer.removeAllViews()
            if (item.courses.isEmpty()) {
                // 无课时显示占位
                val tv = TextView(itemView.context).apply {
                    text = "无课"
                    textSize = 13f
                    val ta = context.theme.obtainStyledAttributes(
                        intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                    setTextColor(ta.getColor(0, Color.GRAY))
                    ta.recycle()
                    setPadding(0, 4, 0, 4)
                }
                courseContainer.addView(tv)
                return
            }

            val inflater = LayoutInflater.from(itemView.context)
            item.courses.forEachIndexed { idx, ci ->
                val view = inflater.inflate(R.layout.item_course, courseContainer, false)
                val colorBar = view.findViewById<View>(R.id.colorBar)
                val tvName = view.findViewById<TextView>(R.id.tvCourseName)
                val tvInfo = view.findViewById<TextView>(R.id.tvCourseInfo)
                val tvPeriod = view.findViewById<TextView>(R.id.tvPeriod)

                val color = courseColors[(item.dayIndex * 3 + idx) % courseColors.size]
                colorBar.setBackgroundColor(color)
                tvName.text = ci.course.name ?: "未知课程"
                tvInfo.text = "${ci.course.teacher ?: ""} @ ${ci.course.room ?: ""}"
                tvPeriod.text = if (ci.periodStart == ci.periodEnd) {
                    "${ci.periodStart}节"
                } else {
                    "${ci.periodStart}-${ci.periodEnd}节"
                }
                tvPeriod.setTextColor(color)

                courseContainer.addView(view)
            }
        }
    }
}
