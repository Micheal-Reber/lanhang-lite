package com.lanhang.lite.schedule

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import androidx.core.view.doOnNextLayout
import com.lanhang.lite.R
import com.lanhang.lite.ui.Motion
import kotlin.math.abs

/**
 * 课表周次横向翻页容器。
 *
 * 为什么不用 ViewPager2：课表页的每一页内容都是**代码动态生成**的（网格要按容器宽度算列宽、
 * 表头和表格要像素级对齐），而且当前页与目标页在拖动过程中只是**并排**平移、不是叠放，
 * 自己实现一层 view 平移比塞进 ViewPager2 + RecyclerView.Adapter 更直接，
 * 也不会和外面的 SwipeRefreshLayout、里面的 NestedScrollView 抢嵌套滚动。
 *
 * 2026-10-30 按 miuix 版课表（Nuclear-Potatoes/lanhang-lite-miuix）的滑动逻辑对齐，三处改动：
 *
 * 1. **手势挂到 [ScheduleSwipeLayout]（dispatch 层）**，不再挂 `scroll.setOnTouchListener`。
 *    后者在手指落在课程卡片上时连 DOWN 都收不到（卡片可点击，会消费掉事件），
 *    表现为「按空白处能滑、按课表块滑不动」；
 * 2. **只平移 [Page.sliding]**：网格页传空 → 整页平移（左侧节次/时间列跟着一起滑）。
 *    曾经把左侧时间列钉住不滑（"每周都一样，不该滑出去再滑回来"），但那一层不参与平移
 *    却要跟着纵向滚动，两页并排时会原地叠在同一屏幕位置 → 两页 scrollY 差几像素就露出
 *    两套时间交错（2026-10-30 连报两次，最终按用户要求改回整页平移）；
 * 3. 翻页行程 = **滑动层自己的宽度**（整页平移时即屏宽），并沿用「横向必须明显多于
 *    竖向」的判定（`|dx| > |dy| × 1.2`），斜着划只会回弹。
 *
 * 交互约定：
 * - 手指横向移动时页面**跟手**平移（不再是「松手才淡入淡出」，之前完全没有过程反馈）；
 * - 松手按「**速度投影**后的位移 ≥ 行程的 5%」或「横向速度 ≥ 最小抛掷速度」判定翻页。
 *   投影 = 松手位移 + 手速 × 0.10s，这是 Compose pager（AnchoredDraggable + SnapFlingBehavior）
 *   的核心手感：手指离开瞬间还带着速度，页面该顺着它再滑一小段再决定去哪。
 *   只按「松手那一刻的位移」判定的话，慢拖到中途停住再松手必然弹回 —— 就是用户报的
 *   「怎么拖都过不去」（2026-10-27 连报两次）。
 * - 学期首/末周继续往该方向拖只有 0.28 倍阻尼位移，松手回弹 —— 明确告诉用户「到头了」；
 * - 切页后把新页滚到与旧页相同的纵向位置：同一学期每页高度一致，
 *   停在「第 9 节」翻到下一周应该还在第 9 节，而不是被拉回顶部。
 *
 * 手势必须守住的既有约束（2026-10-26 修「下拉刷新指示器滞留」时定下的）：
 * 1. 没被判定为横向时一律返回 false —— 绝不吞掉竖向手势的 UP，否则 NestedScrollView
 *    收不到 `onTouchEvent(UP)`、`stopNestedScroll()` 不执行，下拉指示器停在半路；
 * 2. 横滑判定要够狠（1.5 倍 touchSlop 且明显横多于纵），否则下拉时的横向漂移会
 *    提前认定为翻页，把整段下拉手势废掉；一旦已判过竖向，门槛再抬到 2 倍 slop + 2 倍比例
 *    （不整段禁掉：斜着起手也还能翻过去）；
 * 3. 手势结束无条件放开 disallow-intercept，并让调用方恢复下拉刷新；
 * 4. 位移与速度一律基于绝对坐标（`rawX`）。现在被平移的是子层、接收触摸的外壳本身不动，
 *    用本地坐标其实也稳，但绝对坐标不依赖这个前提 —— 哪天改成平移整页也不会再出现
 *    「页面越跑越快、慢拖一直抖」（2026-10-27 的根因）。
 */
class ScheduleWeekPager(
    private val host: FrameLayout,
    /** 造一页：由调用方决定这一周长什么样（网格 / 列表） */
    private val builder: (week: Int) -> Page
) {

    /**
     * 一页。
     *
     * [root] 是整页外壳（必须是 [ScheduleSwipeLayout]），手势挂在它上面；
     * [scroll] 是该页自己的纵向滚动容器（`SwipeRefreshLayout` 也靠它判断「是否已在顶部」）；
     * [sliding] 是真正跟着手指横移的层。**网格页传空**，含义是「整页平移」（含左侧时间列）；
     * 只有需要钉住某一层时才显式列出（列表页传的是包住内容的那个容器，等价于整页）。
     *
     * 网格页曾经把左侧节次/时间列钉住不滑（"每周都一样，不该滑出去再滑回来"），
     * 代价是那一层**不参与平移却又要跟着纵向滚动**：两页并排时它会原地压在同一个屏幕位置，
     * 两页 `scrollY` 差几像素就露出两套时间交错（2026-10-30 连报两次）。
     * 现在改成整页平移 —— 每页带着自己的时间列一起走，物理上不可能重叠。
     */
    class Page(val week: Int, val root: View, val scroll: View, sliding: List<View>) {
        val sliding: List<View> = sliding.ifEmpty { listOf(root) }
    }

    var minWeek: Int = 1
    var maxWeek: Int = 20

    /** 周次已落定（周次胶囊、按周补数据都挂这里） */
    var onWeekChange: (Int) -> Unit = {}

    /** 一次翻页真正结束（含回弹到原页），用于延迟校验与相邻周预取 */
    var onSettle: (Int) -> Unit = {}

    /** 手势被判定为横向：调用方应关掉下拉刷新（否则指示器和翻页互相抢帧） */
    var onHorizontalDragStart: () -> Unit = {}

    /** 手势结束（无论是否切页），用于恢复下拉刷新 / 兜底收回指示器 */
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
        // 不要再乘系数抬高它，否则慢慢滑会被判成「没动」
        val minFling = config.scaledMinimumFlingVelocity.toFloat()
        val swipe = page.root as? ScheduleSwipeLayout ?: return

        var downRawX = 0f
        var downRawY = 0f
        var horizontal = false
        var vertical = false
        var tracker: VelocityTracker? = null

        swipe.pagerTouch = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 先占住「手指在屏上」，再收尾动画：收尾可能触发重建并换掉这一页，
                    // 而正在接收这串事件的正是它的外壳
                    touching = true
                    // 手指按上来时若上一段动画还在跑，先让它直接到位，别去拽一个正在移动的页面
                    finishAnimationNow()
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    horizontal = false
                    vertical = false
                    offset = 0f
                    // 先占住手势，别让 SwipeRefreshLayout 在半路把我们拦走（下拉圆环被拉起来
                    // 会和翻页互相抢帧，就是「两周叠着乱闪」的元凶）；判定为竖向时立刻放行
                    disallowParent(page, true)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!horizontal) {
                        // 已经判过竖向（下拉刷新 / 滚课表）：门槛抬高一档 —— 不整段禁掉，
                        // 否则「起手飘了十几像素纵向」的斜划永远翻不过去（用户报过两次
                        // 「太难滑过去」）；但只要横向明显占优，把手势抢回来是合理的。
                        val needSlop = if (vertical) slop * 2f else slop * 1.5f
                        val needRatio = if (vertical) 2f else 1f
                        if (abs(dx) > needSlop && abs(dx) > abs(dy) * needRatio) {
                            horizontal = true
                            onHorizontalDragStart()
                        } else if (!vertical && abs(dy) > slop && abs(dy) > abs(dx)) {
                            vertical = true
                            disallowParent(page, false)
                        }
                    }
                    if (horizontal) {
                        dragTo(dx)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    tracker?.addMovement(event)
                    // 1000 是**单位**（px/秒），不是采样窗口 —— VelocityTracker 内部本来就只取
                    // 最近 100ms 左右的样本做拟合，算出来的已经接近「离开前一瞬的手速」。
                    // 千万别按「缩短窗口」的思路改成 100：那样单位变成 px/100ms，数值缩到十分之一，
                    // 任何滑动都过不了 fling 门槛。
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.xVelocity ?: 0f
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (horizontal) release(dx, dy, velocity, minFling)
                    endGesture(page)
                    tracker?.recycle()
                    tracker = null
                    horizontal
                }

                MotionEvent.ACTION_CANCEL -> {
                    // 父控件把手势抢走（例如真的开始下拉刷新了）：一律回弹，绝不切周。
                    // 以前把 CANCEL 当松手处理，event.x 归零后算出巨大位移直接判成切周，
                    // 内容停在半路、周次和内容对不上。
                    if (horizontal) snapBack()
                    endGesture(page)
                    tracker?.recycle()
                    tracker = null
                    horizontal
                }

                else -> false
            }
        }
    }

    private fun dragTo(dx: Float) {
        val travel = travelOf(current)
        if (travel <= 0f) return
        // 每次位移都按当前方向校正相邻页：拖动中途反向时（先往右再往左）
        // 如果不换页，松手会翻到手指的反方向那一周。
        // 只在明显位移时校正：围绕 0 的亚像素抖动不该反复重建页面
        if (abs(dx) > slop) ensureSide(if (dx < 0f) 1 else -1)
        if (side == null) {
            // 学期边界：没有相邻周可露出来，只给一小段阻尼位移当作「到头了」的反馈
            applyOffset((dx * EDGE_DAMPING).coerceIn(-travel * 0.3f, travel * 0.3f))
        } else {
            applyOffset(dx.coerceIn(-travel, travel))
        }
    }

    private fun release(dx: Float, dy: Float, velocity: Float, minFling: Float) {
        val travel = travelOf(current)
        if (side == null || travel <= 0f) {
            snapBack()
            return
        }
        // 斜着划不算翻页：竖向分量和横向一个量级时，用户其实是在滚课表
        if (abs(dx) < abs(dy) * AXIS_RATIO) {
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
        val byDistance = sameDirection && abs(projected) >= travel * COMMIT_DISTANCE_RATIO

        // 快滑几乎不看位移：只要真的横向挪动过就算数。
        // 这个下限不是「要求拖多远」，只用来挡住「原地抖动 + 速度尖峰」的误判
        val byFling = abs(velocity) >= minFling &&
            (velocity < 0f) == forward &&
            abs(dx) >= slop * 0.5f

        if (byDistance || byFling) commit(forward) else snapBack()
    }

    private fun endGesture(page: Page) {
        touching = false
        disallowParent(page, false)
        onGestureEnd()
        drainPending()
    }

    /** 放开/占住父控件的拦截开关（SwipeRefreshLayout 在链上） */
    private fun disallowParent(page: Page, disallow: Boolean) {
        (page.root.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(disallow)
    }

    // ==================== 页面 ====================

    /**
     * 这一页的横移行程 = 滑动层自己的宽度。
     * 网格页的滑动层是「7 天列」（屏宽 - 时间列），周视图页是整块内容。
     * 用外层容器宽度是不对的：左轴钉住以后，列区只需要滑一个列区宽度就正好接上。
     */
    private fun travelOf(page: Page?): Float {
        if (page == null) return host.width.toFloat().coerceAtLeast(1f)
        val w = page.sliding.firstOrNull()?.width ?: 0
        return if (w > 0) w.toFloat() else host.width.toFloat().coerceAtLeast(1f)
    }

    /** 把相邻周准备好并放到屏幕外侧；超出学期范围时什么都不建（交给阻尼回弹） */
    private fun ensureSide(direction: Int) {
        val base = current ?: return
        val target = base.week + direction
        if (target < minWeek || target > maxWeek) {
            discardSide()
            return
        }
        val travel = travelOf(base)
        val cached = side
        if (cached?.week == target) {
            // 复用的缓存页：校正一下位置（回弹时它会停在屏外，但如果期间发生过
            // 尺寸变化——旋转、分屏、折叠展开——原来的 ±travel 就不准了）
            val x = if (direction > 0) travel else -travel
            cached.sliding.forEach { it.translationX = x }
            // 回弹后用户可能接着滚动了这一周的内容，缓存页的 scrollY 就旧了。
            // 它此刻已经布局完成，这一下 scrollTo 立即生效；不补的话切页那刻内容会跳一下
            val y = base.scroll.scrollY
            if (cached.scroll.scrollY != y) cached.scroll.scrollTo(0, y)
            return
        }
        discardSide()
        val page = builder(target)
        host.addView(page.root, pageParams())
        install(page)
        val x = if (direction > 0) travel else -travel
        page.sliding.forEach { it.translationX = x }
        // 离屏时就把纵向位置对齐：等切页那一刻再滚，用户会看到一次明显的跳变。
        // 必须用 doOnNextLayout 而不是 post：新建页此刻还没 measure/layout，
        // 直接 scrollTo 会因「滚动范围 = 0」被钳到 0；而 View.post 的 Runnable 跑在
        // 下一帧 performTraversals 之前（layout 还没走），同样白跑一趟。
        val y = base.scroll.scrollY
        if (y > 0) {
            page.scroll.doOnNextLayout {
                if (page.root.parent != null) page.scroll.scrollTo(0, y)
            }
        }
        side = page
        pruneHost()
    }

    /**
     * 宿主里只允许存在「当前页 + 相邻页」两个 root。
     *
     * 页是后 add 的在上层，多出来的任何一页都会把它的**钉住层下方的列区**露在屏幕上
     * （例如某个早退路径漏了 remove），这正是「两套时间」另一种可能的来源。
     * 收尾时扫一遍比在每条分支里各写一次 removeView 更可靠。
     */
    private fun pruneHost() {
        val keepCurrent = current?.root
        val keepSide = side?.root
        for (i in host.childCount - 1 downTo 0) {
            val child = host.getChildAt(i)
            if (child !== keepCurrent && child !== keepSide) host.removeViewAt(i)
        }
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
        page.sliding.forEach { it.translationX = 0f }
        if (keepScrollY > 0) page.scroll.doOnNextLayout { page.scroll.scrollTo(0, keepScrollY) }
        pruneHost()
        onWeekChange(week)
        onSettle(week)
        drainPending()
    }

    private fun pageParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    // ==================== 动画 ====================

    /** 手指位移 → 两页滑动层的横坐标（并排平移，不叠放） */
    private fun applyOffset(value: Float) {
        offset = value
        val base = current ?: return
        base.sliding.forEach { it.translationX = value }
        val other = side ?: return
        // 相邻页滑动层的自然位置：当前页列区右侧（下一周）或左侧（上一周）
        val travel = travelOf(base)
        val shift = if (other.week > base.week) travel + value else -travel + value
        other.sliding.forEach { it.translationX = shift }
    }

    /** 翻页：滑到相邻周完全就位后换 current */
    private fun commit(forward: Boolean) {
        val travel = travelOf(current)
        val next = side
        val expected = if (forward) week + 1 else week - 1
        // 相邻页必须是这次要去的方向（拖动中途反向时如果没来得及换页，宁可回弹）
        if (next == null || travel <= 0f || next.week != expected) {
            snapBack()
            return
        }
        animate(offset, if (forward) -travel else travel, R.integer.motion_medium1) {
            val old = current
            // 这一页要顶上来了：先把纵向位置钉死在与旧页一致处，再放出它的时间列。
            // 顺序反过来的话，时间列会先以自己（可能还没对齐的）scrollY 露一帧，
            // 左轴看着「跳一下」—— 与两套时间叠在一起是同一个毛病
            val settleY = old?.scroll?.scrollY ?: 0
            next.scroll.scrollTo(0, settleY)
            current = next
            side = null
            offset = 0f
            next.sliding.forEach { it.translationX = 0f }
            old?.let { host.removeView(it.root) }
            pruneHost()
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
        val travel = travelOf(current).coerceAtLeast(1f)
        val remaining = (abs(to - from) / travel).coerceIn(0f, 1f)
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
         * 松手即翻页的位移门槛（占行程的比例），作用在**速度投影之后**的落点上。
         *
         * 1080px 屏约 54px，比 MOVE 阶段的横滑准入（1.5 倍 touchSlop ≈ 33px）只高一点点 ——
         * 也就是说：手势一旦被认定为「明确的横滑」，基本就一定会翻页。
         * 这是有意为之（2026-10-27 用户两次反馈「太难滑过去」）：课表页不存在
         * 「横向微调但不翻页」的需求，翻错了再滑回来成本很低，而「拖半天弹回去」很恼人。
         * miuix 版用的是 20%（无速度投影）；这里保留投影 + 5%，两者手感接近但更宽容，
         * 要严格对齐把它改成 0.2f 并把 PROJECTION_SECONDS 归零即可。
         */
        const val COMMIT_DISTANCE_RATIO = 0.05f

        /**
         * 速度投影时长（秒）。松手位移 + 手速 × 该系数 = 判定用的落点。
         *
         * 0.10s 大致等于「一次轻拂的惯性尾巴」：随手一划约 800~1500px/s，投影 80~150px，
         * 加上实际拖的几十像素，落点稳稳越过门槛。取太大（如 0.3s）会让轻点也翻页。
         */
        const val PROJECTION_SECONDS = 0.10f

        /** 横向必须明显多于竖向才算翻页（对齐 miuix 版：`|dx| > |dy| × 1.2`） */
        const val AXIS_RATIO = 1.2f

        /** 学期边界处的阻尼系数（拖不动，但有跟手的反馈） */
        const val EDGE_DAMPING = 0.28f

        /** 收尾动画时长下限比例：剩余距离为 0 时仍保留基础的短促时间，避免 0ms 突跳 */
        const val MIN_DURATION_RATIO = 0.55f
    }
}
