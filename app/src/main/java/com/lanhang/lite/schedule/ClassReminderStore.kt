package com.lanhang.lite.schedule

import android.content.Context
import android.content.SharedPreferences
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper

/**
 * 上课提醒的用户设置。
 *
 * 只存**用户意图**（开不开、提前几分钟），不存「已排了哪些闹钟」——
 * 我们的 requestCode 是窗口内序号（见 [ClassReminder]），每次重排按码段整扫即可完成对账，
 * 再加一份账本反而多一个会和系统对不上的状态。
 */
object ClassReminderStore {

    /** 提前量选项（分钟）。0 = 上课那一刻提醒，只给「就在教学楼上课」的人用，所以不做默认。 */
    val ADVANCE_OPTIONS = listOf(0, 5, 10, 15, 20, 30)

    private const val DEFAULT_ADVANCE = 15

    private const val FILE = "lanhang_class_reminder"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 已删功能留下的键（超级岛 / 课中提醒 / 次日提醒）。留着不影响运行，但将来若同名功能
        // 加回来、默认值又不同，这些旧值会把它意外点亮 —— 一次清掉，且只在真有残留时写盘。
        val stale = listOf("island_enabled", "in_class_enabled", "next_day_enabled")
        val editor = sp.edit()
        var dirty = false
        for (key in stale) {
            if (sp.contains(key)) {
                editor.remove(key)
                dirty = true
            }
        }
        if (dirty) editor.apply()
    }

    /** 总开关。**始终反映用户意图**：没有通知权限时也保持打开（页面上给红条），
     *  不要「偷偷弹回去」—— 那会让人觉得「点了没反应」。 */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    /** 提前几分钟。破损值（被别的版本写坏 / 手工改过）回落到默认，不让界面显示一个不存在的选项。 */
    var advanceMinutes: Int
        get() {
            val raw = sp.getInt("advance_minutes", DEFAULT_ADVANCE)
            return if (raw in 0..60) raw else DEFAULT_ADVANCE
        }
        set(value) = sp.edit().putInt("advance_minutes", value.coerceIn(0, 60)).apply()

    /** 是否已经跳过一次精确闹钟授权页（避免每次进页面都把用户弹出去） */
    var exactAsked: Boolean
        get() = sp.getBoolean("exact_asked", false)
        set(value) = sp.edit().putBoolean("exact_asked", value).apply()

    /**
     * 上课自动勿扰（上课切「仅闹钟」、下课恢复原档位）。
     *
     * 需要 `ACCESS_NOTIFICATION_POLICY`（只能用户手动授予）。默认关 —— 这个权限一旦给了，
     * App 理论上能静音所有通知，不该替用户默认打开。
     */
    var autoDnd: Boolean
        get() = sp.getBoolean("auto_dnd", false)
        set(value) = sp.edit().putBoolean("auto_dnd", value).apply()

    // ------------------------------------------------------------ 摘要

    /** 设置页「工具」里那一行的副标题：`已开启 · 15 分钟前` / `已关闭` */
    fun summary(context: Context): String {
        val text = LocaleHelper.wrap(context)
        if (!enabled) return text.getString(R.string.class_reminder_summary_off)
        if (!ClassReminderNotify.canNotify(context)) {
            return text.getString(R.string.class_reminder_summary_blocked)
        }
        return text.getString(R.string.class_reminder_summary_on, advanceLabel(context))
    }

    /** `15 分钟前` / `上课时` */
    fun advanceLabel(context: Context): String {
        val text = LocaleHelper.wrap(context)
        val minutes = advanceMinutes
        return if (minutes <= 0) {
            text.getString(R.string.class_reminder_advance_zero)
        } else {
            text.getString(R.string.class_reminder_advance_value, minutes)
        }
    }
}
