package com.lanhang.lite.moments

import com.lanhang.lite.session.Session

/**
 * 互动消息的「与我有关」判定。
 *
 * 服务端的 `/message/history`、`/message/unread` **不只回我自己的动态**：
 * 只要我赞过（或评论过）某条动态，那条动态后续收到的赞和评论也会作为互动记录推给我，
 * 相当于「关注了这条动态」。真机实测 20 条消息里 15 条属于这种情况 —— 全是别人的动态。
 *
 * 用户要的只有两种，其余一律不提醒：
 * 1. 别人赞 / 评论了**我发的动态**（动态作者是我）
 * 2. 别人回复了**我的评论**（`reply_to` 是被回复者的用户 id，不是评论 id）
 *
 * 判断依据是动态作者，而不是「我有没有赞过它」：
 * 我赞过但作者不是我 → 不提醒；作者是我但我没赞过（自己发的动态通常自己会赞）→ 照样提醒。
 */
internal object MomentMessageFilter {

    /**
     * 过滤出一批「该提醒我」的消息。
     *
     * @param owners 动态 id → 动态作者 id。传 `null` 表示这批动态的作者没取到
     *               （网络失败 / 会话过期），此时**原样返回不过滤**：
     *               宁可多显示几条，也不要在网络抖动时把整屏清空。
     *               动态作者取到了但对某条消息查不到（动态已删/不可见）则按「与我无关」丢掉。
     * @param me 当前登录用户；为空（未登录）时返回空列表，不给空账号算账
     */
    fun keep(
        messages: List<InteractionMessage>,
        owners: Map<String, String>?,
        me: String = Session.userId
    ): List<InteractionMessage> {
        if (messages.isEmpty()) return emptyList()
        if (me.isBlank()) return emptyList()
        if (owners == null) return messages
        return messages.filter { isAboutMe(it, owners, me) }
    }

    private fun isAboutMe(
        message: InteractionMessage,
        owners: Map<String, String>,
        me: String
    ): Boolean {
        // 自己的点赞/评论不提醒自己（正常服务端不会推，防一手）
        if (message.user_id == me) return false
        // 屏蔽的人一律不提醒 —— 「一起隐藏」的口径：动态、评论、赞、互动消息都算
        if (BlockedUserStore.isBlocked(message.user_id)) return false
        // 我的动态：别人赞或评论都算
        val feedId = message.feed_id?.takeIf { it.isNotBlank() } ?: return false
        if (owners[feedId] == me) return true
        // 别人的动态：只有「回复我的评论」才算。
        // reply_to 是用户 id（发评论时传的就是被回复者 userId），不是 comment_id。
        return message.isComment && message.reply_to == me
    }
}
