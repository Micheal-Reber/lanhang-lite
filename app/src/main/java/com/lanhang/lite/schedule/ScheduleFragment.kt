package com.lanhang.lite.schedule

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.TextViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.lanhang.lite.R
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.ui.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

class ScheduleFragment : Fragment() {

    private lateinit var tvWeekPill: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnCalendar: MaterialButton
    /** 网格/列表切换：单按钮点按（2026-10-25 由两段式分段按钮改回，让周次能真正居中） */
    private lateinit var btnViewSwitch: MaterialButton
    /** 承载所有周次页面的容器，每页一整个视图树，见 [ScheduleWeekPager] */
    private lateinit var pagerHost: FrameLayout
    private lateinit var pager: ScheduleWeekPager
    private lateinit var tvEmpty: TextView
    private lateinit var progress: CircularProgressIndicator
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var bgSolidLayer: View
    private lateinit var bgImageLayer: ImageView
    private lateinit var bgVeil: View
    /** 当前配色令牌：无自定义背景时来自主题，有背景时来自背景分析 */
    private lateinit var style: ScheduleStyle
    /** 已应用的背景标识，避免每次 onResume 重复分析/重建视图 */
    private var appliedBackdropKey: String? = null
    /** 横向翻页期间临时关掉了下拉刷新，手势结束时必须恢复 */
    private var refreshSuppressed = false

    private var currentWeek = 1

    private var maxWeek = 20
    private var startWeek = 1
    private var actualCurrentWeek = 1
    private var scheduleData: ScheduleData? = null
    /** 按周缓存的服务端数据（官方 GetSchedule&week=N 每周一份） */
    private val weekData = HashMap<Int, ScheduleData>()
    private var showingGrid = true  // 默认网格视图

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvWeekPill = view.findViewById(R.id.tvWeekPill)
        btnPrev = view.findViewById(R.id.btnPrevWeek)
        btnNext = view.findViewById(R.id.btnNextWeek)
        btnCalendar = view.findViewById(R.id.btnCalendar)
        btnViewSwitch = view.findViewById(R.id.btnViewSwitch)
        pagerHost = view.findViewById(R.id.pagerHost)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        progress = view.findViewById(R.id.progress)
        // 自建课弹层保存/删除后重建当前周（switchWeek 见到同周会 return，必须直接 rebuild）
        parentFragmentManager.setFragmentResultListener(
            LocalCourseEditSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            if (view != null) pager.rebuild(currentWeek)
        }
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        bgSolidLayer = view.findViewById(R.id.bgSolidLayer)
        bgImageLayer = view.findViewById(R.id.bgImageLayer)
        bgVeil = view.findViewById(R.id.bgVeil)

        pager = ScheduleWeekPager(pagerHost) { week -> buildPage(week) }
        pager.onWeekChange = { week ->
            currentWeek = week
            updateWeekPill()
            ensureWeekData(week)
        }
        // 停稳后再预取相邻周：翻过去时用的是服务端该周数据，而不是本地全量过滤的兜底
        pager.onSettle = { week -> prefetchNeighbors(week) }
        // 判定为横向就关掉下拉刷新（对齐 miuix 版）：SwipeRefreshLayout 的圆环被拉起来
        // 会和翻页互相抢帧，一行代码换来「不叠影、不闪」；手势结束必须恢复
        pager.onHorizontalDragStart = {
            if (swipeRefresh.isEnabled) {
                swipeRefresh.isEnabled = false
                refreshSuppressed = true
            }
        }
        pager.onGestureEnd = {
            if (refreshSuppressed) {
                refreshSuppressed = false
                // 重新 enable 会走库内部的 reset()：清动画 + 指示器归位，不会触发刷新回调
                swipeRefresh.isEnabled = true
            }
            settleRefreshIndicator()
        }

        style = ScheduleStyle.fromTheme(requireContext())
        applyChromeStyle()

        btnPrev.setOnClickListener { switchWeek(currentWeek - 1) }
        btnNext.setOnClickListener { switchWeek(currentWeek + 1) }
        btnCalendar.setOnClickListener { showWeekPicker() }
        // 单按钮切换：点一下就换到另一个视图（图标与无障碍描述见 updateViewSwitch）
        btnViewSwitch.setOnClickListener { switchView(!showingGrid) }
        swipeRefresh.setOnRefreshListener { refresh() }
        // SwipeRefreshLayout 只有**直接子视图**这一份滚动状态可看，而这里的直接子视图是
        // LinearLayout（工具条 + 翻页容器），`canScrollVertically(-1)` 永远 false ——
        // 它会把「表格已经滚到中间还往下拖」也当成下拉刷新，白拉一下不滚动、只弹指示器。
        // 1.1.0 提供官方回调，明确告诉它当前这一页的滚动容器是否真的在顶部。
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            pager.scrollView?.canScrollVertically(-1) ?: false
        }

        // 背景要铺到顶栏与状态栏后面：容器仍从顶栏下方开始，靠负 topMargin 让背景层向上溢出
        // （activity_main.xml 里 toolbar 在课表页透明、容器 clipChildren=false，见 MainActivity.showToolbarMain）
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val statusTop = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()
            ).top
            applyBackgroundOverflow(statusTop + dimen(R.dimen.top_app_bar_height))
            insets
        }

        applyBackdrop()
        loadCacheOrRefresh()
        startWeekRolloverWatcher()
    }

    /**
     * 背景三层向上溢出 [overflowPx]，盖住顶栏与状态栏区域。
     * FrameLayout 里 MATCH_PARENT 高度的子 View 会按 `父高 - (topMargin+bottomMargin)` 测量，
     * 所以负 topMargin 会同时把高度撑高，不需要自己算像素。
     */
    private fun applyBackgroundOverflow(overflowPx: Int) {
        if (overflowPx <= 0) return
        for (layer in listOf(bgSolidLayer, bgImageLayer, bgVeil)) {
            val lp = layer.layoutParams as? FrameLayout.LayoutParams ?: continue
            if (lp.topMargin == -overflowPx) continue
            lp.topMargin = -overflowPx
            layer.layoutParams = lp
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「课表背景」设置返回后，背景/配色可能已变化
        applyBackdrop()
        // 进程挂后台跨周（周日熄屏、周一再打开）时缓存周次已过期
        syncActualWeek()
    }

    /**
     * 每次 Tab 切回课表时由 MainActivity 调用。
     * 四个 Tab 用 hide/show 切换、Fragment 不重建，所以不会走 onResume，
     * 需要显式把浏览周拉回当前实际周——否则上次翻到第 N 周，切回来还停在第 N 周。
     */
    fun onTabSelected() {
        if (view == null) return
        // 先把可能过期的「实际当前周」重算一遍（跨周后切回来要直接落在新一周）
        syncActualWeek()
        if (currentWeek == actualCurrentWeek) return
        // Tab 切换本身已有 fade through，这里不再叠一层，直接重建
        switchWeek(actualCurrentWeek, animate = false)
    }

    /** 空态文案：出现走 200ms 淡入（M3 standard），消失直接隐藏，避免与内容重建互相拖影 */
    private fun setEmptyVisible(visible: Boolean) {
        if (visible) {
            if (tvEmpty.visibility == View.VISIBLE && tvEmpty.alpha == 1f) return
            tvEmpty.visibility = View.VISIBLE
            Motion.fadeIn(requireContext(), tvEmpty, R.integer.motion_short4)
        } else {
            tvEmpty.animate().cancel()
            tvEmpty.visibility = View.GONE
            tvEmpty.alpha = 1f
        }
    }

    /**
     * 加载并应用自定义背景：图片/纯色/渐变 → 分析明暗与主色 → 生成配色令牌。
     * 分析在 IO 线程做，主线程只负责上屏。
     */
    private fun applyBackdrop(force: Boolean = false) {
        val ctx = context ?: return
        val spec = ScheduleBackdrop.load(ctx)
        val key = "${spec.type}|${spec.value}"
        if (!force && key == appliedBackdropKey) return
        appliedBackdropKey = key
        viewLifecycleOwner.lifecycleScope.launch {
            val analysis = withContext(Dispatchers.IO) {
                runCatching { ScheduleBackdrop.analyze(ctx, spec) }.getOrNull()
            }
            val host = context ?: return@launch
            ScheduleBackdrop.bind(host, bgSolidLayer, bgImageLayer, bgVeil, spec, analysis)
            style = if (analysis == null) {
                ScheduleStyle.fromTheme(host)
            } else {
                ScheduleStyle.fromBackdrop(analysis)
            }
            applyChromeStyle()
            renderAll()
        }
    }

    /** 顶栏、面板等「常驻视图」的配色随令牌刷新 */
    private fun applyChromeStyle() {
        val iconTint = ColorStateList.valueOf(style.onBackgroundVariant)
        // MaterialButton 用 iconTint（原来的自绘圆形底 + ImageButton 已删除）
        btnPrev.iconTint = iconTint
        btnNext.iconTint = iconTint
        btnCalendar.iconTint = ColorStateList.valueOf(style.accent)
        tvEmpty.setTextColor(style.onBackgroundVariant)
        // 下拉指示器：默认是系统白圆盘（深色主题/深色背景上像一颗白球），
        // 改成 M3 的「容器色圆盘 + 主色弧」，跟课表自己的配色令牌一起走
        swipeRefresh.setColorSchemeColors(style.accent)
        swipeRefresh.setProgressBackgroundColorSchemeColor(style.circleFill)
        updateWeekPill()
        updateViewSwitch()
        // 课表页顶栏是透明浮层（背景要透上去），标题/图标颜色必须跟着背景明暗走
        (activity as? com.lanhang.lite.ui.MainActivity)
            ?.onScheduleChromeChanged(style.onBackground, style.darkSurface)
    }

    /**
     * 单按钮切换的图标语义：显示「点一下会切到的那个视图」。
     * 当前是网格 → 显示周视图图标（点它进入周视图），反之亦然；无障碍描述同步更新。
     */
    private fun updateViewSwitch() {
        btnViewSwitch.setIconResource(
            if (showingGrid) R.drawable.ic_view_week else R.drawable.ic_view_grid
        )
        btnViewSwitch.contentDescription = getString(
            if (showingGrid) R.string.schedule_switch_to_week else R.string.schedule_switch_to_grid
        )
        btnViewSwitch.iconTint = ColorStateList.valueOf(style.onBackgroundVariant)
    }

    /**
     * 兜底收回下拉指示器。
     *
     * SwipeRefreshLayout 只在**它自己收到** UP/CANCEL 且 `mIsBeingDragged` 为真时才回弹，
     * 或只在 `onStopNestedScroll` 里回弹；中间任何一步漏掉，指示器就停在拉到一半的位置
     * （顶部留一颗圆球，见 2026-10-26 反馈截图）。它没有公开的「复位」方法，但
     * `setEnabled(false)` 会走内部 `reset()`（清动画 + 回 `mOriginalOffsetTop` + GONE），
     * 紧接着恢复启用即可，不触发刷新回调。
     *
     * 排在库自己的回弹动画（200ms）之后执行：正常情况指示器早已归位，再 reset 一次
     * 没有可见变化，只有真卡住时才看得出效果。正在刷新、或用户已经按下一次拖动时不动。
     */
    private fun settleRefreshIndicator() {
        val root = view ?: return
        val delay = root.resources.getInteger(R.integer.motion_medium1).toLong()
        root.postDelayed({
            if (view == null || pager.isTouching || swipeRefresh.isRefreshing) return@postDelayed
            swipeRefresh.isEnabled = false
            swipeRefresh.isEnabled = true
        }, delay)
    }

    /** 数据固定在本地：有缓存直接显示，不自动联网；只有手动下拉刷新才重新拉取覆盖 */
    private fun loadCacheOrRefresh() {
        val cached = ScheduleRepository.loadCached()
        if (cached?.data != null) {
            scheduleData = cached.data
            applyWeekInfo(cached.data)
            ScheduleRepository.loadCachedWeek(actualCurrentWeek)?.data?.let {
                weekData[actualCurrentWeek] = it
            }
            renderAll()
        } else {
            // M3 CircularProgressIndicator 自带 fade+scale 的 show/hide 动画，
            // 直接改 visibility 会绕过它，首载变成硬闪
            progress.show()
            refresh()
        }
    }

    private fun applyWeekInfo(data: ScheduleData) {
        data.weekInfo?.let { info ->
            info.startWeek?.trim()?.toIntOrNull()?.let { startWeek = it }
            info.endWeek?.trim()?.toIntOrNull()?.let { maxWeek = it }
            // 缓存里的 currentWeek 是「写入那一刻」的周次，缓存放上一周就过期了，
            // 必须按锚点推进到今天，否则过了周日还停在第 8 周（见 ScheduleWeek）
            actualCurrentWeek = info.currentWeek?.trim()?.toIntOrNull()
                ?.let { ScheduleWeek.current(it, startWeek, maxWeek) }
                ?: startWeek
            currentWeek = actualCurrentWeek
        }
        pager.minWeek = startWeek
        pager.maxWeek = maxWeek
        updateWeekPill()
    }

    /**
     * 重算「实际当前周」并同步界面。
     *
     * 触发时机：切回课表 Tab、App 回到前台、跨周定时器到点。
     * 只有用户此刻正停在当周才跟着跳过去；正在翻看别的周时保留浏览位置，
     * 只更新周次胶囊的「当周」高亮。
     */
    private fun syncActualWeek() {
        val cached = scheduleData?.weekInfo?.currentWeek?.trim()?.toIntOrNull() ?: return
        val expected = ScheduleWeek.current(cached, startWeek, maxWeek)
        if (expected == actualCurrentWeek) return
        val following = currentWeek == actualCurrentWeek
        actualCurrentWeek = expected
        if (following) {
            currentWeek = expected
            updateWeekPill()
            if (view != null) renderAll()
            ensureWeekData(expected)
        } else {
            updateWeekPill()
        }
    }

    /**
     * 前台跨周自动跳转：App 一直停在课表页（周日挂后台、周一直接点亮）时，
     * onResume / onTabSelected 都不会再触发，靠这个定时器到点重算一次。
     */
    private fun startWeekRolloverWatcher() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(ScheduleWeek.millisUntilNextWeek())
                // 后台到点不重绘也不补拉，回前台 onResume 会补一次
                if (isResumed && view != null) syncActualWeek()
            }
        }
    }

    /**
     * 手动下拉刷新：从教务系统重新拉取并覆盖本地缓存；刷新期间保持当前浏览周不变。
     * 刷新前正停在当周时，刷完跟着新的当周走（跨周后不能退回旧周）。
     */
    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val browseWeek = currentWeek
                val wasFollowing = browseWeek == actualCurrentWeek
                val first = withContext(Dispatchers.IO) { ScheduleRepository.refresh(null) }
                scheduleData = first.data
                // 整学期数据整份换新：内存里按周存的那几份也一起作废，
                // 否则切到别的周还会用旧课（2026-10-26 反馈：教务撤掉的课冷启动后又出现）
                weekData.clear()
                first.data?.let { applyWeekInfo(it) }
                // applyWeekInfo 会把 currentWeek 改成当周，这里还原成刷新前正在看的那周
                val target = if (wasFollowing) actualCurrentWeek else browseWeek
                if (target == actualCurrentWeek) {
                    first.data?.let { weekData[target] = it }
                } else {
                    // 浏览的不是当前实际周：再拉一次浏览周数据覆盖该周缓存
                    try {
                        val byWeek = withContext(Dispatchers.IO) { ScheduleRepository.refresh(target) }
                        byWeek.data?.let { weekData[target] = it }
                    } catch (_: Exception) {
                    }
                }
                currentWeek = target
                updateWeekPill()
                renderAll()
                // 课表可能是换过/撤过课的：提醒的闹钟必须按新数据重排。
                // 只在 refresh(null)（整学期全量）成功后调 —— 提醒只读整学期缓存，
                // 切周拉的 schedule_wN.json 不参与计算。
                context?.let { ClassReminder.rearm(it) }
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Toast.makeText(
                    requireContext(),
                    e.userMessage(requireContext(), R.string.schedule_refresh_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (scheduleData == null) {
                    tvEmpty.text = getString(R.string.schedule_no_data_hint)
                    setEmptyVisible(true)
                }
            } finally {
                progress.hide()
                swipeRefresh.isRefreshing = false
            }
        }
    }

    /**
     * 切周统一入口（按钮 / 周次面板 / 切回 Tab）。
     *
     * - 相邻周：交给 [ScheduleWeekPager] 做滑动（跟手 + 吸附）；
     * - 跨多周（周次面板一次跳很远）：沿用 M3 fade through —— 方向没有意义，
     *   从第 3 周滑到第 15 周只会看到一长串空白平移。
     */
    private fun switchWeek(week: Int, animate: Boolean = true) {
        if (week < startWeek || week > maxWeek) return
        if (week == currentWeek) return
        val jump = abs(week - currentWeek) > 1
        if (animate && !jump && pager.isAttached) {
            pager.goTo(week, animate = true)
            return
        }
        val apply = {
            currentWeek = week
            updateWeekPill()
            pager.rebuild(week)
        }
        // 动画中途再次触发时 Motion.fadeThrough 会取消并立即重建，不会卡半透明
        if (animate && view != null) {
            Motion.fadeThrough(requireContext(), pagerHost) { if (view != null) apply() }
        } else {
            apply()
        }
    }

    /** 渲染优先用该周专属数据；无则回退主缓存全量+本地周次过滤 */
    private fun dataForWeek(week: Int): ScheduleData? =
        weekData[week]
            ?: ScheduleRepository.loadCachedWeek(week)?.data?.also { weekData[week] = it }
            ?: scheduleData

    /** 该周无数据时按官方方式 GetSchedule&week=N 补拉 */
    private fun ensureWeekData(week: Int) {
        if (week < startWeek || week > maxWeek) return
        if (weekData.containsKey(week)) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ScheduleRepository.refresh(week) }
                resp.data?.let {
                    weekData[week] = it
                    // 只有正停在这一周才重画；邻周拉到先存着，翻过去直接用
                    if (currentWeek == week) {
                        pager.rebuild(week)
                    } else {
                        // 邻周数据到位了：可能已经有一页按旧数据（主缓存回退）建好放在屏外，
                        // 不回填的话用户拖过去看到的是旧课表
                        pager.invalidateSide()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 翻到某一周后顺手把左右邻周的服务端数据取回来。
     *
     * 目的不是「没数据」——本地全量缓存按 `isWeekValid` 过滤也能画；
     * 而是抢在用户翻页之前拿到官方该周数据（教务调整过的课只体现在按周接口里）。
     * 命中缓存时 [ensureWeekData] 直接返回，不会反复发请求。
     */
    private fun prefetchNeighbors(week: Int) {
        ensureWeekData(week - 1)
        ensureWeekData(week + 1)
    }

    /**
     * 周次按钮：M3 里用 chip 形态表达"当前筛选值"。
     * 当周 → tonal 填充（secondaryContainer）；非当周 → 透明 + 1dp 描边。
     */
    private fun updateWeekPill() {
        tvWeekPill.text = getString(R.string.schedule_week_format, currentWeek)
        if (currentWeek == actualCurrentWeek) {
            tvWeekPill.backgroundTintList = ColorStateList.valueOf(style.accentFill)
            tvWeekPill.strokeWidth = 0
            tvWeekPill.setTextColor(style.onAccentFill)
        } else {
            tvWeekPill.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            tvWeekPill.strokeWidth = dp(1)
            tvWeekPill.strokeColor = ColorStateList.valueOf(style.outline)
            tvWeekPill.setTextColor(style.onBackgroundVariant)
        }
    }

    /**
     * 周次选择：M3 BottomSheet（原实现是系统 PopupMenu）。
     * 列表按 startWeek..maxWeek 生成，当前周带主色文字与对勾，打开时自动滚到当前周。
     */
    private fun showWeekPicker() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val sheet = layoutInflater.inflate(R.layout.dialog_week_picker, null, false)
        val list = sheet.findViewById<LinearLayout>(R.id.weekList)
        // 必须是 NestedScrollView：BottomSheetBehavior 靠嵌套滚动判断「这一拖是滚列表还是收面板」，
        // 系统 ScrollView 它认不出来，向下拖会直接把面板关掉（见 dialog_week_picker.xml 注释）
        val scroll = sheet.findViewById<androidx.core.widget.NestedScrollView>(R.id.weekPickerScroll)

        for (w in startWeek..maxWeek) {
            val selected = w == currentWeek
            val row = layoutInflater.inflate(R.layout.item_week_option, list, false)
            row.findViewById<TextView>(R.id.tvWeekOption).apply {
                text = getString(R.string.schedule_week_menu_format, w)
                setTextColor(if (selected) style.accent else style.onBackground)
            }
            row.findViewById<ImageView>(R.id.ivWeekCheck).visibility =
                if (selected) View.VISIBLE else View.GONE
            row.setOnClickListener {
                switchWeek(w)
                dialog.dismiss()
            }
            list.addView(row)
        }

        // 列表最高占屏幕一半，周数少时按实际高度收
        val rowHeight = resources.getDimensionPixelSize(R.dimen.list_item_height_single)
        val rows = (maxWeek - startWeek + 1).coerceAtLeast(1)
        scroll.layoutParams = scroll.layoutParams.apply {
            height = minOf(rows * rowHeight, (resources.displayMetrics.heightPixels * 0.5f).toInt())
        }

        dialog.setContentView(sheet)
        // 面板最高只占半屏，收起态（半截）没有意义：列表滚到第一周后继续往下拖，直接关掉面板
        dialog.behavior.skipCollapsed = true
        dialog.show()
        scroll.post {
            val target = list.getChildAt(currentWeek - startWeek)
            if (target != null) {
                // 平滑定位：面板展开（M3 BottomSheet 默认动效）与列表滚动同时进行，
                // 比瞬移更连贯，也避免和面板动画抢帧
                scroll.smoothScrollTo(0, (target.top - scroll.height / 3).coerceAtLeast(0))
            }
        }
    }

    private fun switchView(grid: Boolean) {
        if (showingGrid == grid) return
        showingGrid = grid
        updateViewSwitch()
        // 两种视图是两套完全不同的页面结构（固定表头 + 滚动表格 / 整周滚动列表），
        // 切换就等于重建当前周那一页，不做交叉过渡
        pager.rebuild(currentWeek)
        // 周视图 ↔ 网格视图：M3 fade through 的「入」阶段（210ms, emphasizedDecelerate）
        if (view != null) {
            Motion.fadeIn(requireContext(), pagerHost)
        }
    }

    private fun renderAll() {
        if (scheduleData != null) setEmptyVisible(false)
        if (view == null) return
        pager.rebuild(currentWeek)
    }

    /** 造一页：网格与列表共用同一个翻页容器，只有页面内容不同 */
    private fun buildPage(week: Int): ScheduleWeekPager.Page {
        val ctx = context ?: requireContext()
        return if (showingGrid) {
            val width = pagerHost.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            buildGridPage(ctx, week, width)
        } else {
            buildWeekPage(ctx, week)
        }
    }

    // ==================== 周视图 ====================

    /**
     * 列表视图一页：整周 7 天摘要 + 每天课程区，纵向滚动。
     *
     * 「本周无课」画在页面里而不是页面外的固定浮层 —— 它得跟着这一页一起翻走，
     * 否则翻到空周时上一周的提示还留在屏幕上。
     */
    private fun buildWeekPage(ctx: Context, week: Int): ScheduleWeekPager.Page {
        val scroll = NestedScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, dimen(R.dimen.content_padding_bottom))
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 7 天摘要条 / 每天课程区：原来在 XML 里，现在每页各一份
        val dayStrip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dimen(R.dimen.screen_padding_h)
                topMargin = dimen(R.dimen.space_xs)
                marginEnd = dimen(R.dimen.screen_padding_h)
                bottomMargin = dimen(R.dimen.space_sm)
            }
        }
        val daySections = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(dayStrip)
        content.addView(daySections)

        // 滑动层：周视图没有「每周都一样的左轴」，整块内容跟着手指走
        val sliding = FrameLayout(ctx).apply {
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        // 外壳（[ScheduleSwipeLayout]）：手势挂它身上，永不平移。
        // 挂在外壳上而不是 scroll 上，是因为课程卡片可点击会消费 DOWN，
        // 父级 OnTouchListener 连 DOWN 都收不到 —— 按在课上就滑不动了。
        val shell = ScheduleSwipeLayout(ctx).apply {
            addView(
                sliding,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val data = dataForWeek(week)
        if (data == null) return ScheduleWeekPager.Page(week, shell, scroll, listOf(sliding))

        val density = resources.displayMetrics.density
        val s = style

        val today = LocalDate.now()
        val dayNames = resources.getStringArray(R.array.schedule_day_names)
        val monday = today.with(DayOfWeek.MONDAY)
        val offset = week - actualCurrentWeek
        val weekMonday = monday.plusWeeks(offset.toLong())

        var totalCourses = 0

        for (dayIdx in 0 until 7) {  // 周一~周日
            val date = weekMonday.plusDays(dayIdx.toLong())
            val isToday = date == today
            val courses = getCoursesForDay(data, dayIdx, week)
            if (courses.isNotEmpty()) totalCourses++

            // 摘要格（M3 卡片：今天 = secondaryContainer，其余 = surfaceContainerLow）
            val summary = LayoutInflater.from(ctx)
                .inflate(R.layout.item_day_summary, dayStrip, false) as MaterialCardView
            summary.setCardBackgroundColor(if (isToday) s.accentFill else s.chipFill)
            summary.findViewById<TextView>(R.id.tvDayName).apply {
                text = dayNames[dayIdx]
                setTextColor(if (isToday) s.onAccentFill else s.onBackground)
            }
            summary.findViewById<TextView>(R.id.tvCount).apply {
                text = resources.getQuantityString(
                    R.plurals.schedule_day_count,
                    courses.size,
                    courses.size
                )
                background = s.rounded(density, 50f, s.badgeFill(isToday))
                setTextColor(if (isToday) s.onAccentFill else s.onBackgroundVariant)
            }

            // 摘要格只表达「周几 + 门数」：课程名在下面各自的课程区里已经列全，
            // 摘要里再铺一遍既重复又把 7 张卡撑得高矮不一（2026-10-25 按反馈移除）
            dayStrip.addView(summary)

            // 课程区（M3 filled card）
            val section = LayoutInflater.from(ctx)
                .inflate(R.layout.item_day_section, daySections, false) as MaterialCardView
            section.setCardBackgroundColor(s.cardFill)
            val header = section.findViewById<TextView>(R.id.tvDayHeader)
            header.text = if (isToday) {
                getString(R.string.schedule_day_today, dayNames[dayIdx])
            } else {
                dayNames[dayIdx]
            }
            header.setTextColor(if (isToday) s.accent else s.onBackground)
            val container = section.findViewById<LinearLayout>(R.id.lessonContainer)

            if (courses.isEmpty()) {
                val tv = TextView(ctx).apply {
                    text = getString(R.string.schedule_day_no_course, dayNames[dayIdx])
                    setTextAppearance(R.style.TextAppearance_Lanhang_BodySmall)
                    setTextColor(s.onBackgroundVariant)
                }
                container.addView(tv)
            } else {
                val merged = mergeCourses(courses)
                merged.forEach { ci ->
                    addLessonRow(container, ci)
                }
            }
            daySections.addView(section)
        }

        if (totalCourses == 0) {
            sliding.addView(
                TextView(ctx).apply {
                    text = getString(R.string.schedule_week_no_course)
                    setTextAppearance(R.style.TextAppearance_Lanhang_BodyMedium)
                    setTextColor(s.onBackgroundVariant)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        return ScheduleWeekPager.Page(week, shell, scroll, listOf(sliding))
    }

    private fun addLessonRow(parent: LinearLayout, ci: CourseItem) {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lesson_row, parent, false) as MaterialCardView
        // M3 tonal 卡：容器色 + 成对的 onContainer 文字色（不再白字压在饱和色上）
        val tone = style.courseTone(ci.course.name ?: "?")
        view.setCardBackgroundColor(tone.container)
        val textColor = tone.onContainer

        val periodLabel = if (ci.periodStart == ci.periodEnd) {
            getString(R.string.schedule_period_single, ci.periodStart)
        } else {
            getString(R.string.schedule_period_range, ci.periodStart, ci.periodEnd)
        }
        view.findViewById<TextView>(R.id.tvTimeLabel).apply {
            text = periodLabel
            setTextColor(textColor)
        }
        view.findViewById<TextView>(R.id.tvName).apply {
            text = ci.course.name ?: ""
            setTextColor(textColor)
        }
        view.findViewById<TextView>(R.id.tvMeta).apply {
            text = "${ci.course.teacher ?: ""} · ${ci.course.room ?: ""}"
            setTextColor(textColor)
        }

        view.setOnClickListener { showCourseDetail(ci) }
        parent.addView(view)
    }

    /** 课程详情弹窗：标题/圆角/背景/关闭按钮交给 M3 对话框，内容只有课程色条 + 标签值行 */
    private fun showCourseDetail(ci: CourseItem) {
        val v = layoutInflater.inflate(R.layout.dialog_course_detail, null, false)
        // 色条用该课程的 onContainer（深色那支）：浅色容器做色条在浅面板上几乎看不见
        val courseBar = style.courseTone(ci.course.name ?: "?").onContainer
        v.findViewById<View>(R.id.colorBar).setBackgroundColor(courseBar)
        fun row(id: Int, text: String) {
            v.findViewById<TextView>(id).text = text
        }
        val odd = when (ci.course.oddEven) {
            "1" -> getString(R.string.course_odd_suffix)
            "2" -> getString(R.string.course_even_suffix)
            else -> ""
        }
        val week = ci.course.weekRange?.takeIf { it.isNotBlank() }
        row(R.id.tvCName, ci.course.name?.takeIf { it.isNotBlank() } ?: "—")
        row(R.id.tvCRoom, ci.course.room?.takeIf { it.isNotBlank() } ?: "—")
        row(R.id.tvCTeacher, ci.course.teacher?.takeIf { it.isNotBlank() } ?: "—")
        row(R.id.tvCWeek, week?.let { getString(R.string.course_week_value, it, odd) } ?: "—")
        row(
            R.id.tvCPeriod,
            if (ci.periodStart == ci.periodEnd) {
                getString(R.string.course_period_single, ci.periodStart)
            } else {
                getString(R.string.course_period_range, ci.periodStart, ci.periodEnd)
            }
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.course_detail_title)
            .setView(v)
            .setPositiveButton(R.string.common_close, null)
            .show()
    }

    /** 点击自建课 → 编辑弹层（学校课仍是详情弹窗） */
    private fun openLocalEditor(id: String) {
        LocalCourseEditSheet.newEdit(id).show(parentFragmentManager, LocalCourseEditSheet.TAG)
    }

    // ==================== 网格视图 ====================

    /**
     * 时间列格子：节次号 + 起止时间（时间文案只有 PeriodClock 一个来源）。
     * `includeFontPadding` 必须关掉：TextView 默认的字体留白按行叠加，三行文字会撑出
     * 56dp 行高，窄屏或系统字号放大时每行最后那条时间被分割线裁掉一半。
     */
    private fun buildPeriodCell(
        ctx: android.content.Context,
        period: Int,
        rowH: Int,
        timeW: Int,
        s: ScheduleStyle
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(timeW, rowH)
        addView(TextView(ctx).apply {
            text = period.toString()
            setTextAppearance(R.style.TextAppearance_Lanhang_TitleSmall)
            setTextColor(s.onBackground)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        })
        PeriodClock.rangeText(period, period).split("-").forEach { t ->
            addView(TextView(ctx).apply {
                text = t
                setTextAppearance(R.style.TextAppearance_Lanhang_LabelSmall)
                setTextColor(s.onBackgroundVariant)
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
            })
        }
    }

    /** 行高 = max(56dp, 时间列格子实测内容高 + 6dp)：字号放大时表也跟着变高，而不是裁字 */
    private fun timeRowHeight(ctx: android.content.Context, timeW: Int, s: ScheduleStyle): Int {
        val probe = buildPeriodCell(ctx, 1, 0, timeW, s)
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(timeW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return maxOf(dp(56), probe.measuredHeight + dp(6))
    }

    /**
     * 网格视图一页：固定表头（周几 + 日期）+ 可滚动的节次表格。
     *
     * 表头刻意留在滚动区之外：日期行始终可见，只有表格滚动。
     * 宽度由调用方传入，表头与表格共用同一套列坐标（[xOfCol]），才能像素级对齐。
     */
    private fun buildGridPage(
        ctx: Context,
        week: Int,
        contentW: Int
    ): ScheduleWeekPager.Page {
        val headerHost = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dimen(R.dimen.space_xs), 0, 0)
        }
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = NestedScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, dimen(R.dimen.content_padding_bottom))
        }
        scroll.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerHost)
            addView(scroll)
        }
        // 手势外壳（[ScheduleSwipeLayout]）永不平移，真正跟着手指走的是
        // headerCols（表头 7 天列）和 cols（表格 7 天列）—— 左轴钉住
        val shell = ScheduleSwipeLayout(ctx).apply {
            addView(
                root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val data = dataForWeek(week)
        if (data == null) return ScheduleWeekPager.Page(week, shell, scroll, emptyList())

        val s = style
        val today = LocalDate.now()
        val dayNames = resources.getStringArray(R.array.schedule_day_names)
        val monday = today.with(DayOfWeek.MONDAY)
        val weekMonday = monday.plusWeeks((week - actualCurrentWeek).toLong())

        // 每天：收集并合并连续节次的同一课程（跨节大块）
        val dayCourses = Array(7) { d -> mergeCourses(getCoursesForDay(data, d, week)) }
        // 行数恒等于作息表节数（12 节，末节 21:45-22:30）：网格高度不随周次跳变，
        // 晚休带也稳定出现；空周就是一张空表 + 居中提示，比只有一行字更像日历。
        // 绝不按数据里的最大节次扩展 —— 服务端偶发 0/12 脏数据（已在 getCoursesForDay 跳过），
        // 一旦按它扩行，时间列就没有第 12 节的时间，会画出「有行无时间」的幽灵行。
        val periodCount = PeriodClock.count
        val hasCourse = dayCourses.any { it.isNotEmpty() }

        // ===== 尺寸：参考设计是无缝表格，格子之间只有 1dp 线，不再有 4/5dp 空隙 =====
        val timeW = dp(46)
        // 行高自适应：时间列「节次号 + 两行起止时间」的高度随系统字号缩放变化，
        // 固定 56dp 在窄屏/大字号下会把每行最后那条时间裁掉一半（1080×2520 实测溢出 ~2.5dp）
        val rowH = timeRowHeight(ctx, timeW, s)
        val headerH = dp(48)
        val bandH = dp(24)
        val lineW = dp(1)
        /** 课程块四周留白：不要贴死表格线，否则相邻两门课看起来连成一片 */
        val blockInsetX = dp(2)
        val blockInsetY = dp(1)
        val gridW = contentW - timeW
        /** 第 d 列左边界：d=0 是时间列右边界，d=7 是表格右边界 */
        fun xOfCol(d: Int): Int = timeW + (gridW * d / 7f).roundToInt()
        fun colWidth(d: Int): Int = xOfCol(d + 1) - xOfCol(d)
        /** 列区内部坐标（滑动层的本地坐标，原点在时间列右边界） */
        fun xInCols(d: Int): Int = xOfCol(d) - timeW
        val breaksAfter = mapOf(
            4 to getString(R.string.schedule_break_noon),
            8 to getString(R.string.schedule_break_evening)
        )
        fun breaksBefore(p: Int): Int = breaksAfter.keys.count { it < p }
        fun yTopOfRow(p: Int): Int = (p - 1) * rowH + breaksBefore(p) * bandH
        val gridH = yTopOfRow(periodCount) + rowH

        // ===== 表头：纯文字两行（周几 + 日期），今天整列用强调色，不再有底色卡片 =====
        // 视口固定不动并裁剪，内部的 [headerCols] 才是滑动层 —— 切周时左轴（节次/时间）
        // 钉住，只有 7 天列跟着手指走，对齐 miuix 版课表的滑动逻辑
        val headerCols = FrameLayout(ctx)
        val headerViewport = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(gridW, headerH).apply { leftMargin = timeW }
            clipChildren = true
            addView(headerCols, FrameLayout.LayoutParams(gridW, headerH))
        }
        for (d in 0 until 7) {
            val date = weekMonday.plusDays(d.toLong())
            val isToday = date == today
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(colWidth(d), headerH).apply {
                    leftMargin = xInCols(d)
                }
            }
            cell.addView(TextView(ctx).apply {
                text = dayNames[d]
                setTextAppearance(R.style.TextAppearance_Lanhang_LabelMedium)
                setTextColor(if (isToday) s.accent else s.onBackground)
                gravity = Gravity.CENTER
                maxLines = 1
            })
            cell.addView(TextView(ctx).apply {
                text = String.format("%d/%d", date.monthValue, date.dayOfMonth)
                setTextAppearance(R.style.TextAppearance_Lanhang_LabelSmall)
                setTextColor(if (isToday) s.accent else s.onBackgroundVariant)
                gravity = Gravity.CENTER
                maxLines = 1
            })
            headerCols.addView(cell)
        }
        headerHost.addView(headerViewport)

        // ===== 表格主体：时间列 + 网格线 + 休息带 + 课程块，统一绝对定位 =====
        // 两层结构（2026-10-30）：
        //  - [axisLayer] 左侧时间列，**钉住不动**：每周的节次/时间完全一样，跟着滑出去再滑回来
        //    看着就像页面坏了；
        //  - [cols] 7 天列（竖线 / 今天底色 / 课程块），装在裁剪视口里**跟着手指滑**。
        //    视口必须裁剪，否则列区左移时会盖到时间列上。
        // 两层各自保留原来的叠放顺序（线 → 色带 → 课程块），配色才对得上。
        val body = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, gridH
            )
        }
        val axisLayer = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(timeW, gridH)
        }
        val cols = FrameLayout(ctx)
        val colsViewport = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(gridW, gridH).apply { leftMargin = timeW }
            clipChildren = true
            addView(cols, FrameLayout.LayoutParams(gridW, gridH))
        }
        body.addView(axisLayer)
        body.addView(colsViewport)

        // 左侧节次列：只有节次号 + 起止时间，行线不穿过它
        for (p in 1..periodCount) {
            val cell = buildPeriodCell(ctx, p, rowH, timeW, s)
            (cell.layoutParams as FrameLayout.LayoutParams).topMargin = yTopOfRow(p)
            axisLayer.addView(cell)
        }

        // 今天整列淡底：只铺课程区，表头保持纯文字（周几本身已经是强调色）。
        // 加在横线/竖线之前 → 线条仍然压在底色上，浓度与其它列一致
        val todayCol = (0 until 7)
            .firstOrNull { weekMonday.plusDays(it.toLong()) == today }
        todayCol?.let { d ->
            cols.addView(
                View(ctx).apply { setBackgroundColor(s.todayTint) },
                FrameLayout.LayoutParams(colWidth(d), gridH).apply { leftMargin = xInCols(d) }
            )
        }

        // 横线：表头下方一条 + 每节课底边一条（休息带所在行跳过，由色带自己形成分隔）。
        // 时间列那段单独铺一份在固定层里 —— 左轴不跟手滑，但行线必须一直横穿到底
        for (p in 0..periodCount) {
            if (p > 0 && breaksAfter.containsKey(p)) continue
            val y = (if (p == 0) 0 else yTopOfRow(p) + rowH).coerceAtMost(gridH - lineW)
            axisLayer.addView(
                View(ctx).apply { setBackgroundColor(s.divider) },
                FrameLayout.LayoutParams(timeW, lineW).apply { topMargin = y }
            )
            cols.addView(
                View(ctx).apply { setBackgroundColor(s.divider) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, lineW).apply {
                    topMargin = y
                }
            )
        }

        // 竖线：时间列右边界（钉在固定层，它是左轴的边界）+ 6 条列分隔
        //（最右列贴着屏幕边缘，不再单独画）
        axisLayer.addView(
            View(ctx).apply { setBackgroundColor(s.divider) },
            FrameLayout.LayoutParams(lineW, gridH).apply { leftMargin = timeW - lineW }
        )
        for (d in 1 until 7) {
            cols.addView(
                View(ctx).apply { setBackgroundColor(s.divider) },
                FrameLayout.LayoutParams(lineW, gridH).apply {
                    leftMargin = (xInCols(d) - lineW / 2).coerceAtLeast(0)
                }
            )
        }

        // 午休 / 晚休：通栏色带（横跨时间列），文字居中并明显淡化。
        // 放在网格线之后添加 → 正好盖住带内的竖线，得到参考图那种"整条断开"的效果
        for ((k, label) in breaksAfter) {
            if (k >= periodCount) continue
            val y = yTopOfRow(k) + rowH
            axisLayer.addView(
                View(ctx).apply { setBackgroundColor(s.bandFill) },
                FrameLayout.LayoutParams(timeW, bandH).apply { topMargin = y }
            )
            cols.addView(
                View(ctx).apply { setBackgroundColor(s.bandFill) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bandH).apply {
                    topMargin = y
                }
            )
            cols.addView(
                TextView(ctx).apply {
                    text = label
                    setTextAppearance(R.style.TextAppearance_Lanhang_LabelSmall)
                    setTextColor(ScheduleStyle.withAlpha(s.onBackgroundVariant, 0.55f))
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bandH).apply {
                    topMargin = y
                }
            )
        }

        // 课程块：M3 tonal 卡，按格子内缩 1dp 让出网格线。
        // 同一天里节次区间**相交的不同课程**（同名同教室的连堂已在 mergeCourses 合并）视为冲突：
        // 给它们套一圈主题色描边，点击在一组冲突课之间翻转查看（再点翻回），长按看详情。
        for (d0 in 0 until 7) {
            conflictGroups(dayCourses[d0]).forEach { group ->
                val unionStart = group.minOf { it.periodStart }
                val unionEnd = group.maxOf { it.periodEnd }
                if (group.size == 1) {
                    val ci = group[0]
                    val block = buildCourseBlock(ctx, ci, s, highlighted = false, isLocal = ci.isLocal)
                    val lp = FrameLayout.LayoutParams(
                        colWidth(d0) - 2 * blockInsetX,
                        (ci.periodEnd - ci.periodStart + 1) * rowH - 2 * blockInsetY
                    )
                    lp.leftMargin = xInCols(d0) + blockInsetX
                    lp.topMargin = yTopOfRow(ci.periodStart) + blockInsetY
                    block.setOnClickListener {
                        // 自建课点一下就进编辑；学校课仍看详情
                        val id = ci.localId
                        if (id != null) openLocalEditor(id) else showCourseDetail(ci)
                    }
                    cols.addView(block, lp)
                    return@forEach
                }
                // 冲突组：容器覆盖整组外接矩形，组内卡片叠在同一处，只显示当前那张。
                // 外接矩形按 yTopOfRow 算，跨休息带时会自动把带子高度算进去。
                val containerTop = yTopOfRow(unionStart)
                val container = FrameLayout(ctx)
                val cards = group.map { ci ->
                    buildCourseBlock(ctx, ci, s, highlighted = true, isLocal = ci.isLocal).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            colWidth(d0) - 2 * blockInsetX,
                            (ci.periodEnd - ci.periodStart + 1) * rowH - 2 * blockInsetY
                        ).apply {
                            leftMargin = blockInsetX
                            topMargin = yTopOfRow(ci.periodStart) - containerTop + blockInsetY
                        }
                        visibility = View.INVISIBLE
                    }
                }
                cards.forEach { container.addView(it) }
                var active = 0
                cards[active].visibility = View.VISIBLE
                // 相机拉远：翻转更像「翻卡片」，而不是近距透视的拉伸
                container.cameraDistance = dp(8000).toFloat()
                var flipping = false
                container.setOnClickListener {
                    if (flipping) return@setOnClickListener
                    flipping = true
                    container.animate().rotationY(90f).setDuration(110).withEndAction {
                        cards[active].visibility = View.INVISIBLE
                        active = (active + 1) % cards.size
                        cards[active].visibility = View.VISIBLE
                        container.rotationY = -90f
                        container.animate().rotationY(0f).setDuration(110).withEndAction {
                            flipping = false
                        }.start()
                    }.start()
                }
                container.setOnLongClickListener {
                    val shown = group[active]
                    val id = shown.localId
                    if (id != null) openLocalEditor(id) else showCourseDetail(shown)
                    true
                }
                cols.addView(
                    container,
                    FrameLayout.LayoutParams(
                        colWidth(d0),
                        yTopOfRow(unionEnd) + rowH - containerTop
                    ).apply {
                        leftMargin = xInCols(d0)
                        topMargin = containerTop
                    }
                )
            }
        }

        // 长按空白格 → 新增自建课（对齐参考项目：入口是长按格子，不是 FAB）。
        // 卡片自己消费了点击，所以只有空白区域会走到这里；时间列和休息带不算格子。
        var downX = 0f
        var downY = 0f
        body.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
            }
            false
        }
        body.setOnLongClickListener {
            if (downX < timeW) return@setOnLongClickListener false
            val day = ((downX - timeW) / (gridW / 7f)).toInt()
            if (day !in 0..6) return@setOnLongClickListener false
            var period = -1
            for (p in 1..periodCount) {
                if (downY >= yTopOfRow(p) && downY < yTopOfRow(p) + rowH) {
                    period = p
                    break
                }
            }
            if (period < 0) return@setOnLongClickListener false
            LocalCourseEditSheet.newAdd(day, period, week)
                .show(parentFragmentManager, LocalCourseEditSheet.TAG)
            true
        }

        // 空周：表格照常画，只是没有课程块，居中给一句提示
        if (!hasCourse) {
            cols.addView(
                TextView(ctx).apply {
                    text = getString(R.string.schedule_week_no_course)
                    setTextAppearance(R.style.TextAppearance_Lanhang_BodyMedium)
                    setTextColor(s.onBackgroundVariant)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
        container.addView(body)
        return ScheduleWeekPager.Page(week, shell, scroll, listOf(headerCols, cols))
    }

    /**
     * 课程卡片（课表网格与冲突组共用）。
     *
     * [highlighted] = true 时描一圈主题色：标记「这里有两门课重叠」，
     * 点一下就能翻转看到另一门。
     */
    private fun buildCourseBlock(
        ctx: Context,
        ci: CourseItem,
        s: ScheduleStyle,
        highlighted: Boolean,
        isLocal: Boolean = false
    ): MaterialCardView {
        val tone = s.courseTone(ci.course.name ?: "?")
        val blockText = tone.onContainer
        // 描边优先级：冲突（主题色 2dp）> 自建（中性 1dp）> 普通（无）
        return MaterialCardView(ctx).apply {
            radius = dimen(R.dimen.corner_sm).toFloat()
            cardElevation = 0f
            strokeWidth = when {
                highlighted -> dp(2)
                isLocal -> dp(1)
                else -> 0
            }
            when {
                highlighted -> strokeColor = s.accent
                isLocal -> strokeColor = s.onBackgroundVariant
            }
            setCardBackgroundColor(tone.container)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // 横向留白收紧到 2dp：窄屏一列只有 ~45dp，4dp 让长课名多折一行
                setPadding(
                    dimen(R.dimen.space_xxs),
                    dimen(R.dimen.space_xs),
                    dimen(R.dimen.space_xxs),
                    dimen(R.dimen.space_xs)
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                addView(TextView(ctx).apply {
                    text = ci.course.name ?: ""
                    setTextAppearance(R.style.TextAppearance_Lanhang_LabelSmall)
                    setTextColor(blockText)
                    gravity = Gravity.CENTER
                    maxLines = 6
                    includeFontPadding = false
                    setLineSpacing(0f, 0.95f)
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                    // 课名按块的可用宽高在 9~11sp 内自适应：列窄或块矮时缩字号，
                    // 而不是把「单片机技术（美）」这类长课名裁掉
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this, 9, 11, 1, TypedValue.COMPLEX_UNIT_SP
                    )
                })
                val room = ci.course.room ?: ""
                // 自建课在教室那一行加「自建 · 」前缀：窄格里也能一眼分辨来源
                val roomText = when {
                    !isLocal -> room
                    room.isEmpty() -> ctx.getString(R.string.local_course_tag)
                    else -> "${ctx.getString(R.string.local_course_tag)} · $room"
                }
                if (roomText.isNotEmpty()) {
                    addView(TextView(ctx).apply {
                        text = roomText
                        setTextAppearance(R.style.TextAppearance_Lanhang_LabelSmall)
                        setTextColor(blockText)
                        alpha = 0.82f
                        gravity = Gravity.CENTER
                        maxLines = 2
                        includeFontPadding = false
                        setLineSpacing(0f, 0.95f)
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                            this, 8, 11, 1, TypedValue.COMPLEX_UNIT_SP
                        )
                    })
                }
            })
        }
    }

    /**
     * 把一天的课程按「节次区间是否相交」归组。
     *
     * - 同名同教室的连堂已在 [mergeCourses] 合并，剩下的相交只可能是**不同课程**；
     * - 首尾相接（A 末节 + 1 == B 首节）不算冲突 —— 视觉上本来就是上下两格；
     * - 返回的子表按开始节次升序；单节课自成一组，调用方据此决定画不画描边、能不能翻转。
     */
    private fun conflictGroups(items: List<CourseItem>): List<List<CourseItem>> {
        if (items.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<CourseItem>>()
        for (item in items.sortedBy { it.periodStart }) {
            val current = groups.lastOrNull()
            if (current != null && item.periodStart <= current.maxOf { it.periodEnd }) {
                current.add(item)
            } else {
                groups.add(mutableListOf(item))
            }
        }
        return groups
    }

    // ==================== 数据处理 ====================

    private fun getCoursesForDay(data: ScheduleData, dayIdx: Int, week: Int): List<CourseItem> {
        val dayNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val targetName = dayNames.getOrNull(dayIdx) ?: return emptyList()
        // 不能因为学校这天没课就提前返回 —— 自建层可能还有课
        val day = data.schedule?.find { it.dayName == targetName }

        val result = mutableListOf<CourseItem>()
        day?.periods.orEmpty().forEach { period ->
            val periodNum = period.periodName?.trim()?.toIntOrNull() ?: return@forEach
            // 作息表 12 节（第 12 节 21:45-22:30）；服务端偶尔下发 0 或越界节次，直接跳过。
            // 与 DayCourses.of 同一口径（小部件/提醒走那边），否则网格会多画一节、
            // 时间列却没有该节时间，表现为「第 12 节没有显示时间」。
            if (periodNum !in 1..PeriodClock.count) return@forEach
            period.courses?.forEach { course ->
                if (course.isWeekValid(week) && course.isOddEvenValid(week)) {
                    result.add(CourseItem(course, periodNum, periodNum))
                }
            }
        }
        // 自建层：只追加，不改学校数据。学期从课表数据里取（按 termId 隔离，换学期不串）
        LocalCourseStore.forDay(data.termId.orEmpty(), dayIdx, week).forEach { lc ->
            val start = lc.startPeriod.coerceIn(1, PeriodClock.count)
            val end = lc.endPeriod.coerceIn(start, PeriodClock.count)
            result.add(CourseItem(lc.toCourse(), start, end, isLocal = true, localId = lc.id))
        }
        return result.sortedBy { it.periodStart }
    }

    private fun mergeCourses(courses: List<CourseItem>): List<CourseItem> {
        if (courses.size <= 1) return courses
        val result = mutableListOf<CourseItem>()
        // 同一门课（同名同教室）的多条记录可能是「节次重叠」而不是严格首尾相接：
        // 服务端把连堂课按节次拆开时，偶尔会把同一节重复下发（第 5 节出现两次），
        // 也可能给出 5-6 与 6-7 这种相互重叠的区间。
        // 只认 next.periodStart == cur.periodEnd + 1 会把这些记录各自成块 → 画在同一行上互相压住。
        // 所以这里改成「区间相交或相邻即合并」，取并集 [min(start), max(end)]。
        for (item in courses.sortedBy { it.periodStart }) {
            val last = result.lastOrNull()
            if (last == null) {
                result.add(item)
                continue
            }
            // 来源不同（学校 / 自建）即便同名同教室也不合并：它们是两层，要能分别翻转查看
            val sameCourse = last.course.name == item.course.name &&
                last.course.room == item.course.room &&
                last.isLocal == item.isLocal
            val mergedEnd = maxOf(last.periodEnd, item.periodEnd)
            // 相邻（+1）或重叠（<=）都算连上；但不能跨过午休 / 晚休合成一整块
            val connects = item.periodStart <= last.periodEnd + 1
            val crossesBreak = PeriodClock.breakAfterPeriods.any { it in last.periodStart until mergedEnd }
            if (sameCourse && connects && !crossesBreak) {
                result[result.lastIndex] = last.copy(periodEnd = mergedEnd)
            } else {
                result.add(item)
            }
        }
        return result
    }

    // 课程配色统一走 ScheduleStyle.courseTone（M3 tonal 语义），此处不再有本地色表

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 读 @dimen token 的像素值（代码里的圆角/内边距一律走这里，不直写 dp 数值） */
    private fun dimen(res: Int): Int = resources.getDimensionPixelSize(res)
}
