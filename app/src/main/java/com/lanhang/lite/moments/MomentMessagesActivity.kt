package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityMomentMessagesBinding
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.settings.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作圈互动消息中心：谁赞了、谁评论了我，按时间倒序。
 *
 * 协议与官方 `MomentHistoryMsgActivity` 一致：
 * - `GET /message/history?from_message_id=&size=20` 拿互动记录（游标是上一页最后一条的 message_id）
 * - `GET /message/unread` 拿服务端认为未读的那批，用来打红点
 * - 关联动态的正文摘要走 `POST /feed/batch`（一次批量取，不逐条请求）
 *
 * **列表只显示与我有关的互动**：服务端会把我赞过的别人的动态上的赞/评论也推过来，
 * 那些在这里被 [MomentMessageFilter] 滤掉（判据见该类注释）。整页被滤光时继续往前翻，
 * 否则列表滑不动、会卡在空屏。
 *
 * **已读是本地状态**，写在本机的 [MomentMessageStore] 里：服务端确实有已读概念，
 * 但「标已读」的写接口没有可验证的形态，猜错会把记录从服务端历史里抹掉。
 * 界面上的红点 = 服务端未读 ∩ 本地未读，用户点过就不再亮。
 *
 * 通知权限（Android 13+）在这里处理：未授权时顶部出现提示条，
 * 首次进入自动申请一次；拒绝后点击提示条去申请或跳系统设置，见 [onNotifyBannerClick]。
 */
class MomentMessagesActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMomentMessagesBinding
    private lateinit var adapter: MomentMessageAdapter

    /** 未过滤的全部消息；筛选只影响上屏，不重新请求 */
    private val all = mutableListOf<MomentMessageItem>()

    private var filter = FILTER_ALL
    private var cursor: String? = null
    private var loading = false
    private var ended = false

    /** Android 13+ 的通知权限申请结果 */
    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateNotifyBanner()
        toast(
            getString(
                if (granted) R.string.moments_messages_notify_granted
                else R.string.moments_messages_notify_denied
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMomentMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

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

        adapter = MomentMessageAdapter(
            onOpen = { item -> openDetail(item) },
            onToggleRead = { item -> toggleRead(item) },
            onUser = { userId -> UserProfileActivity.start(this, userId) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || ended) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 3) load(reset = false)
            }
        })
        binding.swipe.setOnRefreshListener { load(reset = true) }
        binding.notifyBanner.setOnClickListener { onNotifyBannerClick() }

        setupFilters()
        binding.progress.isVisible = true
        load(reset = true)
        maybeAskNotifyPermission()
    }

    override fun onResume() {
        super.onResume()
        // 用户此刻就在消息列表里，系统通知没有意义（[MomentMessageWatcher] 也据此跳过弹窗）
        MomentNotify.messagesVisible = true
        MomentNotify.cancel(this)
        // 可能刚从系统设置页回来，重新判断一次
        updateNotifyBanner()
    }

    override fun onPause() {
        MomentNotify.messagesVisible = false
        super.onPause()
    }

    // ------------------------------------------------------------------ 筛选

    private fun setupFilters() {
        val group = binding.filterChips
        group.removeAllViews()
        FILTERS.forEach { (value, labelRes) ->
            val chip = layoutInflater.inflate(R.layout.item_term_chip, group, false) as Chip
            chip.text = getString(labelRes)
            // ChipGroup 的单选靠 id 记账，必须给稳定 id（NO_ID 会被当成匿名视图）
            chip.id = FILTER_CHIP_BASE + value
            chip.isChecked = value == filter
            chip.setOnClickListener { setFilter(value) }
            group.addView(chip)
        }
    }

    private fun setFilter(value: Int) {
        if (filter == value) return
        filter = value
        render()
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 一页数据。
     *
     * `rawCount` 是服务端返回的**原始**条数（判断还有没有下一页要用它）；
     * `lastRawId` 是原始最后一条的 message_id —— 过滤后 `items` 可能为空，
     * 游标必须取自原始列表，否则整页被滤光时会一直重复请求同一页。
     */
    private data class Page(
        val items: List<MomentMessageItem>,
        val rawCount: Int,
        val lastRawId: String?
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
                if (reset) all.clear()
                // 过滤后整页可能一条都不剩（那 20 条全是我赞过的别人的动态）。
                // 这种情况不能停下来等用户继续滑 —— 列表压根滑不动，会卡在空屏上，
                // 所以这里连续往前翻，直到翻出内容或到底。
                while (true) {
                    val page = withContext(Dispatchers.IO) { fetchPage(cursor) }
                    page.lastRawId?.let { cursor = it }
                    if (page.rawCount < MomentsApi.MESSAGE_PAGE_SIZE) ended = true
                    all.addAll(page.items)
                    if (page.items.isNotEmpty() || ended) break
                }
                render()
            } catch (e: ApiException) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@MomentMessagesActivity, getString(R.string.moments_messages_tag), e)
                showError(reset, e.userMessage(this@MomentMessagesActivity, R.string.moments_load_failed))
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@MomentMessagesActivity, getString(R.string.moments_messages_tag), e)
                showError(reset, getString(R.string.moments_network_error))
            } finally {
                // 取消也要复位，否则重进页面会一直转圈
                loading = false
                binding.progress.isVisible = false
                binding.swipe.isRefreshing = false
            }
        }
    }

    private suspend fun fetchPage(fromMessageId: String?): Page {
        val messages = MomentsApi.getHistoryMessages(fromMessageId)
        if (messages.isEmpty()) return Page(emptyList(), 0, null)

        // 关联动态：批量取一次（官方也是批量，20 条消息最多 20 个 feed_id）。
        // 同一份结果既用来算正文摘要，也用来判断「这条动态是不是我发的」。
        val feedIds = messages.mapNotNull { it.feed_id?.takeIf(String::isNotBlank) }.distinct()
        val feeds = if (feedIds.isEmpty()) emptyList() else runCatching {
            MomentsApi.getFeeds(feedIds)
        }.getOrNull()
        // 取失败（null）时 owners 也是 null，过滤器会原样放行，不因网络抖动清空列表
        val owners = feeds?.mapNotNull { feed ->
            val id = feed.feed_id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            id to feed.user_id.orEmpty()
        }?.toMap()
        val excerptById = feeds?.associate { it.feed_id.orEmpty() to excerptOf(it) } ?: emptyMap()

        // 只留下「我的动态被赞/评论」和「回复我的评论」，我赞过的别人的动态不算
        val kept = MomentMessageFilter.keep(messages, owners)

        UserInfoCache.resolve(kept.mapNotNull { it.user_id })

        // 服务端未读拿不到（网络/会话问题）时退化成「本地没标过已读就算未读」，
        // 这样断网也不会把一屏红点全抹掉
        val unreadIds = runCatching {
            MomentsApi.getUnreadMessages().mapNotNull { it.message_id }
        }.getOrNull()?.toSet()
        val readIds = MomentMessageStore.readIds()

        val items = kept.map { message ->
            val id = message.message_id.orEmpty()
            val read = id.isNotEmpty() && id in readIds
            MomentMessageItem(
                message = message,
                excerpt = excerptById[message.feed_id.orEmpty()].orEmpty(),
                unread = if (unreadIds != null) id in unreadIds && !read else !read
            )
        }
        return Page(items, messages.size, messages.lastOrNull()?.message_id)
    }

    /** 动态正文摘要：有文字取文字，纯图片给「[图片]」，都没有说明动态已删或不可见 */
    private fun excerptOf(feed: MomentFeed): String {
        val item = MomentItem(feed)
        val text = item.content.text?.trim().orEmpty()
        return when {
            text.isNotEmpty() -> text
            item.content.media?.isNotEmpty() == true -> getString(R.string.moments_messages_excerpt_image)
            else -> getString(R.string.moments_messages_excerpt_empty)
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val shown = all.filter {
            when (filter) {
                FILTER_COMMENT -> it.message.isComment
                FILTER_LIKE -> !it.message.isComment
                else -> true
            }
        }
        adapter.submit(shown)
        binding.tvEmpty.isVisible = shown.isEmpty()
        binding.tvEmpty.setText(
            when (filter) {
                FILTER_COMMENT -> R.string.moments_messages_empty_comment
                FILTER_LIKE -> R.string.moments_messages_empty_like
                else -> R.string.moments_messages_empty
            }
        )
        val unread = all.count { it.unread }
        binding.toolbar.subtitle = if (unread > 0) {
            getString(R.string.moments_messages_unread_count, unread)
        } else {
            null
        }
        invalidateOptionsMenu()
    }

    /** 首屏失败落在空态上，翻页失败只吐 toast，避免把已有内容清掉 */
    private fun showError(reset: Boolean, message: String) {
        if (reset && all.isEmpty()) {
            binding.tvEmpty.isVisible = true
            binding.tvEmpty.text = message
        } else {
            toast(message)
        }
    }

    // ------------------------------------------------------------------ 交互

    /** 点一行：先标已读（不让红点跟着进详情），再跳对应动态 */
    private fun openDetail(item: MomentMessageItem) {
        if (item.feedId.isBlank()) {
            toast(getString(R.string.moments_messages_excerpt_empty))
            return
        }
        if (item.unread) setRead(item, read = true, notify = false)
        MomentDetailActivity.start(this, item.feedId)
    }

    /** 长按：已读 ⇄ 未读（「已读切换」的入口，点开详情也会自动标已读） */
    private fun toggleRead(item: MomentMessageItem) {
        setRead(item, read = item.unread, notify = true)
    }

    private fun setRead(item: MomentMessageItem, read: Boolean, notify: Boolean) {
        if (item.messageId.isBlank()) return
        val targetUnread = !read
        if (item.unread == targetUnread) return
        MomentMessageStore.markRead(item.messageId, read)
        item.unread = targetUnread
        val index = adapter.indexOf(item.messageId)
        if (index >= 0) adapter.notifyItemChanged(index)
        val unread = all.count { it.unread }
        binding.toolbar.subtitle = if (unread > 0) {
            getString(R.string.moments_messages_unread_count, unread)
        } else {
            null
        }
        if (notify) {
            toast(
                getString(
                    if (read) R.string.moments_messages_marked_read
                    else R.string.moments_messages_marked_unread
                )
            )
        }
    }

    private fun markAllRead() {
        val ids = all.map { it.messageId }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        MomentMessageStore.markAllRead(ids)
        all.forEach { it.unread = false }
        render()
        toast(getString(R.string.moments_messages_all_read))
    }

    // -------------------------------------------------------------- 通知权限

    private fun updateNotifyBanner() {
        binding.notifyBanner.isVisible = !MomentNotify.hasPermission(this)
    }

    /**
     * 首次进入自动申请一次；之后交给提示条（见 [onNotifyBannerClick]）。
     *
     * 全局只自动弹一次（[NotifPermission.wasAsked] 存在 SP 里），不是每次进页面都弹：
     * 这个页面是「看谁赞了我」，用户来得频繁，反复弹权限框会让人烦到直接关通知。
     */
    private fun maybeAskNotifyPermission() {
        if (MomentNotify.hasPermission(this)) return
        if (NotifPermission.wasAsked(this)) return
        NotifPermission.request(this, notifyPermission, MomentNotify.CHANNEL_ID)
    }

    /**
     * 提示条点击：能不能弹系统框、要不要跳设置页，全交给 [NotifPermission.request]。
     * 这里只负责「已经有权限了就把提示条收掉」。
     */
    private fun onNotifyBannerClick() {
        if (MomentNotify.hasPermission(this)) {
            updateNotifyBanner()
            return
        }
        NotifPermission.request(this, notifyPermission, MomentNotify.CHANNEL_ID)
    }

    // ------------------------------------------------------------------ 菜单

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_moment_messages, menu)
        // 顶栏是 M3 surface 底，图标统一跟 onSurface；逐项染色是因为这里没有溢出菜单
        val tint = com.google.android.material.color.MaterialColors.getColor(
            binding.toolbar,
            com.google.android.material.R.attr.colorOnSurface
        )
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.mutate()?.setTint(tint)
            // 没有未读时「全部已读」没有意义，直接藏掉
            if (menu.getItem(i).itemId == R.id.action_mark_all_read) {
                menu.getItem(i).isVisible = all.any { it.unread }
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_mark_all_read -> {
            markAllRead()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_COMMENT = 1
        private const val FILTER_LIKE = 2

        /** 筛选 chip 的 id 基数（ChipGroup 单选记账用，随便取一个不与其它控件冲突的值） */
        private const val FILTER_CHIP_BASE = 5200

        private val FILTERS = listOf(
            FILTER_ALL to R.string.moments_messages_filter_all,
            FILTER_COMMENT to R.string.moments_messages_filter_comment,
            FILTER_LIKE to R.string.moments_messages_filter_like,
        )

        fun start(context: Context) {
            context.startActivity(Intent(context, MomentMessagesActivity::class.java))
        }
    }
}
