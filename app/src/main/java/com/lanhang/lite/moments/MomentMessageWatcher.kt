package com.lanhang.lite.moments

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作圈互动的**定时检查**（不是推送，见 [MomentNotify] 的口径说明）。
 *
 * 链路：`AlarmManager` 每 [INTERVAL_MS] 发一次 [ACTION_TICK] 广播 →
 * [MomentMessageReceiver]（`goAsync` + 协程）→ 本类的 [checkAndNotify] →
 * 查 `/message/unread`、和本地「已通知」集合比对 → 有新条目才弹通知 → 排下一次闹钟。
 *
 * **弹之前还要过一遍 [MomentMessageFilter]**：服务端把我赞过的别人的动态上的互动也推过来，
 * 那些不该弹。过滤要动态作者，所以这一步必须批量取一次 `/feed/batch`；
 * 取不到作者就整轮跳过（不记已通知），下一轮再判。
 *
 * 为什么用闹钟轮询而不是长连接：
 * 服务端只暴露普通的 HTTP 接口（没有 WebSocket / 推送注册），本项目也没有厂商推送通道；
 * 轮询是唯一能拿到「别人刚刚赞了你」的手段。15 分钟一次的代价可以接受，
 * 且用 `ELAPSED_REALTIME`（非 WAKEUP）——熄屏不唤醒设备，省电、也不需要精确闹钟权限。
 */
object MomentMessageWatcher {

    private const val TAG = "MomentMessageWatcher"

    /** 定时检查的广播 action（[MomentMessageReceiver] 收） */
    const val ACTION_TICK = "com.lanhang.lite.moments.MESSAGE_TICK"

    /** 闹钟的 requestCode（与小组件的 4500/4510 错开） */
    private const val TICK_REQUEST = 4610

    /**
     * 检查间隔。
     *
     * 15 分钟是 AlarmManager 非精确闹钟的实际粒度下限（再短系统也会攒批），
     * 而且互动提醒对时效的要求就是「过一会儿能知道」，不需要秒级。
     */
    private const val INTERVAL_MS = 15L * 60L * 1000L

    /** 排下一次检查；未登录直接取消闹钟，避免为空账号空转 */
    fun arm(context: Context) {
        val app = context.applicationContext
        if (Session.userId.isBlank()) {
            cancel(app)
            return
        }
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            manager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                tickPendingIntent(app),
            )
        }.onFailure { Log.w(TAG, "arm failed", it) }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { manager.cancel(tickPendingIntent(app)) }
    }

    private fun tickPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MomentMessageReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            TICK_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 查一次未读互动，有新条目就弹通知。**幂等**：同一条互动只会提醒一次。
     *
     * 失败一律静默（只写日志）：这是后台任务，没有界面可以弹 toast，
     * 网络抖动/会话过期时用户不需要知道「刚才那次检查失败了」。
     */
    suspend fun checkAndNotify(context: Context) {
        val app = context.applicationContext
        if (Session.userId.isBlank()) {
            cancel(app)
            return
        }
        val unread = try {
            withContext(Dispatchers.IO) { MomentsApi.getUnreadMessages() }
        } catch (e: Exception) {
            if (e.isCancellation) throw e
            Log.w(TAG, "check failed", e)
            arm(app)
            return
        }
        // 排下一次要在所有分支之前完成，否则中途 return 会让心跳断掉
        arm(app)
        val ids = unread.mapNotNull { it.message_id?.takeIf(String::isNotBlank) }
        if (ids.isEmpty()) return
        val store = MomentMessageStore
        if (!store.baselineSeeded) {
            // 首次检查只建基线：装上新版本时历史未读不该一股脑弹出来
            store.rememberNotified(ids)
            store.baselineSeeded = true
            return
        }
        val known = store.notifiedIds()
        val fresh = unread.filter { m -> m.message_id?.let { it !in known } == true }
        if (fresh.isEmpty()) {
            store.rememberNotified(ids)
            return
        }
        // 先判「与我有关」：服务端把我赞过的**别人的动态**上的互动也会推过来，
        // 那些不该弹通知。动态作者取不到就整轮跳过、也不记已通知 ——
        // 否则要么弹一堆无关的，要么把真正的新互动提前标成「提醒过了」。
        val feeds = MomentUnread.feedsOf(fresh)
        if (feeds == null) {
            Log.w(TAG, "feed owners unavailable, skip this round")
            return
        }
        val mine = MomentMessageFilter.keep(
            fresh,
            feeds.mapValues { it.value.user_id.orEmpty() },
        )
        // 无论是否真弹通知都记为已通知：没权限/正在看列表时也算「这条提醒已经处理过」；
        // 被过滤掉的那些同样记下，否则每轮都要重算一遍
        store.rememberNotified(ids)
        if (mine.isEmpty()) return
        if (MomentNotify.messagesVisible) return
        if (!MomentNotify.hasPermission(app)) return
        runCatching { notify(app, mine, feeds) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    /** 单条 → 明说是谁做了什么；多条 → 汇总一条，别连弹十几下 */
    private suspend fun notify(
        context: Context,
        fresh: List<InteractionMessage>,
        feeds: Map<String, MomentFeed>
    ) {
        if (fresh.size > 1) {
            val names = fresh.mapNotNull { it.user_id }.distinct().take(3)
            UserInfoCache.resolve(names)
            val text = LocaleHelper.wrap(context)
            val label = names.joinToString("、") { id ->
                UserInfoCache.nameOf(id) ?: text.getString(R.string.moments_anonymous)
            }
            MomentNotify.show(
                context = context,
                title = text.getString(R.string.moments_notify_multi_title, fresh.size),
                body = text.getString(R.string.moments_notify_multi_body, label),
                feedId = null,
            )
            return
        }
        val message = fresh.first()
        val text = LocaleHelper.wrap(context)
        UserInfoCache.resolve(listOfNotNull(message.user_id))
        val name = UserInfoCache.nameOf(message.user_id)
            ?: text.getString(R.string.moments_anonymous)
        val title = if (message.isComment) {
            text.getString(R.string.moments_notify_comment_title, name)
        } else {
            text.getString(R.string.moments_notify_like_title, name)
        }
        val body = if (message.isComment) {
            message.comment_content?.takeIf { it.isNotBlank() }
                ?: excerptOf(context, feeds[message.feed_id])
        } else {
            excerptOf(context, feeds[message.feed_id])
        }.orEmpty()
        MomentNotify.show(
            context = context,
            title = title,
            body = body.ifBlank { text.getString(R.string.moments_notify_body_fallback) },
            feedId = message.feed_id,
        )
    }

    /** 关联动态的正文摘要（动态已取不到就留空，比整条不发强） */
    private fun excerptOf(context: Context, feed: MomentFeed?): String? {
        feed ?: return null
        val item = MomentItem(feed)
        val text = item.content.text?.trim().orEmpty()
        val label = LocaleHelper.wrap(context)
        return when {
            text.isNotEmpty() -> text.take(60)
            item.content.media?.isNotEmpty() == true ->
                label.getString(R.string.moments_messages_excerpt_image)
            else -> label.getString(R.string.moments_messages_excerpt_empty)
        }
    }
}
