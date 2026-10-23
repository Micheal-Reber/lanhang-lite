package com.lanhang.lite.schedule

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.google.android.material.R as MaterialR

/**
 * 课表配色令牌。两套来源：
 * - [fromTheme]：没有自定义背景时，完全沿用 Material 主题色（原有外观不变）。
 * - [fromBackdrop]：有自定义背景时，由 [ScheduleBackdrop.Analysis] 推导，
 *   文字/卡片/分割线全部按背景明暗与主色调重新计算，与系统明暗主题解耦。
 */
class ScheduleStyle(
    val custom: Boolean,
    val onBackground: Int,
    val onBackgroundVariant: Int,
    val onBackgroundFaint: Int,
    val outline: Int,
    val divider: Int,
    val cardFill: Int,
    val chipFill: Int,
    val circleFill: Int,
    val panelFill: Int,
    val accent: Int,
    val accentFill: Int,
    val onAccentFill: Int,
    val bandFill: Int,
    val veilColor: Int,
    val veilAlpha: Float
) {

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

    fun circle(density: Float, fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /**
     * 课程色块上的文字。
     *
     * 统一用白字：以前按对比度在白/深之间自动选，导致同一屏里有的课名是黑的、有的是白的，
     * 看着像没统一过。现在一律白字，靠 [courseColor] 把过亮的色相压深来保证可读性
     * （见 [MIN_WHITE_CONTRAST]），而不是换文字颜色。
     */
    fun onCourse(@Suppress("UNUSED_PARAMETER") color: Int): Int = LIGHT_TEXT

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

        /**
         * 课程配色：HSL 黄金角分布，同课同色、不同课色相差约 137°。
         *
         * 色块上一律白字，所以偏亮的色相（黄/绿/橙）会按原色相与饱和度压深，
         * 直到与白字的对比度达到 [MIN_WHITE_CONTRAST]；蓝/红这类本来就深的颜色不动。
         */
        fun courseColor(courseName: String): Int {
            val hue = ((courseName.hashCode() * 137.508f % 360f) + 360f) % 360f
            return deepenForWhiteText(Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.82f)))
        }

        /**
         * 白字版课程块的最低对比度。WCAG AA 对大字号要求 3:1，课名是 10sp 加粗，
         * 取 3.2 略留余量；实测 3.2 时黄/绿只比原来深一档，肉眼仍是同一个色。
         */
        private const val MIN_WHITE_CONTRAST = 3.2

        /** 保持色相/饱和度，仅降低明度直到白字可读；再深就会失真，所以下限卡在 0.34 */
        private fun deepenForWhiteText(color: Int): Int {
            if (contrast(color, LIGHT_TEXT) >= MIN_WHITE_CONTRAST) return color
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            var value = hsv[2]
            var current = color
            repeat(40) {
                value = (value - 0.015f).coerceAtLeast(0.34f)
                current = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], value))
                if (contrast(current, LIGHT_TEXT) >= MIN_WHITE_CONTRAST || value <= 0.34f) {
                    return current
                }
            }
            return current
        }

        fun fromTheme(context: Context): ScheduleStyle {
            val onBackground = themeColor(context, MaterialR.attr.colorOnSurface, DARK_TEXT)
            val variant = themeColor(context, MaterialR.attr.colorOnSurfaceVariant, onBackground)
            val night = isNight(context)
            return ScheduleStyle(
                custom = false,
                onBackground = onBackground,
                onBackgroundVariant = variant,
                onBackgroundFaint = withAlpha(variant, 0.78f),
                outline = themeColor(context, MaterialR.attr.colorOutline, variant),
                divider = withAlpha(onBackground, if (night) 0.16f else 0.10f),
                cardFill = themeColor(context, MaterialR.attr.colorSurfaceContainerLow, onBackground),
                chipFill = themeColor(context, MaterialR.attr.colorSurfaceContainer, onBackground),
                circleFill = themeColor(context, MaterialR.attr.colorSurfaceContainerHigh, onBackground),
                panelFill = themeColor(context, MaterialR.attr.colorSurfaceContainerHigh, onBackground),
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
                onBackground = onBackground,
                onBackgroundVariant = variant,
                onBackgroundFaint = withAlpha(variant, 0.72f),
                outline = withAlpha(onBackground, 0.42f),
                divider = withAlpha(onBackground, 0.16f),
                cardFill = withAlpha(veil, cardAlpha),
                chipFill = withAlpha(veil, (cardAlpha - 0.06f).coerceAtLeast(0.20f)),
                circleFill = withAlpha(veil, cardAlpha),
                panelFill = withAlpha(veil, cardAlpha),
                accent = onAccentFill,
                accentFill = accentFill,
                onAccentFill = onAccentFill,
                bandFill = withAlpha(veil, cardAlpha),
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
