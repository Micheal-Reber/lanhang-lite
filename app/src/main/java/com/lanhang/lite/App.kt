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
        // 它的优先级高于我们 wrap 的语言，所以这里清一次，避免「跟随系统」被系统记录盖掉。
        // 注意：不能拿 AppCompatDelegate.getApplicationLocales() 当判据 —— 它在部分机型/进程
        // 状态下返回的是「本进程请求过的语言」而不是系统里真实记录的值，判断会漏清（实测残留 [en]，
        // 未包 LocaleHelper.wrap 的页面就整页英文）。33+ 直接读框架 LocaleManager。
        clearLeftoverAppLocale()
        // 语言必须在任何 Activity 创建前 prime（见 LocaleHelper：不用系统的 per-app locale）
        AppLanguage.apply(Session.language)
        applyTheme()
        AppContext.init(this)
        ScheduleRepository.init(this)
        com.lanhang.lite.schedule.LocalCourseStore.init(this)
        GradesRepository.init(this)
        // 校园网账号（密码用 Keystore 加密存）——只读 SP，不走网络
        com.lanhang.lite.campusnet.CampusNetStore.init(this)
        // 工作圈互动消息的本地状态（已读集合 / 已通知基线）：只读 SP
        com.lanhang.lite.moments.MomentMessageStore.init(this)
        // 工作圈关键词过滤规则 / 搜索历史：只读 SP，首次运行会写入默认档位
        com.lanhang.lite.moments.MomentsFilterStore.init(this)
        // 工作圈「不看某个人的动态」名单：只读 SP
        com.lanhang.lite.moments.BlockedUserStore.init(this)
        com.lanhang.lite.moments.MomentSearchStore.init(this)
        // 桌面小组件的状态可能已经过时（比如在别的设备/网页上把网线下了），
        // App 启动时同步一次（内部走协程查网络，不阻塞启动）
        com.lanhang.lite.widget.CampusNetWidgetProvider.refreshAll(this)

        // 上课提醒：先建通知渠道（提前建，用户能在系统设置里更早看到这个分类），
        // 再重排一次闹钟。重排要读整学期课表缓存（几十到几百 KB）+ 排若干个闹钟，
        // 单独线程做，不占冷启动的主线程。
        // 这一步不能省：闹钟会因为「清数据 / 系统回收 / 未知原因」丢失，每次启动补一次最省心。
        com.lanhang.lite.schedule.ClassReminderStore.init(this)
        com.lanhang.lite.schedule.ClassReminderNotify.ensureChannel(this)
        Thread({
            // 失败必须留痕：rearm 一旦抛异常而没人看见，就是「提醒静默消失」的典型现场
            runCatching { com.lanhang.lite.schedule.ClassReminder.rearm(applicationContext) }
                .onFailure { android.util.Log.w("ClassReminder", "startup rearm failed", it) }
        }, "class-reminder-rearm").start()
    }

    /**
     * 清掉旧版本写进系统的 per-app locale（AppCompat `setApplicationLocales` 的残留）。
     *
     * 残留值会盖过默认资源：系统是中文、App 内也选中文时，没包 [com.lanhang.lite.core.LocaleHelper.wrap]
     * 的页面仍会按系统记录的 `en` 解析资源。33+ 用框架 [android.app.LocaleManager] 读写才是
     * 系统里真实生效的那份；低版本才回退 AppCompat 自己的存储。
     */
    private fun clearLeftoverAppLocale() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val lm = getSystemService(android.app.LocaleManager::class.java) ?: return
            if (!lm.applicationLocales.isEmpty) {
                lm.applicationLocales = android.os.LocaleList.getEmptyLocaleList()
            }
        } else if (!androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            )
        }
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
