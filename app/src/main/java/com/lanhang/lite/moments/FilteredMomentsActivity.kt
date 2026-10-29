package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityFilteredMomentsBinding
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.settings.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 过滤设置 → 查看过滤消息：把当前规则挡住的动态列出来。
 *
 * 为什么要有这一页：时间线顶部不再挂「已过滤 N 条」的提示条（2026-10-27 用户要求），
 * 但「我到底被挡掉了什么」是过滤功能唯一的后悔药 —— 只能看条数、看不到内容，
 * 用户就无从判断规则是不是误伤，只能靠猜着关档位。
 *
 * 实现口径与搜索页一致：**服务端没有「列出被过滤内容」的接口**，只能翻时间线 + 本地匹配。
 * 因此：
 * - 有扫描深度（默认 [DEFAULT_DEPTH] 条），结果边扫边上屏，不会盖全屏转圈；
 * - 按钮「继续往前找」一次加 [STEP] 条，硬上限 [MAX_DEPTH]；
 * - 状态行始终写明「已扫描多少条」，否则用户会以为这就是全部。
 *
 * 交互与工作圈列表完全一致（复用 [MomentAdapter] + [MomentActions]）：
 * 能点赞、能评论、点头像进主页、点整行进详情 —— 这一页是「查看」，不是「隔离区」。
 */
class FilteredMomentsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityFilteredMomentsBinding
    private lateinit var adapter: MomentAdapter
    private lateinit var actions: MomentActions

    private var scanJob: Job? = null

    private var cursor: String? = null
    private var scanned = 0
    private var found = 0
    private var ended = false

    /** 本次允许扫到多深；点「继续往前找」递增 */
    private var target = DEFAULT_DEPTH

    private var failed: String? = null

    /**
     * 是否正在扫描。看协程状态而不是单独维护布尔：
     * 「取消旧任务 + 立刻起新任务」时旧任务的 finally 会把标志写回 false，
     * 新任务还在跑却被当成空闲（搜索页踩过这个坑）。
     */
    private val scanning: Boolean get() = scanJob?.isActive == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilteredMomentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // insets 会多次回调，基准值必须先取
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

        adapter = MomentAdapter(
            onLike = { item -> actions.toggleLike(item) },
            onComment = { item -> actions.askComment(item) },
            onImage = { urls, index -> ImagePreviewActivity.start(this, urls, index) },
            onReply = { item, userId -> actions.askComment(item, userId) },
            onCommentsLongPress = { item -> actions.showMyComments(item) },
            onUser = { userId -> UserProfileActivity.start(this, userId) },
            onRowClick = { item ->
                item.feed.feed_id?.let { MomentDetailActivity.start(this, it) }
            },
            onDelete = { item ->
                actions.askDeletePost(item) {
                    // 这一页的「找到 N 条」是本地累加的，删掉一条要跟着减
                    found = (found - 1).coerceAtLeast(0)
                    updateStatus()
                    if (adapter.itemCount == 0) {
                        binding.tvEmpty.text =
                            getString(R.string.moments_filtered_view_none, scanned)
                        binding.tvEmpty.isVisible = true
                    }
                }
            },
            onCommentDelete = { item, comment -> actions.askDeleteComment(item, comment) }
        )
        // 每条都写清「被哪条规则挡住」：全是同一句「已被过滤」等于没说，用户没法判断误伤
        adapter.filterTagText = { item -> reasonOf(item) }
        actions = MomentActions(this, adapter)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnMore.setOnClickListener { searchDeeper() }

        if (!MomentsFilterStore.enabled) {
            showDisabled()
            return
        }
        startScan()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 扫描

    private fun startScan() {
        cursor = null
        scanned = 0
        found = 0
        ended = false
        target = DEFAULT_DEPTH
        failed = null
        adapter.submit(emptyList())
        binding.tvEmpty.isVisible = false
        binding.btnMore.isVisible = false
        binding.progress.isVisible = true
        binding.tvStatus.text = getString(R.string.moments_filtered_view_scanning, 0)
        launchScan()
    }

    /** 继续往前找：一次加 [STEP] 条，到硬上限就停 */
    private fun searchDeeper() {
        if (scanning || ended) return
        if (target >= MAX_DEPTH) return
        target = minOf(MAX_DEPTH, target + STEP)
        binding.btnMore.isVisible = false
        binding.progress.isVisible = adapter.itemCount == 0
        launchScan()
    }

    private fun launchScan() {
        scanJob?.cancel()
        // 上一轮留下的「最近 N 条里没有命中」在开始新一轮时必须收掉，否则会和新进度自相矛盾
        if (adapter.itemCount == 0) binding.tvEmpty.isVisible = false
        scanJob = lifecycleScope.launch {
            try {
                while (isActive && scanned < target && !ended) {
                    val page = withContext(Dispatchers.IO) { fetchPage() }
                    if (page.items.isEmpty()) {
                        ended = true
                        break
                    }
                    // 游标取**原始**最后一条：过滤不影响翻页
                    cursor = page.lastId
                    scanned += page.items.size
                    if (page.rawCount < PAGE) ended = true
                    val matched = page.items.filter { isHidden(it) }
                    if (matched.isNotEmpty()) {
                        found += matched.size
                        adapter.append(matched)
                        binding.tvEmpty.isVisible = false
                    }
                    updateStatus()
                }
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(
                    this@FilteredMomentsActivity,
                    getString(R.string.moments_filter_tag),
                    e
                )
                failed = e.userMessage(
                    this@FilteredMomentsActivity,
                    R.string.moments_network_error
                )
            } finally {
                // 被取消时（退出页面）不要再动界面
                if (isActive) {
                    binding.progress.isVisible = false
                    finishScan()
                }
            }
        }
    }

    private data class Page(val items: List<MomentItem>, val rawCount: Int, val lastId: String?)

    private suspend fun fetchPage(): Page {
        val ids = MomentsApi.getTimeLine(cursor, PAGE)
        if (ids.isEmpty()) return Page(emptyList(), 0, null)
        val feeds = MomentsApi.getFeeds(ids)
        UserInfoCache.resolve(feeds.mapNotNull { it.user_id })
        return Page(feeds.map { MomentItem(it) }, ids.size, ids.last())
    }

    private fun isHidden(item: MomentItem): Boolean = hitOf(item) != null

    private fun hitOf(item: MomentItem): MomentsFilterStore.RuleHit? =
        MomentsFilterStore.match(item.content.text.orEmpty(), item.feed.user_id)

    /** 灰标文案：命中「广告推广」/ 命中自定义词「代课」 */
    private fun reasonOf(item: MomentItem): String? {
        val hit = hitOf(item) ?: return null
        val preset = hit.preset
        return if (preset != null) {
            getString(R.string.moments_filtered_view_reason_preset, getString(preset.titleRes))
        } else {
            getString(
                R.string.moments_filtered_view_reason_custom,
                hit.customRule.orEmpty()
            )
        }
    }

    private fun updateStatus() {
        binding.tvStatus.text = when {
            failed != null -> getString(R.string.moments_filtered_view_offline, scanned)
            scanning -> getString(R.string.moments_filtered_view_scanning, scanned)
            else -> getString(R.string.moments_filtered_view_status, scanned, found)
        }
    }

    private fun finishScan() {
        updateStatus()
        if (failed != null) {
            binding.tvEmpty.text = getString(R.string.moments_filtered_view_offline, scanned)
            binding.tvEmpty.isVisible = adapter.itemCount == 0
            toast(failed.orEmpty())
        } else if (adapter.itemCount == 0) {
            // 时间线为空时会扫到 0 条，用一句不带数字的话，别出现「最近 0 条里没有命中」
            binding.tvEmpty.text = if (scanned > 0) {
                getString(R.string.moments_filtered_view_none, scanned)
            } else {
                getString(R.string.moments_filtered_view_empty)
            }
            binding.tvEmpty.isVisible = true
        }
        // 到硬上限也收掉按钮，避免点了没反应
        binding.btnMore.isVisible = !ended && target < MAX_DEPTH && scanned > 0
    }

    private fun showDisabled() {
        adapter.submit(emptyList())
        binding.progress.isVisible = false
        binding.btnMore.isVisible = false
        binding.tvStatus.text = getString(R.string.moments_filtered_view_off)
        binding.tvEmpty.text = getString(R.string.moments_filtered_view_off)
        binding.tvEmpty.isVisible = true
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * 默认扫描深度。
         *
         * 实测工作圈日均 114 条，200 条约 1.8 天 —— 够看到「最近怎么列表变少了」的疑问，
         * 又只有 4 次 `/timeline` + 约 10 次 `/feed/batch`（服务端单次 ~80ms）。
         * 想再往前靠按钮加，不做「一次扫一年」。
         */
        private const val DEFAULT_DEPTH = 200

        /** 「继续往前找」每次追加的深度 */
        private const val STEP = 200

        /** 硬上限：再深就是几百个请求，服务端限流阈值未知，不做 */
        private const val MAX_DEPTH = 1000

        /** 单页条数：`/timeline` 实测支持 50 */
        private const val PAGE = 50

        fun start(context: Context) {
            context.startActivity(Intent(context, FilteredMomentsActivity::class.java))
        }
    }
}
