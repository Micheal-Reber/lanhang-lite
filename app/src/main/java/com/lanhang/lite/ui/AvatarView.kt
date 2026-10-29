package com.lanhang.lite.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.settings.Diagnostics

/**
 * 圆形头像：**只显示照片，取不到就什么都不显示**（不放占位圆，也不画姓名首字）。
 *
 * 2026-10-26 用户明确要求：头像必须是本人照片，实在取不到就留空，
 * 不要用「姓名首字母彩色圆」代替（上一版因此被指「怎么头像都变成个人名称首字母了」）；
 * 也不要退回灰色/白色占位圆——深色主题里就是一排白球。
 *
 * 用法：
 * ```
 * binding.ivAvatar.bind(name = staff?.name, url = staff?.bestPortrait, altUrl = staff?.altPortrait)
 * ```
 * 地址一律取 `StaffBrief.bestPortrait`（大图优先、缩略图兜底），不要自己拼 `portrait_url`；
 * 主地址失败才试备用尺寸，两个都失败就留空。
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 照片位**必须常驻 VISIBLE**：`GONE` 的子 View 不参与 measure/layout，宽高是 0，
     * coil 的 ViewSizeResolver 会一直等尺寸 → 请求挂着，既不成功也不报错，
     * 头像就永远是空白（2026-10-26 实测：diagnostics.log 一条失败都没有但全都不显示）。
     * 没有图时它没有背景也没有 drawable，画出来就是空的，正好符合"取不到就不显示"。
     */
    private val photo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /** 每次 bind 自增；异步回调里对比，防止列表复用后把上一个用户的图贴到这一行 */
    private var generation = 0

    init {
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * @param name   只用于诊断日志（取不到时方便定位是哪一行），不参与显示
     * @param url    主头像地址
     * @param altUrl 备用地址（一般传另一档尺寸），主地址失败才会用到
     */
    fun bind(name: String?, url: String?, altUrl: String? = null) {
        val token = ++generation
        // 先清空：换人或换地址时不能留着上一张图
        photo.setImageDrawable(null)
        val primary = url?.takeIf { it.isNotBlank() }
        val alt = altUrl?.takeIf { it.isNotBlank() && it != primary }
        if (primary == null) {
            if (alt != null) {
                loadPhoto(alt, null, token)
            } else {
                recordProblem("无头像地址 name=${name.orEmpty()}", null)
            }
            return
        }
        loadPhoto(primary, alt, token)
    }

    private fun loadPhoto(url: String, alt: String?, token: Int) {
        photo.load(url) {
            crossfade(true)
            transformations(CircleCropTransformation())
            listener(
                onError = { _, result ->
                    if (token != generation) return@listener
                    photo.setImageDrawable(null)
                    // 主地址挂了就试备用尺寸；都挂了就留空
                    if (alt != null) {
                        loadPhoto(alt, null, token)
                    } else {
                        recordProblem(url, result.throwable)
                    }
                },
            )
        }
    }

    /**
     * 取不到时记一条诊断（每次启动最多 [MAX_DIAGNOSTICS] 条），用来区分
     * 「没有地址」「网络到不了」「解码失败」——以前这里静默失败，查不出原因。
     */
    private fun recordProblem(detail: String, error: Throwable?) {
        if (recorded.incrementAndGet() > MAX_DIAGNOSTICS) return
        runCatching {
            Diagnostics.record(
                AppContext.instance,
                "Avatar",
                error ?: IllegalStateException(detail.take(160)),
            )
        }
    }

    private companion object {
        /** 诊断日志里每次启动最多记几条头像问题，避免被列表刷满 */
        const val MAX_DIAGNOSTICS = 3

        val recorded = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
