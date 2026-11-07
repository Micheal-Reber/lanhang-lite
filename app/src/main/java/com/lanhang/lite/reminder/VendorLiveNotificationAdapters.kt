package com.lanhang.lite.reminder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.lanhang.lite.R

/**
 * 厂商增强通知能力的「状态」。
 * UNKNOWN 是真实的终态：厂商私有资格永远不能猜。
 */
enum class VendorCapabilityState {
    ENABLED,
    DISABLED,
    NOTIFICATION_PERMISSION_REQUIRED,
    SETTINGS_REQUIRED,
    NOT_SUPPORTED,
    UNKNOWN
}

/** 设置页候选入口，保存为数据以便排序与回退可测。 */
data class IntentSpec(
    val action: String,
    val extras: Map<String, String> = emptyMap()
)

data class VendorLiveNotificationCapability(
    val vendor: LiveCardVendor,
    val state: VendorCapabilityState,
    val summaryRes: Int,
    val settingsIntents: List<IntentSpec>,
    val fallbackToAppNotificationSettings: Boolean
)

const val ACTION_APP_NOTIFICATION_SETTINGS = Settings.ACTION_APP_NOTIFICATION_SETTINGS
const val ACTION_CHANNEL_NOTIFICATION_SETTINGS = Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS

/** API 36+ 标准实时更新（promoted ongoing）授权页 —— ColorOS 16 流体云官方开关在此。 */
const val ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS =
    "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"

/**
 * 所有厂商族的统一顺序：promotion → channel → vendor → application。
 *
 * 实测 OPPO/ColorOS 16：通道页不承载流体云授权 —— 流体云走原生 Android 16
 * Live Updates（promoted ongoing）通道，官方授权页是
 * [ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS]，必须排最前；通道页退居其后。
 * promoted 页仅 API 36+ 存在，低版本 launch 时 ActivityNotFound 逐级回退。
 */
fun vendorSettingsSpecs(vendor: LiveCardVendor): List<IntentSpec> =
    vendorSettingsSpecs(vendor, Build.VERSION.SDK_INT)

/** sdkInt 注入版 —— 契约测试锁顺序用。 */
fun vendorSettingsSpecs(vendor: LiveCardVendor, sdkInt: Int): List<IntentSpec> = buildList {
    if (sdkInt >= 36 && vendor in OPPO_LIVE_UPDATES_VENDOR_FAMILY) {
        add(IntentSpec(ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS))
    }
    add(IntentSpec(ACTION_CHANNEL_NOTIFICATION_SETTINGS))
    vendorSettingsAction(vendor)?.let { add(IntentSpec(it)) }
    add(IntentSpec(ACTION_APP_NOTIFICATION_SETTINGS))
}

/** OPPO 家族 ColorOS 16+ 流体云 = 原生 Live Updates，授权走标准 promoted 设置页。 */
private val OPPO_LIVE_UPDATES_VENDOR_FAMILY = setOf(
    LiveCardVendor.OPPO, LiveCardVendor.ONEPLUS, LiveCardVendor.REALME
)

private fun vendorSettingsAction(vendor: LiveCardVendor): String? = when (vendor) {
    // 只是候选；可用性由 launch 时的异常处理判定
    LiveCardVendor.OPPO, LiveCardVendor.ONEPLUS, LiveCardVendor.REALME ->
        "com.coloros.notificationmanager.action.NOTIFICATION_SETTINGS"
    LiveCardVendor.VIVO, LiveCardVendor.IQOO ->
        "com.vivo.notification.action.NOTIFICATION_SETTINGS"
    LiveCardVendor.XIAOMI -> "miui.intent.action.APP_PERM_EDITOR"
    LiveCardVendor.MEIZU -> "com.meizu.safe.security.SHOW_APPSEC"
    LiveCardVendor.HUAWEI -> "com.huawei.systemmanager.APP_CONTROL"
    LiveCardVendor.HONOR -> "com.hihonor.systemmanager.APP_CONTROL"
    LiveCardVendor.SAMSUNG, LiveCardVendor.GENERIC -> null
}

/**
 * 探测厂商增强通知能力。
 *
 * 只有系统通知权限是决定性的；厂商私有资格一律 UNKNOWN（不猜）。
 */
fun inspectVendorCapability(context: Context, vendor: LiveCardVendor): VendorLiveNotificationCapability {
    val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    return VendorLiveNotificationCapability(
        vendor = vendor,
        state = if (permissionMissing) {
            VendorCapabilityState.NOTIFICATION_PERMISSION_REQUIRED
        } else {
            VendorCapabilityState.UNKNOWN
        },
        summaryRes = if (permissionMissing) {
            R.string.reminder_fluid_status_notification_required
        } else {
            R.string.reminder_fluid_status_unknown
        },
        settingsIntents = vendorSettingsSpecs(vendor),
        fallbackToAppNotificationSettings = true
    )
}

/** 把 [IntentSpec] 展开成可直接 startActivity 的 Intent（附带包名与渠道）。 */
fun vendorSettingsIntents(
    context: Context,
    vendor: LiveCardVendor = detectLiveCardVendor()
): List<Intent> = vendorSettingsSpecs(vendor).map { spec ->
    Intent(spec.action).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, ReminderNotify.CHANNEL_FLUID)
    }
}

// ---------------------------------------------------------------------------
// 电池 / 后台管理页候选
// ---------------------------------------------------------------------------

/** 电池 / 后台管理页候选入口：component 为显式组件，action 为隐式意图，二选一。 */
data class BatteryIntentSpec(
    val component: String? = null,
    val action: String? = null,
)

/**
 * 候选顺序：厂商耗电/自启动管理页 → 标准「忽略电池优化」弹窗 → 原生电池优化应用列表页。
 *
 * 原生列表页没有「无限制后台活动」选项 —— 该选项只存在于厂商定制省电页，
 * 所以厂商页必须排最前。厂商组件名来自社区逆向（随 ROM 版本漂移），
 * 逐个 try-launch 吞异常，全败才落原生。
 */
fun vendorBatterySpecs(vendor: LiveCardVendor): List<BatteryIntentSpec> = buildList {
    when (vendor) {
        // MIUI/HyperOS 省电策略页（内含「无限制」档位）
        LiveCardVendor.XIAOMI ->
            add(
                BatteryIntentSpec(
                    component = "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
        LiveCardVendor.HUAWEI ->
            add(
                BatteryIntentSpec(
                    component = "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            )
        LiveCardVendor.HONOR ->
            add(
                BatteryIntentSpec(
                    component = "com.hihonor.systemmanager/com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            )
        // ColorOS / OxygenOS 13+ 应用耗电详情（内含「允许后台活动」）
        LiveCardVendor.OPPO, LiveCardVendor.ONEPLUS, LiveCardVendor.REALME ->
            add(
                BatteryIntentSpec(
                    component = "com.oplus.battery/com.oplus.pantanal.ums.ui.appdetail.AppDetailActivity"
                )
            )
        LiveCardVendor.VIVO, LiveCardVendor.IQOO ->
            add(
                BatteryIntentSpec(
                    component = "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
        LiveCardVendor.MEIZU, LiveCardVendor.SAMSUNG, LiveCardVendor.GENERIC -> Unit
    }
    add(BatteryIntentSpec(action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
    add(BatteryIntentSpec(action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

/** 逐个尝试启动候选，命中返回 true；全部不可用返回 false（调用方静默放弃）。 */
fun launchBatterySettings(
    context: Context,
    vendor: LiveCardVendor = detectLiveCardVendor(),
): Boolean = vendorBatterySpecs(vendor).any { spec ->
    try {
        val pkg = context.packageName
        val intent = when {
            spec.component != null ->
                Intent().setComponent(ComponentName.unflattenFromString(spec.component))
            else -> Intent(spec.action)
        }
        when (spec.action) {
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
                intent.setData("package:$pkg".toUri())
            else -> {
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                // MIUI PowerKeeper 读这两个 extra 定位到本应用的省电策略页
                intent.putExtra("package_name", pkg)
                intent.putExtra(
                    "package_label",
                    context.applicationInfo.loadLabel(context.packageManager).toString()
                )
            }
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
