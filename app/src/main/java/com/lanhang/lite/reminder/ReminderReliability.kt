package com.lanhang.lite.reminder

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.lanhang.lite.R
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.widget.CampusNetWidgetProvider
import com.lanhang.lite.widget.ScheduleWidgetProvider
import com.lanhang.lite.widget.ScheduleWidgetSmallProvider

/**
 * 后台可靠性快照：把散落在各处的系统事实一次读完，界面只负责展示。
 *
 * 只读公共 Android 事实；**厂商私有探测失败一律 UNKNOWN，绝不影响标准通知链路**。
 */
data class BackgroundReliabilitySnapshot(
    val vendor: LiveCardVendor,
    val notificationPermissionGranted: Boolean,
    val exactAlarmAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val widgetBound: Boolean,
    val liveCardState: VendorCapabilityState,
    val promotedOngoingAllowed: Boolean?,
)

/**
 * 提醒可靠性探针（移植自 Sleepy `BackgroundReliabilityProbe`）。
 *
 * 「提醒会不会按时响」取决于五件互相独立的事：通知权限、精确闹钟、电池优化白名单、
 * 前台服务能否启动、厂商实时卡片是否放行。这里每一项都单独读，界面才能指出
 * 用户到底该去改哪一个开关 —— 把它们揉成一个「可靠性良好」是没用的。
 */
object ReminderReliability {

    fun snapshot(context: Context): BackgroundReliabilitySnapshot {
        val app = context.applicationContext
        val vendor = detectLiveCardVendor()
        val liveCard = runCatching {
            inspectVendorCapability(app, vendor).state
        }.getOrDefault(VendorCapabilityState.UNKNOWN)

        return BackgroundReliabilitySnapshot(
            vendor = vendor,
            notificationPermissionGranted = NotifPermission.isGranted(app),
            exactAlarmAllowed = exactAlarmAllowed(app),
            batteryOptimizationIgnored = batteryOptimizationIgnored(app),
            widgetBound = widgetBound(app),
            liveCardState = liveCard,
            promotedOngoingAllowed = promotedOngoingAllowed(app),
        )
    }

    /** API 36+ 才能查 promoted ongoing 授权；更低的系统没有这个概念，返回 null。 */
    private fun promotedOngoingAllowed(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? android.app.NotificationManager ?: return false
        return runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)
    }

    private fun exactAlarmAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.canScheduleExactAlarms() == true

    private fun batteryOptimizationIgnored(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching {
            power.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    private fun widgetBound(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return WIDGET_PROVIDERS.any { provider ->
            manager.getAppWidgetIds(ComponentName(context, provider)).isNotEmpty()
        }
    }

    private val WIDGET_PROVIDERS = listOf(
        ScheduleWidgetProvider::class.java,
        ScheduleWidgetSmallProvider::class.java,
        CampusNetWidgetProvider::class.java,
    )

    /**
     * 可靠性卡每一行的状态文案（[granted] 为 null = 该平台没有这个概念，行不显示）。
     *
     * 文案在这里 resolve 成资源 id 而不是 String：界面层才 `getString`，
     * 与全项目的「非 UI 层不解析文案」约定一致。
     */
    fun statusRes(granted: Boolean?): Int = when (granted) {
        true -> R.string.reminder_reliability_ready
        false -> R.string.reminder_reliability_missing
        null -> R.string.reminder_reliability_not_applicable
    }

    /** 厂商实时卡片能力 → 文案。UNKNOWN 是终态，不假装「已开启」。 */
    fun vendorStateRes(state: VendorCapabilityState): Int = when (state) {
        VendorCapabilityState.ENABLED -> R.string.reminder_fluid_status_enabled
        VendorCapabilityState.DISABLED -> R.string.reminder_fluid_status_settings_required
        VendorCapabilityState.NOTIFICATION_PERMISSION_REQUIRED ->
            R.string.reminder_fluid_status_notification_required
        VendorCapabilityState.SETTINGS_REQUIRED -> R.string.reminder_fluid_status_settings_required
        VendorCapabilityState.NOT_SUPPORTED -> R.string.reminder_fluid_status_not_supported
        VendorCapabilityState.UNKNOWN -> R.string.reminder_reliability_unknown
    }
}
