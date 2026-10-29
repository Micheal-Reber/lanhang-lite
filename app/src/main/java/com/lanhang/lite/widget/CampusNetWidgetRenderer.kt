package com.lanhang.lite.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.lanhang.lite.core.LocaleHelper
import kotlin.math.roundToInt

/**
 * 校园网小组件的位图绘制。
 *
 * **为什么状态画进位图、按钮用真实 View**（与课表小组件同一思路）：
 * - 位图由**应用进程**按自己的 density 画，布局由**桌面进程** inflate；
 *   两边 density 不一致时（vivo/OriginOS 会给桌面套独立缩放），
 *   画在位图里的东西会和布局里的按钮错开。所以按钮、点击热区一律用布局 + drawable。
 * - 纯文字（标题/副标题）不需要和任何 View 对齐，交给位图最省事。
 *
 * 深浅色：位图是应用进程画的，可以自由取色；按钮底色用布局里的两套 drawable，
 * 绑定时切可见性（**不用 `setInt` 反射调 `setBackgroundResource`**，
 * launcher 端可能静默拒绝）。
 */
object CampusNetWidgetRenderer {

    /** 圆角半径（dp） */
    private const val RADIUS_DP = 18f

    /** 内边距（dp） */
    private const val PAD_H_DP = 14f
    private const val PAD_TOP_DP = 13f
    private const val PAD_BOTTOM_DP = 12f

    /** 标题字号（dp） */
    private const val TITLE_SIZE_DP = 15f

    /** 副标题字号（dp） */
    private const val DETAIL_SIZE_DP = 11f

    /** 状态圆点直径（dp） */
    private const val DOT_DP = 7f

    fun render(
        appContext: Context,
        snapshot: CampusNetWidgetData.Snapshot,
        widthDp: Int,
        heightDp: Int,
    ): Bitmap {
        val context = LocaleHelper.wrap(appContext)
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt().coerceAtLeast(1)
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = palette(context)

        // 底板
        paint.color = palette.background
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            RADIUS_DP * density,
            RADIUS_DP * density,
            paint,
        )

        val padH = PAD_H_DP * density
        // 底部要留给按钮：按钮高 36dp + 底边距 12dp
        val buttonReserve = (36f + PAD_BOTTOM_DP) * density
        val contentBottom = height - buttonReserve

        // 状态圆点 + 标题
        var y = PAD_TOP_DP * density
        paint.color = dotColor(palette, snapshot.state)
        canvas.drawCircle(
            padH + DOT_DP * density / 2f,
            y + TITLE_SIZE_DP * density / 2f,
            DOT_DP * density / 2f,
            paint,
        )
        paint.color = palette.onSurface
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = TITLE_SIZE_DP * density
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            snapshot.title,
            padH + DOT_DP * density + 6f * density,
            y + TITLE_SIZE_DP * density,
            paint,
        )

        // 副标题（可能两行）
        paint.typeface = Typeface.DEFAULT
        paint.textSize = DETAIL_SIZE_DP * density
        paint.color = palette.onSurfaceVariant
        y += TITLE_SIZE_DP * density + 6f * density
        val maxWidth = width - padH * 2
        for (line in wrapText(snapshot.detail, paint, maxWidth)) {
            if (y > contentBottom) break
            canvas.drawText(line, padH, y + DETAIL_SIZE_DP * density, paint)
            y += (DETAIL_SIZE_DP + 3f) * density
        }

        return bitmap
    }

    /** 按可用宽度把一段文字折成多行（超长时截断并加省略号） */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (ch in text) {
            val candidate = line.toString() + ch
            if (paint.measureText(candidate) > maxWidth) {
                if (line.isNotEmpty()) {
                    lines.add(line.toString())
                    line = StringBuilder()
                }
                // 单个字符就超宽（极少见）：直接丢弃，交给下一行
                if (paint.measureText(candidate) > maxWidth) continue
            }
            line.append(ch)
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        // 末行加省略号
        if (lines.isNotEmpty()) {
            val last = lines.last()
            if (paint.measureText(last + "…") > maxWidth && last.isNotEmpty()) {
                lines[lines.lastIndex] = last.dropLast(1) + "…"
            }
        }
        return lines
    }

    /** 当前主题是否为深色（按钮底色/图标颜色与此保持一致） */
    fun isDark(context: Context): Boolean = palette(context).isDark

    /**
     * 状态点颜色：**按状态取**，不是固定一种色。
     *
     * 之前这里写死了一个颜色（深色主题恒绿、浅色主题恒灰），
     * 结果「已认证 / 未认证 / 认证失败」三种状态在小图标上长得一模一样，
     * 只能靠文字区分 —— 用户要「上下线状态清晰显示」，点就是要一眼看出。
     *
     * 浅色底板上颜色要压深才看得清，深色底板上反过来要提亮。
     */
    private fun dotColor(
        palette: Palette,
        state: CampusNetWidgetData.State,
    ): Int = when (state) {
        CampusNetWidgetData.State.ONLINE -> palette.dotOnline
        CampusNetWidgetData.State.OFFLINE -> palette.dotOffline
        CampusNetWidgetData.State.FAILED -> palette.dotFailed
        CampusNetWidgetData.State.BUSY -> palette.dotBusy
    }

    private data class Palette(
        val background: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        /** 状态点：在线=绿，未认证=灰，失败=红，认证中=蓝 */
        val dotOnline: Int,
        val dotOffline: Int,
        val dotFailed: Int,
        val dotBusy: Int,
        val isDark: Boolean,
    )

    private fun palette(context: Context): Palette {
        val night = when (com.lanhang.lite.session.Session.themeMode) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        return if (night) {
            Palette(
                background = 0xFF0D1B24.toInt(),
                onSurface = 0xFFE8F4FA.toInt(),
                onSurfaceVariant = 0xFF9FB6C5.toInt(),
                dotOnline = 0xFF4ADE80.toInt(),
                dotOffline = 0xFF7C8B9A.toInt(),
                dotFailed = 0xFFFF6B6B.toInt(),
                dotBusy = 0xFF4FC3F7.toInt(),
                isDark = true,
            )
        } else {
            Palette(
                background = 0xFFEAF2F8.toInt(),
                onSurface = 0xFF14232D.toInt(),
                onSurfaceVariant = 0xFF5E7482.toInt(),
                dotOnline = 0xFF16A34A.toInt(),
                dotOffline = 0xFF94A3B8.toInt(),
                dotFailed = 0xFFDC2626.toInt(),
                dotBusy = 0xFF2563EB.toInt(),
                isDark = false,
            )
        }
    }
}
