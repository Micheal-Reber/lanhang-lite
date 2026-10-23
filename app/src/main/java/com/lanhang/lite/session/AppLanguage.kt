package com.lanhang.lite.session

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.lanhang.lite.R

/**
 * 界面语言：跟随系统 / 简体中文 / English。
 *
 * 用 AppCompat 的 per-app language（`setApplicationLocales`）实现：
 * Android 13+ 交给系统的应用语言设置，12 及以下由 AppCompat 在进程内切换。
 * 由于低版本系统不保存该选择，偏好一律存在 [Session.language]，每次启动重新应用。
 */
object AppLanguage {

    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    /** 设置页展示顺序 */
    val options = listOf(SYSTEM, ZH, EN)

    fun labelRes(mode: String): Int = when (mode) {
        ZH -> R.string.settings_language_zh
        EN -> R.string.settings_language_en
        else -> R.string.settings_language_system
    }

    /** 把偏好应用到全局；与当前生效值一致时不做任何事（避免多余重建）。 */
    fun apply(mode: String) {
        val target = when (mode) {
            ZH -> LocaleListCompat.forLanguageTags("zh-CN")
            EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != target.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(target)
        }
    }
}
