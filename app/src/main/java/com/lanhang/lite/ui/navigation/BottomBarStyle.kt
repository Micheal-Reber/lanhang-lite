package com.lanhang.lite.ui.navigation

import android.content.Context
import com.lanhang.lite.R

/**
 * 底栏外观：液态玻璃 / 原生（Material3 BottomNavigationView）。
 *
 * 为什么存在这里而不是 `Session`：`session` 包在闭源的 `engine` 模块里，
 * 公开仓 clone 下来用的是 `engine.aar`，给它加字段会让那边的构建直接找不到符号。
 * 界面偏好只归 UI 层管，所以单独存一份。
 *
 * 默认玻璃：现有用户升级上来看到的应当和以前一模一样，新样式要自己选。
 */
enum class BottomBarStyle(val key: String, val labelRes: Int) {
    /** 液态玻璃浮层（Compose + backdrop），默认 */
    GLASS("glass", R.string.settings_bottom_bar_glass),

    /** 原生 Material3 底栏：贴底、全宽、实心 */
    NATIVE("native", R.string.settings_bottom_bar_native);

    companion object {
        /** 未知值（老版本残留 / 手改 prefs）一律回落到玻璃，不要抛异常 */
        fun of(key: String?): BottomBarStyle =
            entries.firstOrNull { it.key == key } ?: GLASS
    }
}

object BottomBarPrefs {

    private const val FILE = "bottom_bar"
    private const val KEY_STYLE = "style"

    fun current(context: Context): BottomBarStyle {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return BottomBarStyle.of(sp.getString(KEY_STYLE, null))
    }

    fun set(context: Context, style: BottomBarStyle) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STYLE, style.key)
            .apply()
    }
}

/**
 * 底栏相关尺寸的唯一出口。
 *
 * 主 Tab 页的内容底部留白要跟着底栏形态变：液态玻璃是浮起 64dp 的胶囊（留 112dp），
 * 原生底栏贴底 72dp（留 88dp）。**布局里不要再直接写 `@dimen/content_padding_bottom`**
 * —— 那是玻璃那一档，选了原生之后底部会多出 24dp 空档，而且切换形态时不会跟着变。
 */
object BottomBarDims {

    /** 主 Tab 页（有底栏）的内容底部留白，单位 px */
    fun contentBottomPadding(context: Context): Int {
        val dimen = if (BottomBarPrefs.current(context) == BottomBarStyle.NATIVE) {
            R.dimen.content_padding_bottom_native
        } else {
            R.dimen.content_padding_bottom
        }
        return context.resources.getDimensionPixelSize(dimen)
    }
}
