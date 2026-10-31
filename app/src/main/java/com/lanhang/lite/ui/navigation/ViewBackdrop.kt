package com.lanhang.lite.ui.navigation

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 把「原生 View 树」接进 backdrop 库的采样链路（底栏与工作圈 FAB 共用）。
 *
 * 背景：backdrop 库只能采样 Compose 的 `GraphicsLayer`，而我们的页面是 Fragment 里的原生
 * View。做法是在玻璃所在的 ComposeView 里铺一张与宿主同尺寸的 Canvas，用 `.layerBackdrop()`
 * 把「当前可见的 Fragment 视图」按窗口坐标重放进这一层，玻璃再从中取模糊底图。
 *
 * 🔴 为什么需要这个类（2026-10-30 排查结论）：
 * `LayerBackdrop` 只在**它自己那一层被重录**时才更新。Fragment 内部滚动 / 列表加载完 /
 * 切页面只会 `invalidate()` 原生 View 树，Compose 完全不知道，玻璃就一直采样「上一次重录」
 * 的那一帧 —— 表现为「工作圈底栏完全透明（录的是加载中的空列表），课表底栏又是正常模糊
 * （切过来时课表内容已经画好了）」这种时好时坏。
 *
 * 解法是把 [revision] 读在**绘制期**：Compose 会把 draw 块里的 state 读登记成绘制依赖，
 * 变化时只重画、不重组，从而让 `layerBackdrop` 重新录制；玻璃那侧由
 * `LiquidGlassBottomBar / LiquidGlassFab` 的 `backdropRevision` 参数做同一件事，
 * 否则玻璃自己的图层（采样结果缓存在 RenderNode 里）会一直复放旧画面。
 *
 * 用法：
 * ```
 * val glass = rememberViewBackdrop(source = { binding.swipe }, host = { binding.fabPublish })
 * Box(Modifier.fillMaxSize()) {
 *     glass.Capture(Modifier.fillMaxSize())
 *     LiquidGlassFab(backdrop = glass.backdrop, backdropRevision = glass::revisionTick, ...)
 * }
 * // 内容动了（滚动 / 布局变化 / 切页）：
 * glass.invalidate()
 * ```
 */
@Stable
class ViewBackdrop internal constructor(
    private val layer: LayerBackdrop,
    private val source: () -> View?,
    private val host: () -> View?,
) {

    /** 交给玻璃采样的 backdrop */
    val backdrop: Backdrop get() = layer

    /** 采样源版本号：唯一作用是把「该重录了」传达给 Compose 的绘制阶段 */
    private var revision by mutableIntStateOf(0)

    private val sourceLocation = IntArray(2)
    private val hostLocation = IntArray(2)

    /**
     * 内容动了：滚动、布局变化、切 Tab、列表加载完成……
     *
     * 只允许主线程调用（View 事件回调），写入的是 Compose 状态。
     */
    fun invalidate() {
        revision++
        // 兜底：保证宿主 ComposeView 会有一帧 draw；真正让底图刷新的是下面的绘制期 state 读
        host()?.invalidate()
    }

    /** 玻璃在绘制期读它，见文件头的说明（不要改成在组合期读） */
    fun revisionTick(): Int = revision

    /**
     * 采样层：把 [ViewBackdrop] 的源视图重放进 `layer`，本身不上屏。
     *
     * `alpha(0f)` 是必需的：这一层只负责录制，如果上屏就会和真实内容叠画
     * （切 Tab 的淡入动画期间尤其明显，底栏那条会先亮起来）。
     */
    @Composable
    fun Capture(modifier: Modifier = Modifier) {
        Canvas(modifier.alpha(0f).layerBackdrop(layer)) {
            revisionTick()
            drawSource()
        }
    }

    private fun DrawScope.drawSource() {
        val source = source() ?: return
        val host = host() ?: return
        if (!source.isAttachedToWindow || !host.isAttachedToWindow) return
        source.getLocationInWindow(sourceLocation)
        host.getLocationInWindow(hostLocation)
        val dx = (sourceLocation[0] - hostLocation[0]).toFloat()
        val dy = (sourceLocation[1] - hostLocation[1]).toFloat()
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val saveCount = nativeCanvas.save()
            nativeCanvas.translate(dx, dy)
            source.draw(nativeCanvas)
            nativeCanvas.restoreToCount(saveCount)
        }
    }
}

/**
 * 记住一个 [ViewBackdrop]。
 *
 * [source] / [host] 走 `rememberUpdatedState`：调用方多半写成 `{ _binding?.xxx }`，
 * 视图重建后要能读到新实例。
 */
@Composable
fun rememberViewBackdrop(
    source: () -> View?,
    host: () -> View?,
): ViewBackdrop {
    // 存 State 而不是解引用后的 lambda：闭包会被 remember 记住，
    // 视图重建后必须还能读到调用方新传进来的实例
    val currentSource = rememberUpdatedState(source)
    val currentHost = rememberUpdatedState(host)
    val layer = rememberLayerBackdrop()
    return remember(layer) {
        ViewBackdrop(layer, { currentSource.value() }, { currentHost.value() })
    }
}
