package com.lanhang.lite.core

import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 双击列表回到顶部。
 *
 * **只观察、不消费事件**：用 [RecyclerView.addOnItemTouchListener] 旁听触摸，
 * `onInterceptTouchEvent` 永远返回 false，所以滚动、点击、长按全都照旧。
 * 换成 `setOnTouchListener` 会让 RecyclerView 收不到事件（滚动直接失效），
 * 包一层自定义 ViewGroup 又会干扰外层 SwipeRefreshLayout 的下拉判定 —— 都不行。
 *
 * 两条边界：
 * 1. **落点在可交互控件上时不接管**。双击图片、头像、赞/评论按钮，
 *    第一次抬手就已经触发了那些点击（弹预览、点赞），这时候再回顶只会让人莫名其妙。
 *    命中可点击子视图就放弃这次双击，让原本的交互照常进行。
 * 2. **距离近才平滑滚动**。`smoothScrollToPosition(0)` 在几百条之后会飞很久
 *    （RecyclerView 按每项高度估算距离，滚动速度又是固定的），
 *    当前不在前几条时直接跳，避免用户盯着一段几秒钟的动画。
 *
 * @param onBeforeScroll 即将回顶前调用。列表用了「单击延迟」守卫的（见 MomentAdapter），
 *        要在这里取消挂起的单击，否则双击的第一下会在 320ms 后把详情页打开。
 * @param onAfterScroll 滚动结束（或直接跳完）后调用，用来重绘依赖内容的玻璃 FAB。
 */
fun RecyclerView.attachDoubleTapToTop(
    onBeforeScroll: (() -> Unit)? = null,
    onAfterScroll: (() -> Unit)? = null
) {
    val ctx = context ?: return
    val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        // 必须吃掉 onDown，否则 GestureDetector 不会继续跟踪这次手势
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tapLandsOnClickable(e.x, e.y)) return false
            onBeforeScroll?.invoke()
            return scrollToTopNow { onAfterScroll?.invoke() }
        }
    })

    addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            detector.onTouchEvent(e)
            return false
        }
    })
}

/**
 * 回到顶部。已在顶部时返回 false（调用方据此决定要不要给反馈）。
 *
 * 供「再点一次底栏 Tab」这类入口直接调用，不需要手势。
 */
fun RecyclerView.scrollToTopNow(onDone: (() -> Unit)? = null): Boolean {
    val lm = layoutManager as? LinearLayoutManager ?: return false
    val first = lm.findFirstVisibleItemPosition()
    // 第一条完整可见且没有偏移 = 已经在顶上了，别再抖一下
    if (first <= 0 && (lm.findViewByPosition(0)?.top ?: 0) >= paddingTop) {
        onDone?.invoke()
        return false
    }
    stopScroll()
    if (first <= SMOOTH_SCROLL_MAX_ITEMS) {
        // 近：平滑滚，并等它真正停下来再回调（scrollToTopNow 是异步的）
        if (onDone == null) {
            smoothScrollToPosition(0)
        } else {
            val watcher = object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                    rv.removeOnScrollListener(this)
                    onDone()
                }
            }
            addOnScrollListener(watcher)
            smoothScrollToPosition(0)
        }
    } else {
        // 远：直接跳。中间几百条的飞行动画只会让人以为卡住了
        scrollToPosition(0)
        onDone?.invoke()
    }
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    return true
}

/** 距离在这么多条以内才做平滑滚动 */
private const val SMOOTH_SCROLL_MAX_ITEMS = 3

/**
 * 这一下是否点在了可交互的子视图上。
 *
 * 不复用 `View.isClickable` 逐层判断而是自己遍历：itemView 本身可能整体可点
 * （搜索页、过滤页的「点卡片进详情」），那种情况下双击整张卡片都应该回顶，
 * 只有卡片**内部**的按钮 / 图片 / 头像才算冲突。
 */
private fun RecyclerView.tapLandsOnClickable(x: Float, y: Float): Boolean {
    val row = findChildViewUnder(x, y) ?: return false
    return row.hasClickableDescendant(x - row.left, y - row.top)
}

/** @param x/y 相对 [this] 左上角的坐标 */
private fun View.hasClickableDescendant(x: Float, y: Float): Boolean {
    if (this !is ViewGroup) return false
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.visibility != View.VISIBLE) continue
        if (x < child.left || x > child.right || y < child.top || y > child.bottom) continue
        if (child.isClickable || child.isLongClickable) return true
        if (child.hasClickableDescendant(x - child.left, y - child.top)) return true
    }
    return false
}
