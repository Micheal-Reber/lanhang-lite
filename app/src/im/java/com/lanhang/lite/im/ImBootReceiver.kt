package com.lanhang.lite.im

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 覆盖升级后把私信后台接收拉起来（IM 版专属）。
 *
 * 服务本身不跨重启：不重起的话，重启手机后直到用户手动打开 App 都收不到私信。
 * `MY_PACKAGE_REPLACED` 覆盖「升级安装后进程已死」的场景 —— 上课提醒那条链路的
 * 教训（漏了它就每次发版全员静默失效）在这里同样成立。
 *
 * `BOOT_COMPLETED` 是系统允许起前台服务的白名单场景之一（`specialUse` 类型在内）。
 * 未登录或用户关了开关时 [ImKeepAliveService.sync] 自己会选择不启动。
 *
 * exported=false：只收系统广播，不与其它应用交互（与 reminder.BootReceiver 同口径）。
 */
class ImBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        ImKeepAliveService.sync(context)
    }
}
