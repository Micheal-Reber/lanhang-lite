package com.lanhang.lite.ui.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 外壳（顶栏 + 底栏）的共享状态。
 *
 * 页面仍然是 Fragment（课表是不可替换的自绘网格），外壳用 Miuix 组件在 Compose 里重画，
 * 二者通过这个对象通信：Fragment 调 [MainActivity 的 showToolbarMain/showToolbarSub]
 * 改标题，用户可以点底栏换 Tab。
 */
@Stable
class ShellState {

    /** 当前 Tab（0 课表 / 1 成绩 / 2 工作圈 / 3 设置） */
    var tab by mutableIntStateOf(TAB_SCHEDULE)

    /** 顶栏主标题 */
    var title by mutableStateOf("")

    /** 顶栏副标题（Miuix SmallTopAppBar 的 subtitle） */
    var subtitle by mutableStateOf("")

    /** 非空 = 二级页，顶栏左侧显示返回箭头 */
    var backAction by mutableStateOf<(() -> Unit)?>(null)

    /** 顶栏右上角的文字动作（如二级页的「发表」）；null = 不显示 */
    var actionText by mutableStateOf<String?>(null)

    /** 文字动作的回调 */
    var onAction by mutableStateOf<(() -> Unit)?>(null)

    /**
     * 顶栏标题切换方向：+1 = 新标题从右滑入（往右切 Tab / 推入二级页），-1 = 从左滑入，0 = 不动。
     * 由 `MainActivity.showToolbarMain/showToolbarSub` 在改标题**之前/同时**写入，
     * `ShellBars.LanhangTopBar` 的 `AnimatedContent` 在 transitionSpec 里读它决定滑动方向。
     */
    var slideDir by mutableIntStateOf(0)

    /** 标题滑动档位：true = Tab 级（快，对齐参考项目的 100×距离+100ms），false = 二级页级（Miuix 临界阻尼弹簧） */
    var slideFast by mutableStateOf(true)

    companion object {
        const val TAB_SCHEDULE = 0
        const val TAB_GRADES = 1
        const val TAB_MOMENTS = 2
        const val TAB_SETTINGS = 3
    }
}

/**
 * 顶栏「大标题 → 居中小标题」的收起状态。
 *
 * 页面还是 Fragment，顶栏却住在 MainActivity 的另一个 ComposeView 里，两者之间没有
 * 嵌套滚动关系，所以没法用 Miuix 自带的 `ExitUntilCollapsedScrollBehavior`
 * （它靠 `Modifier.nestedScroll` 从内容侧收到滚动）。这里改成「由页面主动上报」：
 * 每个列表页用 [ReportListScroll] 把自己的滚动位置写进来，顶栏读它决定用哪一档。
 */
object ShellScroll {
    /**
     * 顶栏收起进度：0f = 大标题，1f = 居中小标题。
     *
     * 2026-10-25 用户第二次反馈「顶栏收缩和文字不同步 / 文字突然闪到顶部」：
     * 上一版只有一个布尔值，到位后整条顶栏换成另一套组件做交叉淡入，切换是离散的。
     * 现在由列表滚动位置逐帧算出 0..1 的进度，标题字号与位置按它插值，和手指完全同步。
     */
    var progress by mutableStateOf(0f)
        private set

    val collapsed: Boolean get() = progress > 0.995f

    fun report(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(progress - v) > 0.004f) progress = v
    }

    fun reset() {
        if (progress != 0f) progress = 0f
    }
}

/** 列表往下滑过 [thresholdPx] 就把顶栏收成小标题；滑回顶部再展开 */
@Composable
fun ReportListScroll(state: LazyListState, spanPx: Int = 320) {
    LaunchedEffect(state) {
        snapshotFlow {
            if (state.firstVisibleItemIndex > 0) {
                1f
            } else {
                (state.firstVisibleItemScrollOffset / spanPx.toFloat()).coerceIn(0f, 1f)
            }
        }.collect { ShellScroll.report(it) }
    }
}
