package com.lanhang.lite.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自动检查（对应 Hoshi 的 `UpdateScheduler`）。
 *
 * **已知差异**：Hoshi 用 WorkManager 跑「1 天一次的周期任务 + 一次性的立即检查」。
 * 本项目没有引入 work-runtime（+300KB 且要写一堆 keep 规则），改成
 * **App 启动时检查一次，24 小时内不重复**：
 * - 打开 App 就会查，和它「进前台立即检查」等效
 * - 不做后台定时：用户不开 App 时也就看不到更新提示，多一个常驻闹钟只为了这个不划算
 *
 * 检查结果只写本地记录（[UpdateStore]），**不发系统通知**——Hoshi 也只是应用内提示
 * （`UpdatePromptEvents` 是 StateFlow，进程不在就丢了），这里行为一致：
 * 下次打开「设置 → 关于」时读记录，副标题直接显示「发现新版本」。
 */
object UpdateScheduler {

    fun checkOnStart(context: Context) {
        val app = context.applicationContext
        // debug 包不查：查到了也装不上（签名不同），只会让人困惑
        if (UpdateChecker.isDebugBuild(app)) return
        if (!UpdateStore.shouldAutoCheck(app)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            when (UpdateChecker.check(app, ignoreSkipped = false)) {
                is UpdateCheck.Available, is UpdateCheck.UpToDate, is UpdateCheck.Skipped ->
                    UpdateStore.markChecked(app)
                // 失败不记时间：下次启动立刻再试一次（失败通常是网络暂时不可用）
                else -> Unit
            }
        }
    }
}
