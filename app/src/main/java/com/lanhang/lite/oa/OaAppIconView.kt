package com.lanhang.lite.oa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * 办事大厅宫格的**自绘图标**（2026-11-14 用户要求：不再用服务端下发的 logo 图）。
 *
 * 为什么不再用远程图：
 * - 各家上传的 logo 一半是白底 PNG，深色卡片上是一块白方块，之前要靠
 *   `StripWhiteBackdropTransformation` 抠白底才能看 —— 抠图是逐像素的 CPU 活，宫格里
 *   几十个格子一起跑，换 Tab 时肉眼可见地掉帧；
 * - 校外网络下这些图（`<校内地址>/logo/...`）根本下不来，宫格就是一片占位灰块；
 * - 服务端那些图尺寸/留白各不相同，40dp 里排出来参差不齐。
 *
 * 所以改成**一个字符 + 一块圆角底**：底色与文字色都取 M3 语义 token（浅/深色自动跟随），
 * 中文取首字、英文数字取首字母大写。零网络、零变换、零闪烁，删掉整套抠图算法之后
 * 宫格这一屏也不再需要 Coil。
 */
class OaAppIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val box = RectF()

    /** 画在中间的字符（空串 = 只画底色） */
    private var label: String = ""

    private var bgColor: Int = 0
    private var fgColor: Int = 0

    /** 绑定应用名。颜色在每次 bind 时重取，主题切换（recreate）后不用额外通知。 */
    fun bind(name: String) {
        label = initialOf(name)
        bgColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
        fgColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 正方形按短边居中：布局给的是 40dp，但大字号/自定义 density 下可能被拉长
        val side = minOf(w, h)
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        box.set(left, top, left + side, top + side)

        bgPaint.color = bgColor
        val radius = side * CORNER_RATIO
        canvas.drawRoundRect(box, radius, radius, bgPaint)

        if (label.isEmpty()) return
        textPaint.color = fgColor
        textPaint.textSize = side * TEXT_RATIO
        val fm = textPaint.fontMetrics
        // 基线 = 中心 + (ascent+descent)/2 的反号：让字形视觉居中而不是字框居中
        val baseline = box.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, box.centerX(), baseline, textPaint)
    }

    /**
     * 取一个能代表这个应用的字符。
     *
     * 中文 → 首字（「网上办事大厅」→「网」）；英文/数字 → 首字母大写（「OA」→「O」）。
     * 名字以符号开头时跳过（「（新）教务」→「教」），全符号/空名就不画字。
     */
    private fun initialOf(name: String): String {
        val ch = name.trim().firstOrNull { it.isLetterOrDigit() } ?: return ""
        return if (ch.code < 0x80) ch.uppercase() else ch.toString()
    }

    private companion object {
        /** 圆角占边长的比例（40dp 时约 11dp，接近 M3 的 squircle 观感） */
        const val CORNER_RATIO = 0.28f

        /** 字号占边长的比例（40dp 时约 17.6px） */
        const val TEXT_RATIO = 0.44f
    }
}
