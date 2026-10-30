package com.lanhang.lite.oa

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.TextRef
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityOaHallBinding
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.OaWebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网上办事大厅（原生宫格）。
 *
 * 为什么不做「WebView 套官方工作台」：工作台要求原生注入 `loginCode/loginCookie/language`
 * 并主动调 `waitNativeCode()`，任何一个没对上就整页白屏；而应用列表本身有 HTTP 接口
 * （`POST /api/apps/all`）。原生渲染的好处是首屏不白屏、可搜索、接口挂了能给出可读错误。
 *
 * 打开应用的链路（与官方一致）：
 * ```
 * 点卡片 → GET /apps/{id} 拿 home_page_url（宫格已有就不发） → GET /apps/getAppToken/{id}
 *        → OaWebViewActivity（带 Token 头的新 WebView）
 * ```
 * 令牌是**一次性**的，所以每次点击都要重新取，不能缓存复用。
 */
class OaHallActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：每个 Activity 自己包，漏了这页会一直跟系统语言
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityOaHallBinding
    private val adapter = OaHallAdapter { app -> openApp(app) }

    private var rows: List<OaRow> = emptyList()
    private var loadJob: Job? = null

    /** 首次加载失败过：空态要显示错误原因 + 重试，而不是「没有应用」 */
    private var loadFailed = false

    /** 正在换 Token，防连点开两个 WebView */
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOaHallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // targetSdk 35 在 Android 15+ 强制 edge-to-edge，不补 inset 的话标题和搜索框会顶到状态栏里
        // （写法同 UserProfileActivity；insets 会多次回调，基准值先取）
        val baseBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 键盘也算「底部遮挡」（2026-10-28）：搜索时结果列表的下半截会被键盘盖住
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
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
                baseBottom + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }

        // 列数按屏宽算：窄屏 3 列、常规 4 列、平板 5 列（写死 4 列在 320dp 上会挤成两行竖排）
        val span = spanCount()
        val manager = GridLayoutManager(this, span)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == OaHallAdapter.TYPE_HEADER) span else 1
        }
        binding.recycler.layoutManager = manager
        binding.recycler.adapter = adapter

        binding.swipe.setOnRefreshListener { load(silent = true) }
        binding.btnRetry.setOnClickListener { load(silent = false) }
        binding.etSearch.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        load(silent = false)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun spanCount(): Int {
        val density = resources.displayMetrics.density
        val usable = resources.displayMetrics.widthPixels - (GRID_PADDING_DP * 2 * density)
        return (usable / (CELL_MIN_DP * density)).toInt().coerceIn(3, 5)
    }

    private fun load(silent: Boolean) {
        if (loadJob?.isActive == true) return
        if (!silent) {
            binding.progress.show()
            binding.emptyGroup.isVisible = false
        }
        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // 收藏只是排序加分项，单独失败不影响宫格
                    OaApi.apps() to OaApi.favorites()
                }
                loadFailed = false
                rows = buildRows(result.first, result.second)
                applyFilter(binding.etSearch.text?.toString().orEmpty())
                withContext(Dispatchers.IO) { OaCache.save(result.first, result.second) }
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@OaHallActivity, getString(R.string.oa_hall_tag), e)
                if (adapter.itemCount > 0) {
                    // 已有宫格（下拉刷新失败）：别把已经显示的内容换成错误页
                    Toast.makeText(
                        this@OaHallActivity,
                        getString(R.string.oa_hall_offline_stale),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val cached = withContext(Dispatchers.IO) { OaCache.load() }
                if (cached != null) {
                    // 断网兜底：显示上次的宫格。不算失败，所以不置 loadFailed
                    loadFailed = false
                    rows = buildRows(cached.first, cached.second)
                    applyFilter(binding.etSearch.text?.toString().orEmpty())
                    Toast.makeText(
                        this@OaHallActivity,
                        getString(R.string.oa_hall_offline_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                loadFailed = true
                adapter.submit(emptyList())
                binding.tvEmpty.text =
                    e.userMessage(this@OaHallActivity, R.string.oa_hall_load_failed)
                binding.emptyGroup.isVisible = true
            } finally {
                binding.progress.hide()
                binding.swipe.isRefreshing = false
            }
        }
    }

    /**
     * 摊平成「标题 + 应用」行。
     *
     * 排序：收藏优先，其次服务端 `order_num`。去重是必要的——同一个应用可能同时出现在
     * 基础应用和某个分类里，重复渲染会让用户以为有两个入口。
     */
    private fun buildRows(all: OaAppsAll, favorites: List<String>): List<OaRow> {
        val favSet = favorites.toSet()
        val seen = HashSet<String>()
        val out = mutableListOf<OaRow>()

        fun section(title: String, apps: List<OaApp>?) {
            val list = apps.orEmpty()
                .filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
                .filter { seen.add(it.id.orEmpty()) }
                .sortedWith(
                    compareBy<OaApp>(
                        { if (it.id.orEmpty() in favSet) 0 else 1 },
                        { it.orderNum ?: Int.MAX_VALUE }
                    )
                )
            if (list.isEmpty()) return
            out += OaRow.Header(title)
            list.forEach { out += OaRow.App(it) }
        }

        section(getString(R.string.oa_hall_basic), all.type1?.apps)
        section(getString(R.string.oa_hall_other), all.type2?.apps)
        all.type2?.types
            ?.sortedBy { it.orderNum ?: Int.MAX_VALUE }
            ?.forEach { group ->
                section(group.typeName.orEmpty().ifBlank { getString(R.string.oa_hall_other) }, group.apps)
            }
        return out
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val visible = if (q.isEmpty()) rows else filterRows(q)
        adapter.submit(visible)
        val empty = visible.isEmpty()
        binding.emptyGroup.isVisible = empty
        if (empty) {
            binding.tvEmpty.setText(if (loadFailed) R.string.oa_hall_load_failed_hint else R.string.oa_hall_empty)
        }
    }

    /** 搜索时丢掉空的分组标题：留一个没有卡片的小标题比不显示更让人困惑 */
    private fun filterRows(query: String): List<OaRow> {
        val out = mutableListOf<OaRow>()
        var header: String? = null
        var headerAdded = false
        for (row in rows) {
            when (row) {
                is OaRow.Header -> {
                    header = row.title
                    headerAdded = false
                }

                is OaRow.App -> if (row.app.matches(query)) {
                    if (!headerAdded && header != null) {
                        out += OaRow.Header(header)
                        headerAdded = true
                    }
                    out += row
                }
            }
        }
        return out
    }

    private fun openApp(app: OaApp) {
        val appId = app.id?.takeIf { it.isNotBlank() } ?: return
        if (opening) return
        val cached = app.homePageUrl?.takeIf { it.isNotBlank() }
        if (cached != null && cached.startsWith("rce://")) {
            toast(R.string.oa_hall_need_official)
            return
        }
        opening = true
        lifecycleScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    // 宫格里已经带了落地页就不再查一次；没有才回源（下架的应用会在这一步失败）
                    val url = cached ?: OaApi.appInfo(appId).homePageUrl
                    if (url.isNullOrBlank() || url.startsWith("rce://")) {
                        throw ApiException(-1, TextRef.Res(R.string.oa_hall_no_page))
                    }
                    url to (OaApi.appToken(appId) ?: OaApi.freshLdapToken())
                }
                OaWebViewActivity.open(this@OaHallActivity, target.first, target.second, app.name)
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@OaHallActivity, getString(R.string.oa_hall_tag), e)
                toast(e.userMessage(this@OaHallActivity, R.string.oa_hall_open_failed))
            } finally {
                opening = false
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 宫格左右各留 12dp（item 自带 4dp margin，视觉上与 16dp 页面留白对齐） */
        const val GRID_PADDING_DP = 12

        /** 单个格子最小宽度，低于这个值就少一列 */
        const val CELL_MIN_DP = 84
    }
}
