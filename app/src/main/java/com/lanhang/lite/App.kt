package com.lanhang.lite

import android.app.Application
import com.lanhang.lite.grades.GradesRepository
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.net.MiniappApi
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.Session

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        MiniappApi.init(this)
        MomentsApi.init(this)
        Session.init(this)
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
