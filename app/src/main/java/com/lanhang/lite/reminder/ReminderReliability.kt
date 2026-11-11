package com.lanhang.lite.reminder

import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.widget.CampusNetWidgetProvider
import com.lanhang.lite.widget.ScheduleWidgetProvider
import com.lanhang.lite.widget.ScheduleWidgetSmallProvider

/**
 * 后台可靠性快照：把散落在各处的系统事实一次读完，界面只负责展示。
 *
 * 只读公共 Android 事实。厂商实时卡片（HyperOS 的「焦点通知」）的开关存在厂商私有存储里，
 * **第三方读不到**，所以不在这里出现 —— 需要看效果的走「测试通知」那条路。
 */
data class BackgroundReliabilitySnapshot(
    val notificationPermissionGranted: Boolean,
    val exactAlarmAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val widgetBound: Boolean,
    val promotedOngoingAllowed: Boolean?,
    /**
     * 应用待机分桶（API 28+）；null = 系统没有这个概念。
     *
     * 这是 H0「闹钟被系统限流」唯一能在 App 内自证的手段：
     * `rare` 每小时只放行 1 个闹钟、`restricted` 每天 1 个。
     * 读**本包**的分桶不需要 `PACKAGE_USAGE_STATS` 权限。
     */
    val standbyBucket: Int?,
)

/**
 * 提醒可靠性探针（移植自 Sleepy `BackgroundReliabilityProbe`）。
 *
 * 「提醒会不会按时响」取决于几件互相独立的事：通知权限、精确闹钟、电池优化白名单、
 * 实时动态（Android 16 promoted）放行。这里每一项都单独读，界面才能指出
 * 用户到底该去改哪一个开关 —— 把它们揉成一个「可靠性良好」是没用的。
 */
object ReminderReliability {

    fun snapshot(context: Context): BackgroundReliabilitySnapshot {
        val app = context.applicationContext

        return BackgroundReliabilitySnapshot(
            notificationPermissionGranted = NotifPermission.isGranted(app),
            exactAlarmAllowed = exactAlarmAllowed(app),
            batteryOptimizationIgnored = batteryOptimizationIgnored(app),
            widgetBound = widgetBound(app),
            promotedOngoingAllowed = promotedOngoingAllowed(app),
            standbyBucket = standbyBucket(app),
        )
    }

    /**
     * 分桶已经紧到会限流闹钟了吗（`rare` 起：每小时 1 个；`restricted` 起：每天 1 个）。
     *
     * 官方同时写明：**用户手动把本 App 加进电池优化白名单会覆盖分桶限制**，
     * 所以这一行缺了指向的仍然是「忽略电池优化」那一行，不新增设置项。
     */
    fun standbyBucketThrottles(bucket: Int?): Boolean =
        bucket != null && bucket >= UsageStatsManager.STANDBY_BUCKET_RARE

    /** 读本包的待机分桶；API 28 以下 / 服务取不到返回 null。不需要任何权限。 */
    private fun standbyBucket(context: Context): Int? {
        if (Build.VERSION.SDK_INT < 28) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return runCatching { usm.appStandbyBucket }.getOrDefault(null)
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

}
