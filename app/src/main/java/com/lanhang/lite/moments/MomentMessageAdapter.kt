package com.lanhang.lite.moments

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lanhang.lite.R
import com.lanhang.lite.ui.AvatarView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * 一条互动消息的视图模型。
 *
 * [excerpt]（关联动态的正文摘要）和 [unread] 都是**本地拼出来的**：
 * 摘要来自 `POST /feed/batch`，已读来自 [MomentMessageStore] 的本地集合，
 * 服务端的互动记录里只有「谁、对哪条动态、什么类型、什么时候」。
 */
class MomentMessageItem(
    val message: InteractionMessage,
    var excerpt: String = "",
    var unread: Boolean = false
) {
    val messageId: String get() = message.message_id.orEmpty()
    val feedId: String get() = message.feed_id.orEmpty()
}

/**
 * 互动消息列表适配器。
 *
 * 点击整行进详情、长按切换已读、点头像/名字进个人主页（与工作圈列表的点击口径一致：
 * 只有头像和名字可点，不把整行做成「点人就跳」）。
 */
class MomentMessageAdapter(
    /** 点某条消息 → 标记已读并进对应动态详情 */
    private val onOpen: (MomentMessageItem) -> Unit,
    /** 长按 → 「标为已读 / 标为未读」 */
    private val onToggleRead: (MomentMessageItem) -> Unit,
    /** 点头像/名字 → 个人主页 */
    private val onUser: (String) -> Unit
) : RecyclerView.Adapter<MomentMessageAdapter.VH>() {

    private val items = mutableListOf<MomentMessageItem>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<MomentMessageItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun indexOf(messageId: String): Int = items.indexOfFirst { it.messageId == messageId }

    fun replaceAt(index: Int, item: MomentMessageItem) {
        if (index < 0 || index >= items.size) return
        items[index] = item
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_moment_message, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: AvatarView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvAction: TextView = itemView.findViewById(R.id.tvAction)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvComment: TextView = itemView.findViewById(R.id.tvComment)
        private val tvExcerpt: TextView = itemView.findViewById(R.id.tvExcerpt)
        private val dotUnread: View = itemView.findViewById(R.id.dotUnread)

        fun bind(item: MomentMessageItem) {
            val ctx = itemView.context
            val user = UserInfoCache.get(item.message.user_id)
            val name = user?.name?.takeIf { it.isNotBlank() }
            val label = name ?: ctx.getString(R.string.moments_anonymous)
            tvName.text = label
            // 头像取不到就留空（与工作圈列表同口径，不放占位圆）
            ivAvatar.bind(label, user?.bestPortrait, user?.altPortrait)
            tvAction.text = ctx.getString(
                if (item.message.isComment) R.string.moments_messages_action_comment
                else R.string.moments_messages_action_like
            )
            tvTime.text = relativeTime(ctx, item.message.create_dt ?: 0L)

            val comment = item.message.comment_content.orEmpty().trim()
            // 点赞没有正文，别留一条空行占位
            tvComment.text = comment
            tvComment.visibility = if (comment.isEmpty()) View.GONE else View.VISIBLE

            tvExcerpt.text = item.excerpt
            tvExcerpt.visibility = if (item.excerpt.isBlank()) View.GONE else View.VISIBLE

            dotUnread.visibility = if (item.unread) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onOpen(item) }
            itemView.setOnLongClickListener {
                onToggleRead(item)
                true
            }
            val userId = item.message.user_id
            if (!userId.isNullOrBlank()) {
                ivAvatar.setOnClickListener { onUser(userId) }
                tvName.setOnClickListener { onUser(userId) }
            } else {
                ivAvatar.setOnClickListener(null)
                tvName.setOnClickListener(null)
            }
        }
    }

    private companion object {
        /**
         * 相对时间：1 分钟内「刚刚」，1 小时内「N 分钟前」，当天「N 小时前」，
         * 昨天「昨天」，更早按**App 当前语言**显示月日。
         */
        fun relativeTime(context: Context, ms: Long): String {
            if (ms <= 0L) return ""
            val now = System.currentTimeMillis()
            val diff = now - ms
            return when {
                diff < DateUtils.MINUTE_IN_MILLIS ->
                    context.getString(R.string.moments_messages_just_now)
                diff < DateUtils.HOUR_IN_MILLIS ->
                    context.getString(
                        R.string.moments_messages_minutes_ago,
                        (diff / DateUtils.MINUTE_IN_MILLIS).toInt(),
                    )
                diff < DateUtils.DAY_IN_MILLIS ->
                    context.getString(
                        R.string.moments_messages_hours_ago,
                        (diff / DateUtils.HOUR_IN_MILLIS).toInt(),
                    )
                diff < DateUtils.DAY_IN_MILLIS * 2 ->
                    context.getString(R.string.moments_messages_yesterday)
                else -> dateText(context, ms, sameYear(ms, now))
            }
        }

        /**
         * 绝对日期（月日，去年及更早带年份）。
         *
         * ⚠️ **不要用 `DateUtils.formatDateTime`**：它按**系统语言**出日期，而本 App 的
         * 语言是 [com.lanhang.lite.core.LocaleHelper] 单独用 `createConfigurationContext`
         * 包的，两者可以不相等 —— 中文界面 + 英文系统时，日期会冒出 `Oct 25`，
         * 和「昨天」「3 分钟前」这些走字符串资源的相对时间混在一起。
         *
         * 语言一律取当前 Context 的 configuration（即 App 实际显示语言），
         * 骨架交给 `getBestDateTimePattern`，由系统给该语言正确的顺序与月名
         * （中文 `10月25日`，英文 `Oct 25`）。
         */
        fun dateText(context: Context, ms: Long, sameYear: Boolean): String {
            val locale = context.resources.configuration.locales[0]
            val skeleton = if (sameYear) "MMMd" else "yMMMd"
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
            return SimpleDateFormat(pattern, locale).format(Date(ms))
        }

        fun sameYear(ms: Long, now: Long): Boolean {
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val today = Calendar.getInstance().apply { timeInMillis = now }
            return then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        }
    }
}
