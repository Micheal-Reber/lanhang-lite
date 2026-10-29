package com.lanhang.lite.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.databinding.ActivityClassReminderBinding
import com.lanhang.lite.session.Session
// App 模块开了 nonTransitiveRClass：R.attr.* 只有本项目自己定义的属性，
// 主题里的 colorError / colorOnSurfaceVariant 在 material 的 R 里
import com.google.android.material.R as MATERIAL_R

/**
 * 设置 → 工具 → 上课提醒。
 *
 * 页面存在的理由：这个功能有**三个**独立的系统开关（通知权限、精确闹钟、电池优化），
 * 任何一个缺失都会让提醒「看起来开着但不响」。一个对话框装不下，而且用户需要看到
 * 「现在到底缺哪一项」，所以做成独立页。
 *
 * 两条刻意的产品决策：
 * - **通知权限是「开启提醒」的前置条件**：没有通知权限时点开关**不会**把开关打开，
 *   而是直接把用户送去授权（能弹系统框就弹，弹不了就说明原因再跳设置页），
 *   授权回来后自动把开关打开。留下一个「开着但永远不会响」的开关是更糟的体验 ——
 *   用户以为搞定了，直到某天上课才发现提醒根本没来过。
 *   权限是**被动失效**的（用户自己进系统设置关掉通知）时开关保留不动，只给红条：
 *   那是用户的选择，App 不该偷偷改回去；此时 `rearm` 会把闹钟全部撤销，保证不出现
 *   「界面开着、实际不响」的中间状态。
 */
class ClassReminderActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ClassReminderActivity::class.java))
        }
    }

    private lateinit var binding: ActivityClassReminderBinding

    /** render 期间改 switch 会回调监听，必须忽略，否则开关会自我翻转 */
    private var rendering = false

    /**
     * 「用户刚点了开启，但缺通知权限」这个意图。
     *
     * 不落盘：它只在用户在**本页**做这一次操作后有效。授权回来（无论走系统对话框还是
     * 设置页）由 [onResume] / 权限回调兑现；用户中途退出页面就丢掉，不会在几周后
     * 因为某次无关的 onResume 突然把提醒打开。
     */
    private var pendingEnable = false

    /**
     * 「刚点了上课勿扰、但还没给勿扰权限」这个意图。
     *
     * 和 [pendingEnable] 一样不落盘：从系统设置页回来就兑现，中途退出就丢掉 ——
     * 不要让它几周后因为某次无关的 onResume 突然把勿扰打开（那比「没开」更糟）。
     */
    private var pendingDnd = false

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 系统对话框里点了「允许」：兑现用户刚才的开启意图
            applyPendingEnable()
        } else {
            // 明确拒绝：开关保持关闭，只是告诉他后果。不再自动跳设置页 ——
            // 用户刚在对话框里点了「不允许」，立刻被甩到设置页会被当成骚扰
            pendingEnable = false
            Toast.makeText(this, R.string.class_reminder_toast_need_notify, Toast.LENGTH_SHORT)
                .show()
        }
        render()
        ClassReminder.rearm(this)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClassReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // insets 会多次回调，基准值必须先取
        val baseBottomPadding = binding.scroll.paddingBottom
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
                baseBottomPadding + bars.bottom
            )
            insets
        }

        bind()
        render()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页回来时权限可能变了：
        // 1) 有未兑现的「开启」意图、且权限已经到手 → 现在把开关打开
        // 2) 权限是被动失效的（用户自己关掉了通知）→ 撤掉闹钟，避免「开着但不响」
        if (pendingEnable && ClassReminderNotify.canNotify(this)) {
            applyPendingEnable()
        } else if (ClassReminderStore.enabled && !ClassReminderNotify.canNotify(this)) {
            ClassReminder.rearm(this)
        }
        // 从勿扰权限页回来：给了就留下开关，没给就把开关弹回关态 ——
        // 留一个「开着但一次都不会生效」的开关，比不让开更误导
        if (pendingDnd) {
            if (ClassDnd.canControl(this)) {
                pendingDnd = false
                Toast.makeText(this, R.string.class_reminder_dnd_toast_on, Toast.LENGTH_SHORT).show()
            } else if (ClassReminderStore.autoDnd) {
                pendingDnd = false
                ClassReminderStore.autoDnd = false
                ClassReminder.rearm(this)
                Toast.makeText(this, R.string.class_reminder_dnd_toast_need, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        render()
    }

    /** 兑现「缺权限时点了开启」的意图：现在权限有了，把开关打开并排闹钟 */
    private fun applyPendingEnable() {
        if (!pendingEnable) return
        pendingEnable = false
        ClassReminderStore.enabled = true
        render()
        ClassReminder.rearm(this)
        Toast.makeText(this, R.string.class_reminder_toast_notify_on, Toast.LENGTH_SHORT).show()
        maybeAskExactPermission()
    }

    // ------------------------------------------------------------ 交互

    private fun bind() {
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            if (!checked) {
                ClassReminderStore.enabled = false
                ClassReminder.rearm(this)
                render()
                return@setOnCheckedChangeListener
            }
            // 开启 = 一次「要通知」的请求，通知权限是它的前置条件。
            // 没权限时先把开关弹回去（不能让界面显示「已开启」而实际一条都发不出去），
            // 再按权限的三种状态分别引导：能弹框就弹框，弹不了说明原因后跳设置页。
            if (!ClassReminderNotify.canNotify(this)) {
                pendingEnable = true
                setSwitchSilently(false)
                render()
                NotifPermission.request(this, notifyPermission, ClassReminderNotify.CHANNEL_ID)
                return@setOnCheckedChangeListener
            }
            ClassReminderStore.enabled = true
            ClassReminder.rearm(this)
            render()
            maybeAskExactPermission()
        }
        binding.rowAdvance.setOnClickListener { showAdvanceDialog() }
        // 上课自动勿扰：开 → 没有权限就跳系统「勿扰权限」页（代码弹不出对话框）；
        // 关 → 如果正处在勿扰里，立刻把档位还回去
        binding.swDnd.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            if (!checked) {
                ClassReminderStore.autoDnd = false
                ClassDnd.restore(this)
                ClassReminder.rearm(this)
                render()
                return@setOnCheckedChangeListener
            }
            ClassReminderStore.autoDnd = true
            if (!ClassDnd.canControl(this)) {
                pendingDnd = true
                ClassDnd.openSettings(this)
            }
            ClassReminder.rearm(this)
            render()
        }
        binding.rowDnd.setOnClickListener { binding.swDnd.toggle() }
        binding.tvPromoteHint.setOnClickListener { openAppNotificationSettings() }
        binding.rowNotify.setOnClickListener { requestNotifyPermission() }
        binding.rowExact.setOnClickListener { requestExactPermission() }
        binding.rowBattery.setOnClickListener { requestBatteryExemption() }
    }

    /**
     * 只改开关外观、**不触发监听**。
     *
     * 在 `setOnCheckedChangeListener` 回调里直接 `isChecked = false` 会再进一次回调
     * （有的机型甚至递归两次），用 [rendering] 门控是这套代码里已有的做法，这里复用。
     */
    private fun setSwitchSilently(checked: Boolean) {
        rendering = true
        binding.swEnabled.isChecked = checked
        rendering = false
    }

    /**
     * 首次开启时提示一次「精确提醒」。
     *
     * Android 14 起对 targetSdk≥33 的新装应用**默认拒绝**精确闹钟，所以新用户全都落在这个坑里；
     * 实测不给权限时闹钟的投递窗口可达 **1 小时** —— 课前 15 分钟的提醒如果晚一小时才到，
     * 直接被「已经上课就不发」挡掉，用户会以为功能坏了。
     *
     * 只提示一次（`exactAsked`），之后靠页面上的红字提醒，不再反复弹。
     */
    private fun maybeAskExactPermission() {
        if (ClassReminder.canScheduleExact(this) || ClassReminderStore.exactAsked) return
        ClassReminderStore.exactAsked = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.class_reminder_exact_dialog_title)
            .setMessage(R.string.class_reminder_exact_dialog_message)
            .setNegativeButton(R.string.class_reminder_exact_dialog_later, null)
            .setPositiveButton(R.string.class_reminder_exact_dialog_open) { _, _ ->
                requestExactPermission()
            }
            .show()
    }

    private fun showAdvanceDialog() {
        val options = ClassReminderStore.ADVANCE_OPTIONS
        val labels = options.map { minutes ->
            if (minutes <= 0) {
                getString(R.string.class_reminder_advance_zero)
            } else {
                getString(R.string.class_reminder_advance_value, minutes)
            }
        }.toTypedArray()
        val checked = options.indexOf(ClassReminderStore.advanceMinutes).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.class_reminder_advance)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                ClassReminderStore.advanceMinutes = options[which]
                // 提前量变了 → 所有触发时刻都变，必须立刻重排
                ClassReminder.rearm(this)
                render()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /**
     * 「通知权限」那一行 / 测试按钮的授权入口。
     *
     * 三种状态的处理全在 [NotifPermission.request] 里：能弹系统框就弹，
     * 「拒绝且不再询问」或通知总开关被关就说明原因再跳设置页，渠道被单独关掉跳渠道页。
     * 这里不再自己判 `shouldShowRequestPermissionRationale` —— 那是两份逻辑走岔的起点。
     */
    private fun requestNotifyPermission() {
        // 已经有权限时这一行是「已开启」+ ›，点它应当去渠道设置（能单独关掉上课提醒那条渠道），
        // 而不是什么都不做
        if (ClassReminderNotify.canNotify(this)) {
            NotifPermission.openSettings(this, ClassReminderNotify.CHANNEL_ID)
            return
        }
        NotifPermission.request(this, notifyPermission, ClassReminderNotify.CHANNEL_ID)
    }

    /**
     * 系统是否允许「实时动态」提升（状态栏胶囊）。
     *
     * 判定统一委托给 [ClassReminderNotify.promotionAllowed] —— 页面和真正发通知的地方
     * 各写一份，才会出现「页面说能提升、实际被系统丢掉」的静默失败。那边用的是官方
     * `canPostPromotedNotifications()`，同时考虑权限、用户设置与 appop。
     */
    private fun promotionAllowed(): Boolean = ClassReminderNotify.promotionAllowed(this)

    /** 应用通知设置页（「实时活动 / 允许提升通知」在里面） */
    private fun openAppNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }.onFailure { openAppDetail() }
    }

    private fun openAppDetail() {        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    /**
     * 精确闹钟权限。Android 12+ 需要 `SCHEDULE_EXACT_ALARM`，而 **Android 14 起对
     * targetSdk≥33 的新装应用默认拒绝**（本机实测 `appops` 确实是 default），
     * 所以新装用户必然要来这里点一次。
     *
     * 唯一正规入口是系统「闹钟和提醒」页，不能用运行时权限对话框。
     */
    private fun requestExactPermission() {
        if (ClassReminder.canScheduleExact(this)) {
            Toast.makeText(this, R.string.class_reminder_toast_exact_granted, Toast.LENGTH_SHORT)
                .show()
            return
        }
        ClassReminderStore.exactAsked = true
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure { openAppDetail() }
    }

    /**
     * 电池优化白名单。国产 ROM 的「省电策略」比原生 Doze 更激进，个别机型会把闹钟攒很长时间；
     * 这一项是**可选**的，页面上不标成错误，只是把入口摆出来。
     */
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(packageName) == true) {
            // 已经放行了：给一个「撤销」的去处，而不是只弹一句「已放行」
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val enabled = ClassReminderStore.enabled
        val notifyOk = ClassReminderNotify.canNotify(this)
        val exactOk = ClassReminder.canScheduleExact(this)
        val batteryOk = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true

        rendering = true
        binding.swEnabled.isChecked = enabled
        binding.swDnd.isChecked = ClassReminderStore.autoDnd
        rendering = false

        binding.tvAdvanceSummary.text = ClassReminderStore.advanceLabel(this)
        // 勿扰那一行的副标题随权限状态变：没授权时直说「点了也不会生效」，别让用户以为已经开了
        binding.tvDndDesc.text = getString(
            if (ClassDnd.canControl(this)) {
                R.string.class_reminder_dnd_on
            } else {
                R.string.class_reminder_dnd_need_access
            }
        )
        // Android 16 的胶囊还要 appop 放行（新装默认 reject）：不放行就永远看不到，
        // 用户只会觉得「说好的实时提醒呢」。把入口摆成红字让他自己去开。
        binding.tvPromoteHint.isVisible = Build.VERSION.SDK_INT >= 36 && !promotionAllowed()

        binding.tvNotifySummary.text = getString(
            if (notifyOk) R.string.class_reminder_status_on else R.string.class_reminder_notify_off
        )
        binding.tvNotifySummary.setTextColor(
            attrColor(
                if (notifyOk) MATERIAL_R.attr.colorOnSurfaceVariant else MATERIAL_R.attr.colorError
            )
        )

        binding.tvExactSummary.text = getString(
            if (exactOk) R.string.class_reminder_status_on else R.string.class_reminder_exact_off
        )
        binding.tvExactSummary.setTextColor(
            attrColor(
                if (exactOk) MATERIAL_R.attr.colorOnSurfaceVariant else MATERIAL_R.attr.colorError
            )
        )

        binding.tvBatterySummary.text = getString(
            if (batteryOk) R.string.class_reminder_status_on else R.string.class_reminder_battery_off
        )
        // 2026-10-28 实测（本机 HyperOS）：设了提前 15 分钟，排期确实是 07:45（dumpsys 里 window=0），
        // 但系统 07:51:49 才投递 —— 同一夜冻结里小组件 00:05 的午夜闹钟也是 08:47 才补投。
        // 也就是说「晚到」不是排期算错，是系统把后台应用冻住了、等用户拿起手机才补发。
        // 这一项（电池「无限制」+ 允许自启动）是用户唯一能自救的开关，所以提醒开着却没放行时标红。
        binding.tvBatterySummary.setTextColor(
            attrColor(
                if (!batteryOk && enabled) {
                    MATERIAL_R.attr.colorError
                } else {
                    MATERIAL_R.attr.colorOnSurfaceVariant
                }
            )
        )

        // 课表缓存只读一次：这一页要「下一次提醒」和「有没有数据」两个结论，
        // 让两个函数各解析一遍 JSON 会在页面打开时多花几十毫秒
        val data = if (Session.userId.isNotBlank()) {
            ScheduleRepository.loadCached()?.data
        } else {
            null
        }

        binding.tvNext.text = when {
            !enabled -> getString(R.string.class_reminder_next_disabled)
            !notifyOk -> getString(R.string.class_reminder_next_no_permission)
            else -> ClassReminder.nextSlotText(this, data)
                ?: getString(R.string.class_reminder_next_none)
        }

        // 「查不到」≠「没有」：没课表缓存时不能说「未来 7 天没有课」，那会让人以为课表是空的
        binding.tvDataHint.isVisible = Session.userId.isNotBlank() && data == null
    }

    /** `R.attr.colorError` 在 App 的 R 里不存在（nonTransitiveRClass），要用 material 的 R */
    private fun attrColor(attrRes: Int): Int = MaterialColors.getColor(binding.root, attrRes)
}
