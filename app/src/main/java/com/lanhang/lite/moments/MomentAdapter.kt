package com.lanhang.lite.moments

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
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
        private val tvComments: TextView = itemView.findViewById(R.id.tvComments)
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
            val user = UserInfoCache.get(item.feed.user_id)
            tvName.text = user?.name ?: "用户"
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
                tvComments.text = buildComments(item)
                commentsRow.visibility = View.VISIBLE
            } else {
                commentsRow.visibility = View.GONE
            }
            dividerMid.visibility =
                if (hasLikes && hasComments) View.VISIBLE else View.GONE

            tvLike.text = if (item.likedByMe) "已赞 ${item.likes.size}" else "赞 ${item.likes.size}"
            ivLike.setImageResource(if (item.likedByMe) R.drawable.ic_favorite else R.drawable.ic_favorite_outline)
            val tintRes = if (item.likedByMe) R.color.like_red else R.color.icon_muted
            ivLike.imageTintList = androidx.core.content.ContextCompat.getColorStateList(itemView.context, tintRes)
            tvComment.text = "评论 ${item.comments.size}"

            llLike.setOnClickListener { onLike(item) }
            llComment.setOnClickListener { onComment(item) }

            if (hasComments) {
                tvComments.movementMethod = LinkMovementMethod.getInstance()
                tvComments.highlightColor = Color.TRANSPARENT
                tvComments.setOnLongClickListener {
                    onCommentsLongPress(item)
                    true
                }
                commentsRow.setOnLongClickListener {
                    onCommentsLongPress(item)
                    true
                }
            } else {
                tvComments.movementMethod = null
            }
        }

        /** 官方样式：人名/回复双方染链接色且可点击回复，内容原色 */
        private fun buildComments(item: MomentItem): CharSequence {
            val link = androidx.core.content.ContextCompat.getColor(
                itemView.context, com.lanhang.lite.R.color.link
            )
            val sb = android.text.SpannableStringBuilder()
            val list = item.comments
            list.forEachIndexed { i, c ->
                val who = UserInfoCache.nameOf(c.user_id)
                val replyTo = c.reply_to?.takeIf { it.isNotEmpty() && it != c.user_id }
                fun appendName(s: String, targetUserId: String?) {
                    val st = sb.length
                    sb.append(s)
                    if (targetUserId.isNullOrEmpty()) {
                        sb.setSpan(
                            ForegroundColorSpan(link),
                            st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    } else {
                        sb.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) = onReply(item, targetUserId)
                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = link
                                ds.isUnderlineText = false
                            }
                        }, st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                if (replyTo != null) {
                    appendName(who, c.user_id)
                    sb.append("回复")
                    appendName(UserInfoCache.nameOf(replyTo), replyTo)
                    sb.append(": ")
                } else {
                    appendName(who, c.user_id)
                    sb.append(": ")
                }
                sb.append(c.content.orEmpty())
                if (i < list.lastIndex) sb.append("\n")
            }
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
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }
}
