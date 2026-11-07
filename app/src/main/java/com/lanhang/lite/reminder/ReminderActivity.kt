package com.lanhang.lite.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.core.toast
import com.lanhang.lite.databinding.ActivityReminderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 课程提醒设置页（照 Sleepy 的 `ReminderScreen` 逐项对齐）。
 *
 * 六块内容与 Sleepy 一致：总开关、每日提醒（今日摘要 / 明日预告）、每节课前提醒
 * （提前分钟 / 形态 / 流体云）、上课自动勿扰、后台可靠性。
 * 「按日期设置提醒」「需要提醒的课表」依赖节假日与多课表数据，本应用是单课表，按
 * AGENTS.md 的既定取舍省略。
 *
 * 关键约定：
 * - 开关打开前先要通知权限；没权限时**开关不得停在打开状态**，授权回来再由 onResume 自动补开；
 * - 任何偏好改动都走 [ReminderRescheduler.request]（300ms 防抖），不直接全量重排；
 * - 勿扰开关需要「通知策略访问」权限，没授权时跳系统页，回来按系统真实状态落定。
 */
class ReminderActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：每个 Activity 自己包，漏了这页会一直跟系统语言
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityReminderBinding
    private val handler = Handler(Looper.getMainLooper())

    /** 用户点了总开关但还在等权限：授权回来要自动补开 */
    private var pendingMasterEnable = false

    /** 用户点了勿扰开关但还在等「通知策略访问」授权 */
    private var pendingDndEnable = false

    /** 正在按代码改写开关状态，别让监听器把这次改动当成用户操作 */
    private var suppressCallbacks = false

    private var minutesJob: Job? = null

    /** 胶囊主显示内容的取值顺序（与下拉项一一对应） */
    private val primaryValues = listOf(
        ReminderPrefs.FIELD_NAME,
        ReminderPrefs.FIELD_TIME,
        ReminderPrefs.FIELD_ROOM,
        ReminderPrefs.FIELD_COUNTDOWN
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingMasterEnable) {
            pendingMasterEnable = false
            setMaster(granted)
        }
        refreshPermissionHint()
        refreshReliability()
    }

    private val dndLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 用户可能拒绝或秒退 —— 以系统实时授权状态为准
        pendingDndEnable = false
        val granted = policyAccessGranted()
        setDnd(granted)
        refreshDndStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // targetSdk 35 在 Android 15+ 强制 edge-to-edge：不补 inset 标题会压在状态栏下面
        val baseBottom = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0
            )
            binding.scroll.setPadding(
                binding.scroll.paddingLeft,
                binding.scroll.paddingTop,
                binding.scroll.paddingRight,
                baseBottom + bars.bottom
            )
            insets
        }

        ReminderNotify.ensureChannels(this)
        setupMaster()
        setupDaily()
        setupBeforeClass()
        setupDnd()
        setupReliability()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回：授权状态可能已经变了，开关按真实状态落定
        if (pendingMasterEnable && NotifPermission.isGranted(this)) {
            pendingMasterEnable = false
            setMaster(true)
        }
        if (pendingDndEnable) {
            val granted = policyAccessGranted()
            pendingDndEnable = false
            setDnd(granted)
        }
        refreshAll()
        // 预览可能跨周（今天是周日、明天是周一）：先把窗口内缺的按周缓存补上再重画，
        // 否则「明天有 4 次课」会被写成「明天没有课程」。补不到就维持当前渲染。
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ReminderData.ensureWeeksCovered(2) }
            }
            refreshDailyTexts()
            refreshBeforePreview()
        }
        // 状态驱动兜底：从 App 外回来（或改完开关）后再对一眼流体云投放状态
        lifecycleScope.launch {
            runCatching { ReminderScheduler.reconcileFluidCloud() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ==================== 总开关 ====================

    private fun setupMaster() {
        binding.swMaster.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            if (checked && !NotifPermission.isGranted(this)) {
                // 先弹回关态，再引导授权：留一个「开着但永远不会响」的开关比不响应更糟
                setMaster(false)
                pendingMasterEnable = true
                NotifPermission.request(this, permissionLauncher)
                return@setOnCheckedChangeListener
            }
            persistMaster(checked)
        }
    }

    private fun setMaster(enabled: Boolean) {
        suppressCallbacks = true
        binding.swMaster.isChecked = enabled
        suppressCallbacks = false
        persistMaster(enabled)
    }

    private fun persistMaster(enabled: Boolean) {
        ReminderPrefs.setEnabled(this, enabled)
        if (enabled) ReminderRescheduler.request() else ReminderScheduler.restart()
        refreshMasterDependents()
        refreshPermissionHint()
    }

    /**
     * 「提醒权限需要处理」按钮的显隐：通知权限没开，或者精确闹钟没放行，就呼点出来。
     * （通知权限 = 提醒根本不会发；精确闹钟 = 闹钟被系统拖成非精确，课前提醒会延迟甚至不响。）
     */
    private fun refreshPermissionHint() {
        val notifGranted = NotifPermission.isGranted(this)
        val exactAlarmAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true)
        binding.btnReliabilityAttention.visibility =
            if (notifGranted && exactAlarmAllowed) View.GONE else View.VISIBLE
    }

    // ==================== 每日提醒 ====================

    private fun setupDaily() {
        binding.swDaily.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            ReminderPrefs.setDailyEnabled(this, checked)
            ReminderRescheduler.request()
            refreshMasterDependents()
        }
        binding.swToday.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            ReminderPrefs.setTodayEnabled(this, checked)
            ReminderRescheduler.request()
        }
        binding.swTomorrow.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            ReminderPrefs.setTomorrowEnabled(this, checked)
            ReminderRescheduler.request()
        }
        binding.rowTodayTime.setOnClickListener {
            pickTime(ReminderPrefs.dailyTime(this), 7, 0) { hour, minute ->
                ReminderPrefs.setDailyTime(this, clock(hour, minute))
                ReminderRescheduler.request()
                refreshDailyTexts()
            }
        }
        binding.rowTomorrowTime.setOnClickListener {
            pickTime(ReminderPrefs.tomorrowTime(this), 22, 0) { hour, minute ->
                ReminderPrefs.setTomorrowTime(this, clock(hour, minute))
                ReminderRescheduler.request()
                refreshDailyTexts()
            }
        }
    }

    private fun refreshDailyTexts() {
        binding.tvTodayTime.text = ReminderPrefs.dailyTime(this)
        binding.tvTomorrowTime.text = ReminderPrefs.tomorrowTime(this)
        val today = LocalDate.now()
        binding.tvDailyPreview.text =
            buildDailyPreview(today, R.string.reminder_preview_today_date)
        binding.tvTomorrowPreview.text =
            buildDailyPreview(today.plusDays(1), R.string.reminder_preview_tomorrow_date)
    }

    // ==================== 课前提醒 ====================

    private fun setupBeforeClass() {
        binding.swBefore.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            ReminderPrefs.setBeforeClassEnabled(this, checked)
            ReminderRescheduler.request()
            refreshMasterDependents()
        }

        val initial = ReminderPrefs.beforeClassMinutes(this).toString()
        binding.etMinutes.setText(initial)
        binding.etMinutes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // 防抖 500ms：每敲一键就全量 cancelAll + scheduleAll 太贵（查库 + 重排 7 天闹钟）
                minutesJob?.cancel()
                val value = s?.toString()?.toIntOrNull() ?: return
                minutesJob = lifecycleScope.launch {
                    delay(500)
                    ReminderPrefs.setBeforeClassMinutes(this@ReminderActivity, value)
                    ReminderRescheduler.request()
                    refreshBeforePreview()
                }
            }
        })

        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnModeFluid) {
                ReminderPrefs.MODE_FLUID
            } else {
                ReminderPrefs.MODE_BANNER
            }
            ReminderPrefs.setBeforeClassMode(this, mode)
            if (mode != ReminderPrefs.MODE_FLUID) {
                // 切回横幅必须真正停掉流体云：FluidCloudService 的 15s 循环会不断 re-post，卡片会复活
                runCatching { FluidCloudService.requestStop(this) }
            }
            ReminderRescheduler.request()
            applyModeState()
        }

        binding.ddPrimary.setSimpleItems(
            arrayOf(
                getString(R.string.reminder_fluid_field_name),
                getString(R.string.reminder_fluid_field_time),
                getString(R.string.reminder_fluid_field_room),
                getString(R.string.reminder_fluid_field_countdown)
            )
        )
        binding.ddPrimary.setOnItemClickListener { _, _, position, _ ->
            val value = primaryValues.getOrNull(position) ?: return@setOnItemClickListener
            ReminderPrefs.setFluidPrimary(this, value)
            ReminderPrefs.setBeforeClassMode(this, ReminderPrefs.MODE_FLUID)
            syncModeButtons()
            applyModeState()
            ReminderRescheduler.request()
        }

        binding.tvFluidGoSettings.setOnClickListener { openVendorSettings() }
        binding.btnFluidTest.setOnClickListener { startFluidTestWithGate() }
        binding.btnFluidStop.setOnClickListener { stopFluidTest() }
    }

    private fun applyModeState() {
        val fluid = ReminderPrefs.isBeforeClassFluidEnabled(this)
        binding.sectionFluid.visibility = if (fluid) View.VISIBLE else View.GONE
        binding.tvModeSub.setText(
            if (fluid) R.string.reminder_mode_fluid_sub else R.string.reminder_mode_banner_sub
        )
        if (fluid) refreshFluidStatus()
        refreshBeforePreview()
    }

    private fun syncModeButtons() {
        suppressCallbacks = true
        binding.toggleMode.check(
            if (ReminderPrefs.isBeforeClassFluidEnabled(this)) R.id.btnModeFluid else R.id.btnModeBanner
        )
        suppressCallbacks = false
    }

    private fun refreshBeforeClassTexts() {
        val fieldLabel = when (ReminderPrefs.fluidPrimary(this)) {
            ReminderPrefs.FIELD_NAME -> R.string.reminder_fluid_field_name
            ReminderPrefs.FIELD_TIME -> R.string.reminder_fluid_field_time
            ReminderPrefs.FIELD_COUNTDOWN -> R.string.reminder_fluid_field_countdown
            else -> R.string.reminder_fluid_field_room
        }
        binding.ddPrimary.setText(getString(fieldLabel), false)
        syncModeButtons()
        applyModeState()
    }

    // ==================== 流体云测试 ====================

    /**
     * 三查闸门：通知权限 → promoted 实时更新授权 → 厂商能力。
     *
     * 点击时现场实查，不读缓存快照 —— 快照异步刷新有竞态，用户刚从系统设置授权回来
     * 会被旧值误拦。
     */
    private fun startFluidTestWithGate() {
        val missing = mutableListOf<Int>()
        if (!NotifPermission.isGranted(this)) {
            missing += R.string.reminder_fluid_gate_notification
        }
        if (Build.VERSION.SDK_INT >= 36) {
            val nm = getSystemService(NotificationManager::class.java)
            val allowed = runCatching { nm?.canPostPromotedNotifications() == true }
                .getOrDefault(false)
            if (!allowed) missing += R.string.reminder_fluid_gate_promoted
        }
        val cap = inspectVendorCapability(this, detectLiveCardVendor())
        if (cap.state == VendorCapabilityState.NOTIFICATION_PERMISSION_REQUIRED) {
            if (R.string.reminder_fluid_gate_notification !in missing) {
                missing += R.string.reminder_fluid_gate_notification
            }
        }
        if (cap.state == VendorCapabilityState.SETTINGS_REQUIRED ||
            cap.state == VendorCapabilityState.NOT_SUPPORTED
        ) {
            missing += R.string.reminder_fluid_gate_vendor
        }
        if (missing.isEmpty()) {
            startFluidTest()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reminder_fluid_gate_title)
            .setMessage(
                getString(R.string.reminder_fluid_gate_message) + "\n\n" +
                    missing.joinToString("\n") { "• " + getString(it) }
            )
            .setNegativeButton(R.string.reminder_fluid_gate_go_settings) { _, _ ->
                openVendorSettings()
            }
            .setPositiveButton(R.string.reminder_fluid_gate_cast_anyway) { _, _ ->
                startFluidTest()
            }
            .show()
    }

    private fun startFluidTest() {
        val intent = Intent(this, FluidCloudService::class.java)
            .setAction(FluidCloudService.ACTION_TEST)
        val started = runCatching {
            ContextCompat.startForegroundService(this, intent)
            true
        }.getOrDefault(false)
        if (!started) {
            toast(R.string.reminder_fluid_test_start_failed)
            return
        }
        toast(R.string.reminder_fluid_test_started)
        refreshFluidTestState()
        // 测试窗口固定 2 分钟，到点服务自己收尾，界面跟着落定
        handler.postDelayed({ refreshFluidTestState() }, TEST_WINDOW_MS + 1_500L)
    }

    private fun stopFluidTest() {
        val stopped = runCatching {
            FluidCloudService.requestStop(this)
            true
        }.getOrDefault(false)
        if (!stopped) {
            toast(R.string.reminder_fluid_test_stop_failed)
            return
        }
        handler.postDelayed({ refreshFluidTestState() }, 600L)
    }

    private fun refreshFluidTestState() {
        val casting = FluidCastState.casting
        binding.rowFluidCasting.isVisible = casting
        binding.btnFluidTest.isVisible = !casting
    }

    private fun refreshFluidStatus() {
        val granted = NotifPermission.isGranted(this)
        binding.tvFluidStatus.setText(
            if (granted) R.string.reminder_fluid_status_unknown
            else R.string.reminder_fluid_status_notification_required
        )
        binding.tvFluidStatus.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(
                binding.tvFluidStatus,
                if (granted) {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                } else {
                    com.google.android.material.R.attr.colorError
                }
            )
        )
    }

    private fun openVendorSettings() {
        val vendor = detectLiveCardVendor()
        for (intent in vendorSettingsIntents(this, vendor)) {
            val opened = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (opened) return
        }
        NotifPermission.openSettings(this, ReminderNotify.CHANNEL_FLUID)
    }

    // ==================== 上课自动勿扰 ====================

    private fun setupDnd() {
        binding.swDnd.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            if (checked && !policyAccessGranted()) {
                setDnd(false)
                pendingDndEnable = true
                val opened = runCatching {
                    dndLauncher.launch(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    )
                    true
                }.getOrDefault(false)
                if (!opened) pendingDndEnable = false
                refreshDndStatus()
                return@setOnCheckedChangeListener
            }
            persistDnd(checked)
        }
    }

    private fun setDnd(enabled: Boolean) {
        suppressCallbacks = true
        binding.swDnd.isChecked = enabled
        suppressCallbacks = false
        persistDnd(enabled)
    }

    private fun persistDnd(enabled: Boolean) {
        ReminderPrefs.setClassDndEnabled(this, enabled)
        ClassDndScheduler(this).syncFromPrefs()
        refreshDndStatus()
    }

    private fun policyAccessGranted(): Boolean {
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    private fun refreshDndStatus() {
        val missing = ReminderPrefs.isClassDndEnabled(this) && !policyAccessGranted()
        binding.tvDndStatus.visibility = if (missing) View.VISIBLE else View.GONE
        binding.tvDndStatus.setText(R.string.reminder_dnd_permission_needed)
    }

    // ==================== 后台可靠性 ====================

    private fun setupReliability() {
        binding.btnReliabilityAttention.setOnClickListener {
            // 直接滚到权限卡，用户立刻看到该处理的是哪一项（Sleepy 的相同行为）
            binding.scroll.smoothScrollTo(0, binding.cardReliability.top)
        }
        binding.tvRelNotification.setOnClickListener {
            // 没权限才响应点击：已有权限时该行只是展示
            if (!NotifPermission.isGranted(this)) NotifPermission.request(this, permissionLauncher)
        }
        binding.tvRelBattery.setOnClickListener { launchBatterySettings(this) }
        binding.tvRelExactAlarm.setOnClickListener { openExactAlarmSettings() }
    }

    private fun refreshReliability() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { ReminderReliability.snapshot(this@ReminderActivity) }
            if (isFinishing || isDestroyed) return@launch
            val res = resources
            // 可点击修复的行，未达标时染成主色（与 Sleepy 的 ReminderReliabilityCard 一致）
            val primaryAttr = com.google.android.material.R.attr.colorPrimary
            val variantAttr = com.google.android.material.R.attr.colorOnSurfaceVariant
            val primaryColor = com.google.android.material.color.MaterialColors
                .getColor(binding.tvRelNotification, primaryAttr)
            val variantColor = com.google.android.material.color.MaterialColors
                .getColor(binding.tvRelNotification, variantAttr)
            binding.tvRelNotification.setTextColor(
                if (snapshot.notificationPermissionGranted) variantColor else primaryColor
            )
            binding.tvRelNotification.text = res.getString(
                R.string.reminder_reliability_notification,
                res.getString(ReminderReliability.statusRes(snapshot.notificationPermissionGranted))
            )
            binding.tvRelExactAlarm.setTextColor(
                if (snapshot.exactAlarmAllowed) variantColor else primaryColor
            )
            binding.tvRelExactAlarm.text = res.getString(
                R.string.reminder_reliability_exact_alarm,
                res.getString(ReminderReliability.statusRes(snapshot.exactAlarmAllowed))
            )
            binding.tvRelBattery.setTextColor(
                if (snapshot.batteryOptimizationIgnored) variantColor else primaryColor
            )
            binding.tvRelBattery.text = res.getString(
                R.string.reminder_reliability_battery,
                res.getString(ReminderReliability.statusRes(snapshot.batteryOptimizationIgnored))
            )
            binding.tvRelWidget.text = res.getString(
                R.string.reminder_reliability_widget,
                res.getString(ReminderReliability.statusRes(snapshot.widgetBound))
            )
            binding.tvRelVendor.text = res.getString(
                R.string.reminder_reliability_vendor,
                res.getString(ReminderReliability.vendorStateRes(snapshot.liveCardState))
            )
            binding.tvRelPromoted.visibility = if (snapshot.promotedOngoingAllowed == null) {
                View.GONE
            } else {
                View.VISIBLE
            }
            if (snapshot.promotedOngoingAllowed != null) {
                binding.tvRelPromoted.text = res.getString(
                    R.string.reminder_reliability_promoted,
                    res.getString(
                        ReminderReliability.statusRes(snapshot.promotedOngoingAllowed)
                    )
                )
            }
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData("package:$packageName".toUri())
        val opened = runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)
        if (!opened) NotifPermission.openSettings(this)
    }

    // ==================== 公共 ====================

    private fun refreshAll() {
        suppressCallbacks = true
        binding.swMaster.isChecked = ReminderPrefs.isEnabled(this)
        binding.swDaily.isChecked = ReminderPrefs.isDailyEnabled(this)
        binding.swToday.isChecked = ReminderPrefs.isTodayEnabled(this)
        binding.swTomorrow.isChecked = ReminderPrefs.isTomorrowEnabled(this)
        binding.swBefore.isChecked = ReminderPrefs.isBeforeClassEnabled(this)
        binding.swDnd.isChecked = ReminderPrefs.isClassDndEnabled(this)
        suppressCallbacks = false

        refreshMasterDependents()
        refreshPermissionHint()
        refreshDailyTexts()
        refreshBeforeClassTexts()
        refreshDndStatus()
        refreshFluidTestState()
        refreshReliability()
    }

    /** 总开关关闭时，下面三张卡整体隐藏（与 Sleepy 的 `if (masterEnabled)` 一致）。 */
    private fun refreshMasterDependents() {
        val enabled = ReminderPrefs.isEnabled(this)
        binding.cardDaily.isVisible = enabled
        binding.cardBefore.isVisible = enabled
        binding.cardDnd.isVisible = enabled
        // 卡片内部再按自己的开关二次隐藏（Sleepy 的 if (dailyEnabled) / if (beforeClassEnabled)）
        binding.groupDailyDetail.isVisible = ReminderPrefs.isDailyEnabled(this)
        binding.groupBeforeDetail.isVisible = ReminderPrefs.isBeforeClassEnabled(this)
    }

    private fun pickTime(
        current: String,
        fallbackHour: Int,
        fallbackMinute: Int,
        onPicked: (Int, Int) -> Unit
    ) {
        val parts = current.split(":")
        val hour = (parts.getOrNull(0)?.toIntOrNull() ?: fallbackHour).coerceIn(0, 23)
        val minute = (parts.getOrNull(1)?.toIntOrNull() ?: fallbackMinute).coerceIn(0, 59)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(R.string.reminder_pick_time)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour, picker.minute) }
        picker.show(supportFragmentManager, "reminder_time")
    }

    private fun clock(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour, minute)

    private fun buildDailyPreview(date: LocalDate, labelRes: Int): String {
        val res = resources
        val courses = ReminderData.coursesOn(date)
        val label = dateLabel(res, labelRes, date)
        val first = courses.firstOrNull()
            ?: return res.getString(R.string.reminder_daily_preview_dynamic_no_course, label)
        val teacher = first.teacher.trim().takeIf { it.isNotEmpty() }
            ?.let { res.getString(R.string.reminder_preview_teacher, it) }
            .orEmpty()
        return res.getString(
            R.string.reminder_daily_preview_dynamic,
            label,
            courses.size,
            first.name.ifBlank { res.getString(R.string.default_course_name) },
            first.startText.ifBlank { res.getString(R.string.agenda_time_unknown) },
            first.room.ifBlank { res.getString(R.string.notif_room_unknown) },
            teacher
        )
    }

    private fun refreshBeforePreview() {
        val res = resources
        val next = ReminderData.nextCourse()
            ?: run {
                binding.tvBeforePreview.setText(R.string.reminder_before_class_preview_dynamic_no_course)
                return
            }
        val label = dateLabel(res, R.string.reminder_preview_date, next.date)
        val teacher = next.teacher.trim().takeIf { it.isNotEmpty() }
            ?.let { res.getString(R.string.reminder_preview_teacher, it) }
            .orEmpty()
        binding.tvBeforePreview.text = res.getString(
            R.string.reminder_before_class_preview_dynamic,
            label,
            next.name.ifBlank { res.getString(R.string.default_course_name) },
            next.startText.ifBlank { res.getString(R.string.agenda_time_unknown) },
            next.room.ifBlank { res.getString(R.string.notif_room_unknown) },
            teacher
        )
    }

    private fun dateLabel(res: Resources, resId: Int, date: LocalDate): String {
        val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return res.getString(resId, date.format(DateTimeFormatter.ofPattern("M/d")), day)
    }

    private companion object {
        const val TEST_WINDOW_MS = 2 * 60_000L
    }
}
