package com.lanhang.lite.reminder

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * 厂商实时卡片能力探测（移植自 Sleepy `VendorLiveCardSupport`）。
 *
 * 每个探测都是**只读、防御性**的（try/catch，默认 false），允许在主线程每次发通知时调用。
 *
 * 探测清单：
 *  - [flymeLiveEnabled]：`content://com.android.systemui.notification.provider` 的
 *    `isNotificationLiveEnabled`（Flyme 11+ 实时通知总开关）
 *  - [flymeVersion]：从 `Build.DISPLAY` 解析（如 "Flyme 11.2.0"）
 *  - [samsungNowBarFeature]：`com.samsung.feature.nowbar`
 *  - [vivoRegisterSceneList]：反射调 NotificationManager 的 `setSuperXInfosSceneList`
 *    （vivo 的白名单是按包的，这里只能尽力转发；是否授权永远是未知）
 */
object VendorLiveCardSupport {

    private const val TAG = "ReminderVendor"

    fun flymeLiveEnabled(context: Context): Boolean {
        if (context.checkSelfPermission("flyme.permission.READ_NOTIFICATION_LIVE_STATE")
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 与 provider 探测失败区分开：这里是 manifest 未声明/系统未授予，
            // 不打异常栈但留一条 warning，便于真机排查「胶囊从不出现」
            Log.w(TAG, "flyme.permission.READ_NOTIFICATION_LIVE_STATE not granted; live path closed")
            return false
        }
        return try {
            val uri = Uri.parse("content://com.android.systemui.notification.provider")
            val call: Bundle? = context.contentResolver.call(
                uri, "isNotificationLiveEnabled", null, null as Bundle?
            )
            call?.getBoolean("result", false) ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "isNotificationLiveEnabled probe failed", t)
            false
        }
    }

    fun flymeVersion(): Int {
        val display = Build.DISPLAY ?: return -1
        // Build.DISPLAY 有 "Flyme 11.2.0" / "FlymeOS 11" / 旧版小写 "flyme 10"，大小写不敏感
        val match = Regex("(?i)flyme\\s*([0-9]+)").find(display) ?: return -1
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: -1
    }

    fun samsungNowBarFeature(context: Context): Boolean = try {
        context.packageManager.hasSystemFeature("com.samsung.feature.nowbar")
    } catch (_: Throwable) {
        false
    }

    @SuppressLint("BlockedPrivateApi")
    fun vivoRegisterSceneList(context: Context, scenes: List<String>): Boolean {
        return try {
            val method = Class.forName("android.app.NotificationManager")
                .getMethod(
                    "setSuperXInfosSceneList",
                    MutableList::class.java,
                    MutableList::class.java,
                    MutableList::class.java,
                    MutableList::class.java,
                )
            val enabled = MutableList(scenes.size) { "true" }
            val allowed = MutableList(scenes.size) { "true" }
            val packages = MutableList(scenes.size) { context.packageName }
            method.invoke(
                context.getSystemService("notification"),
                scenes, enabled, packages, allowed,
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setSuperXInfosSceneList probe failed", t)
            false
        }
    }
}
