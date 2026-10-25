package com.lanhang.lite.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.lanhang.lite.R
import com.lanhang.lite.databinding.ActivityMainBinding
import com.lanhang.lite.grades.GradesFragment
import com.lanhang.lite.moments.MomentsFragment
import com.lanhang.lite.net.MiniappApi
import com.lanhang.lite.schedule.ScheduleFragment
import com.lanhang.lite.settings.SettingsFragment
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.miuix.LanhangMiuixTheme
import com.lanhang.lite.ui.miuix.isNightMode
import com.lanhang.lite.ui.shell.LanhangBottomBar
import com.lanhang.lite.ui.shell.LanhangTopBar
import com.lanhang.lite.ui.shell.ShellScroll
import com.lanhang.lite.ui.shell.ShellState
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost
import com.lanhang.lite.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面外壳。
 *
 * 2026-10-24 起视觉层换成 Miuix：顶栏是 `SmallTopAppBar`，底栏是 `NavigationBar`
 * （替代原来的 MaterialToolbar + 液态玻璃底栏）。页面本身仍是 Fragment，
 * 课表那张自绘网格继续复用原有实现，只是外面套了 Miuix 的皮肤。
 *
 * 2026-10-24 二次改版（用户反馈「切页没有过渡动画」）：四个 Tab 从 hide/show 改为
 * **detach/attach + setCustomAnimations**——hide/show 是 Visibility 变化，Fragment 动画
 * 完全不生效；detach/attach 才会走 enter/exit 动画，而且 Fragment 实例与 View 状态
 * 都由 FragmentManager 保存，切回来仍是原来的滚动位置（replace 就做不到）。
 */
class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMainBinding

    /** 顶栏 / 底栏共享状态（Compose 状态，switchTab 与两个外壳组件共同读写） */
    val shell = ShellState()

    private var scheduleFragment: Fragment? = null
    private var gradesFragment: Fragment? = null
    private var momentsFragment: Fragment? = null
    private var settingsFragment: Fragment? = null

    /** 夜间模式变化时让 Compose 外壳重画 */
    private var nightMode by mutableStateOf(false)

    /** 重建（旋转/夜间模式 recreate）时恢复选中 Tab，且不播放入场动画 */
    private var restoring = false

    /** 上一次切页动画里「正在滑出」的旧页（动画结束才 detach） */
    private var slidingOut: Fragment? = null
    /** Tab 横移动画（便于中途切页时取消）与其压暗层 */
    private var swapAnimator: android.animation.ValueAnimator? = null
    /** 切页兜底看门狗：动画超时/被打断时强制复位，绝不停在「半切」状态 */
    private var swapWatchdog: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：Miuix 的顶栏 / 底栏自己处理状态栏与导航栏 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nightMode = isNightMode()
        setupShell()
        // 2026-10-25：切亮/暗色会走 recreate()，个别机型上顶栏区域会残留上一窗口的画面
        // （表现为标题叠影）。首帧之后再强制整窗重绘一次，顶栏/底栏颜色立即跟到新主题。
        binding.root.post { binding.root.invalidate() }

        scheduleFragment = supportFragmentManager.findFragmentByTag(TAG_SCHEDULE)
        gradesFragment = supportFragmentManager.findFragmentByTag(TAG_GRADES)
        momentsFragment = supportFragmentManager.findFragmentByTag(TAG_MOMENTS)
        settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS)
        if (savedInstanceState == null) {
            switchTab(ShellState.TAB_SCHEDULE)
        } else {
            restoring = true
            switchTab(savedInstanceState.getInt(KEY_TAB, ShellState.TAB_SCHEDULE))
            restoring = false
        }
        handleWidgetIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, shell.tab)
    }

    private fun setupShell() {
        binding.shellTopBar.setContent {
            LanhangMiuixTheme(darkTheme = nightMode) {
                LanhangTopBar(
                        state = shell,
                        // 2026-10-25 用户要求：课表页的「课表」也要和「工作圈」一样是大标题
                        large = shell.backAction == null,
                    )
            }
        }
        // 全屏 overlay：只负责渲染 Miuix OverlayDialog（评论 / 发表 / 我的评论）。
        binding.shellOverlay.setContent {
            LanhangMiuixTheme(darkTheme = nightMode) {
                MiuixPopupHost()
            }
        }
        binding.shellBottomNav.setContent {
            LanhangMiuixTheme(darkTheme = nightMode) {
                LanhangBottomBar(
                    selectedIndex = shell.tab,
                    onSelect = { switchTab(it) },
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 主题（亮/暗）切换后让 Compose 外壳跟上；Fragment 里的 Compose 内容自己读 Configuration
        nightMode = isNightMode()
    }

    /** 课表 Tab 的标题是「课表」——原来回落到 app_name，顶栏写着「蓝航Lite」（用户 2026-10-24 指出） */
    private fun titleForTab(tab: Int): String = when (tab) {
        ShellState.TAB_MOMENTS -> getString(R.string.tab_moments)
        ShellState.TAB_SETTINGS -> getString(R.string.tab_settings)
        ShellState.TAB_GRADES -> getString(R.string.tab_grades)
        else -> getString(R.string.tab_schedule)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false) == true) {
            switchTab(ShellState.TAB_SCHEDULE)
        }
    }

    fun switchTab(tab: Int) {
        val from = shell.tab
        val fm0 = supportFragmentManager
        // 同一个 Tab 再点一次：不切页、不播动画，只把该页退回主层级
        // （用户 2026-10-24：本来就在设置页又点一下设置，不该有切换动效）
        // 注意：**首次挂载**时 shell.tab 的默认值就等于 TAB_SCHEDULE，此时容器里还没有任何页面，
        // 必须继续往下走正常流程，否则冷启动会停在空白页（2026-10-24 修）
        if (from == tab && currentAttachedFragment(fm0) != null) {
            applyTitle(tab)
            if (tab == ShellState.TAB_SETTINGS) (settingsFragment as? SettingsFragment)?.syncToolbar()
            if (tab == ShellState.TAB_MOMENTS) (momentsFragment as? MomentsFragment)?.onTabSelected()
            return
        }
        // 上一次切页动画可能还没收尾，先收干净，避免容器里同时挂三个页面
        settleSlidingOut()
        resetAllPageViews()

        shell.tab = tab
        // 换页时顶栏回到大标题形态（新页面自己的列表滚动会重新上报）
        ShellScroll.reset()
        val fm = supportFragmentManager
        val outgoing = currentAttachedFragment(fm)
        val animating = !restoring && outgoing?.view != null
        val targetTag = tagFor(tab)
        val tx = fm.beginTransaction()
        val existing = fm.findFragmentByTag(targetTag)
        val target: Fragment
        if (existing == null) {
            target = newFragment(tab)
            tx.add(R.id.fragmentContainer, target, targetTag)
        } else {
            target = existing
            if (existing.isDetached) tx.attach(existing)
        }
        cache(target, targetTag)
        for (tag in ALL_TAGS) {
            if (tag == targetTag) continue
            val f = fm.findFragmentByTag(tag) ?: continue
            // 动画期间保留「即将滑出的旧页」，动画结束再 detach——否则只有新页在动，很生硬
            if (animating && f === outgoing) continue
            if (f.isAdded && !f.isDetached) tx.detach(f)
        }
        // 顶栏标题跟着页面**同方向**滑入：否则点下去标题瞬间变、页面还在滑，观感就是「闪一下」
        applyTitle(tab, slideDir = if (animating) (if (tab > from) 1 else -1) else 0)
        tx.commitNow()
        if (animating) startPagerSwap(outgoing!!, target, forward = tab > from)
        // 设置页可能停在二级页（如主题切换 recreate 后），切回时把顶栏对齐到其内部状态。
        if (tab == ShellState.TAB_SETTINGS) {
            (settingsFragment as? SettingsFragment)?.syncToolbar()
        }
        if (tab == ShellState.TAB_MOMENTS) {
            (momentsFragment as? MomentsFragment)?.onTabSelected()
        }
        // 课表：detach/attach 复用视图，回来时清掉换周/切视图动画的残留并重画，
        // 否则会停在「一片空白」（用户 2026-10-25 反馈）
        if (tab == ShellState.TAB_SCHEDULE) {
            (scheduleFragment as? ScheduleFragment)?.onTabSelected()
        }
    }

    /**
     * Tab 之间的过渡 = Miuix `NavTransitions.MiuixDefault` 的几何 + 弹簧（2026-10-24 深夜八次改版）：
     *  · 新页从侧边**整幅**推入（translationX: +width → 0）
     *  · 旧页视差让位（-0.25×width）并淡到 0.9（alpha 1 → 0.9）
     *  · 旧页上方压一层 dim scrim（新页不做圆角裁剪，见下方说明）
     *  · 进度用临界阻尼弹簧（dampingRatio=1 / stiffness=146 → ω=√146，≈ Miuix NavDriverSpec），560ms
     *
     * 为什么改掉上一版的 320ms + `PathInterpolator(0.2,0,0,1)`：那条曲线前段极快，
     * 观感是「一下闪过去再干等」，用户判定「快速闪动」。另外这里**必须等新页画出第一帧**再起动画，
     * 否则滑进来的前几帧是空白（Compose 页面首帧晚于 fragment attach）。
     * 仍然**不能**用 `FragmentTransaction.setCustomAnimations`（detach/attach + ComposeView 会 100% 崩）。
     */
    private fun startPagerSwap(outgoing: Fragment, target: Fragment, forward: Boolean) {
        val container = findViewById<android.view.ViewGroup>(R.id.fragmentContainer)
        val inView = target.view
        if (container == null || inView == null || container.width <= 0) {
            finishSlidingOut(outgoing)
            return
        }
        slidingOut = outgoing
        val w = container.width.toFloat()
        val dir = if (forward) 1f else -1f
        // 先把新页摆到屏幕外：必须在它第一帧绘制**之前**完成，否则会闪一下。
        // 注意：Tab 横移**不加圆角裁剪**（用户 2026-10-24：tab 切换出现圆角不对），
        // 圆角只属于「二级页推入」（Miuix 的 corner clip，见 SettingsScreen）。
        inView.bringToFront()
        inView.translationX = w * dir
        var started = false
        val begin = {
            if (!started) {
                started = true
                runSwap(outgoing, inView, w, dir)
            }
        }
        inView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                inView.viewTreeObserver.removeOnPreDrawListener(this)
                begin()
                return true
            }
        })
        inView.invalidate()
        // 兜底：pre-draw 万一不来（视图已被 detach），别让旧页永远留在容器里
        container.postDelayed({ begin() }, 320L)
        // 再兜一层：整段动画超过 SWAP_MS + 1.6s 还没收尾（设备卡顿 / 组合迟迟不来），
        // 直接强制收尾。否则新页会一直停在屏幕外，用户看到的是「点了 tab 没反应」。
        cancelSwapWatchdog()
        val dog = Runnable {
            swapWatchdog = null
            swapAnimator?.cancel()
            swapAnimator = null
            inView.animate().cancel()
            inView.translationX = 0f
            inView.alpha = 1f
            finishSlidingOut(outgoing)
        }
        swapWatchdog = dog
        container.postDelayed(dog, TAB_SWAP_MS + 1600L)
    }

    private fun cancelSwapWatchdog() {
        swapWatchdog?.let { binding.fragmentContainer.removeCallbacks(it) }
        swapWatchdog = null
    }

    /**
     * 把四个页面根 View 复位（位移/透明度/残留压暗层/残留动画全部清掉）。
     * 每次切页开头调用一次：即使上一次动画因为设备卡顿没跑完，也不会把状态带进这一次。
     */
    private fun resetAllPageViews() {
        cancelSwapWatchdog()
        // 顶栏也可能停在上一次「滑入」的半路（alpha=0 等于顶栏消失），一起复位
        binding.shellTopBar.animate().cancel()
        binding.shellTopBar.alpha = 1f
        binding.shellTopBar.translationX = 0f
        swapAnimator?.cancel()
        swapAnimator = null
        ALL_TAGS.forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.view?.let {
                it.animate().cancel()
                it.translationX = 0f
                it.alpha = 1f
            }
        }
    }

    /**
     * 真正跑横移动画：**两页整幅反向平移**，没有淡入淡出 / 压暗 / 视差 / 圆角裁剪。
     *
     * 参照 KernelSU-Style-UI-Kit（`ui/component/bottombar/BottomBar.kt` 的 `MainPagerState.animateToPage`）：
     * 它内部是一个真实的 `HorizontalPager`，点击底栏就
     * `pagerState.animateScrollBy(scrollPixels, tween(100 × 距离 + 100ms, EaseInOut))`；
     * Pager 的语义就是「新旧两页各平移一整个屏宽、首尾相接」，既没有 dim 也没有 alpha 视差。
     * 我们前几版按 Miuix `NavDisplay`（那是**二级页推入**的规范）套了 dim 0.5 + 视差 0.25 +
     * 淡到 0.9 + 560ms 弹簧 —— 在 Tab 这种「平级切换」上就是用户连续两轮说的「快速闪动 / 不自然」。
     * **不要再加回 scrim / 视差 / 透明度**。
     */
    private fun runSwap(outgoing: Fragment, inView: android.view.View, w: Float, dir: Float) {
        if (findViewById<android.view.ViewGroup>(R.id.fragmentContainer) == null) {
            finishSlidingOut(outgoing)
            return
        }
        val outView = outgoing.view
        // 两页必须**不透明**：Compose 页面根没有底色，重叠时会互相透出重影
        val pageBg = androidx.core.content.ContextCompat.getColor(this, R.color.page_bg)
        inView.setBackgroundColor(pageBg)
        outView?.setBackgroundColor(pageBg)
        inView.bringToFront()
        inView.animate().cancel()
        outView?.animate()?.cancel()
        inView.translationX = w * dir
        outView?.translationX = 0f
        swapAnimator?.cancel()
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TAB_SWAP_MS
            interpolator = TAB_INTERPOLATOR
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                inView.translationX = w * dir * (1f - p)
                outView?.translationX = -w * dir * p
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    inView.translationX = 0f
                    outView?.translationX = 0f
                    finishSlidingOut(outgoing)
                }
            })
        }
        swapAnimator = anim
        anim.start()
    }

    /** 动画结束：把「滑出中」的旧页 detach 并把位移复位。 */
    private fun finishSlidingOut(f: Fragment) {
        if (slidingOut === f) slidingOut = null
        resetSwapState()
        f.view?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.alpha = 1f
        }
        if (f.isAdded && !f.isDetached) {
            // 在动画结束回调里用 commitNow 会打断当前帧，这里用异步 commit
            supportFragmentManager.beginTransaction().detach(f).commit()
        }
    }

    /** 切页动画中途又切页：立刻把上一个「滑出中」的页面收尾。 */
    private fun settleSlidingOut() {
        val f = slidingOut ?: return
        slidingOut = null
        resetSwapState()
        // 新页可能停在半路，把它的位移/裁剪复位
        ALL_TAGS.forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.view?.let {
                if (it.translationX != 0f) it.translationX = 0f
            }
        }
        f.view?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.alpha = 1f
        }
        if (f.isAdded && !f.isDetached) {
            supportFragmentManager.beginTransaction().detach(f).commitNow()
        }
    }

    /** 取消横移动画（幂等）。 */
    private fun resetSwapState() {
        cancelSwapWatchdog()
        swapAnimator?.cancel()
        swapAnimator = null
    }

    private fun currentAttachedFragment(fm: androidx.fragment.app.FragmentManager): Fragment? =
        ALL_TAGS.firstNotNullOfOrNull { tag ->
            fm.findFragmentByTag(tag)?.takeIf { it.isAdded && !it.isDetached }
        }

    private fun newFragment(tab: Int): Fragment = when (tab) {
        ShellState.TAB_GRADES -> GradesFragment()
        ShellState.TAB_MOMENTS -> MomentsFragment()
        ShellState.TAB_SETTINGS -> SettingsFragment()
        else -> ScheduleFragment()
    }

    private fun cache(f: Fragment, tag: String) {
        when (tag) {
            TAG_GRADES -> gradesFragment = f
            TAG_MOMENTS -> momentsFragment = f
            TAG_SETTINGS -> settingsFragment = f
            else -> scheduleFragment = f
        }
    }

    private fun tagFor(tab: Int): String = when (tab) {
        ShellState.TAB_GRADES -> TAG_GRADES
        ShellState.TAB_MOMENTS -> TAG_MOMENTS
        ShellState.TAB_SETTINGS -> TAG_SETTINGS
        else -> TAG_SCHEDULE
    }

    private fun applyTitle(tab: Int, slideDir: Int = 0) {
        showToolbarMain(titleForTab(tab), slideDir)
    }

    /**
     * 主 Tab 态：标题 + 无返回箭头。
     * [slideDir] ≠ 0 时顶栏整体按该方向滑入（+1 从右、-1 从左），和切页/推页方向一致。
     */
    fun showToolbarMain(title: String, slideDir: Int = 0) {
        shell.slideFast = true
        shell.slideDir = slideDir
        shell.title = title
        shell.backAction = null
        shell.actionText = null
        shell.onAction = null
    }

    /**
     * 二级页态：返回箭头 + 子页标题 + 可选右上角文字动作
     * （如「发表动态」页的「发表」，对应 Miuix sub page top app bar 的 actions 槽）。
     */
    fun showToolbarSub(
        title: String,
        onBack: () -> Unit,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        slideDir: Int = 0,
    ) {
        // 之前没有返回箭头 = 正在「推入二级页」；否则是「退回上一级」
        val pushing = shell.backAction == null
        shell.slideFast = false
        shell.slideDir = if (slideDir != 0) slideDir else if (pushing) 1 else -1
        shell.title = title
        shell.backAction = onBack
        shell.actionText = actionText
        shell.onAction = if (actionText == null) null else onAction
    }

    /** 设置页「课表工具 → 调试」入口：手动握手并拉一次课表原始数据 */
    fun debugSchedule() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val ok = MiniappApi.handshake(Session.deviceId(this@MainActivity))
                    if (!ok) {
                        return@withContext getString(
                            R.string.debug_handshake_failed,
                            Session.ldapToken.take(20)
                        )
                    }
                    val raw = MiniappApi.getScheduleRaw(null)
                    getString(R.string.debug_handshake_ok, raw.length)
                }
                com.lanhang.lite.ui.miuix.showMiuixInfo(
                    this@MainActivity,
                    getString(R.string.debug_schedule_title),
                    result,
                    getString(R.string.common_ok),
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.debug_failed, e.message ?: e.toString()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 退出登录（原顶栏「更多」菜单里的入口，现由设置页调用） */
    fun logout() {
        Session.clear()
        com.lanhang.lite.net.ApiClient.clearCookies()
        com.lanhang.lite.net.MomentsApi.logout()
        cacheDir.deleteRecursively()
        com.lanhang.lite.widget.ScheduleWidgetUpdater.updateAll(applicationContext)
        startActivity(android.content.Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        /**
         * Tab 横移时长：参照 KernelSU-Style-UI-Kit 的 `100 × 距离 + 100`（相邻 Tab = 200ms），
         * 取 220ms 稍留余量给首帧组合。**不要再回到 320/560ms**——那是用户反馈「闪动/拖沓」的区间。
         */
        const val TAB_SWAP_MS = 220L
        /** Compose 的 `EaseInOut` = cubic-bezier(0.42, 0, 0.58, 1)，与参考项目用的同一条曲线 */
        val TAB_INTERPOLATOR: android.view.animation.Interpolator =
            android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
        private const val TAG_SCHEDULE = "schedule"
        private const val TAG_GRADES = "grades"
        private const val TAG_MOMENTS = "moments"
        private const val TAG_SETTINGS = "settings"
        private const val KEY_TAB = "shell_tab"

        private val ALL_TAGS = arrayOf(TAG_SCHEDULE, TAG_GRADES, TAG_MOMENTS, TAG_SETTINGS)

        const val EXTRA_OPEN_SCHEDULE = "com.lanhang.lite.extra.OPEN_SCHEDULE"
    }
}
