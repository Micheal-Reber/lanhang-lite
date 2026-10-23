package com.lanhang.lite.moments

import android.annotation.SuppressLint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.gson.JsonParser
import com.lanhang.lite.R
import com.lanhang.lite.session.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 一条动态的可变视图模型（点赞/评论本地翻转） */
class MomentItem(val feed: MomentFeed) {
    val content: FeedContent by lazy {
        val raw = feed.content
        if (raw.isNullOrBlank()) FeedContent() else try {
            JsonParser.parseString(raw).asJsonObject.let {
                FeedContent(
                    text = it.get("text")?.takeIf { e -> !e.isJsonNull }?.asString,
                    media = it.get("media")?.takeIf { e -> !e.isJsonNull }?.asJsonArray?.mapNotNull { e ->
                        try {
                            com.lanhang.lite.net.ApiClient.gson.fromJson(e, MediaItem::class.java)
                        } catch (_: Exception) {
                            null
                        }
                    }
                )
            }
        } catch (_: Exception) {
            FeedContent()
        }
    }
    val likes = CopyOnWriteArrayList<LikeEntry>(feed.likes ?: emptyList())
    val comments = CopyOnWriteArrayList<CommentEntry>(feed.comments ?: emptyList())

    val likedByMe: Boolean get() = likes.any { it.user_id == Session.userId }
}

class MomentAdapter(
    private val onLike: (MomentItem) -> Unit,
    private val onComment: (MomentItem) -> Unit,
    private val onImage: (List<String>, Int) -> Unit,
    private val onReply: (MomentItem, String) -> Unit,
    private val onCommentsLongPress: (MomentItem) -> Unit
) : RecyclerView.Adapter<MomentAdapter.VH>() {

    val items = mutableListOf<MomentItem>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<MomentItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<MomentItem>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun indexOf(feedId: String?): Int = items.indexOfFirst { it.feed.feed_id == feedId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_moment, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvSub: TextView = itemView.findViewById(R.id.tvSub)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvText: TextView = itemView.findViewById(R.id.tvText)
        private val rvImages: RecyclerView = itemView.findViewById(R.id.rvImages)
        private val tvLikes: TextView = itemView.findViewById(R.id.tvLikes)
        private val llComments: LinearLayout = itemView.findViewById(R.id.llComments)
        private val llInteract: View = itemView.findViewById(R.id.llInteract)
        private val likesRow: View = itemView.findViewById(R.id.likesRow)
        private val dividerMid: View = itemView.findViewById(R.id.dividerMid)
        private val commentsRow: View = itemView.findViewById(R.id.commentsRow)
        private val llLike: LinearLayout = itemView.findViewById(R.id.llLike)
        private val llComment: LinearLayout = itemView.findViewById(R.id.llComment)
        private val ivLike: ImageView = itemView.findViewById(R.id.ivLike)
        private val tvLike: TextView = itemView.findViewById(R.id.tvLike)
        private val tvComment: TextView = itemView.findViewById(R.id.tvComment)

        fun bind(item: MomentItem) {
            val ctx = itemView.context
            val user = UserInfoCache.get(item.feed.user_id)
            tvName.text = user?.name ?: ctx.getString(R.string.moments_anonymous)
            val sub = listOfNotNull(user?.depart_name, user?.duty_name)
                .filter { it.isNotBlank() }.joinToString(" · ")
            tvSub.text = sub
            tvSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE

            ivAvatar.load(user?.portrait_url) {
                placeholder(R.drawable.bg_avatar_placeholder)
                error(R.drawable.bg_avatar_placeholder)
                transformations(CircleCropTransformation())
            }

            tvTime.text = item.feed.create_dt?.let { timeFormat.format(Date(it)) }.orEmpty()

            val text = item.content.text.orEmpty()
            tvText.text = text
            tvText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE

            bindImages(item.content.media)

            val hasLikes = item.likes.isNotEmpty()
            val hasComments = item.comments.isNotEmpty()
            llInteract.visibility = if (hasLikes || hasComments) View.VISIBLE else View.GONE

            if (hasLikes) {
                val names = item.likes.map { UserInfoCache.nameOf(it.user_id) }
                tvLikes.text = names.joinToString("，")
                likesRow.visibility = View.VISIBLE
            } else {
                likesRow.visibility = View.GONE
            }

            if (hasComments) {
                commentsRow.visibility = View.VISIBLE
                bindComments(item)
                commentsRow.setOnLongClickListener {
                    onCommentsLongPress(item)
                    true
                }
            } else {
                commentsRow.visibility = View.GONE
                llComments.removeAllViews()
            }
            dividerMid.visibility =
                if (hasLikes && hasComments) View.VISIBLE else View.GONE

            tvLike.text = if (item.likedByMe) {
                ctx.getString(R.string.moments_liked, item.likes.size)
            } else {
                ctx.getString(R.string.moments_like, item.likes.size)
            }
            ivLike.setImageResource(if (item.likedByMe) R.drawable.ic_favorite else R.drawable.ic_favorite_outline)
            val tintRes = if (item.likedByMe) R.color.like_red else R.color.icon_muted
            ivLike.imageTintList = androidx.core.content.ContextCompat.getColorStateList(itemView.context, tintRes)
            tvComment.text = ctx.getString(R.string.moments_comment_count, item.comments.size)

            llLike.setOnClickListener { onLike(item) }
            llComment.setOnClickListener { onComment(item) }
        }

        /**
         * 每条评论独占一行：整行（正文 + 行末空白）点击都回复该评论作者。
         * 旧实现把所有评论拼进一个 TextView，只有名字的 ClickableSpan 能点，点正文完全没反应。
         */
        private fun bindComments(item: MomentItem) {
            val ctx = itemView.context
            val link = androidx.core.content.ContextCompat.getColor(ctx, R.color.link)
            val list = item.comments
            // 复用已有行，避免每次 bind 都 inflate
            while (llComments.childCount > list.size) {
                llComments.removeViewAt(llComments.childCount - 1)
            }
            list.forEachIndexed { i, c ->
                val uid = c.user_id ?: return@forEachIndexed
                val row = if (i < llComments.childCount) {
                    llComments.getChildAt(i) as TextView
                } else {
                    (LayoutInflater.from(ctx)
                        .inflate(R.layout.item_moment_comment, llComments, false) as TextView)
                        .also { llComments.addView(it) }
                }
                row.text = buildCommentLine(c, link)
                row.setOnClickListener { onReply(item, uid) }
                // 行本身 clickable 会吞掉触摸流，长按必须挂在行上，否则原来的「长按删评论」会失效
                row.setOnLongClickListener {
                    onCommentsLongPress(item)
                    true
                }
            }
        }

        /** 「张三：内容」/「张三 回复 李四：内容」，人名染链接色；点击意图由整行监听统一处理 */
        private fun buildCommentLine(c: CommentEntry, link: Int): CharSequence {
            val sb = SpannableStringBuilder()
            fun appendName(name: String) {
                val st = sb.length
                sb.append(name)
                sb.setSpan(
                    ForegroundColorSpan(link), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            appendName(UserInfoCache.nameOf(c.user_id))
            val replyTo = c.reply_to?.takeIf { it.isNotEmpty() && it != c.user_id }
            if (replyTo != null) {
                sb.append(" ")
                sb.append(itemView.context.getString(R.string.moments_reply_inline))
                sb.append(" ")
                appendName(UserInfoCache.nameOf(replyTo))
            }
            sb.append(itemView.context.getString(R.string.moments_comment_separator))
            sb.append(c.content.orEmpty())
            return sb
        }

        private fun bindImages(media: List<MediaItem>?) {
            val list = media?.filter { !it.displayUrl.isNullOrBlank() }.orEmpty()
            if (list.isEmpty()) {
                rvImages.visibility = View.GONE
                return
            }
            rvImages.visibility = View.VISIBLE
            val cell = imageCellSize(itemView)
            val previewUrls = list.map { it.url?.takeIf { u -> u.isNotBlank() } ?: it.displayUrl.orEmpty() }
            rvImages.layoutManager = GridLayoutManager(itemView.context, 3)
            rvImages.adapter = ImageAdapter(list, cell) { index ->
                onImage(previewUrls, index)
            }
        }
    }

    private fun imageCellSize(parent: View): Int {
        val screenW = parent.resources.displayMetrics.widthPixels
        val cardMargin = (24 * parent.resources.displayMetrics.density).toInt()
        val cardPad = (28 * parent.resources.displayMetrics.density).toInt()
        val gap = (6 * parent.resources.displayMetrics.density).toInt()
        return (screenW - cardMargin - cardPad - gap * 2) / 3
    }

    private class ImageAdapter(
        private val items: List<MediaItem>,
        private val cell: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ImageAdapter.IVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IVH {
            val iv = ImageView(parent.context)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val lp = RecyclerView.LayoutParams(cell, cell)
            val margin = (3 * parent.resources.displayMetrics.density).toInt()
            lp.setMargins(margin, margin, margin, margin)
            iv.layoutParams = lp
            return IVH(iv)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: IVH, position: Int) {
            val item = items[position]
            holder.iv.load(item.displayUrl) {
                placeholder(R.drawable.bg_avatar_placeholder)
                error(R.drawable.bg_avatar_placeholder)
            }
            holder.iv.setOnClickListener {
                onClick(position)
            }
        }

        class IVH(val iv: ImageView) : RecyclerView.ViewHolder(iv)
    }

    companion object {
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
