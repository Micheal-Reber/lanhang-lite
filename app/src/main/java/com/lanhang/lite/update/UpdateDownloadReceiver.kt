package com.lanhang.lite.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 下载完成回调（对应 Hoshi 的 `UpdateDownloadCompleteReceiver`）。
 *
 * 为什么还要它：关于页的轮询只在页面开着时跑。用户点了下载就切走 / 杀进程时，
 * 没有这个广播「已下载」状态就永远写不进去，回来还显示「正在下载…」。
 *
 * `exported="false"`：这是系统发给**发起下载的那个应用**的显式广播，不需要对外暴露。
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0L) return
        val app = context.applicationContext
        // goAsync：onReceive 只有 10s，校验要读整个 apk（几 MB 的 sha256 也够呛在主线程做）
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                UpdateDownloadManager.handleCompletion(app, id)
            } finally {
                pending.finish()
            }
        }
    }
}
