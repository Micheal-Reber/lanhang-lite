package com.lanhang.lite.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lanhang.lite.R
import com.lanhang.lite.databinding.ActivityMainBinding
import com.lanhang.lite.moments.MomentsFragment
import com.lanhang.lite.net.MiniappApi
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.navigation.LanhangTab
import com.lanhang.lite.ui.navigation.LiquidGlassBottomBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var scheduleFragment: Fragment? = null
    private var gradesFragment: Fragment? = null
    private var momentsFragment: Fragment? = null
    private var settingsFragment: Fragment? = null

    /** 底栏选中态（Compose 状态，switchTab 与底栏共同读写） */
    private val tabState = mutableIntStateOf(TAB_SCHEDULE)

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

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, 0, v.paddingRight, bars.bottom)
            val tb = binding.toolbar
            tb.minimumHeight = 0
            tb.setPadding(tb.paddingLeft, bars.top, tb.paddingRight, 0)
            val lp = tb.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            lp.height = bars.top + (36 * resources.displayMetrics.density).toInt()
            tb.layoutParams = lp
            insets
        }

        // 液态玻璃底栏：照抄 PocketDiary LiquidGlassBottomBar（Compose + backdrop 库）
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        binding.bottomNav.setContent {
            LanhangTheme(darkTheme = night) {
                // 本地矢量图替代 material-icons-extended（vectorResource 是 @Composable，须在 setContent 内）
                val tabs = listOf(
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_calendar),
                        ImageVector.vectorResource(R.drawable.ic_calendar),
                        "课表",
                    ),
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_school),
                        ImageVector.vectorResource(R.drawable.ic_school),
                        "成绩",
                    ),
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_dynamic_feed),
                        ImageVector.vectorResource(R.drawable.ic_dynamic_feed),
                        "工作圈",
                    ),
                    LanhangTab(
                        ImageVector.vectorResource(R.drawable.ic_settings),
                        ImageVector.vectorResource(R.drawable.ic_settings),
                        "设置",
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
            binding.toolbar.title = when (restored) {
                TAB_MOMENTS -> "工作圈"
                TAB_SETTINGS -> "设置"
                TAB_GRADES -> "成绩"
                else -> getString(R.string.app_name)
            }
        }
        handleWidgetIntent(intent)
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
                binding.toolbar.title = "成绩"
            }
            TAB_MOMENTS -> {
                tx.hide(s).hide(g).hide(st).show(m)
                binding.toolbar.title = "工作圈"
                (m as? MomentsFragment)?.onTabSelected()
            }
            TAB_SETTINGS -> {
                tx.hide(s).hide(g).hide(m).show(st)
                binding.toolbar.title = "设置"
            }
            else -> {
                tx.hide(g).hide(m).hide(st).show(s)
                binding.toolbar.title = getString(R.string.app_name)
            }
        }
        // Finish show/hide before sampling; otherwise the next frame can still contain
        // the previous fragment and leave that image in the glass backdrop.
        tx.commitNow()
        binding.bottomNav.invalidate()
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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_debug_schedule -> { debugSchedule(); true }
        R.id.action_logout -> { logout(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun debugSchedule() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val ok = MiniappApi.handshake(Session.deviceId(this@MainActivity))
                    if (!ok) return@withContext "握手失败：ldapToken=${Session.ldapToken.take(20)}…"
                    val raw = MiniappApi.getScheduleRaw(null)
                    "握手OK，GetSchedule返回${raw.length}字符"
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("课表接口调试")
                    .setMessage(result)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, "调试失败: " + (e.message ?: e.toString()), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logout() {
        Session.clear()
        com.lanhang.lite.net.ApiClient.clearCookies()
        com.lanhang.lite.net.MomentsApi.logout()
        cacheDir.deleteRecursively()
        com.lanhang.lite.widget.ScheduleWidgetUpdater.updateAll(applicationContext)
        startActivity(android.content.Intent(this, LoginActivity::class.java))
        finish()
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
    }
}
