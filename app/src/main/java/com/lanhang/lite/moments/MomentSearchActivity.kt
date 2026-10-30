package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.lanhang.lite.core.attachDoubleTapToTop
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityMomentSearchBinding
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.settings.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作圈关键词搜索。
 *
 * **服务端没有搜索接口**（融云圈 SDK 的端点里一个 search 都没有），所以搜索只能是
 * 「翻时间线 + 本地匹配」：每 50 条 id 一轮 `/timeline` + `/feed/batch`。
 * 由此带来两个必须写在界面上的事实：
 *
 * 1. **搜索有深度上限**。默认只扫最近 [DEFAULT_DEPTH] 条（按实测日均 114 条算约 3 天），
 *    不够就点「继续往前找」一次加 [STEP] 条，硬上限 [MAX_DEPTH] —— 不做
 *    「一次扫一年」，那要几百个请求。状态行始终显示「已搜索 N / 目标」。
 * 2. **结果是边搜边出**。不盖全屏转圈：第一页命中几百毫秒内就上屏，
 *    用户随时可以停（退出页面协程即取消）。
 *
 * 与过滤的关系：**搜索无视过滤规则**。用户明确输入了关键词，就说明他认定这条内容存在，
 * 这时再按长期偏好藏东西会变成「我明明记得有人发过，却搜不到」。
 * 被规则命中的结果照常显示，只是打一个「已被过滤规则命中」的小标。
 */
class MomentSearchActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMomentSearchBinding
    private lateinit var adapter: MomentAdapter
    private lateinit var actions: MomentActions

    /** 当前查询（原文，用于历史记录与高亮） */
    private var query = ""

    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    private var cursor: String? = null
    private var scanned = 0
    private var found = 0
    private var ended = false

    /** 本次搜索允许扫到多深；点「继续往前找」递增 */
    private var target = DEFAULT_DEPTH

    /**
     * 是否正在扫描。直接看协程状态而不是单独维护一个布尔：
     * 「取消旧任务 + 立刻起新任务」时，旧任务的 finally 会把标志写成 false，
     * 结果新任务在跑却被当成空闲（继续往前找会被误触发）。
     */
    private val scanning: Boolean get() = searchJob?.isActive == true
    private var failed: String? = null

    /** 点历史记录回填输入框时置 true：避免 setText 触发的 watcher 再排一次防抖 */
    private var ignoreWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMomentSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 键盘也算「底部遮挡」（2026-10-28）：edge-to-edge 下 adjustResize 不改窗口高度，
            // 不补 ime 高度的话结果列表的下半截会一直躲在键盘后面
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0
            )
            // 底部按钮要让开导航条：root 只有上下方向需要，左右保持原值
            binding.root.setPadding(
                binding.root.paddingLeft,
                binding.root.paddingTop,
                binding.root.paddingRight,
                maxOf(bars.bottom, ime.bottom)
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
                    // 搜索结果的条数是本地累加的，删掉一条要跟着减
                    found = (found - 1).coerceAtLeast(0)
                    updateStatus()
                    if (adapter.itemCount == 0 && scanned > 0) {
                        binding.tvEmpty.text = getString(R.string.moments_search_none, scanned)
                        binding.tvEmpty.isVisible = true
                    }
                }
            }
        )
        actions = MomentActions(this, adapter)
        // 命中过滤规则的打标：解释「为什么工作圈列表里看不到这条」
        adapter.filterTagText = { item ->
            if (MomentsFilterStore.match(item.content.text.orEmpty(), item.feed.user_id) != null) {
                getString(R.string.moments_search_filtered_tag)
            } else {
                null
            }
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        // 双击回到顶部：点结果进详情被延后 320ms，这里撤掉它再回顶
        binding.recycler.attachDoubleTapToTop(
            onBeforeScroll = { adapter.cancelPendingRowClick() }
        )

        bindInput()
        binding.btnMore.setOnClickListener { searchDeeper() }
        binding.tvHistoryClear.setOnClickListener {
            MomentSearchStore.clear()
            renderHistory()
        }
        showIdle()
        focusInput()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        debounceJob?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 输入

    private fun bindInput() {
        binding.etQuery.doAfterTextChanged { editable ->
            if (ignoreWatcher) return@doAfterTextChanged
            val text = editable?.toString().orEmpty()
            debounceJob?.cancel()
            if (text.isBlank()) {
                searchJob?.cancel()
                failed = null
                showIdle()
                return@doAfterTextChanged
            }
            if (text.trim().length > MomentTextMatcher.MAX_QUERY_LENGTH) {
                toast(getString(R.string.moments_search_too_long, MomentTextMatcher.MAX_QUERY_LENGTH))
                return@doAfterTextChanged
            }
            // 防抖 300ms：连续打字时只搜最后一次，旧任务必须取消，否则结果会串
            debounceJob = lifecycleScope.launch {
                delay(DEBOUNCE_MS)
                startSearch(text)
            }
        }
        binding.etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                debounceJob?.cancel()
                startSearch(binding.etQuery.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    private fun focusInput() {
        binding.etQuery.requestFocus()
        binding.etQuery.post {
            val imm = getSystemService(InputMethodManager::class.java) ?: return@post
            imm.showSoftInput(binding.etQuery, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.etQuery.windowToken, 0)
    }

    // ---------------------------------------------------------------- 搜索

    /** 空输入态：显示最近搜索 + 搜索范围说明 */
    private fun showIdle() {
        query = ""
        cursor = null
        scanned = 0
        found = 0
        ended = false
        target = DEFAULT_DEPTH
        adapter.highlightWords = emptyList()
        adapter.submit(emptyList())
        binding.btnMore.isVisible = false
        binding.progress.isVisible = false
        binding.tvStatus.text = getString(R.string.moments_search_scope, DEFAULT_DEPTH)
        val hasHistory = MomentSearchStore.history().isNotEmpty()
        binding.historyBox.isVisible = hasHistory
        binding.tvEmpty.isVisible = !hasHistory
        if (!hasHistory) binding.tvEmpty.text = getString(R.string.moments_search_prompt)
        renderHistory()
    }

    private fun renderHistory() {
        val history = MomentSearchStore.history()
        binding.chipHistory.removeAllViews()
        binding.historyBox.isVisible = history.isNotEmpty() && query.isBlank()
        history.forEach { keyword ->
            val chip = Chip(this).apply {
                text = keyword
                setOnClickListener {
                    ignoreWatcher = true
                    binding.etQuery.setText(keyword)
                    binding.etQuery.setSelection(keyword.length)
                    ignoreWatcher = false
                    debounceJob?.cancel()
                    startSearch(keyword)
                }
            }
            binding.chipHistory.addView(chip)
        }
    }

    private fun startSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        query = q
        searchJob?.cancel()
        failed = null
        cursor = null
        scanned = 0
        found = 0
        ended = false
        target = DEFAULT_DEPTH
        adapter.highlightWords = MomentTextMatcher.words(q)
        adapter.submit(emptyList())
        binding.historyBox.isVisible = false
        binding.tvEmpty.isVisible = false
        binding.btnMore.isVisible = false
        binding.progress.isVisible = true
        binding.tvStatus.text = getString(R.string.moments_search_progress, 0, target)
        launchScan()
    }

    /** 继续往前找：一次加 [STEP] 条，到硬上限就停 */
    private fun searchDeeper() {
        if (scanning || ended) return
        if (target >= MAX_DEPTH) return
        target = minOf(MAX_DEPTH, target + STEP)
        binding.btnMore.isVisible = false
        // 已经有结果时不要再盖一层转圈：进度写在状态行里，结果继续往上加
        binding.progress.isVisible = adapter.itemCount == 0
        launchScan()
    }

    private fun launchScan() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            try {
                val words = MomentTextMatcher.words(query)
                if (words.isEmpty()) return@launch
                // 边翻边出结果：每页命中立刻上屏，用户不用等全量扫完
                while (isActive && scanned < target && !ended) {
                    val page = withContext(Dispatchers.IO) { fetchPage() }
                    if (page.items.isEmpty()) {
                        ended = true
                        break
                    }
                    // 游标取**原始**最后一条：过滤/命中都不影响翻页
                    cursor = page.lastId
                    scanned += page.items.size
                    if (page.rawCount < PAGE) ended = true
                    val matched = page.items.filter { matches(it, words) }
                    if (matched.isNotEmpty()) {
                        found += matched.size
                        adapter.append(matched)
                        binding.tvEmpty.isVisible = false
                    }
                    updateStatus()
                }
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@MomentSearchActivity, getString(R.string.moments_search_tag), e)
                failed = e.userMessage(this@MomentSearchActivity, R.string.moments_network_error)
            } finally {
                // 被取消（改了关键词 / 退出页面）时不要再动界面：新的一轮搜索已经在写状态了
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

    private fun matches(item: MomentItem, words: List<String>): Boolean {
        if (MomentTextMatcher.matchesAll(item.content.text.orEmpty(), words)) return true
        // 作者名也参与匹配（搜「某人的动态」是合理需求）；多词时同样要求全部命中
        val name = UserInfoCache.nameOf(item.feed.user_id).orEmpty()
        return name.isNotEmpty() && MomentTextMatcher.matchesAll(name, words)
    }

    private fun updateStatus() {
        binding.tvStatus.text = when {
            failed != null -> getString(R.string.moments_search_offline, scanned)
            scanning -> getString(R.string.moments_search_progress, scanned, target)
            found > 0 -> getString(R.string.moments_search_result, found, scanned)
            scanned > 0 -> getString(R.string.moments_search_none, scanned)
            else -> getString(R.string.moments_search_scope, target)
        }
    }

    private fun finishScan() {
        updateStatus()
        if (failed != null) {
            binding.tvEmpty.text = getString(R.string.moments_search_offline, scanned)
            binding.tvEmpty.isVisible = adapter.itemCount == 0
            toast(failed.orEmpty())
        } else if (adapter.itemCount == 0 && scanned > 0) {
            // 不能只显示「没找到」：还没到头的时候必须告诉用户还能继续往前找
            binding.tvEmpty.text = getString(R.string.moments_search_none, scanned)
            binding.tvEmpty.isVisible = true
        }
        // 到硬上限也收掉按钮，避免点了没反应
        binding.btnMore.isVisible = !ended && target < MAX_DEPTH && scanned > 0
        if (found > 0) {
            MomentSearchStore.remember(query)
            renderHistory()
        }
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * 默认扫描深度。
         *
         * 实测工作圈日均 114 条，100 条只够 0.9 天、200 条 1.8 天 —— 都撑不起「找上周那条失物招领」。
         * 300 条约 2.6 天，代价是 6 次 `/timeline` + 15 次 `/feed/batch`（服务端实测单次 ~80ms），
         * 首屏命中 1 页就出，全量扫完 2~3 秒，属于「用户愿意等、又不会一次打太多请求」的平衡点。
         */
        private const val DEFAULT_DEPTH = 300

        /** 「继续往前找」每次追加的深度 */
        private const val STEP = 300

        /** 硬上限：再深就是几百个请求，服务端限流阈值未知，不做 */
        private const val MAX_DEPTH = 1500

        /** 单页条数：`/timeline` 实测支持 50（返回 50 个 id，1.4KB） */
        private const val PAGE = 50

        private const val DEBOUNCE_MS = 300L

        fun start(context: Context) {
            context.startActivity(Intent(context, MomentSearchActivity::class.java))
        }
    }
}
