package com.lanhang.lite.schedule

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.lanhang.lite.R

/**
 * 「课表背景」预览渲染器。
 *
 * 刻意复用与真实课表完全相同的 [ScheduleStyle] 令牌和课程配色算法，
 * 所以预览里的文字明暗、卡片透明度、强调色就是应用后的实际效果，
 * 不会出现「预览好看、应用后看不清」的偏差。
 */
object SchedulePreview {

    /** 预览里的示例课程：文案走资源，随界面语言切换 */
    private fun samples(ctx: android.content.Context): Array<Triple<String, String, Int>> = arrayOf(
        Triple(ctx.getString(R.string.widget_preview_course_name_1), "A101", 1),
        Triple(
            ctx.getString(R.string.widget_preview_course_name_2),
            ctx.getString(R.string.widget_preview_course_room_2),
            3
        ),
        Triple(
            ctx.getString(R.string.widget_preview_course_name_3),
            ctx.getString(R.string.widget_preview_course_room_3),
            5
        )
    )

    fun render(
        host: FrameLayout,
        spec: ScheduleBackdrop.Spec,
        analysis: ScheduleBackdrop.Analysis?,
        imageSource: Any?,
        style: ScheduleStyle
    ) {
        val solid = host.findViewById<View>(R.id.pvSolid)
        val image = host.findViewById<ImageView>(R.id.pvImage)
        val veil = host.findViewById<View>(R.id.pvVeil)
        val content = host.findViewById<LinearLayout>(R.id.pvContent)

        when (spec.type) {
            ScheduleBackdrop.TYPE_IMAGE -> {
                solid.visibility = View.GONE
                image.visibility = View.VISIBLE
                if (imageSource != null) {
                    image.load(imageSource) { crossfade(false) }
                } else {
                    image.setImageDrawable(null)
                }
            }

            ScheduleBackdrop.TYPE_COLOR -> {
                image.visibility = View.GONE
                solid.visibility = View.VISIBLE
                ScheduleBackdrop.colorOf(spec.value)?.let { color ->
                    solid.background = GradientDrawable().apply { setColor(color) }
                }
            }

            ScheduleBackdrop.TYPE_GRADIENT -> {
                image.visibility = View.GONE
                solid.visibility = View.VISIBLE
                ScheduleBackdrop.preset(spec.value)?.let { preset ->
                    solid.background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(preset.start, preset.end)
                    )
                }
            }

            else -> {
                solid.visibility = View.GONE
                image.visibility = View.GONE
            }
        }

        if (analysis != null) {
            veil.visibility = View.VISIBLE
            veil.setBackgroundColor(ScheduleStyle.withAlpha(analysis.veilColor, analysis.veilAlpha))
        } else {
            veil.visibility = View.GONE
        }

        buildContent(content, style)
    }

    private fun buildContent(content: LinearLayout, style: ScheduleStyle) {
        val ctx = content.context
        val dayNames = ctx.resources.getStringArray(R.array.schedule_day_names)
        val d = ctx.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * d).toInt()
        content.removeAllViews()
        content.setPadding(dp(10), dp(10), dp(10), dp(10))

        // 顶栏：周次胶囊 + 提示
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.schedule_week_format, 8)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(style.onAccentFill)
            background = style.rounded(d, 10f, style.accentFill)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        })
        topBar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.schedule_day_today, dayNames[4])
            textSize = 10f
            setTextColor(style.onBackgroundVariant)
        })
        content.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))

        // 七天摘要
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (index in dayNames.indices) {
            val isToday = index == 4
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = style.rounded(d, 8f, if (isToday) style.accentFill else style.chipFill)
            }
            cell.addView(TextView(ctx).apply {
                text = dayNames[index]
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (isToday) style.onAccentFill else style.onBackground)
            })
            cell.addView(TextView(ctx).apply {
                val count = 2 + index % 3
                text = ctx.resources.getQuantityString(
                    R.plurals.schedule_day_count,
                    count,
                    count
                )
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(if (isToday) style.onAccentFill else style.onBackgroundVariant)
            })
            strip.addView(cell, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(3)
            })
        }
        content.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply {
            topMargin = dp(6)
        })

        // 课程行：课程块统一白字（色块偏亮时由 ScheduleStyle.courseColor 压深）
        samples(ctx).forEach { (name, room, seed) ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(ctx).apply {
                text = "${2 * seed - 1}\n08:00"
                textSize = 7f
                gravity = Gravity.CENTER
                setTextColor(style.onBackgroundVariant)
            }, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.MATCH_PARENT))

            val blockColor = ScheduleStyle.courseColor(name + seed)
            val blockText = style.onCourse(blockColor)
            val block = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = style.rounded(d, 8f, blockColor)
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            block.addView(TextView(ctx).apply {
                text = name
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(blockText)
            })
            block.addView(TextView(ctx).apply {
                text = room
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(blockText)
            })
            row.addView(block, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)).apply {
                topMargin = dp(5)
            })
        }
    }
}
