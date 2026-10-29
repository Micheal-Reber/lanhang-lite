package com.lanhang.lite.moments

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import com.lanhang.lite.R
import com.lanhang.lite.ui.Motion
import kotlin.math.abs
import kotlin.math.min

/**
 * 可缩放图片：双指捏合、拖动平移、双击 1x/2.5x 切换、单击回调（关闭）。
 *
 * `scaleType` 固定 MATRIX：所有变换都走同一个 [matrixHelper]，`reset()` 在 drawable 就绪后
 * 按 fitCenter 算基准比例（[baseScale]），一切缩放都是相对它的倍数。
 *
 * ## 三个必须留住的手势细节（都是踩过的坑）
 *
 * 1. **第二根手指落下就锁父容器**（[onTouchEvent] 的 ACTION_POINTER_DOWN）。放在 ViewPager2 里时，
 *    内部的 RecyclerView 只要判定「横向拖动超过 touchSlop」就会 intercept，事件从此不再下发到
 *    这个 View —— 表现为「双指捏合完全没反应」，而单指双击是好的。等到 [zoom] 里再
 *    `requestDisallowInterceptTouchEvent` 已经晚了。
 * 2. **双击以指尖为锚点放大、以视图中心缩小**：以中心放大时，用户想看的那个角会被推出屏幕。
 *    缩小时反过来必须锚在中心，否则图变小后被 [clamp] 拉回居中，看起来像「飘」了一下。
 * 3. **动画用累计倍率**（`cum = 1 + (total-1)*p` 再求每帧增量），不要每帧 `curScale *= f`：
 *    后者把浮点误差也乘进去，连续双击十几次之后回不到原比例。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    /** 单击（onSingleTapConfirmed，双击不触发） */
    var onTap: (() -> Unit)? = null

    /**
     * 是否处于放大状态。
     *
     * 预览页用它决定「能不能翻页」：放大时横向拖动应该平移图片，而不是切到下一张。
     */
    val isZoomed: Boolean
        get() = drawableReady && curScale > baseScale * 1.01f

    private val matrixHelper = Matrix()
    private var baseScale = 1f
    private var curScale = 1f
    private var drawableReady = false
    private var anim: ValueAnimator? = null

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val zoomedIn = curScale > baseScale * 1.05f
                val target = if (zoomedIn) baseScale else baseScale * DOUBLE_TAP_SCALE
                animateScaleTo(target, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (curScale > baseScale && !scaleDetector.isInProgress) {
                    (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    matrixHelper.postTranslate(-distanceX, -distanceY)
                    clamp()
                    applyMatrix()
                }
                return true
            }
        })

    fun reset() {
        val d = drawable ?: return
        if (d.intrinsicWidth <= 0 || width <= 0 || height <= 0) return
        baseScale = min(
            width.toFloat() / d.intrinsicWidth,
            height.toFloat() / d.intrinsicHeight
        )
        curScale = baseScale
        matrixHelper.reset()
        matrixHelper.postScale(curScale, curScale)
        clamp()
        drawableReady = true
        applyMatrix()
    }

    /** 翻页离开时调用：把这张图恢复成 fitCenter（回来时不该还是放大状态） */
    fun resetIfZoomed() {
        if (isZoomed) {
            anim?.cancel()
            reset()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) reset()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        drawableReady = false
        if (drawable != null) post { reset() }
    }

    private fun zoom(factor: Float, fx: Float, fy: Float) {
        if (!drawableReady) return
        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
        val target = (curScale * factor).coerceIn(baseScale, baseScale * MAX_SCALE)
        val actual = target / curScale
        if (actual == 1f) return
        anim?.cancel()
        matrixHelper.postScale(actual, actual, fx, fy)
        curScale = target
        clamp()
        applyMatrix()
    }

    private fun animateScaleTo(target: Float, fx: Float, fy: Float) {
        if (!drawableReady) return
        val start = curScale
        val total = target / start
        if (abs(total - 1f) < 0.001f) return
        anim?.cancel()
        // 放大锚在指尖（想看哪儿放大哪儿），缩小锚在中心（否则会被 clamp 拉回居中，看起来像飘）
        val pivotX = if (total < 1f) width / 2f else fx
        val pivotY = if (total < 1f) height / 2f else fy
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            // M3 medium2 (300ms) + FastOutSlowInInterpolator（≈ emphasizedDecelerate）
            duration = Motion.duration(context, R.integer.motion_medium2)
            interpolator = Motion.emphasizedDecelerate(context)
            var applied = 1f
            addUpdateListener { v ->
                val p = v.animatedValue as Float
                val cum = 1f + (total - 1f) * p
                val step = cum / applied
                applied = cum
                matrixHelper.postScale(step, step, pivotX, pivotY)
                curScale = start * cum
                clamp()
                applyMatrix()
            }
            start()
        }
    }

    private fun applyMatrix() {
        imageMatrix = matrixHelper
    }

    /** 把图拽回可视区：比视图小就居中，比视图大就不许露出黑边 */
    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrixHelper.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) {
            dx = width / 2f - r.centerX()
        } else {
            if (r.left > 0) dx = -r.left
            if (r.right < width) dx = width - r.right
        }
        if (r.height() <= height) {
            dy = height / 2f - r.centerY()
        } else {
            if (r.top > 0) dy = -r.top
            if (r.bottom < height) dy = height - r.bottom
        }
        if (dx != 0f || dy != 0f) matrixHelper.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(isZoomed)

            // 第二根手指落下：立刻锁住父容器，否则 ViewPager2 会把整串事件抢走（捏合失效）
            MotionEvent.ACTION_POINTER_DOWN ->
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // 🔴 必须**自己消费**（无条件 true），不能 `return super.onTouchEvent(event)`。
        // ImageView 默认不可点击 → View.onTouchEvent 在 ACTION_DOWN 上返回 false →
        // 系统认为这个 View 不要这串手势，后续 MOVE/UP 根本不会再送进来 ——
        // 双击、双指捏合、单击全部失效（2026-10-28 用户实测「双击放大没反应、双手也不行」，就是这个）。
        // 翻页不受影响：未放大时我们没调 requestDisallowInterceptTouchEvent，
        // ViewPager2 照样能在拖动超过 touchSlop 时 intercept。
        return true
    }

    companion object {
        /** 双指最大放到 fitCenter 的多少倍（再大就只剩马赛克） */
        private const val MAX_SCALE = 8f

        /** 双击放大到 fitCenter 的多少倍：2.5 刚好能看清正文又不至于只剩一小块 */
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
