package com.lanhang.lite.moments

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
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
import com.google.gson.JsonParser
import com.lanhang.lite.R
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.AvatarView
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
    private val onCommentsLongPress: (MomentItem) -> Unit,
    /** 点头像 / 昵称进个人主页；为 null 时不注册点击（比如不想跳转的场景） */
    private val onUser: ((String) -> Unit)? = null,
    /** 整行点击（搜索页点结果进详情）；null = 不注册，行为与工作圈列表一致 */
    private val onRowClick: ((MomentItem) -> Unit)? = null,
    /**
     * 删除自己发布的动态（二次确认、调接口、摘列表都由宿主处理）。
     * 为 null 时卡片右下角不显示「删除」，长按也不触发；非本人的动态一律不显示。
     */
    private val onDelete: ((MomentItem) -> Unit)? = null,
    /**
     * 删除一条自己的评论（本人评论正文下方的「删除」）。
     * 为 null 时不显示这个键，仍保留长按评论区 →「我的评论」的旧入口。
     */
    private val onCommentDelete: ((MomentItem, CommentEntry) -> Unit)? = null
) : RecyclerView.Adapter<MomentAdapter.VH>() {

    val items = mutableListOf<MomentItem>()

    /**
     * 整行点击改成「延迟执行」，给双击回顶让路。
     *
     * 手势层拿到双击时，第一次抬手早已触发过单击 —— 双击回顶就变成了「先打开详情页」。
     * 所以单击先挂 320ms（要长过 GestureDetector 的 300ms 双击窗口），
     * 真双击时由 [cancelPendingRowClick] 撤掉；只有单击才真的会执行。
     *
     * 没有整行点击的页面（工作圈主列表）完全不受影响。
     */
    private val rowClickHandler = Handler(Looper.getMainLooper())
    private var pendingRowClick: Runnable? = null

    /** 双击回顶会调用它，撤掉还没触发的那次单击 */
    fun cancelPendingRowClick() {
        pendingRowClick?.let { rowClickHandler.removeCallbacks(it) }
        pendingRowClick = null
    }

    /** 搜索命中的关键词：正文里命中的片段铺底色。空 = 不高亮 */
    var highlightWords: List<String> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * 条目标记文案：返回 null / 空串就不显示这枚灰标。
     *
     * 两个调用方各有各的说法：搜索页写「已被过滤规则命中」（解释为什么列表里看不到它），
     * 「查看过滤消息」页写命中的具体规则（命中「广告推广」/ 自定义词），方便用户判断是不是误伤。
     * 工作圈列表与详情页传 null —— 那边过滤规则已经生效，不需要额外标注。
     */
    var filterTagText: ((MomentItem) -> CharSequence?)? = null

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

    /**
     * 删除成功后从列表里摘掉一条（宿主已经调完接口）。
     *
     * @return true = 命中并已 `notifyItemRemoved`；false = 这条不在当前列表里
     * （比如分页/过滤把它分在另一份列表），宿主可据此决定是否还要处理。
     */
    fun remove(feedId: String): Boolean {
        val index = indexOf(feedId)
        if (index < 0) return false
        items.removeAt(index)
        notifyItemRemoved(index)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_moment, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        // 滚走的行不该再在后台把详情页打开
        cancelPendingRowClick()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: AvatarView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvSub: TextView = itemView.findViewById(R.id.tvSub)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvText: TextView = itemView.findViewById(R.id.tvText)
        private val tvFilterTag: TextView = itemView.findViewById(R.id.tvFilterTag)
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
        private val tvDelete: TextView = itemView.findViewById(R.id.tvDelete)

        fun bind(item: MomentItem) {
            val ctx = itemView.context
            val isMine = item.feed.user_id == Session.userId
            val user = UserInfoCache.get(item.feed.user_id)
            val name = user?.name?.takeIf { it.isNotBlank() }
            val label = name ?: ctx.getString(R.string.moments_anonymous)
            tvName.text = label
            val sub = listOfNotNull(user?.depart_name, user?.duty_name)
                .filter { it.isNotBlank() }.joinToString(" · ")
            tvSub.text = sub
            tvSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE

            // 取不到照片就留空，不退成姓名首字、也不放占位圆（2026-10-26 用户明确要求）。
            // 地址走 bestPortrait（大图优先），主地址失败时 AvatarView 会自动试 altPortrait
            ivAvatar.bind(label, user?.bestPortrait, user?.altPortrait)

            tvTime.text = item.feed.create_dt?.let { timeFormat.format(Date(it)) }.orEmpty()

            val text = item.content.text.orEmpty()
            // 搜索页高亮：命中的片段铺一层 accent 底色，文字颜色不动（深色模式也读得清）
            tvText.text = if (highlightWords.isEmpty() || text.isEmpty()) {
                text
            } else {
                MomentTextMatcher.highlight(text, highlightWords, accentColor())
            }
            tvText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE

            val filterTag = filterTagText?.invoke(item)
            tvFilterTag.visibility =
                if (filterTag.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (!filterTag.isNullOrEmpty()) tvFilterTag.text = filterTag

            bindImages(item.content.media)

            // 被屏蔽的人不该在点赞行和评论区冒出来：只藏他发的动态等于半遮半掩
            val blocked = BlockedUserStore.ids()
            val visibleLikes = if (blocked.isEmpty()) {
                item.likes
            } else {
                item.likes.filterNot { it.user_id?.let(blocked::contains) == true }
            }
            val visibleComments = if (blocked.isEmpty()) {
                item.comments
            } else {
                item.comments.filterNot { it.user_id?.let(blocked::contains) == true }
            }

            val hasLikes = visibleLikes.isNotEmpty()
            val hasComments = visibleComments.isNotEmpty()
            llInteract.visibility = if (hasLikes || hasComments) View.VISIBLE else View.GONE

            if (hasLikes) {
                val names = visibleLikes.map { like ->
                    UserInfoCache.nameOf(like.user_id) ?: UserInfoCache.fallbackName(ctx)
                }
                tvLikes.text = names.joinToString("，")
                likesRow.visibility = View.VISIBLE
            } else {
                likesRow.visibility = View.GONE
            }

            if (hasComments) {
                commentsRow.visibility = View.VISIBLE
                bindComments(item, visibleComments)
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

            // 数字与上面列出来的名字 / 评论条数保持一致，否则「写了 3 个名字、标着 4 个赞」
            tvLike.text = if (item.likedByMe) {
                ctx.getString(R.string.moments_liked, visibleLikes.size)
            } else {
                ctx.getString(R.string.moments_like, visibleLikes.size)
            }
            ivLike.setImageResource(if (item.likedByMe) R.drawable.ic_favorite else R.drawable.ic_favorite_outline)
            val tintRes = if (item.likedByMe) R.color.like_red else R.color.icon_muted
            ivLike.imageTintList = androidx.core.content.ContextCompat.getColorStateList(itemView.context, tintRes)
            tvComment.text = ctx.getString(R.string.moments_comment_count, visibleComments.size)

            llLike.setOnClickListener { onLike(item) }
            llComment.setOnClickListener { onComment(item) }

            // 删除自己的动态：右下角文字键 + 长按卡片，两条入口都交给宿主的二次确认。
            // 复用时必须显式清掉监听，否则滚到别人的帖子会继承上一条的删除行为。
            val canDelete = onDelete != null && isMine
            tvDelete.visibility = if (canDelete) View.VISIBLE else View.GONE
            tvDelete.setOnClickListener(
                if (canDelete) View.OnClickListener { onDelete.invoke(item) } else null
            )
            itemView.setOnLongClickListener(
                if (canDelete) View.OnLongClickListener {
                    onDelete.invoke(item)
                    true
                } else null
            )

            // 头像 / 昵称进个人主页（与官方 BaseFeedViewHolder 一致：只这两个视图可点，
            // tvSub 不注册，避免整行变成可点导致误触）。复用时必须显式清掉旧监听。
            val userId = item.feed.user_id
            if (onUser != null && !userId.isNullOrBlank()) {
                ivAvatar.setOnClickListener { onUser.invoke(userId) }
                tvName.setOnClickListener { onUser.invoke(userId) }
            } else {
                ivAvatar.setOnClickListener(null)
                tvName.setOnClickListener(null)
            }

            // 整行点击只在调用方要求时注册（搜索页）；复用时同样要清掉。
            // 点击延迟 320ms 执行：给双击回顶留出取消的机会（见 cancelPendingRowClick）
            val rowClick = onRowClick
            if (rowClick != null) {
                itemView.setOnClickListener {
                    cancelPendingRowClick()
                    val job = Runnable {
                        pendingRowClick = null
                        rowClick.invoke(item)
                    }
                    pendingRowClick = job
                    rowClickHandler.postDelayed(job, ROW_CLICK_DELAY_MS)
                }
            } else {
                itemView.setOnClickListener(null)
            }
        }

        /**
         * 高亮底色用的主色。
         *
         * 取 `@color/link` 而不是主题属性：`android.nonTransitiveRClass=true` 下 App 的 R 里
         * 没有依赖库声明的 attr（`R.attr.colorPrimary` 编译不过），而 link 本来就是这一屏的强调色。
         */
        private fun accentColor(): Int =
            androidx.core.content.ContextCompat.getColor(itemView.context, R.color.link)

        /**
         * 每条评论独占一行：整行（正文 + 行末空白）点击都回复该评论作者。
         * 旧实现把所有评论拼进一个 TextView，只有名字的 ClickableSpan 能点，点正文完全没反应。
         *
         * 本人评论在正文下方多一个「删除」：服务端只允许删自己的评论，
         * 别人的评论根本不显示这个键（不是显示了再报错）。
         */
        private fun bindComments(item: MomentItem, list: List<CommentEntry>) {
            val ctx = itemView.context
            val link = androidx.core.content.ContextCompat.getColor(ctx, R.color.link)
            // 复用已有行，避免每次 bind 都 inflate
            while (llComments.childCount > list.size) {
                llComments.removeViewAt(llComments.childCount - 1)
            }
            list.forEachIndexed { i, c ->
                val uid = c.user_id ?: return@forEachIndexed
                val row = if (i < llComments.childCount) {
                    llComments.getChildAt(i)
                } else {
                    LayoutInflater.from(ctx)
                        .inflate(R.layout.item_moment_comment, llComments, false)
                        .also { llComments.addView(it) }
                }
                val line = row.findViewById<TextView>(R.id.tvCommentLine)
                val delete = row.findViewById<TextView>(R.id.tvCommentDelete)
                line.text = buildCommentLine(c, link)

                row.setOnClickListener { onReply(item, uid) }
                // 行本身 clickable 会吞掉触摸流，长按必须挂在行上，否则原来的「长按删评论」会失效
                row.setOnLongClickListener {
                    onCommentsLongPress(item)
                    true
                }

                // 只有自己的评论才露「删除」；服务端生成的 comment_id 为空时删不了，也一并藏掉
                val canDelete = onCommentDelete != null &&
                    c.user_id == Session.userId &&
                    !c.comment_id.isNullOrEmpty()
                delete.visibility = if (canDelete) View.VISIBLE else View.GONE
                delete.setOnClickListener(
                    if (canDelete) View.OnClickListener {
                        onCommentDelete.invoke(item, c)
                    } else null
                )
            }
        }

        /** 「张三：内容」/「张三 回复 李四：内容」，人名染链接色；点击意图由整行监听统一处理 */
        private fun buildCommentLine(c: CommentEntry, link: Int): CharSequence {
            val sb = SpannableStringBuilder()
            fun appendName(id: String?) {
                val name = UserInfoCache.nameOf(id) ?: UserInfoCache.fallbackName(itemView.context)
                val st = sb.length
                sb.append(name)
                sb.setSpan(
                    ForegroundColorSpan(link), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            appendName(c.user_id)
            val replyTo = c.reply_to?.takeIf { it.isNotEmpty() && it != c.user_id }
            if (replyTo != null) {
                sb.append(" ")
                sb.append(itemView.context.getString(R.string.moments_reply_inline))
                sb.append(" ")
                appendName(replyTo)
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
                // 图片格子用方形占位；头像是 ui/AvatarView（取不到照片就留空），不用占位图
                placeholder(R.drawable.bg_image_placeholder)
                error(R.drawable.bg_image_placeholder)
            }
            holder.iv.setOnClickListener {
                onClick(position)
            }
        }

        class IVH(val iv: ImageView) : RecyclerView.ViewHolder(iv)
    }

    companion object {
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        /**
         * 整行点击的延迟。必须**长过 GestureDetector 的 300ms 双击窗口**，
         * 否则双击时第一次单击已经执行完，守卫形同虚设。
         */
        private const val ROW_CLICK_DELAY_MS = 320L
    }
}
