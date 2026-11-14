package com.lanhang.lite.session

import android.content.Context
import com.lanhang.lite.directory.DirectoryRepository
import com.lanhang.lite.moments.MomentMessageStore
import com.lanhang.lite.moments.MomentMessageWatcher
import com.lanhang.lite.moments.MomentNotify
import com.lanhang.lite.moments.MomentsCache
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.oa.OaCache
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.schedule.LocalCourseStore
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.grades.GradesRepository
import com.lanhang.lite.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 退出登录的本地清理（2026-10-27 从 `MainActivity.performLogout` 抽出来）。
 *
 * 抽出来的原因：入口从顶栏菜单挪到了「设置 → 账号卡下方」，逻辑留在 Activity 里会被复制一份到
 * Fragment；而这段清理**漏一项就会串号**（下一个人登录后看到上一个人的头像缓存、未读红点、
 * 桌面小组件还是旧课表），必须只有一处实现。
 *
 * 只做本地清理，不负责跳转：调用方清完再决定怎么回登录页（设置页要顺带 finish 宿主 Activity）。
 */
object Logout {

    fun clear(context: Context) {
        // 全局态用 applicationContext：调用方是 Fragment 时也安全，不会捕获已销毁的 Activity
        val app = context.applicationContext

        Session.clear()
        ApiClient.clearCookies()
        // 融云侧也要断：只清本地 token 的话，服务端会话仍然保活，下次登录会复用旧身份
        MomentsApi.logout()

        // 互动消息的本地状态（已读、已通知基线）跟着账号走：换人登录后必须重来，
        // 否则旧账号的 message_id 会让新账号的消息被误判成「已通知 / 已读」
        MomentMessageWatcher.cancel(app)
        MomentNotify.cancel(app)
        MomentMessageStore.clear()

        // 课程提醒是「按课表排好的系统闹钟」，会话没了就必须撤掉：
        // 否则退出登录后旧账号的课仍会每天按时弹通知（闹钟不知道账号换了），
        // 上课自动勿扰还会继续抢系统勿扰开关。偏好（开关/时间）保留，
        // 新账号登录后 ScheduleRepository.refresh 会自动重新排一遍。
        com.lanhang.lite.reminder.ReminderScheduler.cancelAllAsync()
        com.lanhang.lite.reminder.ClassDndScheduler(app).disableAndRestore()
        com.lanhang.lite.reminder.FluidCloudService.requestStop(app)
        com.lanhang.lite.reminder.ReminderNotify.cancelAll(app)

        // 课表、成绩、工作圈全是按账号缓存的，留着就是串号。
        //
        // 🔴 2026-11-14 修正：这些缓存**全在 filesDir**（schedule_cache.json / schedule_w*.json /
        // grades_*.json / local_courses.json / oa_apps.json / 工作圈快照 / directory/），
        // 而这里以前只删 `cacheDir`（那里面只有上传用的临时图）—— 等于什么都没清。
        // 换个人登录会先看到上一个人的课表、成绩、动态和常用应用顺序。
        // 这几个 clear 一直写好了却没人调用，就是「函数无引用」的由来。
        runCatching { ScheduleRepository.clearCache() }
        runCatching { GradesRepository.clearCache() }
        runCatching { LocalCourseStore.clear() }
        runCatching { OaCache.clear() }
        runCatching { MomentsCache.clear() }
        // 通讯录缓存是 suspend 的（IO 线程逐个删文件），低频操作，起个后台任务即可
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { DirectoryRepository.clear() }
        // 上传用的临时图确实在 cacheDir
        app.cacheDir.deleteRecursively()

        // 网页里的登录态也算账号数据（2026-11-14 加「赣青二课」后尤其重要）：
        // 校外站点的会话 Cookie 被 WebLoginStore 抄进了 SP，WebView 自己也留着一份。
        // 不清的话，换个人登录后点开二课还是上一个人的账号。
        com.lanhang.lite.ui.WebLoginStore.clear(app)
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }

        // 桌面小组件还不知道账号换了，立刻按空会话重画一次
        ScheduleWidgetUpdater.updateAll(app)
    }
}
