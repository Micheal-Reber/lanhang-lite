package com.lanhang.lite.im

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.lanhang.lite.R
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.ui.MainActivity

/**
 * 收到私信时的**系统通知**（IM 版专属）。
 *
 * 口径先说清（和 [com.lanhang.lite.moments.MomentNotify] 一样，别写成「推送」）：
 * 这是**长连接在线时**的本地通知，不是厂商推送通道。融云 SDK 在 `:ipc` 进程里维持连接，
 * App 进程活着（含切到后台）就能收到；**进程被系统清掉、或用户强行停止后收不到任何消息**，
 * 直到下次打开 App 重建连接。本项目没有接 MiPush 等厂商通道。
 *
 * 三条硬性约定：
 * - 渠道单独一条（[CHANNEL_ID]），用户可以在系统里只关私信不关上课提醒；
 * - 通知图标底色一律 `notification_accent`（品牌蓝），与上课提醒、工作圈互动统一；
 * - **一个会话一条通知**（id 由 peerId 派生，同会话后一条覆盖前一条），不按消息条数堆；
 *   正在看的那条会话不弹（由 [ImClient.visibleThreadKey] 判定）。
 */
object ImNotify {

    const val CHANNEL_ID = "im_message"

    private const val TAG = "LanhangIM"

    /** 通知 id 基数：不与上课提醒（5000+）、工作圈互动（4700）撞号 */
    private const val ID_BASE = 4800

    /** 打开 App 那个 PendingIntent 的固定请求码 */
    private const val RC_MAIN = 4890

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.im_notify_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.im_notify_channel_desc)
                enableVibration(true)
            }
        )
    }

    /** 权限判定统一委托 [NotifPermission]，与上课提醒、工作圈互动共用一份逻辑 */
    private fun hasPermission(context: Context): Boolean =
        NotifPermission.isGranted(context, CHANNEL_ID)

    /**
     * 弹一条私信通知。
     *
     * @param peerId 会话 id（单聊是对方 userId，群聊是群 id），用来做通知 id 与点击去处
     * @param title  通知标题（对方姓名 / 群名）
     * @param body   通知正文（消息内容；群聊时带上发送者）
     */
    fun show(
        context: Context,
        peerId: String,
        isGroup: Boolean,
        title: String,
        body: String,
    ) {
        if (peerId.isBlank()) return
        if (!hasPermission(context)) {
            Log.i(TAG, "skip notification: no permission/channel off")
            return
        }
        ensureChannel(context)
        val pending = contentIntent(context, peerId, isGroup, title) ?: return
        val text = body.ifBlank { context.getString(R.string.im_notify_title) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title.ifBlank { context.getString(R.string.im_notify_title) })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(idOf(peerId), notification)
        } catch (t: Throwable) {
            Log.w(TAG, "notify im failed", t)
        }
    }

    /** 进了某个会话就撤掉它的通知（内容此刻摆在眼前） */
    fun cancel(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        runCatching { NotificationManagerCompat.from(context).cancel(idOf(peerId)) }
    }

    /**
     * 通知 id：`peerId.hashCode()` 的低 11 位。
     *
     * 取低位而不是取模到几百：1024 个槽位里两个会话撞号的概率极低，撞了也只是
     * 「两条通知合并成一条」的观感问题，不会串会话（点击去处由 PendingIntent 决定，
     * 而 PendingIntent 的请求码用的是完整 hashCode，仍然各是各的）。
     */
    private fun idOf(peerId: String): Int = ID_BASE + (peerId.hashCode() and 0x7FF)

    /**
     * 点通知 → 直接进那个会话；返回栈里垫一个 MainActivity，
     * 否则从聊天页按返回会直接退回桌面（用户会以为「点通知把 App 顶掉了」）。
     */
    private fun contentIntent(
        context: Context,
        peerId: String,
        isGroup: Boolean,
        title: String,
    ): PendingIntent? {
        val chat = ImChatActivity.intentOf(context, peerId, isGroup, title)
        val stack = TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, MainActivity::class.java))
            .addNextIntent(chat)
        return runCatching {
            stack.getPendingIntent(
                RC_MAIN + (peerId.hashCode() and 0xFF),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }
}
