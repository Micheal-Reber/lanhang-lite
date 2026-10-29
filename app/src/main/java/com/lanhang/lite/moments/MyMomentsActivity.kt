package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityMyMomentsBinding
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.session.Session
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.core.LocaleHelper
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个人动态：某个用户发过的全部工作圈内容，按年份分组。
 *
 * 对应官方 `PrivateMomentListActivity`（`/timeline/{userId}` 拿 feed_ids，
 * 再 `POST /feed/batch` 取正文），分页游标是上一页最后一条的 feed_id。
 * 复用 [MomentAdapter] 渲染单条动态，年份分组用 [ConcatAdapter] 拼接标题条。
 *
 * 页面同时承担两个入口（官方也是同一个页面）：
 * - 顶栏菜单「我的动态」→ [start] 不传 userId，看自己；
 * - 个人资料页「个人动态」→ [start] 传 targetUserId，看别人。
 * 两者的差异只有标题、空态文案和过滤用的 userId。
 */
class MyMomentsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMyMomentsBinding

    /** 要看的用户；缺省即自己 */
    private val targetUserId: String by lazy {
        intent.getStringExtra(EXTRA_USER_ID).orEmpty().ifBlank { Session.userId }
    }

    private val isSelf: Boolean get() = targetUserId == Session.userId

    private var concat = ConcatAdapter()
    private val sections = mutableListOf<Section>()

    /** 已上屏的 feed_id，`/timeline/{id}` 翻页时可能与上一页重叠，靠它兜底去重 */
    private val seen = mutableSetOf<String>()

    private var cursor: String? = null
    private var loading = false
    private var ended = false

    /** 连续「整页都是重复 feed」的次数，用来兜住服务端游标语义与预期不一致的情况 */
    private var emptyRounds = 0

    private data class Section(
        val year: Int,
        val header: YearHeaderAdapter,
        val list: MomentAdapter
    )

    private data class Page(val feeds: List<MomentFeed>, val rawCount: Int, val cursorId: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyMomentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setTitle(
            if (isSelf) R.string.my_moments_title else R.string.moments_user_posts_title
        )

        // insets 会多次回调，基准值必须先取：直接读当前 padding 会一次次累加
        val baseBottomPadding = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0
            )
            binding.recycler.setPadding(
                binding.recycler.paddingLeft,
                binding.recycler.paddingTop,
                binding.recycler.paddingRight,
                baseBottomPadding + bars.bottom
            )
            insets
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = concat
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || ended) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 3) load(reset = false)
            }
        })
        binding.swipe.setOnRefreshListener { load(reset = true) }

        if (targetUserId.isBlank()) {
            binding.tvEmpty.isVisible = true
            return
        }
        binding.progress.isVisible = true
        load(reset = true)
    }

    private fun resetSections() {
        sections.clear()
        seen.clear()
        emptyRounds = 0
        // 整条列表换一个新 ConcatAdapter：比逐个 removeAdapter 更省心，且刷新本就该回到顶部
        concat = ConcatAdapter()
        binding.recycler.adapter = concat
    }

    private fun newFeedAdapter() = MomentAdapter(
        onLike = { item -> toggleLike(item) },
        onComment = { item -> askComment(item) },
        onImage = { urls, index -> ImagePreviewActivity.start(this, urls, index) },
        onReply = { item, userId -> askComment(item, userId) },
        onCommentsLongPress = { item -> showMyComments(item) },
        // 这一页所有动态都是同一个人的，点他头像仍进资料页（自洽，也方便从贴文回资料）
        onUser = { uid -> UserProfileActivity.start(this, uid) },
        // 只有本人动态才显示「删除」；看别人的「个人动态」时由 MomentAdapter 自动隐藏
        onDelete = { item -> confirmDeletePost(item) },
        // 评论同理：只有自己发的评论下方才显示「删除」
        onCommentDelete = { item, comment -> confirmDeleteComment(item, comment) }
    )

    private fun load(reset: Boolean) {
        if (loading) return
        if (!reset && ended) return
        loading = true
        if (reset) {
            ended = false
            cursor = null
            binding.swipe.isRefreshing = true
        }
        lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val ids = MomentsApi.getUserTimeLine(
                        targetUserId,
                        if (reset) null else cursor,
                        PAGE_SIZE
                    )
                    // 只保留目标用户发的（服务端理论上已过滤，防御性再判一次）
                    val feeds = MomentsApi.getFeeds(ids).filter {
                        it.user_id.isNullOrEmpty() || it.user_id == targetUserId
                    }
                    val userIds = feeds.flatMap { f ->
                        listOfNotNull(f.user_id) +
                            (f.likes?.mapNotNull { it.user_id } ?: emptyList()) +
                            (f.comments?.mapNotNull { it.user_id } ?: emptyList())
                    }
                    UserInfoCache.resolve(userIds)
                    Page(feeds, ids.size, ids.lastOrNull())
                }

                if (reset) resetSections()

                val byYear = LinkedHashMap<Int, MutableList<MomentItem>>()
                page.feeds.forEach { feed ->
                    val id = feed.feed_id ?: return@forEach
                    if (!seen.add(id)) return@forEach
                    byYear.getOrPut(yearOf(feed.create_dt)) { mutableListOf() }
                        .add(MomentItem(feed))
                }
                byYear.forEach { (year, items) -> appendSection(year, items) }

                if (page.rawCount > 0) cursor = page.cursorId ?: cursor
                if (page.rawCount < PAGE_SIZE) {
                    ended = true
                } else {
                    // 整页都是重复数据时最多再试一页，避免游标不推进导致反复请求
                    emptyRounds = if (byYear.isEmpty()) emptyRounds + 1 else 0
                    if (emptyRounds >= 2) ended = true
                }

                binding.tvEmpty.isVisible = concat.itemCount == 0
                binding.tvEmpty.setText(
                    if (isSelf) R.string.my_moments_empty else R.string.moments_user_empty
                )
            } catch (e: ApiException) {
                Diagnostics.record(this@MyMomentsActivity, getString(R.string.my_moments_tag), e)
                // 20009 = feed_noauth：对方把动态设成了不可见，不能和「从没发过」用同一句文案
                if (e.code == MOMENTS_NO_AUTH) {
                    showError(reset, getString(R.string.moments_user_no_auth))
                } else {
                    showError(reset, e.userMessage(this@MyMomentsActivity, R.string.moments_load_failed))
                }
            } catch (e: Exception) {
                Diagnostics.record(this@MyMomentsActivity, getString(R.string.my_moments_tag), e)
                showError(reset, getString(R.string.moments_network_error))
            }
            loading = false
            binding.progress.isVisible = false
            binding.swipe.isRefreshing = false
        }
    }

    /** 首屏失败落在空态上，翻页失败只吐 toast，避免把已有内容清掉 */
    private fun showError(reset: Boolean, message: String) {
        if (reset && concat.itemCount == 0) {
            binding.tvEmpty.isVisible = true
            binding.tvEmpty.text = message
        } else {
            toast(message)
        }
    }

    /** 年份相同的追加到已有分组，跨年则新起一组标题 + 列表 */
    private fun appendSection(year: Int, items: List<MomentItem>) {
        if (items.isEmpty()) return
        val existing = sections.firstOrNull { it.year == year }
        if (existing != null) {
            existing.list.append(items)
            return
        }
        val header = YearHeaderAdapter(yearLabel(year))
        val list = newFeedAdapter()
        list.append(items)
        sections += Section(year, header, list)
        concat.addAdapter(header)
        concat.addAdapter(list)
    }

    private fun yearLabel(year: Int): String =
        if (year > 0) getString(R.string.my_moments_year, year)
        else getString(R.string.my_moments_year_unknown)

    private fun yearOf(timestamp: Long?): Int {
        if (timestamp == null || timestamp <= 0L) return 0
        return Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)
    }

    /** 子 adapter 只会改自己那一段，ConcatAdapter 负责把位置换算到整条列表 */
    private fun notifyFeedChanged(feedId: String?) {
        sections.forEach { section ->
            val index = section.list.indexOf(feedId)
            if (index >= 0) {
                section.list.notifyItemChanged(index)
                return
            }
        }
    }

    private fun toggleLike(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        val liked = item.likedByMe
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (liked) MomentsApi.cancelLike(feedId) else MomentsApi.like(feedId)
                }
                if (liked) {
                    item.likes.removeIf { it.user_id == Session.userId }
                } else {
                    item.likes.add(
                        LikeEntry(
                            like_id = "local_${System.currentTimeMillis()}",
                            feed_id = feedId,
                            user_id = Session.userId,
                            create_dt = System.currentTimeMillis()
                        )
                    )
                }
                notifyFeedChanged(feedId)
            } catch (e: Exception) {
                toast(e.userMessage(this@MyMomentsActivity, R.string.moments_action_failed))
            }
        }
    }

    private fun askComment(item: MomentItem, replyToUserId: String? = null) {
        val feedId = item.feed.feed_id ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.moments_comment_hint)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (replyToUserId.isNullOrEmpty()) getString(R.string.moments_comment_title)
                else getString(
                    R.string.moments_reply_title,
                    UserInfoCache.nameOf(replyToUserId) ?: UserInfoCache.fallbackName(this)
                )
            )
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val fresh = withContext(Dispatchers.IO) {
                            MomentsApi.postComment(feedId, text, replyToUserId)
                            runCatching { MomentsApi.getFeed(feedId) }.getOrNull()
                        }
                        if (fresh != null) {
                            item.comments.clear()
                            item.comments.addAll(fresh.comments.orEmpty())
                            item.likes.clear()
                            item.likes.addAll(fresh.likes.orEmpty())
                        } else {
                            item.comments.add(MomentsApi.localComment(feedId, text))
                        }
                        notifyFeedChanged(feedId)
                        toast(getString(R.string.moments_sent))
                    } catch (e: Exception) {
                        toast(e.userMessage(this@MyMomentsActivity, R.string.moments_send_failed))
                    }
                }
            }
            .show()
    }

    private fun showMyComments(item: MomentItem) {
        val mine = item.comments.filter {
            it.user_id == Session.userId && !it.comment_id.isNullOrEmpty()
        }
        if (mine.isEmpty()) {
            toast(getString(R.string.moments_no_deletable))
            return
        }
        val labels = mine.map {
            getString(R.string.moments_delete_item, it.content.orEmpty().take(40))
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.moments_my_comments)
            .setItems(labels) { _, which -> confirmDeleteComment(item, mine[which]) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteComment(item: MomentItem, comment: CommentEntry) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.moments_delete_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                val feedId = item.feed.feed_id ?: return@setPositiveButton
                val commentId = comment.comment_id ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            MomentsApi.deleteComment(feedId, commentId)
                        }
                        item.comments.removeIf { it.comment_id == commentId }
                        notifyFeedChanged(feedId)
                        toast(getString(R.string.moments_deleted))
                    } catch (e: Exception) {
                        toast(e.userMessage(this@MyMomentsActivity, R.string.moments_delete_failed))
                    }
                }
            }
            .show()
    }

    /**
     * 删除自己发布的动态（`DELETE /feed/{id}`）。
     *
     * 成功后在按年分组里摘掉；某一年被删空时连年份标题一起移除，
     * 否则会剩一条只有「2026」的孤标题。
     */
    private fun confirmDeletePost(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        if (item.feed.user_id != Session.userId) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.moments_delete_post)
            .setMessage(R.string.moments_delete_post_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { MomentsApi.deleteFeed(feedId) }
                        removeFeed(feedId)
                        toast(getString(R.string.moments_deleted))
                    } catch (e: Exception) {
                        toast(e.userMessage(this@MyMomentsActivity, R.string.moments_delete_failed))
                    }
                }
            }
            .show()
    }

    /** 从分组列表里摘掉一条；未命中说明它不在当前已加载范围内 */
    private fun removeFeed(feedId: String) {
        seen.remove(feedId)
        val iterator = sections.iterator()
        while (iterator.hasNext()) {
            val section = iterator.next()
            if (!section.list.remove(feedId)) continue
            if (section.list.itemCount == 0) {
                concat.removeAdapter(section.header)
                concat.removeAdapter(section.list)
                iterator.remove()
            }
            break
        }
        binding.tvEmpty.isVisible = concat.itemCount == 0
        binding.tvEmpty.setText(
            if (isSelf) R.string.my_moments_empty else R.string.moments_user_empty
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 年份标题条：一年一条，不参与点击 */
    private class YearHeaderAdapter(private val label: String) :
        RecyclerView.Adapter<YearHeaderAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tv: TextView = view.findViewById(R.id.tvYear)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_moment_year_header, parent, false)
            )

        override fun getItemCount(): Int = 1

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = label
        }
    }

    companion object {
        /** 官方 PrivateMomentListActivity 单页 20 条 */
        private const val PAGE_SIZE = 20

        /** 融云圈 code：对方设置了动态不可见 */
        private const val MOMENTS_NO_AUTH = 20009

        /** 入口 extra：要看的用户；不传即自己 */
        const val EXTRA_USER_ID = "user_id"

        fun start(context: Context) {
            context.startActivity(Intent(context, MyMomentsActivity::class.java))
        }

        fun start(context: Context, userId: String) {
            context.startActivity(
                Intent(context, MyMomentsActivity::class.java)
                    .putExtra(EXTRA_USER_ID, userId)
            )
        }
    }
}
