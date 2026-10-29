package com.lanhang.lite.moments

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
 * 工作圈互动（谁赞了、谁评论了）的**系统通知**。
 *
 * 口径必须说清楚，别写成「推送服务」：
 * - 这是**本地通知**，不是推送通道。触发方是 [MomentMessageWatcher] 的定时检查
 *   （AlarmManager 每 15 分钟查一次 `/message/unread`）。App 被系统冻结/强制停止、
 *   或处于深度 Doze 时不会按时检查，也就不会有提醒——真推送需要厂商通道或常驻长连接，
 *   本项目两者都没有。
 * - Android 13+ 需要运行时授予 `POST_NOTIFICATIONS`，未授权时**静默跳过**（不反复骚扰）；
 *   用户在系统设置里关掉通知同理，靠 [hasPermission] 一并挡住。
 * - 同一时刻只保留一条通知（固定 id，后一条覆盖前一条），避免短时间内连点几十个赞就刷屏。
 */
object MomentNotify {

    const val CHANNEL_ID = "moment_interaction"

    private const val TAG = "MomentNotify"

    /** 通知的固定 id：同一条被覆盖，而不是越积越多 */
    private const val NOTIFY_ID = 4700

    /** 消息列表是否正被用户看着（由 [MomentMessagesActivity] 的 onResume/onPause 维护） */
    @Volatile
    var messagesVisible: Boolean = false

    /** App 启动时调一次即可；重复调用无副作用 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.moments_notify_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.moments_notify_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 能否弹通知：运行时权限、通知总开关、**渠道**三者都要通。
     *
     * 判定统一委托给 [NotifPermission]（上课提醒用的是同一份），避免两边判定走岔。
     * 渠道这一项是后加的：用户可以在系统里只关掉「工作圈互动」而保留别的通知，
     * 只看 `areNotificationsEnabled()` 会误判成有权限，然后通知静默消失。
     */
    fun hasPermission(context: Context): Boolean =
        NotifPermission.isGranted(context, CHANNEL_ID)

    /**
     * 弹一条互动提醒。
     *
     * @param feedId 关联的动态；为空时点开进消息列表（多条互动时用它）
     */
    fun show(
        context: Context,
        title: String,
        body: String,
        feedId: String?,
    ) {
        if (title.isBlank()) return
        if (!hasPermission(context)) {
            Log.w(TAG, "skip moment notification: hasPermission=false")
            return
        }
        ensureChannel(context)
        val pending = contentIntent(context, feedId) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            // 图标底色用品牌蓝，和上课提醒保持一致（同一款 App 两个通知两个色像是 bug）
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "notify moment failed", t)
        }
    }

    /** 进消息中心时清掉：提醒里的内容此刻就摆在眼前 */
    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFY_ID) }
    }

    /**
     * 点开通知的去处：
     * - 单条互动 → 对应动态详情；
     * - 多条互动 → 消息列表。
     *
     * 用 [TaskStackBuilder] 而不是裸 `getActivity`：详情页之上没有 MainActivity 的话，
     * 按返回会直接退回桌面，用户会以为「点通知把 App 顶掉了」。
     */
    private fun contentIntent(context: Context, feedId: String?): PendingIntent? {
        val target = if (feedId.isNullOrBlank()) {
            Intent(context, MomentMessagesActivity::class.java)
        } else {
            MomentDetailActivity.intent(context, feedId)
        }
        val stack = TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, MainActivity::class.java))
            .addNextIntent(target)
        return stack.getPendingIntent(
            if (feedId.isNullOrBlank()) 4711 else 4712,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
