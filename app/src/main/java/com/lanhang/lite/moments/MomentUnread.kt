package com.lanhang.lite.moments

import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 未读互动数的**唯一**计算口径，顶栏铃铛的红点用它。
 *
 * 不能直接用 `/message/unread` 的条数：服务端把「我赞过的别人的动态」上的互动
 * 也算成未读（见 [MomentMessageFilter]），那样红点会被噪音点亮，跟列表内容对不上。
 * 这里的口径 = 服务端未读 ∩ 与我有关 ∩ 本地没标过已读。
 */
internal object MomentUnread {

    /**
     * 未读数；**取不到就返回 `null`**（网络失败、会话过期、动态作者查不到）。
     *
     * 返回 null 而不是 0：调用方据此「本轮不更新红点」，
     * 否则一次网络抖动就会把红点抹掉，看着像消息被自己读完了。
     */
    suspend fun count(): Int? {
        if (Session.userId.isBlank()) return null
        return try {
            withContext(Dispatchers.IO) {
                val unread = MomentsApi.getUnreadMessages()
                if (unread.isEmpty()) return@withContext 0
                // 判相关性要动态作者；取不到就不给结论，别拿噪音点亮红点
                val feeds = feedsOf(unread) ?: return@withContext null
                val read = MomentMessageStore.readIds()
                MomentMessageFilter.keep(unread, feeds.mapValues { it.value.user_id.orEmpty() })
                    .count { message -> message.message_id?.let { it !in read } != false }
            }
        } catch (e: Exception) {
            if (e.isCancellation) throw e
            null
        }
    }

    /**
     * 批量取这批消息关联的动态（作者、正文摘要都要用）。
     *
     * 失败返回 `null`（与「取到了但这几个 feed 不存在」区分开：
     * 后者返回空 Map，意味着这批动态已删/不可见，按「与我无关」处理）。
     */
    suspend fun feedsOf(messages: List<InteractionMessage>): Map<String, MomentFeed>? {
        val ids = messages.mapNotNull { it.feed_id?.takeIf(String::isNotBlank) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            withContext(Dispatchers.IO) { MomentsApi.getFeeds(ids) }
                .mapNotNull { feed ->
                    val id = feed.feed_id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    id to feed
                }
                .toMap()
        } catch (e: Exception) {
            if (e.isCancellation) throw e
            null
        }
    }
}
