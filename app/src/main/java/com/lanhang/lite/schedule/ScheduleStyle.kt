package com.lanhang.lite.schedule

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.google.android.material.R as MaterialR

/**
 * 课表配色令牌。两套来源：
 * - [fromTheme]：没有自定义背景时，完全沿用 Material 主题色。
 * - [fromBackdrop]：有自定义背景时，由 [ScheduleBackdrop.Analysis] 推导，
 *   文字/卡片/分割线全部按背景明暗与主色调重新计算，与系统明暗主题解耦。
 *
 * 课程色自 2026-10-25 起改走 M3 的 tonal 语义（[CourseTone]），不再是"高饱和底 + 白字"。
 */
class ScheduleStyle(
    val custom: Boolean,
    /** 当前底色是否偏暗：决定课程 tonal 卡用深容器还是浅容器 */
    val darkSurface: Boolean,
    val onBackground: Int,
    val onBackgroundVariant: Int,
    val onBackgroundFaint: Int,
    val outline: Int,
    val divider: Int,
    val cardFill: Int,
    val chipFill: Int,
    val circleFill: Int,
    val accent: Int,
    val accentFill: Int,
    val onAccentFill: Int,
    val bandFill: Int,
    val veilColor: Int,
    val veilAlpha: Float
) {

    /**
     * 「今天」整列的强调淡底。
     *
     * 只有表头文字变蓝，在 7 列并排的窄格里不够醒目，整列铺一层同色系淡底才能一眼定位。
     * 必须足够薄：网格线、休息带、课程卡都画在它上面，太浓会把整张表染色。
     *
     * - 浅色底：取 [accentFill]（M3 secondaryContainer 那种浅蓝）。直接拿 [accent]（深蓝）
     *   加透明度叠在白底上会发灰，看着像脏而不是"蓝"。
     * - 深色底：[accentFill] 是深蓝容器色，叠在深色背景上几乎看不见，改用本身就是亮蓝的 [accent]。
     */
    val todayTint: Int
        get() = if (darkSurface) withAlpha(accent, 0.14f) else withAlpha(accentFill, 0.55f)

    /**
     * 一门课的 M3 tonal 配色。
     *
     * - 浅色底：container ≈ tone 90（很浅的同色相底）+ onContainer ≈ tone 20（深色文字）
     * - 深色底：反向，container ≈ tone 30 + onContainer ≈ tone 95
     *
     * 这样课程块看起来就是 M3 的 "filled card"，而区分度仍来自色相（每门课色相不同）。
     */
    data class CourseTone(val container: Int, val onContainer: Int)

    /** 圆角矩形；fill 可带透明度（自定义背景下的半透明卡片） */
    fun rounded(
        density: Float,
        radiusDp: Float,
        fill: Int,
        strokeDp: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusDp * density
        setColor(fill)
        if (strokeDp > 0f) setStroke((strokeDp * density).toInt().coerceAtLeast(1), strokeColor)
    }

    /** 课程 tonal 卡：容器色与容器上的文字色成对给出 */
    fun courseTone(courseName: String): CourseTone {
        val colors = courseColors(courseName, darkSurface)
        return CourseTone(container = colors.container, onContainer = colors.onContainer)
    }

    /** 课程色的成对取值（容器 + 容器上文字） */
    data class CourseColors(val container: Int, val onContainer: Int)

    /** 天数摘要里的「N 门」小胶囊 */
    fun badgeFill(selected: Boolean): Int = if (selected) {
        withAlpha(onAccentFill, 0.18f)
    } else {
        withAlpha(onBackground, 0.10f)
    }

    companion object {
        val DARK_TEXT = 0xFF12181D.toInt()
        val LIGHT_TEXT = 0xFFFFFFFF.toInt()

        private const val FALLBACK_PRIMARY = 0xFF084168.toInt()

        /** 课程色相：黄金角分布，同课同色、不同课色相差约 137° */
        fun hueOf(courseName: String): Float =
            ((courseName.hashCode() * 137.508f % 360f) + 360f) % 360f

        /**
         * 课程容器色 / 容器上的文字色。**课表页与 4×4 桌面小部件共用这一份**。
         *
         * 小部件拿不到 Activity 主题（渲染在 App 进程里、但配色必须与桌面明暗解耦），
         * 所以把「从色相推 M3 tonal」这段纯计算放在 companion，两边都调它，
         * 避免小部件自己再写一套 HSL 压深（旧实现就是这么跑偏的：白字 + 高饱和底，
         * 和课表页的 tonal 卡完全不是一套视觉语言）。
         *
         * - 深色底：container ≈ tone 30 + onContainer ≈ tone 95
         * - 浅色底：container ≈ tone 90 + onContainer ≈ tone 20
         */
        fun courseColors(courseName: String, darkSurface: Boolean): CourseColors {
            val hue = hueOf(courseName)
            return if (darkSurface) {
                CourseColors(
                    container = tonal(hue, 0.38f, 0.30f),
                    onContainer = tonal(hue, 0.22f, 0.95f)
                )
            } else {
                CourseColors(
                    container = tonal(hue, 0.44f, 0.93f),
                    onContainer = tonal(hue, 0.62f, 0.26f)
                )
            }
        }

        private fun tonal(hue: Float, saturation: Float, value: Float): Int =
            Color.HSVToColor(floatArrayOf(hue, saturation, value))

        fun fromTheme(context: Context): ScheduleStyle {
            val onBackground = themeColor(context, MaterialR.attr.colorOnSurface, DARK_TEXT)
            val variant = themeColor(context, MaterialR.attr.colorOnSurfaceVariant, onBackground)
            val night = isNight(context)
            return ScheduleStyle(
                custom = false,
                darkSurface = night,
                onBackground = onBackground,
                onBackgroundVariant = variant,
                onBackgroundFaint = withAlpha(variant, 0.78f),
                outline = themeColor(context, MaterialR.attr.colorOutline, variant),
                // M3 的网格/列表分隔线标准色是 outlineVariant（不是把 onSurface 降透明度）
                divider = themeColor(context, MaterialR.attr.colorOutlineVariant, variant),
                cardFill = themeColor(context, MaterialR.attr.colorSurfaceContainerLow, onBackground),
                chipFill = themeColor(context, MaterialR.attr.colorSurfaceContainer, onBackground),
                circleFill = themeColor(context, MaterialR.attr.colorSurfaceContainerHigh, onBackground),
                accent = themeColor(context, MaterialR.attr.colorPrimary, FALLBACK_PRIMARY),
                accentFill = themeColor(context, MaterialR.attr.colorSecondaryContainer, variant),
                onAccentFill = themeColor(context, MaterialR.attr.colorOnSecondaryContainer, onBackground),
                bandFill = themeColor(context, MaterialR.attr.colorSurfaceContainerHigh, onBackground),
                veilColor = Color.TRANSPARENT,
                veilAlpha = 0f
            )
        }

        fun fromBackdrop(analysis: ScheduleBackdrop.Analysis): ScheduleStyle {
            val veil = analysis.veilColor
            val cardAlpha = (analysis.veilAlpha + 0.18f).coerceIn(0.30f, 0.95f)
            val onBackground = if (analysis.darkText) DARK_TEXT else LIGHT_TEXT
            val variant = if (analysis.darkText) 0xFF3C454D.toInt() else 0xFFD9E2EA.toInt()
            val accentFill = mix(veil, analysis.accent, 0.20f)
            val onAccentFill = ensureContrast(analysis.accent, accentFill, analysis.darkText, 4.3)
            return ScheduleStyle(
                custom = true,
                darkSurface = !analysis.darkText,
                onBackground = onBackground,
                onBackgroundVariant = variant,
                onBackgroundFaint = withAlpha(variant, 0.72f),
                outline = withAlpha(onBackground, 0.42f),
                divider = withAlpha(onBackground, 0.16f),
                cardFill = withAlpha(veil, cardAlpha),
                chipFill = withAlpha(veil, (cardAlpha - 0.06f).coerceAtLeast(0.20f)),
                circleFill = withAlpha(veil, cardAlpha),
                accent = onAccentFill,
                accentFill = accentFill,
                onAccentFill = onAccentFill,
                // 休息带必须是**不透明**色：它是通栏覆盖层，半透明会把底下的网格竖线透出来。
                // 以背景平均色为基底，深色背景调亮一档、浅色背景调暗一档，
                // 这样带子与自定义背景同色系，不会像中性灰那样突兀。
                bandFill = if (analysis.darkText) {
                    mix(analysis.meanColor, Color.BLACK, 0.06f)
                } else {
                    mix(analysis.meanColor, Color.WHITE, 0.10f)
                },
                veilColor = veil,
                veilAlpha = analysis.veilAlpha
            )
        }

        private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
            val typed = context.obtainStyledAttributes(intArrayOf(attr))
            return try {
                typed.getColor(0, fallback)
            } finally {
                typed.recycle()
            }
        }

        private fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        /** 相对亮度（WCAG 2.x，sRGB 转线性） */
        fun relLuminance(color: Int): Double {
            fun channel(value: Int): Double {
                val s = value / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(color)) +
                0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        }

        fun contrast(foreground: Int, background: Int): Double {
            val a = relLuminance(foreground)
            val b = relLuminance(background)
            val lighter = if (a > b) a else b
            val darker = if (a > b) b else a
            return (lighter + 0.05) / (darker + 0.05)
        }

        fun withAlpha(color: Int, alpha: Float): Int {
            val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        /** sRGB 空间线性混合（与 Android 的 alpha 合成/渐变插值一致） */
        fun mix(base: Int, target: Int, ratio: Float): Int {
            val t = ratio.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(base) + (Color.red(target) - Color.red(base)) * t).toInt(),
                (Color.green(base) + (Color.green(target) - Color.green(base)) * t).toInt(),
                (Color.blue(base) + (Color.blue(target) - Color.blue(base)) * t).toInt()
            )
        }

        /** 保持色相/饱和度，仅调明度直到与 [against] 达到 [target] 对比度 */
        fun ensureContrast(color: Int, against: Int, darkText: Boolean, target: Double): Int {
            if (contrast(color, against) >= target) return color
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            var value = hsv[2]
            var current = color
            repeat(26) {
                value = if (darkText) (value - 0.035f).coerceAtLeast(0.06f)
                else (value + 0.035f).coerceAtMost(1f)
                current = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], value))
                if (contrast(current, against) >= target) return current
                if (darkText && value <= 0.06f) return current
                if (!darkText && value >= 1f) return current
            }
            return current
        }
    }
}
