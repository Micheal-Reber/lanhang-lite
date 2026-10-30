package com.lanhang.lite.moments

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.attachDoubleTapToTop
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.scrollToTopNow
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.FragmentMomentsBinding
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.session.Session
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.LanhangTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MomentsFragment : Fragment() {

    private var _binding: FragmentMomentsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MomentAdapter
    private var fromFeedId: String? = null
    private var loading = false
    private var ended = false

    /** 本次会话加载过的全部动态（**未过滤**）：规则变化时按新规则重排，不必重新请求 */
    private val allItems = mutableListOf<MomentItem>()

    /**
     * 被过滤规则挡住的条数。
     *
     * 只用来决定「全被挡住」时的空态文案 —— 时间线顶部不再有任何过滤提示条，
     * 被挡内容统一从「设置 → 工作圈过滤 → 查看过滤消息」看。
     */
    private var hiddenCount = 0

    /** 连续「整页被滤光」的自动续翻次数，防止规则极严时无限翻页 */
    private var autoLoads = 0

    /** 上次生效的过滤规则指纹：从设置页回来时据此决定要不要重刷列表 */
    private var lastFilterSignature: String? = null

    /** 发表页返回后刷新时间线：刚发的动态要立刻出现在最上面 */
    private val publishLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) load(reset = true)
    }

    private val onFabScroll = android.view.ViewTreeObserver.OnScrollChangedListener {
        _binding?.fabPublish?.invalidate()
    }
    private val onFabLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        _binding?.fabPublish?.invalidate()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMomentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MomentAdapter(
            onLike = { item -> toggleLike(item) },
            onComment = { item -> askComment(item) },
            onImage = { urls, index ->
                ImagePreviewActivity.start(requireContext(), urls, index)
            },
            onReply = { item, userId -> askComment(item, userId) },
            onCommentsLongPress = { item -> showMyComments(item) },
            // 头像 / 昵称 → 个人资料页（官方把「点人」交给宿主，这里落我们自己的页面）
            onUser = { userId ->
                context?.let { UserProfileActivity.start(it, userId) }
            },
            onDelete = { item -> confirmDeletePost(item) },
            onCommentDelete = { item, comment -> confirmDeleteComment(item, comment) }
        )
        setupGlassFab()
        binding.root.viewTreeObserver.addOnScrollChangedListener(onFabScroll)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(onFabLayout)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        // 双击（正文/空白处）回到顶部；落在图片、头像、赞/评论上时不接管
        binding.recycler.attachDoubleTapToTop(
            onBeforeScroll = { adapter.cancelPendingRowClick() },
            onAfterScroll = { _binding?.fabPublish?.invalidate() }
        )
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || ended) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val total = lm.itemCount
                val last = lm.findLastVisibleItemPosition()
                if (last >= total - 3) load(reset = false)
            }
        })
        binding.swipe.setOnRefreshListener { load(reset = true) }

        // 视图重建（进程恢复/主题切换）后 adapter 必为空，不能因 savedInstanceState 跳过加载
        if (savedInstanceState == null || adapter.itemCount == 0) {
            binding.progress.isVisible = true
            load(reset = true)
        }
    }

    /** 底栏选中「工作圈」时回调：列表为空则自动刷新（兜底全黑） */
    fun onTabSelected() {
        if (_binding == null) return
        binding.fabPublish.invalidate()
        if (loading || adapter.itemCount > 0) return
        binding.progress.isVisible = true
        load(reset = true)
    }

    /**
     * 已经在「工作圈」Tab 上又点了一次。
     *
     * 和 [onTabSelected] 分开：切过来不该动滚动位置（用户可能就是想接着看），
     * 只有「再点一次」才回顶 —— 这是主流 App 的惯例，和双击列表互不干扰。
     */
    fun onTabReselect() {
        if (_binding == null || adapter.itemCount == 0) return
        adapter.cancelPendingRowClick()
        binding.recycler.scrollToTopNow { _binding?.fabPublish?.invalidate() }
    }

    override fun onDestroyView() {
        binding.root.viewTreeObserver.removeOnScrollChangedListener(onFabScroll)
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(onFabLayout)
        super.onDestroyView()
        _binding = null
    }

    /** 与底栏同款：Compose Canvas 录一层内容做实时 backdrop，玻璃 FAB 从中采样 */
    private fun setupGlassFab() {
        val fab = binding.fabPublish
        val source = binding.swipe
        val publishLabel = getString(R.string.moments_cd_publish)
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        fab.setContent {
            LanhangTheme(darkTheme = night) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop),
                    ) {
                        val sourceLocation = IntArray(2)
                        val hostLocation = IntArray(2)
                        source.getLocationInWindow(sourceLocation)
                        fab.getLocationInWindow(hostLocation)
                        drawIntoCanvas { canvas ->
                            val nativeCanvas = canvas.nativeCanvas
                            val saveCount = nativeCanvas.save()
                            nativeCanvas.translate(
                                (sourceLocation[0] - hostLocation[0]).toFloat(),
                                (sourceLocation[1] - hostLocation[1]).toFloat(),
                            )
                            source.draw(nativeCanvas)
                            nativeCanvas.restoreToCount(saveCount)
                        }
                    }
                    LiquidGlassFab(
                        backdrop = backdrop,
                        contentDescription = publishLabel,
                        onClick = {
                            // 发表已改为独立页面；成功后回 RESULT_OK，这里再刷新时间线
                            context?.let {
                                publishLauncher.launch(PublishMomentActivity.intent(it))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /**
     * 加载一页。
     *
     * 过滤只发生在**上屏之前**：分页游标 `fromFeedId` 与判尾 `page.size < PAGE_SIZE`
     * 一律用**未过滤**的原始页。写成「先过滤再取游标」的话，整页被滤光时会一直重复
     * 请求同一页 —— 消息中心就踩过这个坑（2026-10-27）。
     *
     * 整页被滤光且还没到头时自动再翻一页（最多 [MAX_AUTO_LOADS] 次），
     * 否则用户看到的是空白且滑不动。
     */
    private fun load(reset: Boolean) {
        if (loading) return
        if (!reset && ended) return
        loading = true
        if (reset) {
            ended = false
            fromFeedId = null
            autoLoads = 0
            binding.swipe.isRefreshing = true
        }
        viewLifecycleOwner.lifecycleScope.launch {
            var needMore = false
            try {
                val page = withContext(Dispatchers.IO) {
                    val ids = MomentsApi.getTimeLine(if (reset) null else fromFeedId)
                    val feeds = MomentsApi.getFeeds(ids)
                    val userIds = feeds.flatMap { f ->
                        listOfNotNull(f.user_id) +
                            (f.likes?.mapNotNull { it.user_id } ?: emptyList()) +
                            (f.comments?.mapNotNull { it.user_id } ?: emptyList())
                    }
                    UserInfoCache.resolve(userIds)
                    feeds
                }
                val items = page.map { MomentItem(it) }
                val hidden = items.filter { isFiltered(it) }
                val visible = items - hidden.toSet()
                if (reset) {
                    adapter.clear()
                    allItems.clear()
                    hiddenCount = 0
                }
                allItems.addAll(items)
                hiddenCount += hidden.size
                if (page.size < MomentsApi.PAGE_SIZE) ended = true
                fromFeedId = page.lastOrNull()?.feed_id ?: fromFeedId
                adapter.append(visible)
                binding.fabPublish.invalidate()
                updateEmpty()
                if (visible.isEmpty() && !ended && hiddenCount > 0) needMore = true
            } catch (e: ApiException) {
                if (e.isCancellation) return@launch
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_load), e)
                val message = e.userMessage(requireContext(), R.string.moments_load_failed)
                if (reset && adapter.itemCount == 0) {
                    binding.tvEmpty.text = message
                    binding.tvEmpty.isVisible = true
                }
                toast(message)
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_load), e)
                // toast/占位只放固定提示，原始异常留给 diagnostics.log，避免往界面喷堆栈
                val message = getString(R.string.moments_network_error)
                if (reset && adapter.itemCount == 0) {
                    binding.tvEmpty.text = message
                    binding.tvEmpty.isVisible = true
                }
                toast(message)
            } finally {
                loading = false
                // 协程取消后 view 可能已经销毁，收尾一律走可空引用
                _binding?.progress?.isVisible = false
                _binding?.swipe?.isRefreshing = false
            }
            if (needMore && autoLoads < MAX_AUTO_LOADS) {
                autoLoads++
                load(reset = false)
            } else {
                autoLoads = 0
            }
        }
    }

    // ------------------------------------------------------------------ 过滤

    /**
     * 这条该不该被藏。
     *
     * 屏蔽排在关键词之前：屏蔽是用户点名不想看的人，**不该被任何自动豁免推翻** ——
     * 否则被屏蔽的人发一条「求借充电宝」就会被失物招领豁免放出来。
     */
    private fun isFiltered(item: MomentItem): Boolean {
        if (BlockedUserStore.isBlocked(item.feed.user_id)) return true
        return MomentsFilterStore.match(item.content.text.orEmpty(), item.feed.user_id) != null
    }

    /** 规则指纹：档位 / 自定义词 / 总开关 / 屏蔽名单任一变化都要重刷列表 */
    private fun filterSignature(): String = buildString {
        append(MomentsFilterStore.enabled)
        MomentsFilterStore.activePresets().forEach { append('|').append(it.key) }
        append('#')
        append(MomentsFilterStore.customRules().joinToString("\u0001"))
        append('#')
        // 名单变化也要重排，否则「刚屏蔽的人还在列表里」直到下次刷新
        append(BlockedUserStore.ids().sorted().joinToString(","))
    }

    /** 用当前规则重新算一遍「哪些被挡住」，再整体重排（规则变化时用） */
    private fun applyFilter() {
        val hidden = allItems.filter { isFiltered(it) }
        hiddenCount = hidden.size
        adapter.submit(allItems - hidden.toSet())
        binding.fabPublish.invalidate()
        updateEmpty()
    }

    /**
     * 空态要分清两种：真的没有动态、以及**全被规则挡住**。
     * 后者如果还显示「暂无动态」，用户会以为工作圈挂了。
     *
     * 提示只出现在列表为空时的居中占位上，时间线顶部不挂任何过滤条。
     */
    private fun updateEmpty() {
        if (adapter.itemCount > 0) {
            binding.tvEmpty.isVisible = false
            binding.tvEmpty.setOnClickListener(null)
            return
        }
        binding.tvEmpty.isVisible = true
        if (hiddenCount > 0) {
            binding.tvEmpty.text = getString(R.string.moments_empty_filtered) + "\n" +
                getString(R.string.moments_empty_filtered_action)
            binding.tvEmpty.setOnClickListener {
                context?.let { MomentsFilterActivity.start(it) }
            }
        } else {
            binding.tvEmpty.text = getString(R.string.moments_empty)
            binding.tvEmpty.setOnClickListener(null)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从过滤设置页回来：规则变了就按新规则重排已加载的内容，不重新请求
        val signature = filterSignature()
        if (signature == lastFilterSignature) return
        lastFilterSignature = signature
        if (allItems.isEmpty()) return
        applyFilter()
    }

    private fun toggleLike(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        val liked = item.likedByMe
        viewLifecycleOwner.lifecycleScope.launch {
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
                adapter.notifyItemChanged(adapter.indexOf(feedId).coerceAtLeast(0))
                binding.fabPublish.invalidate()
            } catch (e: Exception) {
                toast(e.userMessage(requireContext(), R.string.moments_action_failed))
            }
        }
    }


    private fun askComment(item: MomentItem, replyToUserId: String? = null) {
        val feedId = item.feed.feed_id ?: return
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.moments_comment_hint)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (replyToUserId.isNullOrEmpty()) getString(R.string.moments_comment_title)
                else getString(
                    R.string.moments_reply_title,
                    UserInfoCache.nameOf(replyToUserId) ?: UserInfoCache.fallbackName(requireContext())
                )
            )
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
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
                        adapter.notifyItemChanged(adapter.indexOf(feedId).coerceAtLeast(0))
                binding.fabPublish.invalidate()
                        toast(getString(R.string.moments_sent))
                    } catch (e: Exception) {
                        toast(e.userMessage(requireContext(), R.string.moments_send_failed))
                    }
                }
            }
            .show()
    }

    /** 长按评论区：列出自己发过的评论，可删除（DELETE /comment/{feed}/{comment}） */
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moments_my_comments)
            .setItems(labels) { _, which -> confirmDeleteComment(item, mine[which]) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteComment(item: MomentItem, comment: CommentEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.moments_delete_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                val feedId = item.feed.feed_id ?: return@setPositiveButton
                val cid = comment.comment_id ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { MomentsApi.deleteComment(feedId, cid) }
                        item.comments.removeIf { it.comment_id == cid }
                        adapter.notifyItemChanged(adapter.indexOf(feedId).coerceAtLeast(0))
                        binding.fabPublish.invalidate()
                        toast(getString(R.string.moments_deleted))
                    } catch (e: Exception) {
                        toast(e.userMessage(requireContext(), R.string.moments_delete_failed))
                    }
                }
            }
            .show()
    }

    /**
     * 删除自己发布的动态：二次确认 → `DELETE /feed/{id}` → 本地摘掉。
     *
     * 只改本地列表、不重拉整页：重拉会打断阅读位置，而且刚删的那条可能还在服务端
     * 时间线的分页缓存里。`allItems` 里同一条即使被过滤规则挡住也要一起清，
     * 否则下次 [applyFilter] 会把它又捡回来。
     */
    private fun confirmDeletePost(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        if (item.feed.user_id != Session.userId) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moments_delete_post)
            .setMessage(R.string.moments_delete_post_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { MomentsApi.deleteFeed(feedId) }
                        val wasHidden = allItems.firstOrNull { it.feed.feed_id == feedId }
                            ?.let { isFiltered(it) } == true
                        allItems.removeAll { it.feed.feed_id == feedId }
                        if (wasHidden) hiddenCount = (hiddenCount - 1).coerceAtLeast(0)
                        adapter.remove(feedId)
                        binding.fabPublish.invalidate()
                        updateEmpty()
                        toast(getString(R.string.moments_deleted))
                    } catch (e: Exception) {
                        toast(e.userMessage(requireContext(), R.string.moments_delete_failed))
                    }
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 整页被滤光时最多自动续翻几次（规则极严时不至于把时间线翻穿） */
        private const val MAX_AUTO_LOADS = 5
    }

}
