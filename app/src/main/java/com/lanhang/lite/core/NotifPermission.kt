package com.lanhang.lite.core

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R

/**
 * 通知权限的**唯一**判定与引导入口。
 *
 * 工作圈互动（[com.lanhang.lite.moments.MomentNotify]）和上课提醒
 * （[com.lanhang.lite.schedule.ClassReminderNotify]）共用这一份，
 * 因为「怎么判断有没有权限」和「没有权限时把用户送去哪」这两件事，
 * 两边各写一遍必然逐渐不一致（此前工作圈那份已经比上课提醒那份多考虑了
 * 「拒绝且不再询问」，就是同一份逻辑走岔的例子）。
 *
 * ## 为什么是「四态」而不是一个布尔
 *
 * 一个 `hasPermission()` 布尔只能回答「能不能弹」，回答不了「点授权按钮该干什么」：
 *
 * - [State.ASKABLE]：还能弹系统对话框（从未申请，或被拒一次但允许再问）→ 直接弹；
 * - [State.APP_BLOCKED]：13+ 上「拒绝且不再询问」，或用户在设置里关掉了通知总开关
 *   → 系统不会再弹框，**只能跳设置页**（此时还去 `launch()` 是「点了没反应」）；
 * - [State.CHANNEL_BLOCKED]：应用通知开着，但本功能那条渠道被单独关了
 *   → `areNotificationsEnabled()` 是 true，只看布尔会误判成「有权限」，然后通知静默消失。
 *
 * ## 一个刻意的取舍
 *
 * 跳系统设置页之前先弹一句说明（[confirmAndOpenSettings]），而不是从开关直接把用户
 * 甩到设置里 —— 那种「怎么突然换页面了」比「多一次点击」更糟。运行时权限对话框本身
 * 带系统说明，不再加一层。
 */
object NotifPermission {

    enum class State {
        /** 运行时权限已授予、通知总开关开着、目标渠道也开着 */
        GRANTED,

        /** 还能弹系统权限对话框 */
        ASKABLE,

        /** 应用级通知不可用：拒绝且不再询问 / 通知总开关被关 / Android 13 以下被关 */
        APP_BLOCKED,

        /** 应用通知开着，但目标渠道 `importance == NONE` */
        CHANNEL_BLOCKED,
    }

    private const val PREF = "lanhang_notif_perm"

    /** 「是否已经弹过一次系统权限框」——判定「还能不能弹」的必要条件 */
    private const val KEY_ASKED = "asked"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 没有 Activity 的调用方（rearm、渲染摘要）只需要这个布尔 */
    fun isGranted(context: Context, channelId: String? = null): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return channelId == null || !isChannelOff(context, channelId)
    }

    /** 有 Activity 时用这个：多出的信息是「系统框还能不能弹」 */
    fun state(activity: Activity, channelId: String? = null): State {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val canAsk = !wasAsked(activity) || ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )
            return if (canAsk) State.ASKABLE else State.APP_BLOCKED
        }
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
            return State.APP_BLOCKED
        }
        if (channelId != null && isChannelOff(activity, channelId)) return State.CHANNEL_BLOCKED
        return State.GRANTED
    }

    /**
     * 引导用户去授权：能弹系统框就弹，弹不了就说明原因再跳设置页。
     *
     * 调用方拿到结果的方式：系统框走 [ActivityResultLauncher] 回调，设置页走
     * `onResume` 复检（见 `ClassReminderActivity.onResume` 里的 `pendingEnable`）。
     */
    fun request(
        activity: Activity,
        launcher: ActivityResultLauncher<String>,
        channelId: String? = null
    ) {
        when (val state = state(activity, channelId)) {
            State.GRANTED -> Unit
            State.ASKABLE -> {
                markAsked(activity)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            State.APP_BLOCKED -> confirmAndOpenSettings(
                activity,
                channelId,
                R.string.notif_perm_blocked_title,
                R.string.notif_perm_blocked_message
            )
            State.CHANNEL_BLOCKED -> confirmAndOpenSettings(
                activity,
                channelId,
                R.string.notif_perm_channel_title,
                R.string.notif_perm_channel_message
            )
        }
    }

    /** 直接跳系统页（设置页里那种「去授权」行用）；不做二次确认 */
    fun openSettings(context: Context, channelId: String? = null) {
        val pkg = context.packageName
        // 渠道页最好用：用户在那一页能单独打开「上课提醒」，不用去翻通知总开关
        if (channelId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val opened = start(
                context,
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            )
            if (opened) return
        }
        val appPage = start(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        )
        if (appPage) return
        // 个别 ROM 没有通知设置页，退回「应用详情」
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", pkg, null))
        )
    }

    fun wasAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * 渠道是否被单独关掉。
     *
     * 渠道还没建（`getNotificationChannel` 返回 null）时**不算关**：那是「从没弹过通知」，
     * 不是用户的选择；把它当关会让人第一次打开功能就被拉去设置页。
     */
    private fun isChannelOff(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun confirmAndOpenSettings(
        activity: Activity,
        channelId: String?,
        titleRes: Int,
        messageRes: Int
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.notif_perm_dialog_open) { _, _ ->
                openSettings(activity, channelId)
            }
            .show()
    }

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
