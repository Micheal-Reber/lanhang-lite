package com.lanhang.lite

import android.app.Application
import com.lanhang.lite.grades.GradesRepository
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.net.MiniappApi
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.AppLanguage
import com.lanhang.lite.session.Session

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        MiniappApi.init(this)
        MomentsApi.init(this)
        Session.init(this)
        // 旧版用 AppCompat 的 per-app language 会把语言写进系统设置，
        // 它的优先级高于我们 wrap 的语言，所以这里清一次，避免「跟随系统」被系统记录盖掉
        if (!androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            )
        }
        // 语言必须在任何 Activity 创建前 prime（见 LocaleHelper：不用系统的 per-app locale）
        AppLanguage.apply(Session.language)
        applyTheme()
        AppContext.init(this)
        ScheduleRepository.init(this)
        GradesRepository.init(this)
    }

    private fun applyTheme() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (Session.themeMode) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
