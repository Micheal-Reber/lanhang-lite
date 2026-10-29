package com.lanhang.lite.moments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 互动检查闹钟的接收器（[MomentMessageWatcher.ACTION_TICK]），同时顺带收开机广播。
 *
 * 开机也要排一次：闹钟不会被系统持久化，重启后如果不重新 arm，
 * 用户不开 App 就再也收不到提醒。
 *
 * 网络请求是挂起的，广播里不能直接等，所以用 `goAsync()` 把广播的生命周期延长到协程结束
 * （上限约 10 秒；这里的请求是本地校园网毫秒级返回，够用）。无论成败都要 `finish()`，
 * 漏掉会让系统一直把这次广播算作未完成。
 */
class MomentMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != MomentMessageWatcher.ACTION_TICK && action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                MomentMessageWatcher.checkAndNotify(app)
            } catch (e: Throwable) {
                // 后台任务没有界面可提示，只留日志（会话过期、断网都会走到这里）
                Log.w("MomentMessageReceiver", "check failed action=$action", e)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
