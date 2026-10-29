package com.lanhang.lite.schedule

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 上课自动勿扰：上课那一刻把系统切到「仅闹钟」，下课原样恢复。
 *
 * ## 为什么只做「仅闹钟」一档
 *
 * 系统有三档（仅闹钟 / 优先 / 完全静音）。做选择器就要在设置页再开一层下钻，而这一页已经有
 * 四行开关了；课堂里最合理的行为就是「除了闹钟全静音」——完全静音会把闹钟也关掉，
 * 那是另一个功能（叫醒服务）的语义，不该混在课表里。
 *
 * ## 权限是硬门槛
 *
 * `setInterruptionFilter` 需要 `ACCESS_NOTIFICATION_POLICY`，**只能用户手动授予**
 * （系统「勿扰权限」页），代码弹不出来。没授权时：什么都不做、也不动用户设置 ——
 * 绝不假装成功（那会让用户以为「勿扰开了」，实际上是静音键救了他）。
 *
 * ## 原档位只在第一次进入时记一次
 *
 * 连堂课第一节课开始记「上课前的档位」，第二节课开始不会把「勿扰」当成原档位再记一遍；
 * 下课时按记下的那个值恢复。
 */
object ClassDnd {

    private const val TAG = "ClassDnd"

    private const val FILE = "lanhang_class_dnd"
    private const val KEY_PREV = "previous_filter"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun nm(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /** 用户有没有授予「勿扰权限」 */
    fun canControl(context: Context): Boolean =
        nm(context)?.isNotificationPolicyAccessGranted == true

    /** 唯一能让用户开这个权限的入口（系统设置里的访问权限页） */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open dnd access settings failed", it) }
    }

    /** 上课：切到「仅闹钟」。返回是否真的生效 */
    fun apply(context: Context): Boolean {
        val manager = nm(context) ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false
        return runCatching {
            val prefs = sp(context)
            if (!prefs.contains(KEY_PREV)) {
                // 只在第一次进入勿扰时记原档位，连堂第二节课不会覆盖成「勿扰」
                prefs.edit().putInt(KEY_PREV, manager.currentInterruptionFilter).apply()
            }
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            Log.d(TAG, "dnd applied")
            true
        }.getOrElse {
            Log.w(TAG, "dnd apply failed: ${it.message}")
            false
        }
    }

    /** 下课：恢复上课前的档位（没记过就什么都不做） */
    fun restore(context: Context) {
        val manager = nm(context) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val prefs = sp(context)
        if (!prefs.contains(KEY_PREV)) return
        val previous = prefs.getInt(KEY_PREV, NotificationManager.INTERRUPTION_FILTER_ALL)
        runCatching { manager.setInterruptionFilter(previous) }
        prefs.edit().remove(KEY_PREV).apply()
        Log.d(TAG, "dnd restored to $previous")
    }
}
