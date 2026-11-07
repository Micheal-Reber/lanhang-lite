package com.lanhang.lite.reminder

import android.content.Context

/**
 * 课程提醒偏好（照 Sleepy 的 `AppPrefs` 提醒段一一对应）。
 *
 * 键名沿用 `reminder_*` / `daily_reminder*` / `before_class_*` / `class_dnd_enabled`
 * 这套命名，方便对照阅读；**值语义不要改**，改了等于改了用户已经设过的开关。
 *
 * 默认值（与 Sleepy 完全一致）：
 *  - 总开关关（关着的时候其余档位仍保留，重新打开即恢复）
 *  - 每日提醒开、今日摘要开、明日预告关
 *  - 今日摘要 07:00、明日预告 22:00
 *  - 每节课前提醒关、提前 10 分钟、形态=横幅、胶囊主显示=上课地点
 *  - 上课自动勿扰关
 */
object ReminderPrefs {

    private const val FILE = "lanhang_reminder"

    const val MODE_BANNER = "banner"
    const val MODE_FLUID = "fluid"

    const val FIELD_NAME = "name"
    const val FIELD_TIME = "time"
    const val FIELD_ROOM = "room"
    const val FIELD_COUNTDOWN = "countdown"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- 总开关 ----

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean("reminder_master", false)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("reminder_master", value).apply()

    // ---- 每日提醒 ----

    fun isDailyEnabled(context: Context): Boolean =
        prefs(context).getBoolean("daily_reminder", true)

    fun setDailyEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("daily_reminder", value).apply()

    fun isTodayEnabled(context: Context): Boolean =
        prefs(context).getBoolean("today_reminder", true)

    fun setTodayEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("today_reminder", value).apply()

    fun isTomorrowEnabled(context: Context): Boolean =
        prefs(context).getBoolean("tomorrow_reminder", false)

    fun setTomorrowEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("tomorrow_reminder", value).apply()

    fun dailyTime(context: Context): String =
        prefs(context).getString("daily_reminder_time", "07:00") ?: "07:00"

    fun setDailyTime(context: Context, value: String) =
        prefs(context).edit().putString("daily_reminder_time", value).apply()

    fun tomorrowTime(context: Context): String =
        prefs(context).getString("tomorrow_reminder_time", "22:00") ?: "22:00"

    fun setTomorrowTime(context: Context, value: String) =
        prefs(context).edit().putString("tomorrow_reminder_time", value).apply()

    // ---- 每节课前提醒 ----

    fun isBeforeClassEnabled(context: Context): Boolean =
        prefs(context).getBoolean("before_class_enabled", false)

    fun setBeforeClassEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("before_class_enabled", value).apply()

    /** 提前分钟数；破损值（0、负数、超大）钳到 1..999，排闹钟时不会算出过去的时刻。 */
    fun beforeClassMinutes(context: Context): Int =
        prefs(context).getInt("before_class_minutes", 10).coerceIn(1, 999)

    fun setBeforeClassMinutes(context: Context, value: Int) =
        prefs(context).edit().putInt("before_class_minutes", value.coerceIn(1, 999)).apply()

    /** 提醒形态：横幅 / 流体云，二选一是唯一真值（旧版两个独立布尔会互相静默吞掉）。 */
    fun beforeClassMode(context: Context): String =
        prefs(context).getString("before_class_mode", MODE_BANNER) ?: MODE_BANNER

    fun setBeforeClassMode(context: Context, mode: String) =
        prefs(context).edit().putString("before_class_mode", mode).apply()

    fun isBeforeClassBannerEnabled(context: Context): Boolean =
        beforeClassMode(context) != MODE_FLUID

    fun isBeforeClassFluidEnabled(context: Context): Boolean =
        beforeClassMode(context) == MODE_FLUID

    /** 胶囊主显示内容：name / time / room / countdown，默认 room（与 Sleepy 一致）。 */
    fun fluidPrimary(context: Context): String =
        prefs(context).getString("before_class_fluid_primary", FIELD_ROOM) ?: FIELD_ROOM

    fun setFluidPrimary(context: Context, value: String) =
        prefs(context).edit().putString("before_class_fluid_primary", value).apply()

    // ---- 上课自动勿扰 ----

    fun isClassDndEnabled(context: Context): Boolean =
        prefs(context).getBoolean("class_dnd_enabled", false)

    fun setClassDndEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("class_dnd_enabled", value).apply()

    // ---- 闹钟账本（独立 SP：本机状态，不该跟着账号数据备份/迁移） ----

    private const val LEDGER_FILE = "lanhang_alarm_ledger"
    private const val KEY_ARMED = "before_class_armed_codes"

    fun loadArmedCodes(context: Context): Set<Int> =
        context.applicationContext.getSharedPreferences(LEDGER_FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_ARMED, null)
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    fun saveArmedCodes(context: Context, codes: Set<Int>) {
        context.applicationContext.getSharedPreferences(LEDGER_FILE, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ARMED, codes.map { it.toString() }.toSet())
            .apply()
    }

    /** 退出登录 / 关闭提醒时清账本：换人登录后旧账号的 requestCode 必须能被撤销。 */
    fun clearArmedCodes(context: Context) = saveArmedCodes(context, emptySet())
}
