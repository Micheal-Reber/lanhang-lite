package com.lanhang.lite.session

import android.content.Context
import com.lanhang.lite.moments.MomentMessageStore
import com.lanhang.lite.moments.MomentMessageWatcher
import com.lanhang.lite.moments.MomentNotify
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.widget.ScheduleWidgetUpdater

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

        // 课表、成绩、工作圈全是按账号缓存的，留着就是串号
        app.cacheDir.deleteRecursively()
        // 上课提醒的闹钟也要撤：课表是按账号的，不撤会在下一个人登录前继续为上一个账号响
        com.lanhang.lite.schedule.ClassReminder.cancel(app)
        com.lanhang.lite.schedule.ClassReminderNotify.cancelAll(app)
        // 桌面小组件还不知道账号换了，立刻按空会话重画一次
        ScheduleWidgetUpdater.updateAll(app)
    }
}
