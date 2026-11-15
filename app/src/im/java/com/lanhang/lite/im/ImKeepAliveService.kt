package com.lanhang.lite.im

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lanhang.lite.session.Session

/**
 * 私信后台接收的载体（2026-11-15，用户反馈「不打开 App 就没有私信通知」）。
 *
 * 为什么需要它：本项目没有厂商推送通道（MiPush / HMS 都要学校服务端配证书，我们改不了），
 * 收消息的唯一路径是融云长连接 + 本地通知（见 [ImNotify] 的口径说明）。长连接挂在 App
 * 进程上，进程一被回收就断，之后谁发消息都不会有任何反应，直到用户下次手动打开 App。
 *
 * 这个服务做三件事：
 * 1. **前台服务保活**：进程优先级提到前台级别，普通清理不会把它当缓存进程回收；
 * 2. **被杀后由系统拉回**：`START_STICKY`，系统回收后会重新 `onStartCommand`，
 *    重新 `warmUp` 建连，不需要用户打开 App；
 * 3. **代价透明**：通知栏有一条低重要性常驻提示（[ImNotify.keepAliveNotification]），
 *    不响铃不弹横幅；不需要的用户在「私信列表 → 菜单 → 后台接收消息」关掉即可。
 *
 * 边界（别指望它解决所有情况）：用户在系统里「强行停止」、或 ROM 没给自启动权限时，
 * 任何代码都起不来 —— 这和上课提醒的 ROM 冻结是同一类系统限制，只能引导用户开权限。
 *
 * 类型沿用项目已有的 `specialUse`（与课前流体云一致），权限 `FOREGROUND_SERVICE` /
 * `FOREGROUND_SERVICE_SPECIAL_USE` 已在 main 清单声明，这里不再重复。
 */
class ImKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后系统只给几秒钟，必须先挂通知再做任何其它事
        try {
            startForeground(ImNotify.NOTIFY_KEEP_ALIVE, ImNotify.keepAliveNotification(this))
        } catch (t: Throwable) {
            // 极少数 ROM 会拒绝（比如通知被整体禁用后又限制 FGS）：别崩，降级成普通服务
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }
        ImClient.warmUp(this)
        return START_STICKY
    }

    companion object {
        private const val TAG = "LanhangIM"

        private const val PREF = "lanhang_im"
        private const val KEY_KEEP_ALIVE = "keep_alive"

        /** 默认开：装 IM 版就是为了收消息；不要的人有一条明确的开关 */
        fun isEnabled(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP_ALIVE, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
            sync(context)
        }

        /**
         * 按「开关 + 登录态」对账：该起就起，该停就停。
         *
         * 调用点：`ImBridge.warmUp`（每次进主界面）、开机/升级广播、开关切换、退出登录。
         * 重复调用是安全的：服务已运行时只是再走一次 `onStartCommand`，`warmUp` 本身幂等。
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            val loggedIn = Session.token.isNotBlank() || Session.imToken.isNotBlank()
            if (!loggedIn || !isEnabled(app)) {
                stop(app)
                return
            }
            try {
                ContextCompat.startForegroundService(app, Intent(app, ImKeepAliveService::class.java))
            } catch (t: Throwable) {
                // Android 12+ 不允许从后台随便起前台服务（BOOT_COMPLETED 等白名单场景除外）。
                // 发生在后台时直接放弃这一次：下次进前台 / 开机会再同步，不能让 App 崩掉
                Log.w(TAG, "start keep-alive failed: ${t.javaClass.simpleName}")
            }
        }

        /** 停服务并撤掉常驻提示（关开关 / 退出登录） */
        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, ImKeepAliveService::class.java)) }
            runCatching {
                NotificationManagerCompat.from(app).cancel(ImNotify.NOTIFY_KEEP_ALIVE)
            }
        }
    }
}
