package com.lanhang.lite.im

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.lanhang.lite.R

/**
 * 调试用：手动弹一条私信 / 好友申请通知，验证渠道 / 图标 / 点击去处，不用等真人发消息。
 *
 * 判「是不是 debug 包」用 `ApplicationInfo.FLAG_DEBUGGABLE`，因为本项目没开
 * `buildFeatures.buildConfig`（没有 `BuildConfig.DEBUG`）。release 包里收到广播直接返回。
 * 走法是自家 `exported=false` 的 receiver，shell 起不来，得用 run-as：
 *
 * ```
 * adb shell run-as com.lanhang.lite.im am broadcast --user 0 \
 *   -n com.lanhang.lite.im/com.lanhang.lite.im.ImDebugReceiver \
 *   -a com.lanhang.lite.im.DEBUG_NOTIFY
 *
 * # 好友申请通知（另一条渠道 / 人形图标 / 点进「新朋友」页）
 * adb shell run-as com.lanhang.lite.im am broadcast --user 0 \
 *   -n com.lanhang.lite.im/com.lanhang.lite.im.ImDebugReceiver \
 *   -a com.lanhang.lite.im.DEBUG_FRIEND_NOTIFY
 * ```
 */
class ImDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        when (intent.action) {
            ACTION_DEBUG_NOTIFY -> {
                ImNotify.ensureChannel(context)
                ImNotify.show(
                    context,
                    peerId = "debug_peer",
                    isGroup = false,
                    title = "测试同学",
                    body = "这是一条测试私信：用来确认通知渠道、图标底色和点击进入聊天页。",
                )
            }
            ACTION_DEBUG_FRIEND -> {
                // 好友申请通知走的是另一条渠道 / 图标 / 点击去处，得单独验一遍
                ImNotify.ensureChannel(context)
                ImNotify.showFriendRequest(
                    context,
                    context.getString(R.string.im_notify_friend_title),
                    context.getString(R.string.im_notify_friend_requested, "测试同学"),
                )
            }
            ACTION_DEBUG_DUMP -> {
                // 把某会话最近 60 条消息的关键字段打进 logcat：专门用来查
                // 「文件消息点开提示下载失败」这类「代码里没有可读日志」的问题
                val peer = intent.getStringExtra(EXTRA_PEER)?.takeIf { it.isNotBlank() } ?: return
                ImClient.dumpConversation(peer, intent.getBooleanExtra(EXTRA_GROUP, false))
            }
        }
    }

    companion object {
        const val ACTION_DEBUG_NOTIFY = "com.lanhang.lite.im.DEBUG_NOTIFY"

        /** `am broadcast -a com.lanhang.lite.im.DEBUG_FRIEND_NOTIFY` */
        const val ACTION_DEBUG_FRIEND = "com.lanhang.lite.im.DEBUG_FRIEND_NOTIFY"

        /**
         * 打印某会话最近 60 条消息的 uid / mediaUrl / localPath：
         * ```
         * adb shell run-as com.lanhang.lite.im am broadcast --user 0 \
         *   -n com.lanhang.lite.im/com.lanhang.lite.im.ImDebugReceiver \
         *   -a com.lanhang.lite.im.DEBUG_DUMP_CHAT --es peer <targetId>
         * ```
         */
        const val ACTION_DEBUG_DUMP = "com.lanhang.lite.im.DEBUG_DUMP_CHAT"

        const val EXTRA_PEER = "peer"

        const val EXTRA_GROUP = "group"
    }
}
