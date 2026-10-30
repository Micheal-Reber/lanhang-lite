package com.lanhang.lite.schedule

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 课表一页的外壳（2026-10-30，对齐 miuix 版课表的滑动逻辑）。
 *
 * 唯一职责是**在子 View 之前**看到触摸事件，好让「左右滑动切周」在课程卡片、
 * 日卡片、摘要格上也能起手。
 *
 * 背景：以前把手势监听挂在 `NestedScrollView` 上（`scroll.setOnTouchListener`）。
 * 但课程卡片是 `MaterialCardView` + `setOnClickListener`（可点击），手指落在卡片上时
 * 卡片会消费 `ACTION_DOWN` —— 一旦有子 View 接手，父 ViewGroup 的 `OnTouchListener`
 * 连 DOWN 都收不到，更别说后续 MOVE。表现就是「按在空白处能滑，按在课表块上滑不动」。
 * `dispatchTouchEvent` 在分发**之前**执行，绕开了这条限制。
 *
 * 约定：[pagerTouch] 返回 true 表示「这次事件归切周用」，容器会吃掉后续事件，
 * 并给子 View 补一个 `ACTION_CANCEL`（否则被中断的点击会残留按下态、涟漪卡住）。
 */
class ScheduleSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var pagerTouch: ((MotionEvent) -> Boolean)? = null

    /** 本次手势是否已被切周接管 */
    private var consuming = false

    /** 本次手势是否已经给子 View 补过 CANCEL */
    private var cancelSent = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handler = pagerTouch
        if (handler != null && handler(ev)) {
            if (!consuming) {
                consuming = true
                cancelSent = false
            }
            if (!cancelSent) {
                cancelSent = true
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                consuming = false
                cancelSent = false
            }
            return true
        }
        consuming = false
        cancelSent = false
        return super.dispatchTouchEvent(ev)
    }
}
