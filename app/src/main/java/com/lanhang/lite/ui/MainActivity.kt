package com.lanhang.lite.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.color.MaterialColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lanhang.lite.R
import com.lanhang.lite.databinding.ActivityMainBinding
import com.lanhang.lite.moments.MomentsFragment
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.navigation.LanhangTab
import com.lanhang.lite.ui.navigation.LiquidGlassBottomBar
import android.content.Context
import com.lanhang.lite.core.LocaleHelper
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    private lateinit var binding: ActivityMainBinding

    private var scheduleFragment: Fragment? = null
    private var gradesFragment: Fragment? = null
    private var momentsFragment: Fragment? = null
    private var settingsFragment: Fragment? = null

    /** 底栏选中态（Compose 状态，switchTab 与底栏共同读写） */
    private val tabState = mutableIntStateOf(TAB_SCHEDULE)

    /** 设置二级页的返回动作；null = 目录/主 Tab 态 */
    private var toolbarBackAction: (() -> Unit)? = null

    /** 二级页态时只留「返回」，隐藏工作圈那组动作 */
    private var toolbarSubMode = false

    /**
     * 顶栏「消息」铃铛的红点。
     *
     * 菜单会在 `invalidateOptionsMenu` 时整个重建（切 Tab、进退二级页都会触发），
     * 旧的 action view 跟着菜单一起销毁，所以每次 `onCreateOptionsMenu` 都重挂一次，
     * 而不是只在首次创建。
     */
    private var unreadBadge: BadgeDrawable? = null

    /** 最近一次算出来的未读数；0 = 不亮红点 */
    private var unreadCount = 0

    /** 上次查未读的时刻，用来节流（onResume / 切 Tab 都可能连续触发） */
    private var unreadCheckedAt = 0L

    /** 系统深色模式：状态栏图标明暗的默认依据（配置变化会重建 Activity，lazy 足够） */
    private val nightMode: Boolean by lazy {
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 课表页顶栏是透明浮层（背景要铺满整屏透上来），所以标题/图标的颜色
     * 交给课表按它自己的背景明暗上报，见 ScheduleFragment.applyChromeStyle。
     */
    private var scheduleChromeColor: Int? = null
    private var scheduleDarkBackground = false

    /** 当前顶栏菜单图标颜色；onCreateOptionsMenu 读它，避免每次重建菜单都重新推导 */
    private var toolbarIconTint: Int? = null

    /** 底栏玻璃的模糊底图：fragmentContainer 在底栏矩形外扩 margin 的区域截图 */
    private val onContentScrolled = android.view.ViewTreeObserver.OnScrollChangedListener {
        binding.bottomNav.invalidate()
    }
    private val onContentLayoutChanged = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        binding.bottomNav.invalidate()
    }
    private var forwardedTouchTarget: android.view.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // 课表页顶栏是透明浮层，内容会从其下方一直画到状态栏；
        // 显式抬高 Z，保证顶栏（标题/菜单图标）永远画在溢出的背景之上。1px 不产生可见阴影。
        binding.toolbar.translationZ = 1f
        binding.toolbar.setNavigationOnClickListener { toolbarBackAction?.invoke() }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, 0, v.paddingRight, bars.bottom)
            val tb = binding.toolbar
            tb.minimumHeight = 0
            tb.setPadding(tb.paddingLeft, bars.top, tb.paddingRight, 0)
            val lp = tb.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            // 高度 = 状态栏 inset + M3 small top app bar 的 64dp（token 见 res/values/dimens.xml）
            lp.height = bars.top + resources.getDimensionPixelSize(R.dimen.top_app_bar_height)
            tb.layoutParams = lp
            insets
        }

        // 液态玻璃底栏：照抄 PocketDiary LiquidGlassBottomBar（Compose + backdrop 库）
        val night = nightMode
        binding.bottomNav.setContent {
            LanhangTheme(darkTheme = night) {
                // 本地矢量图替代 material-icons-extended（vectorResource 是 @Composable，须在 setContent 内）
                val tabs = listOf(
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_calendar),
                        ImageVector.vectorResource(R.drawable.ic_calendar),
                        stringResource(R.string.tab_schedule),
                    ),
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_school),
                        ImageVector.vectorResource(R.drawable.ic_school),
                        stringResource(R.string.tab_grades),
                    ),
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_dynamic_feed),
                        ImageVector.vectorResource(R.drawable.ic_dynamic_feed),
                        stringResource(R.string.tab_moments),
                    ),
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_settings),
                        ImageVector.vectorResource(R.drawable.ic_settings),
                        stringResource(R.string.tab_settings),
                    ),
                )
                // PocketDiary's host pattern: a live LayerBackdrop source and the glass
                // bar share one Compose drawing layer. Draw the visible native fragment
                // directly at its window-relative position instead of passing stale bitmaps.
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop),
                    ) {
                        val source = binding.fragmentContainer
                        val sourceLocation = IntArray(2)
                        val hostLocation = IntArray(2)
                        source.getLocationInWindow(sourceLocation)
                        binding.bottomNav.getLocationInWindow(hostLocation)
                        drawIntoCanvas { canvas ->
                            val nativeCanvas = canvas.nativeCanvas
                            val saveCount = nativeCanvas.save()
                            nativeCanvas.translate(
                                (sourceLocation[0] - hostLocation[0]).toFloat(),
                                (sourceLocation[1] - hostLocation[1]).toFloat(),
                            )
                            source.draw(nativeCanvas)
                            nativeCanvas.restoreToCount(saveCount)
                        }
                    }
                    LiquidGlassBottomBar(
                        tabs = tabs,
                        selectedIndex = tabState.intValue,
                        onNavigate = { tab -> switchTab(tabs.indexOf(tab)) },
                        backdrop = backdrop,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
        binding.bottomNav.setOnTouchListener { _, event -> forwardTouchOutsideBar(event) }

        scheduleFragment = supportFragmentManager.findFragmentByTag(TAG_SCHEDULE)
        gradesFragment = supportFragmentManager.findFragmentByTag(TAG_GRADES)
        momentsFragment = supportFragmentManager.findFragmentByTag(TAG_MOMENTS)
        settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS)
        if (savedInstanceState == null) {
            switchTab(TAB_SCHEDULE)
        } else {
            val restored = when {
                momentsFragment?.isHidden == false -> TAB_MOMENTS
                settingsFragment?.isHidden == false -> TAB_SETTINGS
                gradesFragment?.isHidden == false -> TAB_GRADES
                else -> TAB_SCHEDULE
            }
            tabState.intValue = restored
            showToolbarMain(when (restored) {
                TAB_MOMENTS -> getString(R.string.tab_moments)
                TAB_SETTINGS -> getString(R.string.tab_settings)
                TAB_GRADES -> getString(R.string.tab_grades)
                else -> getString(R.string.tab_schedule)
            })
        }
        handleWidgetIntent(intent)
        // 通知渠道建一次即可（重复调用无副作用）；放在这里而不是 App.onCreate，
        // 是因为它只服务登录后的互动提醒，登录页没必要建
        com.lanhang.lite.moments.MomentNotify.ensureChannel(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false) == true) {
            switchTab(TAB_SCHEDULE)
        }
    }

    private fun switchTab(tab: Int) {
        // 记下切之前是哪个 Tab：底栏对「已选中的 Tab」也会回调，
        // 靠它区分「切过来」和「再点一次」（后者才是回顶的信号）。
        val previousTab = tabState.intValue
        tabState.intValue = tab
        val tx = supportFragmentManager.beginTransaction()
        val s = scheduleFragment ?: com.lanhang.lite.schedule.ScheduleFragment().also {
            scheduleFragment = it
            tx.add(R.id.fragmentContainer, it, TAG_SCHEDULE)
        }
        val g = gradesFragment ?: com.lanhang.lite.grades.GradesFragment().also {
            gradesFragment = it
            tx.add(R.id.fragmentContainer, it, TAG_GRADES)
        }
        val m = momentsFragment ?: MomentsFragment().also {
            momentsFragment = it
            tx.add(R.id.fragmentContainer, it, TAG_MOMENTS)
        }
        val st = settingsFragment ?: com.lanhang.lite.settings.SettingsFragment().also {
            settingsFragment = it
            tx.add(R.id.fragmentContainer, it, TAG_SETTINGS)
        }
        when (tab) {
            TAB_GRADES -> {
                tx.hide(s).hide(m).hide(st).show(g)
                showToolbarMain(getString(R.string.tab_grades))
            }
            TAB_MOMENTS -> {
                tx.hide(s).hide(g).hide(st).show(m)
                showToolbarMain(getString(R.string.tab_moments))
                // 已经在这个 Tab 上再点一次 → 回顶；从别的 Tab 切过来不动滚动位置
                if (previousTab == TAB_MOMENTS) {
                    (m as? MomentsFragment)?.onTabReselect()
                } else {
                    (m as? MomentsFragment)?.onTabSelected()
                }
                // 切到工作圈才看得见铃铛，这时候顺手刷一次未读（节流）
                refreshUnreadBadge()
            }
            TAB_SETTINGS -> {
                tx.hide(s).hide(g).hide(m).show(st)
                showToolbarMain(getString(R.string.tab_settings))
            }
            else -> {
                tx.hide(g).hide(m).hide(st).show(s)
                showToolbarMain(getString(R.string.tab_schedule))
            }
        }
        // Finish show/hide before sampling; otherwise the next frame can still contain
        // the previous fragment and leave that image in the glass backdrop.
        tx.commitNow()
        binding.bottomNav.invalidate()
        // M3 fade through 的「入」阶段（210ms, emphasizedDecelerate）：commitNow 之后对目标页根视图淡入。
        // 不能用 FragmentTransaction.setTransition —— commitNow 下不播放，且会打乱底栏当帧采样。
        val entering = when (tab) {
            TAB_GRADES -> g.view
            TAB_MOMENTS -> m.view
            TAB_SETTINGS -> st.view
            else -> s.view
        }
        listOfNotNull(s.view, g.view, m.view, st.view).forEach {
            it.animate().cancel()
            it.alpha = 1f
        }
        entering?.let { Motion.fadeIn(this, it) }
        // 课表页每次被切回来都回到当前实际周（hide/show 不触发 onResume，只能显式通知）
        if (tab == TAB_SCHEDULE) {
            (scheduleFragment as? com.lanhang.lite.schedule.ScheduleFragment)?.onTabSelected()
        }
        // 设置页可能停在二级页（如主题切换 recreate 后），切回时把 Toolbar 对齐到其内部状态。
        if (tab == TAB_SETTINGS) {
            (settingsFragment as? com.lanhang.lite.settings.SettingsFragment)?.syncToolbar()
        }
    }

    /** 主 Tab 态：标题 + 无返回箭头。 */
    fun showToolbarMain(title: String) {
        toolbarSubMode = false
        toolbarBackAction = null
        binding.toolbar.navigationIcon = null
        binding.toolbar.title = title
        if (tabState.intValue == TAB_SCHEDULE) {
            // 课表页要让自定义背景铺满整屏，所以顶栏必须是透明浮层；
            // 文字色用课表上报的值（自定义背景可能是深是浅），字体/图标仍走 M3 规格
            val foreground = scheduleChromeColor ?: materialColor(MaterialR.attr.colorOnSurface)
            binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.toolbar.setTitleTextColor(foreground)
            toolbarIconTint = foreground
            setStatusBarIconsLight(!scheduleDarkBackground)
        } else {
            applyOpaqueToolbar()
        }
        invalidateOptionsMenu()
    }

    /** 设置二级页态：返回箭头 + 子页标题（对应 PocketDiary 的 DetailScaffold）。 */
    fun showToolbarSub(title: String, onBack: () -> Unit) {
        toolbarSubMode = true
        toolbarBackAction = onBack
        binding.toolbar.navigationIcon =
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_back)
        binding.toolbar.title = title
        applyOpaqueToolbar()
        invalidateOptionsMenu()
    }

    /** 常规页顶栏：M3 surface 底 + onSurface 前景，状态栏图标跟随系统明暗 */
    private fun applyOpaqueToolbar() {
        binding.toolbar.setBackgroundColor(materialColor(MaterialR.attr.colorSurface))
        val onSurface = materialColor(MaterialR.attr.colorOnSurface)
        binding.toolbar.setTitleTextColor(onSurface)
        toolbarIconTint = onSurface
        setStatusBarIconsLight(!nightMode)
    }

    private fun materialColor(attr: Int): Int = MaterialColors.getColor(binding.toolbar, attr)

    /** 状态栏图标明暗：`lightStatusBars=true` 表示用深色图标（配浅色背景），与系统同名属性语义一致 */
    private fun setStatusBarIconsLight(lightStatusBars: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, binding.root)
        if (controller.isAppearanceLightStatusBars != lightStatusBars) {
            controller.isAppearanceLightStatusBars = lightStatusBars
        }
    }

    /**
     * 课表页配色上报：顶栏透明后，标题与菜单图标颜色必须跟着背景明暗走，
     * 否则浅色背景图上会是浅字、深色背景图上会是深字。
     */
    fun onScheduleChromeChanged(onBackground: Int, darkBackground: Boolean) {
        scheduleChromeColor = onBackground
        scheduleDarkBackground = darkBackground
        if (tabState.intValue != TAB_SCHEDULE || toolbarSubMode) return
        binding.toolbar.setTitleTextColor(onBackground)
        toolbarIconTint = onBackground
        setStatusBarIconsLight(!darkBackground)
        invalidateOptionsMenu()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // 「我的动态」「消息」「搜索」只在工作圈 Tab 且主 Tab 态出现（都是工作圈的入口）
        val inMoments = !toolbarSubMode && tabState.intValue == TAB_MOMENTS
        menu.findItem(R.id.action_my_moments)?.isVisible = inMoments
        menu.findItem(R.id.action_moments_messages)?.isVisible = inMoments
        menu.findItem(R.id.action_moments_search)?.isVisible = inMoments
        applyUnreadBadge()
        // 可见性确定之后再挂：铃铛被隐藏时菜单项没有 action view，attach 会落空
        attachUnreadBadge(inMoments)
        return super.onPrepareOptionsMenu(menu)
    }

    // ------------------------------------------------------------------ 消息红点

    /**
     * 把红点挂到铃铛图标上。
     *
     * 三个时序坑（都踩过）：
     * 1. 菜单刚 inflate / 刚改完可见性时 action view 还没建出来，
     *    `BadgeUtils.attachBadgeDrawable(badge, toolbar, menuItemId)` 找不到锚点只打一行
     *    `Trying to attach badge to a null menuItemView` 的**警告**（不抛异常），红点静默不显示；
     * 2. 菜单重建会换掉 action view，旧红点随之消失，因此每次 prepare 都重挂；
     * 3. 铃铛在工作圈之外的 Tab 是被隐藏的，这时根本没有 view 可挂，直接跳过。
     *
     * 所以这里自己拿 `MenuItem.actionView` 当锚点（拿不到就下一帧重试，最多几次），
     * 而不是交给那个「失败也无声」的重载。
     */
    private fun attachUnreadBadge(visible: Boolean) {
        if (!visible) return
        val badge = unreadBadge ?: BadgeDrawable.create(this).also { unreadBadge = it }
        attachBadgeWhenReady(badge, attemptsLeft = BADGE_ATTACH_RETRY)
    }

    private fun attachBadgeWhenReady(badge: BadgeDrawable, attemptsLeft: Int) {
        binding.toolbar.post {
            // post 到执行时状态可能已经变了（比如用户又切走了），再判一次
            if (toolbarSubMode || tabState.intValue != TAB_MOMENTS) {
                applyUnreadBadge()
                return@post
            }
            // 普通图标菜单项的 `MenuItem.actionView` 是 null（那是给自定义 actionLayout 用的），
            // 真正的 view 由菜单 presenter 创建、id 就是菜单项 id，所以按 id 找
            val anchor = binding.toolbar.findViewById<android.view.View>(
                R.id.action_moments_messages
            )
            if (anchor == null) {
                if (attemptsLeft > 0) attachBadgeWhenReady(badge, attemptsLeft - 1)
                return@post
            }
            // BadgeDrawable 是挂在 view 的 overlay 上的，重复 attach 会叠两层，先摘再挂
            runCatching { BadgeUtils.detachBadgeDrawable(badge, anchor) }
            runCatching { BadgeUtils.attachBadgeDrawable(badge, anchor) }
            applyUnreadBadge()
        }
    }

    /**
     * 按当前状态刷新红点的数字与可见性。
     *
     * 只在「工作圈 Tab + 主 Tab 态」显示 —— 铃铛本身也是这时候才出现，
     * 否则红点会孤零零浮在别的页面的顶栏上（菜单项被隐藏了，角标不会跟着消失）。
     */
    private fun applyUnreadBadge() {
        val badge = unreadBadge ?: return
        badge.number = unreadCount
        badge.isVisible = unreadCount > 0 &&
            !toolbarSubMode && tabState.intValue == TAB_MOMENTS
    }

    /**
     * 重新算未读数并更新红点。
     *
     * [force] = false 时节流：[UNREAD_REFRESH_MS] 内不重复请求。`onResume` / 切 Tab 都调它，
     * 不节流会在来回切页时连着打好几次接口。
     * 取不到（网络/会话）时 `MomentUnread.count()` 返回 null，**保持原状**，
     * 免得一次抖动把红点抹掉，看着像消息被自己读完了。
     */
    private fun refreshUnreadBadge(force: Boolean = false) {
        if (Session.userId.isBlank()) {
            unreadCount = 0
            applyUnreadBadge()
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - unreadCheckedAt < UNREAD_REFRESH_MS) return
        unreadCheckedAt = now
        lifecycleScope.launch {
            val count = com.lanhang.lite.moments.MomentUnread.count() ?: return@launch
            if (count == unreadCount) return@launch
            unreadCount = count
            applyUnreadBadge()
        }
    }

    private fun forwardTouchOutsideBar(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val navLoc = IntArray(2)
            binding.bottomNav.getLocationOnScreen(navLoc)
            val density = resources.displayMetrics.density
            val barLeft = navLoc[0] + (16f * density).toInt()
            val barTop = navLoc[1] + binding.bottomNav.height - (64f * density).toInt()
            val barRight = navLoc[0] + binding.bottomNav.width - (16f * density).toInt()
            val barBottom = navLoc[1] + binding.bottomNav.height
            val insideBar = event.rawX >= barLeft && event.rawX <= barRight &&
                event.rawY >= barTop && event.rawY <= barBottom
            if (insideBar) {
                forwardedTouchTarget = null
                return false
            }
            val toolbarLoc = IntArray(2)
            binding.toolbar.getLocationOnScreen(toolbarLoc)
            forwardedTouchTarget = if (
                event.rawX >= toolbarLoc[0] && event.rawX <= toolbarLoc[0] + binding.toolbar.width &&
                event.rawY >= toolbarLoc[1] && event.rawY <= toolbarLoc[1] + binding.toolbar.height
            ) binding.toolbar else binding.fragmentContainer
        }
        val target = forwardedTouchTarget ?: return false
        val targetLoc = IntArray(2)
        val navLoc = IntArray(2)
        target.getLocationOnScreen(targetLoc)
        binding.bottomNav.getLocationOnScreen(navLoc)
        val forwarded = android.view.MotionEvent.obtain(event)
        forwarded.offsetLocation(
            (navLoc[0] - targetLoc[0]).toFloat(),
            (navLoc[1] - targetLoc[1]).toFloat(),
        )
        target.dispatchTouchEvent(forwarded)
        forwarded.recycle()
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
            event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        ) forwardedTouchTarget = null
        return true
    }

    override fun onResume() {
        super.onResume()
        val vto = binding.root.viewTreeObserver
        if (vto.isAlive) {
            vto.addOnScrollChangedListener(onContentScrolled)
            vto.addOnGlobalLayoutListener(onContentLayoutChanged)
        }
        // 工作圈互动的定时检查：每次回到 App 都重排一次（闹钟不长命，链式续期），
        // 顺带保证「登录后 / 从后台回来」都能恢复正常节奏
        com.lanhang.lite.moments.MomentMessageWatcher.arm(this)
        // 顶栏铃铛的未读红点（节流，见 refreshUnreadBadge）
        refreshUnreadBadge()
    }

    override fun onPause() {
        val vto = binding.root.viewTreeObserver
        if (vto.isAlive) {
            vto.removeOnScrollChangedListener(onContentScrolled)
            vto.removeOnGlobalLayoutListener(onContentLayoutChanged)
        }
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // M3 顶栏默认是 surface 底，菜单图标跟随 onSurface（深色模式自动反转）；
        // 课表页顶栏透明，颜色由课表按背景明暗上报，见 onScheduleChromeChanged
        val iconTint = toolbarIconTint ?: materialColor(MaterialR.attr.colorOnSurface)
        // 逐项染色：菜单里已没有 «更多选项» 溢出，图标即按钮本身，必须跟着顶栏前景色走，
        // 否则透明顶栏配深色背景时会看不见（标题与图标要同色）
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.mutate()?.setTint(iconTint)
        }
        // 红点在 onPrepareOptionsMenu 里挂（那里才知道铃铛是否显示）
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_moments_search -> {
            com.lanhang.lite.moments.MomentSearchActivity.start(this)
            true
        }
        R.id.action_my_moments -> {
            com.lanhang.lite.moments.MyMomentsActivity.start(this)
            true
        }
        R.id.action_moments_messages -> {
            // 清掉节流时间戳：从消息中心回来时 onResume 会强制重算一次未读，
            // 否则刚读完的红点还要挂到下个节流周期才消失
            unreadCheckedAt = 0L
            com.lanhang.lite.moments.MomentMessagesActivity.start(this)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val TAG_SCHEDULE = "schedule"
        private const val TAG_GRADES = "grades"
        private const val TAG_MOMENTS = "moments"
        private const val TAG_SETTINGS = "settings"

        private const val TAB_SCHEDULE = 0
        private const val TAB_GRADES = 1
        private const val TAB_MOMENTS = 2
        private const val TAB_SETTINGS = 3

        const val EXTRA_OPEN_SCHEDULE = "com.lanhang.lite.extra.OPEN_SCHEDULE"

        /**
         * 未读红点的刷新节流：45 秒内不重复请求。
         *
         * 一次刷新要打两个接口（`/message/unread` + `/feed/batch` 判相关性），
         * 而 `onResume`、切 Tab 都会来一次，不节流的话来回切页面会连打十几次。
         */
        private const val UNREAD_REFRESH_MS = 45_000L

        /**
         * 红点锚点的重试次数。
         *
         * 菜单项的 action view 要等菜单撑开后才有，`post` 一次通常就够；
         * 留几次余量是给「切 Tab 同时重建菜单」这种一帧内多次改动的场景。
         */
        private const val BADGE_ATTACH_RETRY = 3
    }
}
