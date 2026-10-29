package com.lanhang.lite.core

import android.view.View
import android.view.ViewGroup

/**
 * 键盘弹出时把某个控件滚进可见区。
 *
 * ## 为什么不用 `target.requestRectangleOnScreen(...)`
 *
 * 键盘**动画期间** insets 会回调很多次，而第一次回调时 `ime` 高度只有几像素、滚动区底部
 * 的 padding 也才长起来一点点，`requestRectangleOnScreen` 一看「目标还在可见区内」就返回了；
 * 之后再没人重试，页面就停在原地（2026-10-28 用户实测：能手动划上来，但不会自己顶上去）。
 *
 * ## 这里的做法
 *
 * **每次回调都调一次、只往下滚、滚够为止**：键盘每长高一帧，就把内容往下推同样的距离，
 * 目标控件始终贴在键盘上沿；等它真的可见了 `delta <= 0` 自然退化成空操作，不会来回跳。
 *
 * 调用方必须在 **layout 之后**调（`scroll.post { }`）：`scrollBy` 会被当前的滚动范围钳制，
 * 而范围是这趟布局才更新的，提前调会被截断成 0。
 *
 * @param scroll 滚动容器（ScrollView / NestedScrollView 之类，直接子 View 是内容布局）
 * @param target 要露出来的控件，必须是 [scroll] 的后代
 * @param extraBottom 目标下方额外留白（px），避免控件正好贴着键盘上沿
 */
fun revealInScroll(scroll: View, target: View, extraBottom: Int = 0) {
    val content = (scroll as? ViewGroup)?.getChildAt(0) ?: return
    if (target.height <= 0) return

    // 累加 top 得到目标在内容布局坐标系里的位置（中间的 FrameLayout / LinearLayout 不参与换算）
    var top = 0
    var node: View = target
    while (node !== content) {
        top += node.top
        node = node.parent as? View ?: return
    }
    val bottom = top + target.height + extraBottom

    // 可见区下沿 = 当前已滚过的距离 + 容器高度 − 底部 padding（padding 里已经含键盘高度）
    val viewportBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
    val delta = bottom - viewportBottom
    if (delta > 0) scroll.scrollBy(0, delta)
}
