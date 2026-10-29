package com.lanhang.lite.schedule

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import android.widget.FrameLayout
import com.lanhang.lite.R
import com.lanhang.lite.ui.Motion
import kotlin.math.abs

/**
 * 课表周次横向翻页容器（2026-10-27）。
 *
 * 为什么不用 ViewPager2：课表页的每一页内容都是**代码动态生成**的（网格要按容器宽度算列宽、
 * 表头和表格要像素级对齐），而且当前页与目标页在拖动过程中只是**并排**平移、不是叠放，
 * 自己实现一层 view 平移比塞进 ViewPager2 + RecyclerView.Adapter 更直接，
 * 也不会和外面的 SwipeRefreshLayout、里面的 NestedScrollView 抢嵌套滚动。
 *
 * 交互约定：
 * - 手指横向移动时页面**跟手**平移（不再是「松手才淡入淡出」，之前完全没有过程反馈）；
 * - 松手按「**速度投影**后的位移 ≥ 5% 屏宽」或「横向速度 ≥ 最小抛掷速度」判定翻页。
 *   投影 = 松手位移 + 手速 × 0.10s，这是 Compose pager（AnchoredDraggable + SnapFlingBehavior）
 *   的核心手感：手指离开瞬间还带着速度，页面该顺着它再滑一小段再决定去哪。
 *   只按「松手那一刻的位移」判定的话，慢拖到中途停住再松手必然弹回 —— 就是用户报的
 *   「怎么拖都过不去」。误翻由 MOVE 阶段的横滑判定挡（2 倍 slop 且横向明显大于纵向，
 *   约 44px），想纵向滚动的手势根本进不到收尾逻辑；
 * - 学期首/末周继续往该方向拖只有 0.28 倍阻尼位移，松手回弹 —— 明确告诉用户「到头了」；
 * - 切页后把新页滚到与旧页相同的纵向位置：同一学期每页高度一致，
 *   停在「第 9 节」翻到下一周应该还在第 9 节，而不是被拉回顶部。
 *
 * 手势必须守住的既有约束（2026-10-26 修「下拉刷新指示器滞留」时定下的）：
 * 1. `onTouch` 一律返回 false，绝不吞掉 UP —— 吞掉后 NestedScrollView 收不到
 *    `onTouchEvent(UP)`，`stopNestedScroll()` 不执行，SwipeRefreshLayout 等不到回弹；
 * 2. 横滑判定要够狠（2 倍 touchSlop 且明显横多于纵），否则下拉时的横向漂移会
 *    提前 `requestDisallowInterceptTouchEvent(true)`，把整段下拉手势废掉；
 * 3. 手势结束无条件放开 disallow-intercept。
 * 4. **位移和速度都必须基于绝对坐标**（`event.rawX`，VelocityTracker 喂 `offsetLocation` 后的副本）。
 *    被触摸的 `scroll` 自己也在被平移，`event.x` 会跟着补偿平移量 —— 用它算位移会把
 *    「手指总位移」算成「总位移 + 已平移量」，页面越跑越快，慢拖时表现为剧烈抖动。
 *    2026-10-27 用户报「滑得慢会一直抖」，根因就是这里，别改回去。
 */
class ScheduleWeekPager(
    private val host: FrameLayout,
    /** 造一页：由调用方决定这一周长什么样（网格 / 列表） */
    private val builder: (week: Int) -> Page
) {

    /**
     * 一页。
     *
     * [root] 是整页容器（平移对象），[scroll] 是该页自己的纵向滚动容器
     * （手势装在它上面，`SwipeRefreshLayout` 也靠它判断「是否已在顶部」）。
     */
    class Page(val week: Int, val root: View, val scroll: View)

    var minWeek: Int = 1
    var maxWeek: Int = 20

    /** 周次已落定（周次胶囊、按周补数据都挂这里） */
    var onWeekChange: (Int) -> Unit = {}

    /** 一次翻页真正结束（含回弹到原页），用于延迟校验与相邻周预取 */
    var onSettle: (Int) -> Unit = {}

    /** 手势结束（无论是否切页），用于兜底收回下拉指示器 */
    var onGestureEnd: () -> Unit = {}

    private var current: Page? = null
    private var side: Page? = null

    /** 手指位移（px）：正 = 往右拖（上一周），负 = 往左拖（下一周） */
    private var offset = 0f

    private var animator: ValueAnimator? = null
    private var animTarget = 0f
    private var animEnd: (() -> Unit)? = null

    private var touching = false

    /** 系统 touchSlop（手势判定与抖动过滤共用一份） */
    private val slop: Int by lazy { ViewConfiguration.get(host.context).scaledTouchSlop }

    /** 动画/手势期间要求重建时先记下来，等这一轮结束再做，避免半路换页 */
    private var pendingWeek: Int? = null

    /** 相邻周数据刷新过：缓存页内容已陈旧，等手头这一轮结束就丢掉 */
    private var sideStale = false

    val week: Int get() = current?.week ?: minWeek

    val isAttached: Boolean get() = current != null

    /** 当前页的纵向滚动容器（给 SwipeRefreshLayout 判断能不能下拉） */
    val scrollView: View? get() = current?.scroll

    /** 用户手指是否还按在内容上（兜底收回指示器时不能掐掉新的拖动） */
    val isTouching: Boolean get() = touching

    // ==================== 对外 ====================

    /** 重建/首次构建指定周（无动画）。数据更新、视图模式切换都走这里。 */
    fun rebuild(week: Int) {
        val target = week.coerceIn(minWeek, maxWeek)
        if (isTouching || animator != null) {
            pendingWeek = target
            return
        }
        rebuildTo(target)
    }

    /**
     * 相邻周的数据刷新过了，丢掉缓存页。
     *
     * 回弹时不丢相邻页（省一次整页构建），代价是这一页的内容会一直停在构建那一刻。
     * 所以调用方在「非当前周」的数据回填之后必须调这个，否则用户翻过去看到的是旧课表。
     * 手势/动画进行中不能换页，先记成待办，等这一轮结束由 [drainPending] 处理。
     */
    fun invalidateSide() {
        if (isTouching || animator != null) {
            sideStale = true
            return
        }
        discardSide()
    }

    /** 切到指定周。相邻周走滑动，跨多周直接换页（方向没有意义，由调用方决定要不要淡入淡出）。 */
    fun goTo(week: Int, animate: Boolean = true) {
        val target = week.coerceIn(minWeek, maxWeek)
        // 先把可能在跑的动画收尾：它会把 current 换成相邻周，必须先结算再判断方向和跨度
        finishAnimationNow()
        val base = current
        if (base == null) {
            rebuild(target)
            return
        }
        if (target == base.week) return
        if (!animate || abs(target - base.week) > 1) {
            rebuild(target)
            return
        }
        val forward = target > base.week
        ensureSide(if (forward) 1 else -1)
        if (side == null) return
        applyOffset(0f)
        commit(forward)
    }

    // ==================== 手势 ====================

    private fun install(page: Page) {
        val config = ViewConfiguration.get(host.context)
        // 一记「随手一划」的速度已经远超 minimumFlingVelocity（多数机型约 137px/s），
        // 不要再乘系数抬高它，否则慢快滑会被判成「没动」
        val minFling = config.scaledMinimumFlingVelocity.toFloat()
        // 位移必须用 rawX/rawY（窗口绝对坐标），不能用 event.x/event.y。
        // event.x 是「相对这个 scroll 的本地坐标」，而这个 scroll 正被我们平移：
        // 页面左移 10px，同一根手指算出来的 event.x 就会 +10。于是
        //   dx(n) = 手指总位移 + 前几帧累计的平移量
        // 页面越跑越快，正反馈发散 —— 表现就是慢拖时页面一直抖、一冲就顶到边再来回弹。
        // 2026-10-27 用户反馈「滑得慢会一直抖动」，根因就在这里。
        var downRawX = 0f
        var downRawY = 0f
        var horizontal = false
        var tracker: VelocityTracker? = null

        page.scroll.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 先占住「手指在屏上」，再收尾动画：收尾可能触发重建并换掉这一页，
                    // 而正在接收这串事件的正是它的滚动视图
                    touching = true
                    // 手指按上来时若上一段动画还在跑，先让它直接到位，别去拽一个正在移动的页面
                    finishAnimationNow()
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    horizontal = false
                    offset = 0f
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    // 速度也必须喂「补偿过的副本」：VelocityTracker 内部读的是 event.getX()，
                    // 和上面同一个污染源。不补偿的话算出来是手指速度 + 页面平移速度（约 2 倍），
                    // fling 判定会过分灵敏，轻轻一碰就翻页。
                    feedTracker(tracker, event, page.root.translationX)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!horizontal && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                        horizontal = true
                        touched.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) dragTo(dx)
                    false
                }

                MotionEvent.ACTION_UP -> {
                    feedTracker(tracker, event, page.root.translationX)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    // 1000 是**单位**（px/秒），不是采样窗口 —— VelocityTracker 内部本来就只取
                    // 最近 100ms 左右的样本做拟合，算出来的已经接近「离开前一瞬的手速」。
                    // 千万别按「缩短窗口」的思路改成 100：那样单位变成 px/100ms，数值缩到十分之一，
                    // 任何滑动都过不了 fling 门槛。
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.xVelocity ?: 0f
                    if (horizontal) {
                        if (abs(dx) > abs(dy)) {
                            release(dx, velocity, minFling)
                        } else {
                            snapBack()
                        }
                    }
                    endGesture(touched)
                    tracker?.recycle()
                    tracker = null
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (horizontal) snapBack()
                    endGesture(touched)
                    tracker?.recycle()
                    tracker = null
                    false
                }

                else -> false
            }
        }
    }

    /**
     * 给 VelocityTracker 喂一份「抵消页面平移」的事件副本。
     *
     * VelocityTracker 内部按下发到 View 的本地坐标做差分，而这块内容正被我们平移：
     * 同一根手指，页面每左移 1px，本地坐标就 +1。不补偿的话算出来的速度是
     * 「手指速度 + 平移速度」，约等于真实值的两倍，fling 判定会贼灵敏 ——
     * 轻轻一碰就翻页，正是补偿缺失的典型症状。
     *
     * 加上当前 translationX 之后，坐标只剩「页面初始左边界」这一个常量偏移，差分不受影响。
     */
    private fun feedTracker(tracker: VelocityTracker?, event: MotionEvent, translation: Float) {
        if (tracker == null) return
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(translation, 0f)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun dragTo(dx: Float) {
        val width = host.width.toFloat()
        if (width <= 0f) return
        // 每次位移都按当前方向校正相邻页：拖动中途反向时（先往右再往左）
        // 如果不换页，松手会翻到手指的反方向那一周。
        // 只在明显位移时校正：围绕 0 的亚像素抖动不该反复重建页面
        if (abs(dx) > slop) ensureSide(if (dx < 0f) 1 else -1)
        if (side == null) {
            // 学期边界：没有相邻周可露出来，只给一小段阻尼位移当作「到头了」的反馈
            applyOffset((dx * 0.28f).coerceIn(-width * 0.3f, width * 0.3f))
        } else {
            applyOffset(dx.coerceIn(-width, width))
        }
    }

    private fun release(dx: Float, velocity: Float, minFling: Float) {
        val width = host.width.toFloat()
        if (side == null || width <= 0f) {
            snapBack()
            return
        }
        // 方向优先信「手指真实移动过」，几乎没移动时退回速度方向
        val forward = if (abs(dx) > slop) dx < 0f else velocity < 0f

        // 速度投影（Compose pager 的核心手感）：松手那一刻手指还带着速度，
        // 页面应该顺着它再滑一小段，而不是立刻按「当前停在哪儿」定生死。
        // 不投影的话，慢拖到中途停一下再松手必然弹回 —— 这就是
        // 「拖了好多还是过不去」的直接来源（2026-10-27 第二次反馈）。
        val projected = dx + velocity * PROJECTION_SECONDS
        val sameDirection = (projected < 0f) == forward
        val byDistance = sameDirection && abs(projected) >= width * COMMIT_DISTANCE_RATIO

        // 快滑几乎不看位移：只要真的横向挪动过就算数。
        // 这个下限不是「要求拖多远」，只用来挡住「原地抖动 + 速度尖峰」的误判
        val byFling = abs(velocity) >= minFling &&
            (velocity < 0f) == forward &&
            abs(dx) >= slop * 0.5f

        if (byDistance || byFling) commit(forward) else snapBack()
    }

    private fun endGesture(touched: View) {
        touching = false
        touched.parent?.requestDisallowInterceptTouchEvent(false)
        onGestureEnd()
        drainPending()
    }

    // ==================== 页面 ====================

    /** 把相邻周准备好并放到屏幕外侧；超出学期范围时什么都不建（交给阻尼回弹） */
    private fun ensureSide(direction: Int) {
        val base = current ?: return
        val target = base.week + direction
        if (target < minWeek || target > maxWeek) {
            discardSide()
            return
        }
        val cached = side
        if (cached?.week == target) {
            // 复用的缓存页：校正一下位置（回弹时它会停在屏外，但如果期间发生过
            // 尺寸变化——旋转、分屏、折叠展开——原来的 ±width 就不准了）
            cached.root.translationX =
                if (direction > 0) host.width.toFloat() else -host.width.toFloat()
            return
        }
        discardSide()
        val page = builder(target)
        host.addView(page.root, pageParams())
        install(page)
        page.root.translationX =
            if (direction > 0) host.width.toFloat() else -host.width.toFloat()
        // 离屏时就把纵向位置对齐：等切页那一刻再滚，用户会看到一次明显的跳变
        val y = base.scroll.scrollY
        if (y > 0) page.scroll.post { if (page.root.parent != null) page.scroll.scrollTo(0, y) }
        side = page
    }

    private fun discardSide() {
        side?.let { host.removeView(it.root) }
        side = null
    }

    private fun rebuildTo(week: Int) {
        val base = current
        val keepScrollY = base?.scroll?.scrollY ?: 0
        val page = builder(week)
        host.addView(page.root, pageParams())
        install(page)
        base?.let { host.removeView(it.root) }
        discardSide()
        current = page
        offset = 0f
        page.root.translationX = 0f
        if (keepScrollY > 0) page.scroll.post { page.scroll.scrollTo(0, keepScrollY) }
        onWeekChange(week)
        onSettle(week)
        drainPending()
    }

    private fun pageParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    // ==================== 动画 ====================

    /** 手指位移 → 两页的横坐标（并排平移，不叠放） */
    private fun applyOffset(value: Float) {
        offset = value
        val width = host.width.toFloat()
        val base = current ?: return
        base.root.translationX = value
        val other = side ?: return
        other.root.translationX = if (other.week > base.week) width + value else -width + value
    }

    /** 翻页：滑到相邻周完全就位后换 current */
    private fun commit(forward: Boolean) {
        val width = host.width.toFloat()
        val next = side
        val expected = if (forward) week + 1 else week - 1
        // 相邻页必须是这次要去的方向（拖动中途反向时如果没来得及换页，宁可回弹）
        if (next == null || width <= 0f || next.week != expected) {
            snapBack()
            return
        }
        animate(offset, if (forward) -width else width, R.integer.motion_medium1) {
            val old = current
            current = next
            side = null
            offset = 0f
            next.root.translationX = 0f
            old?.let { host.removeView(it.root) }
            onWeekChange(next.week)
            onSettle(next.week)
            drainPending()
        }
    }

    /** 回弹到原页 */
    private fun snapBack() {
        animate(offset, 0f, R.integer.motion_short4) {
            // 故意**不**丢弃相邻页，只把它挪回屏幕外。
            // 相邻页是一次 builder()（课表网格 100+ 个 View、要按容器宽度重新算列宽），
            // 每次回弹都丢掉的话，下一拖动第一帧要同步重建，必然掉帧 —— 手感上就是
            // 「一起手卡一下」。留着它，连续翻页时只有第一次拖要付这个成本。
            // 数据变化/切视图模式都走 rebuildTo()，那里会 discardSide()，不会显示陈旧内容。
            applyOffset(0f)
            onSettle(week)
            drainPending()
        }
    }

    /**
     * 拖动收尾动画。
     *
     * 插值统一用 M3 `standard`（0.2, 0, 0, 1）而不是 `emphasizedDecelerate`：
     * 后者起始斜率极陡（0.05 处就到 0.7），从「手指刚离开时页面近乎静止」这个状态
     * 起跑会先猛地一顿再减速，手感是「跳一下」。standard 起步缓、收尾长，
     * 与手指速度衔接得上，也更接近 Compose spring 的观感。
     *
     * 时长按剩余距离缩放：拖到半屏再松手只剩几步路，用满 250ms 会显得发黏；
     * 而从几乎原位翻页要走整屏，时间不够就像被甩飞。
     */
    private fun animate(
        from: Float,
        to: Float,
        durationRes: Int,
        interpolator: Interpolator? = null,
        end: () -> Unit
    ) {
        animator?.cancel()
        val ctx = host.context
        animTarget = to
        animEnd = end
        val width = host.width.toFloat().coerceAtLeast(1f)
        val remaining = (abs(to - from) / width).coerceIn(0f, 1f)
        val base = Motion.duration(ctx, durationRes)
        val durationMs = (base * (MIN_DURATION_RATIO + (1f - MIN_DURATION_RATIO) * remaining)).toLong()
        val anim = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            this.interpolator = interpolator ?: Motion.standard(ctx)
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 被新动画顶替或已被 finishAnimationNow 收尾时不再重复执行
                    if (animator !== animation) return
                    animator = null
                    animEnd = null
                    end()
                }
            })
        }
        animator = anim
        anim.start()
    }

    /**
     * 立刻结束正在跑的动画并执行它的收尾。
     * 手指在动画中途按下来时必须这样处理，否则会拖着一个停在半路的页面。
     */
    private fun finishAnimationNow() {
        val anim = animator ?: return
        val end = animEnd
        val target = animTarget
        animator = null
        animEnd = null
        anim.removeAllUpdateListeners()
        anim.cancel()
        applyOffset(target)
        end?.invoke()
        drainPending()
    }

    /** 动画/手势期间攒下的重建请求，等这一轮结束再执行 */
    private fun drainPending() {
        if (isTouching || animator != null) return
        if (sideStale) {
            sideStale = false
            discardSide()
        }
        val week = pendingWeek ?: return
        pendingWeek = null
        rebuildTo(week)
    }

    private companion object {
        /**
         * 松手即翻页的位移门槛（占屏宽比例），作用在**速度投影之后**的落点上。
         *
         * 1080px 屏约 54px，比 MOVE 阶段的横滑准入（2 倍 touchSlop ≈ 44px）只高一点点 ——
         * 也就是说：手势一旦被认定为「明确的横滑」，基本就一定会翻页。
         * 这是有意为之（2026-10-27 用户两次反馈「太难滑过去」）：课表页不存在
         * 「横向微调但不翻页」的需求，翻错了再滑回来成本很低，而「拖半天弹回去」很恼人。
         */
        const val COMMIT_DISTANCE_RATIO = 0.05f

        /**
         * 速度投影时长（秒）。松手位移 + 手速 × 该系数 = 判定用的落点。
         *
         * 0.10s 大致等于「一次轻拂的惯性尾巴」：随手一划约 800~1500px/s，投影 80~150px，
         * 加上实际拖的几十像素，落点稳稳越过门槛。取太大（如 0.3s）会让轻点也翻页。
         */
        const val PROJECTION_SECONDS = 0.10f

        /** 收尾动画时长下限比例：剩余距离为 0 时仍保留基础的短促时间，避免 0ms 突跳 */
        const val MIN_DURATION_RATIO = 0.55f
    }
}
