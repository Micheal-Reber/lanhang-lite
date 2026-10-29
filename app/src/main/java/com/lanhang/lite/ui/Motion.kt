package com.lanhang.lite.ui

import android.content.Context
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import androidx.annotation.IntegerRes
import com.lanhang.lite.R

/**
 * 动效统一出口（Material Design 3 Motion）。
 *
 * 约定（AGENTS.md 第 5 节）：
 * - 时长一律取 `@integer/motion_*`（res/values/motion.xml）；
 * - 缓动一律取 `@interpolator/m3_*`（res/interpolator/）；
 * - 液态玻璃底栏（ui/navigation/liquid 目录、LiquidGlassFab 的玻璃参数）是豁免区，不接入本体系。
 *
 * 这些 View 动画都会遵守系统「动画程序时长缩放」（animatorDurationScale=0 时静止），
 * 无障碍「移除动画」偏好因此自动满足。
 */
object Motion {

    /** 读取 M3 时长 token（毫秒）。 */
    fun duration(context: Context, @IntegerRes token: Int): Long =
        context.resources.getInteger(token).toLong()

    /** M3 emphasizedDecelerate：(0.05, 0.7, 0.1, 1)，进入屏幕用。 */
    fun emphasizedDecelerate(context: Context): Interpolator =
        AnimationUtils.loadInterpolator(context, R.interpolator.m3_emphasized_decelerate)

    /** M3 emphasizedAccelerate：(0.3, 0, 0.8, 0.15)，退出屏幕用。 */
    fun emphasizedAccelerate(context: Context): Interpolator =
        AnimationUtils.loadInterpolator(context, R.interpolator.m3_emphasized_accelerate)

    /** M3 standard：(0.2, 0, 0, 1)，不离开屏幕的工具型动画。 */
    fun standard(context: Context): Interpolator =
        AnimationUtils.loadInterpolator(context, R.interpolator.m3_standard)

    /**
     * M3 fade through：旧内容淡出 90ms（emphasizedAccelerate）→ [rebuild] 重建 → 新内容淡入 210ms
     * （emphasizedDecelerate）。用于「同级目的地切换」（周次翻页、Tab 切换）。
     *
     * 中途再次触发时先取消当前动画并立即重建，不会把内容留在半透明状态；[rebuild] 内部若持有
     * 已销毁的视图，请自行判空（Fragment 里传 `if (view != null) ...`）。
     */
    fun fadeThrough(context: Context, host: View, rebuild: () -> Unit) {
        host.animate().cancel()
        host.alpha = 1f
        host.animate()
            .alpha(0f)
            .setDuration(duration(context, R.integer.motion_fade_through_out))
            .setInterpolator(emphasizedAccelerate(context))
            .withEndAction {
                rebuild()
                host.alpha = 0f
                host.animate()
                    .alpha(1f)
                    .setDuration(duration(context, R.integer.motion_fade_through_in))
                    .setInterpolator(emphasizedDecelerate(context))
                    .start()
            }
            .start()
    }

    /**
     * 只做「入」阶段：内容已就位后从 alpha 0 淡入，默认 210ms（emphasizedDecelerate）。
     * 用于同一容器内的视图切换；想更短可传 `R.integer/motion_short4`。
     */
    fun fadeIn(
        context: Context,
        host: View,
        @IntegerRes durationToken: Int = R.integer.motion_fade_through_in
    ) {
        host.animate().cancel()
        host.alpha = 0f
        host.animate()
            .alpha(1f)
            .setDuration(duration(context, durationToken))
            .setInterpolator(emphasizedDecelerate(context))
            .start()
    }
}
