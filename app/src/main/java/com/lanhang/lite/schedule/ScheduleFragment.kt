package com.lanhang.lite.schedule

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.lanhang.lite.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

class ScheduleFragment : Fragment() {

    private lateinit var tvWeekPill: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnCalendar: ImageButton
    private lateinit var btnViewToggle: ImageButton
    private lateinit var contentWeek: View
    private lateinit var contentGrid: View
    private lateinit var dayStrip: LinearLayout
    private lateinit var daySections: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var bgSolidLayer: View
    private lateinit var bgImageLayer: ImageView
    private lateinit var bgVeil: View
    /** 当前配色令牌：无自定义背景时来自主题，有背景时来自背景分析 */
    private lateinit var style: ScheduleStyle
    /** 已应用的背景标识，避免每次 onResume 重复分析/重建视图 */
    private var appliedBackdropKey: String? = null

    private var currentWeek = 1
    private var maxWeek = 20
    private var startWeek = 1
    private var actualCurrentWeek = 1
    private var scheduleData: ScheduleData? = null
    /** 按周缓存的服务端数据（官方 GetSchedule&week=N 每周一份） */
    private val weekData = HashMap<Int, ScheduleData>()
    private var showingGrid = true  // 默认网格视图

    // 课程配色：HSL 黄金角算法（同课同色）
    private val courseColorMap = mutableMapOf<String, Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_schedule, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvWeekPill = view.findViewById(R.id.tvWeekPill)
        btnPrev = view.findViewById(R.id.btnPrevWeek)
        btnNext = view.findViewById(R.id.btnNextWeek)
        btnCalendar = view.findViewById(R.id.btnCalendar)
        btnViewToggle = view.findViewById(R.id.btnViewToggle)
        contentWeek = view.findViewById(R.id.contentWeek)
        contentGrid = view.findViewById(R.id.contentGrid)
        dayStrip = view.findViewById(R.id.dayStrip)
        daySections = view.findViewById(R.id.daySections)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        progress = view.findViewById(R.id.progress)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        bgSolidLayer = view.findViewById(R.id.bgSolidLayer)
        bgImageLayer = view.findViewById(R.id.bgImageLayer)
        bgVeil = view.findViewById(R.id.bgVeil)

        style = ScheduleStyle.fromTheme(requireContext())
        applyChromeStyle()

        btnPrev.setOnClickListener { switchWeek(currentWeek - 1) }
        btnNext.setOnClickListener { switchWeek(currentWeek + 1) }
        btnCalendar.setOnClickListener { showWeekPicker(it) }
        btnViewToggle.setOnClickListener { switchView(!showingGrid) }
        swipeRefresh.setOnRefreshListener { refresh() }
        installWeekSwipe(view.findViewById(R.id.weekScroll))
        installWeekSwipe(view.findViewById(R.id.gridScroll))

        applyBackdrop()
        loadCacheOrRefresh()
    }

    override fun onResume() {
        super.onResume()
        // 从「课表背景」设置返回后，背景/配色可能已变化
        applyBackdrop()
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
        val density = resources.displayMetrics.density
        val iconTint = ColorStateList.valueOf(style.onBackgroundVariant)
        for (button in arrayOf(btnPrev, btnNext, btnViewToggle)) {
            button.background = style.circle(density, style.circleFill)
            button.imageTintList = iconTint
        }
        btnCalendar.background = style.circle(density, style.circleFill)
        btnCalendar.imageTintList = ColorStateList.valueOf(style.accent)
        contentGrid.background = style.rounded(density, 16f, style.panelFill)
        tvEmpty.setTextColor(style.onBackgroundVariant)
        updateWeekPill()
    }

    /**
     * 仅识别明显的横向手势：左滑进入下一周、右滑返回上一周。
     * NestedScrollView 保持处理纵向滚动；识别到横滑后才阻止 SwipeRefreshLayout 拦截。
     */
    private fun installWeekSwipe(target: View) {
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        val minimumDistance = dp(56).toFloat()
        var downX = 0f
        var downY = 0f
        var horizontal = false

        target.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    horizontal = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!horizontal && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        horizontal = true
                        touchedView.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val isWeekSwipe = horizontal &&
                        abs(dx) >= minimumDistance &&
                        abs(dx) > abs(dy) * 1.2f
                    if (isWeekSwipe) {
                        switchWeek(if (dx < 0f) currentWeek + 1 else currentWeek - 1)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    horizontal = false
                    false
                }

                else -> false
            }
        }
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
            progress.visibility = View.VISIBLE
            refresh()
        }
    }

    private fun applyWeekInfo(data: ScheduleData) {
        data.weekInfo?.let { info ->
            info.currentWeek?.toIntOrNull()?.let { actualCurrentWeek = it; currentWeek = it }
            info.endWeek?.toIntOrNull()?.let { maxWeek = it }
            info.startWeek?.toIntOrNull()?.let { startWeek = it }
        }
        updateWeekPill()
    }

    /** 手动下拉刷新：从教务系统重新拉取并覆盖本地缓存；刷新期间保持当前浏览周不变 */
    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val browseWeek = currentWeek
                val first = withContext(Dispatchers.IO) { ScheduleRepository.refresh(null) }
                scheduleData = first.data
                first.data?.let { applyWeekInfo(it) }
                currentWeek = browseWeek
                if (browseWeek == actualCurrentWeek) {
                    first.data?.let { weekData[browseWeek] = it }
                } else {
                    // 浏览的不是当前实际周：再拉一次浏览周数据覆盖该周缓存
                    try {
                        val byWeek = withContext(Dispatchers.IO) { ScheduleRepository.refresh(browseWeek) }
                        byWeek.data?.let { weekData[browseWeek] = it }
                    } catch (_: Exception) {
                    }
                }
                updateWeekPill()
                renderAll()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.schedule_refresh_failed, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
                if (scheduleData == null) tvEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun switchWeek(week: Int) {
        if (week < startWeek || week > maxWeek) return
        currentWeek = week
        updateWeekPill()
        renderAll()
        ensureWeekData(week)
    }

    /** 渲染优先用该周专属数据；无则回退主缓存全量+本地周次过滤 */
    private fun dataForWeek(week: Int): ScheduleData? =
        weekData[week]
            ?: ScheduleRepository.loadCachedWeek(week)?.data?.also { weekData[week] = it }
            ?: scheduleData

    /** 该周无数据时按官方方式 GetSchedule&week=N 补拉 */
    private fun ensureWeekData(week: Int) {
        if (weekData.containsKey(week)) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { ScheduleRepository.refresh(week) }
                resp.data?.let { weekData[week] = it }
                if (currentWeek == week) renderAll()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateWeekPill() {
        tvWeekPill.text = getString(R.string.schedule_week_format, currentWeek)
        val density = resources.displayMetrics.density
        if (currentWeek == actualCurrentWeek) {
            tvWeekPill.background = style.rounded(density, 12f, style.accentFill)
            tvWeekPill.setTextColor(style.onAccentFill)
        } else {
            tvWeekPill.background = style.rounded(density, 12f, Color.TRANSPARENT, 1f, style.outline)
            tvWeekPill.setTextColor(style.onBackgroundVariant)
        }
    }

    private fun showWeekPicker(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        for (w in startWeek..maxWeek) {
            popup.menu.add(0, w, w, getString(R.string.schedule_week_menu_format, w))
        }
        popup.setOnMenuItemClickListener { item ->
            switchWeek(item.itemId)
            true
        }
        popup.show()
    }

    private fun switchView(grid: Boolean) {
        showingGrid = grid
        contentWeek.visibility = if (grid) View.GONE else View.VISIBLE
        contentGrid.visibility = if (grid) View.VISIBLE else View.GONE
        // 右上角按钮显示「点一下会切到哪个视图」：网格态给列表图标，列表态给网格图标
        btnViewToggle.setImageResource(
            if (grid) R.drawable.ic_view_week else R.drawable.ic_view_grid
        )
        // setImageResource 会丢掉之前设的 tint（回到 drawable 自带的黑），必须补一次，
        // 否则切过一次视图后这颗图标就不再跟背景配色走 —— 与左侧图标不一致的根源
        btnViewToggle.imageTintList = ColorStateList.valueOf(style.onBackgroundVariant)
        btnViewToggle.contentDescription = getString(
            if (grid) R.string.schedule_switch_to_week else R.string.schedule_switch_to_grid
        )
        if (grid) renderGrid() else renderWeekView()
    }

    private fun renderAll() {
        if (showingGrid) renderGrid() else renderWeekView()
    }

    // ==================== 周视图 ====================

    private fun renderWeekView() {
        val week = currentWeek
        val data = dataForWeek(week) ?: return
        dayStrip.removeAllViews()
        daySections.removeAllViews()

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

            // 摘要格
            val summary = LayoutInflater.from(context).inflate(R.layout.item_day_summary, dayStrip, false)
            summary.background = s.rounded(density, 12f, if (isToday) s.accentFill else s.chipFill)
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

            val preview = summary.findViewById<LinearLayout>(R.id.coursePreview)
            courses.take(5).forEach { ci ->
                val tv = TextView(context).apply {
                    text = ci.course.name ?: ""
                    textSize = 9f
                    setTextColor(if (isToday) s.onAccentFill else s.onBackgroundVariant)
                    maxLines = 2
                    setPadding(0, 1, 0, 1)
                }
                preview.addView(tv)
            }
            dayStrip.addView(summary)

            // 课程区
            val section = LayoutInflater.from(requireContext()).inflate(R.layout.item_day_section, daySections, false)
            section.background = s.rounded(density, 16f, s.cardFill)
            val header = section.findViewById<TextView>(R.id.tvDayHeader)
            header.text = if (isToday) {
                getString(R.string.schedule_day_today, dayNames[dayIdx])
            } else {
                dayNames[dayIdx]
            }
            header.setTextColor(if (isToday) s.accent else s.onBackground)
            val container = section.findViewById<LinearLayout>(R.id.lessonContainer)

            if (courses.isEmpty()) {
                val tv = TextView(context).apply {
                    text = getString(R.string.schedule_day_no_course, dayNames[dayIdx])
                    textSize = 12f
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

        tvEmpty.setTextColor(s.onBackgroundVariant)
        tvEmpty.visibility = if (totalCourses == 0) View.VISIBLE else View.GONE
    }

    private fun addLessonRow(parent: LinearLayout, ci: CourseItem) {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lesson_row, parent, false)
        val color = courseColor(ci.course.name ?: "?")
        view.background = style.rounded(resources.displayMetrics.density, 12f, color)
        val textColor = style.onCourse(color)

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

    /** 课程详情弹窗（参考官方：标题条 + 标签/值行），标题条用该课程的颜色 */
    private fun showCourseDetail(ci: CourseItem) {
        val v = layoutInflater.inflate(R.layout.dialog_course_detail, null, false)
        val courseBg = courseColor(ci.course.name ?: "?")
        v.findViewById<TextView>(R.id.tvDetailTitle).apply {
            setBackgroundColor(courseBg)
            setTextColor(style.onCourse(courseBg))
        }
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

        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(v)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ==================== 网格视图 ====================

    private fun renderGrid() {
        val data = dataForWeek(currentWeek) ?: return
        val container = requireView().findViewById<LinearLayout>(R.id.gridContent)
        container.removeAllViews()

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val s = style
        val today = LocalDate.now()
        val dayNames = resources.getStringArray(R.array.schedule_day_names)
        val monday = today.with(DayOfWeek.MONDAY)
        val weekMonday = monday.plusWeeks((currentWeek - actualCurrentWeek).toLong())

        // 每天：收集并合并连续节次的同一课程（跨节大块）
        val dayCourses = Array(7) { d -> mergeCourses(getCoursesForDay(data, d, currentWeek)) }
        val maxPeriod = dayCourses.maxOf { list -> list.maxOfOrNull { it.periodEnd } ?: 0 }
        if (maxPeriod == 0) {
            container.addView(TextView(ctx).apply {
                text = getString(R.string.schedule_week_no_course)
                setTextColor(s.onBackgroundVariant)
                gravity = Gravity.CENTER
                setPadding(0, 64, 0, 0)
            })
            return
        }

        val periodTimes = mapOf(
            1 to "08:00-08:45", 2 to "08:55-09:40", 3 to "10:00-10:45", 4 to "10:55-11:40",
            5 to "14:00-14:45", 6 to "14:55-15:40", 7 to "16:00-16:45", 8 to "16:55-17:40",
            9 to "19:00-19:45", 10 to "19:55-20:40", 11 to "20:50-21:35"
        )

        // 尺寸
        val screenW = resources.displayMetrics.widthPixels - dp(32)  // 减去外边距+面板内边距
        val rowH = dp(56)
        val gapH = dp(4)
        val gapW = dp(5)
        val timeW = dp(46)
        val colW = ((screenW - timeW - gapW * (7 + 1)) / 7f).toInt().coerceAtLeast(dp(30))
        val bandH = dp(22)
        val breaksAfter = mapOf(
            4 to getString(R.string.schedule_break_noon),
            8 to getString(R.string.schedule_break_evening)
        )
        fun breaksBefore(p: Int): Int = breaksAfter.keys.count { it < p }
        fun yTopOfRow(p: Int): Int = (p - 1) * (rowH + gapH) + breaksBefore(p) * bandH

        // ===== 表头行：左占位 + 7 天头（小爱风格：无底色，星期+日期） =====
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerRow.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(timeW + gapW, dp(48))
        })
        for (d in 0 until 7) {
            val date = weekMonday.plusDays(d.toLong())
            val isToday = date == today
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(colW, dp(48)).apply { marginEnd = gapW }
                if (isToday) {
                    background = s.rounded(density, 10f, s.accentFill)
                }
            }
            head.addView(TextView(ctx).apply {
                text = dayNames[d]
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isToday) s.onAccentFill else s.onBackground)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            head.addView(TextView(ctx).apply {
                text = String.format("%d/%d", date.monthValue, date.dayOfMonth)
                textSize = 9f
                setTextColor(if (isToday) s.onAccentFill else s.onBackgroundVariant)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            headerRow.addView(head)
        }
        container.addView(headerRow)
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(2)) })

        // ===== 主体：左时间列 + 块区 =====
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 左侧节次列（小爱风格：节次号 + 起止时间，无底色）
        val timeCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(timeW + gapW, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        for (p in 1..maxPeriod) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(timeW, rowH).apply { bottomMargin = gapH }
            }
            cell.addView(TextView(ctx).apply {
                text = "$p"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(s.onBackground)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            val times = (periodTimes[p] ?: "").split("-")
            for (t in times) {
                cell.addView(TextView(ctx).apply {
                    text = t
                    textSize = 8f
                    setTextColor(s.onBackgroundVariant)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                })
            }
            timeCol.addView(cell)
            // 午休/晚休分隔带
            breaksAfter[p]?.let { label ->
                timeCol.addView(TextView(ctx).apply {
                    text = label
                    textSize = 10f
                    setTextColor(s.onBackgroundVariant)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(timeW + gapW, bandH)
                })
            }
        }
        body.addView(timeCol)

        // 课程块区（FrameLayout 绝对定位）
        val gridH = yTopOfRow(maxPeriod) + rowH + gapH
        val blockArea = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(7 * colW + 6 * gapW, gridH)
        }

        // 横向网格线（有休息带的行不画）
        for (p in 1 until maxPeriod) {
            if (breaksAfter.containsKey(p)) continue
            val divider = View(ctx)
            divider.setBackgroundColor(s.divider)
            val dlp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(1))
            dlp.topMargin = yTopOfRow(p) + rowH + gapH / 2
            blockArea.addView(divider, dlp)
        }

        // 纵向网格线
        for (d in 1..6) {
            val divider = View(ctx)
            divider.setBackgroundColor(s.divider)
            val dlp = FrameLayout.LayoutParams(dp(1), FrameLayout.LayoutParams.MATCH_PARENT)
            dlp.leftMargin = d * (colW + gapW) - gapW / 2
            blockArea.addView(divider, dlp)
        }

        // 午休/晚休分隔带
        for ((k, label) in breaksAfter) {
            if (k >= maxPeriod) continue
            val band = TextView(ctx).apply {
                text = label
                textSize = 10f
                setTextColor(s.onBackgroundVariant)
                gravity = Gravity.CENTER
                background = s.rounded(density, 6f, s.bandFill)
            }
            val blp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bandH)
            blp.topMargin = k * (rowH + gapH) + breaksBefore(k) * bandH
            blockArea.addView(band, blp)
        }

        // 课程块
        for (d0 in 0 until 7) {
            dayCourses[d0].forEach { ci ->
                val span = ci.periodEnd - ci.periodStart + 1
                val blockColor = courseColor(ci.course.name ?: "?")
                val blockText = style.onCourse(blockColor)
                val block = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    background = s.rounded(density, 10f, blockColor)
                }
                block.addView(TextView(ctx).apply {
                    text = ci.course.name ?: ""
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(blockText)
                    gravity = Gravity.CENTER
                    maxLines = 6
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                })
                val room = ci.course.room ?: ""
                if (room.isNotEmpty()) {
                    block.addView(TextView(ctx).apply {
                        text = room
                        textSize = 9f
                        setTextColor(blockText)
                        alpha = 0.92f
                        gravity = Gravity.CENTER
                        maxLines = 2
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    })
                }
                val lp = FrameLayout.LayoutParams(colW, span * (rowH + gapH) - gapH)
                lp.leftMargin = d0 * (colW + gapW)
                lp.topMargin = yTopOfRow(ci.periodStart)
                block.setOnClickListener { showCourseDetail(ci) }
                blockArea.addView(block, lp)
            }
        }
        body.addView(blockArea)
        container.addView(body)
    }

    // ==================== 数据处理 ====================

    private fun getCoursesForDay(data: ScheduleData, dayIdx: Int, week: Int): List<CourseItem> {
        val dayNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val targetName = dayNames.getOrNull(dayIdx) ?: return emptyList()
        val day = data.schedule?.find { it.dayName == targetName } ?: return emptyList()

        val result = mutableListOf<CourseItem>()
        day.periods?.forEach { period ->
            val periodNum = period.periodName?.trim()?.toIntOrNull() ?: return@forEach
            period.courses?.forEach { course ->
                if (course.isWeekValid(week) && course.isOddEvenValid(week)) {
                    result.add(CourseItem(course, periodNum, periodNum))
                }
            }
        }
        return result.sortedBy { it.periodStart }
    }

    private fun mergeCourses(courses: List<CourseItem>): List<CourseItem> {
        if (courses.size <= 1) return courses
        val result = mutableListOf<CourseItem>()
        var cur = courses[0]
        for (i in 1 until courses.size) {
            val next = courses[i]
            // 连续节次且不跨越午休/晚休（4→5、8→9）
            if (next.course.name == cur.course.name && next.course.room == cur.course.room &&
                next.periodStart == cur.periodEnd + 1 &&
                cur.periodEnd != 4 && cur.periodEnd != 8
            ) {
                cur = cur.copy(periodEnd = next.periodEnd)
            } else {
                result.add(cur)
                cur = next
            }
        }
        result.add(cur)
        return result
    }

    // ==================== 配色 ====================

    // 黄金角色相分布：同课同色，不同课色相差 ~137°，区分度最大（算法见 ScheduleStyle）
    private fun courseColor(courseName: String): Int {
        courseColorMap[courseName]?.let { return it }
        val color = ScheduleStyle.courseColor(courseName)
        courseColorMap[courseName] = color
        return color
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
