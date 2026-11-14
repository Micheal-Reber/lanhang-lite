package com.lanhang.lite.oa

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.TextRef
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.toast
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.FragmentOaHallBinding
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.OaWebViewActivity
import com.lanhang.lite.ui.navigation.BottomBarDims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「工作」Tab：网上办事大厅（原生宫格）。原来是独立 Activity（OaHallActivity），
 * 2026-11-14 用户要求改成底栏常驻 Tab 并改名「工作」，成绩从设置里当二级页打开。
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
 *
 * 顶栏、系统栏 inset、语言包装全归宿主 MainActivity 管（Fragment 跟随宿主）；
 * 这里只管自己那块：搜索框、宫格与底部留白。
 *
 * **没有下拉刷新**（2026-11-14 用户要求取消）：宫格由 [OaCache] 的时效驱动 ——
 * 新鲜期（1 小时）内重进完全不联网，过期后先渲染缓存再后台静默替换。
 */
class OaHallFragment : Fragment() {

    private var _binding: FragmentOaHallBinding? = null
    private val binding get() = _binding!!
    private val adapter = OaHallAdapter { app -> openApp(app) }

    private var rows: List<OaRow> = emptyList()
    private var loadJob: Job? = null

    /** 首次加载失败过：空态要显示错误原因 + 重试，而不是「没有应用」 */
    private var loadFailed = false

    /** 正在换 Token，防连点开两个 WebView */
    private var opening = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOaHallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 底部留白按底栏形态给（玻璃 112dp / 原生 88dp，见 BottomBarDims），
        // 写死在 xml 里的话切换底栏形态不会跟着变。
        val baseBottom = BottomBarDims.contentBottomPadding(requireContext())
        // 键盘也算「底部遮挡」（2026-10-28，独立页时代就有）：搜索时结果列表的下半截会被键盘盖住。
        // 宿主 activity root 已经让开过 bars.bottom（见 MainActivity 的 inset 监听），
        // 所以这里只补「键盘高出导航栏的那一段」，否则有导航栏的设备会空出双份。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            _binding?.recycler?.updatePadding(
                bottom = baseBottom + maxOf(0, ime.bottom - bars.bottom)
            )
            insets
        }

        // 列数按屏宽算：窄屏 3 列、常规 4 列、平板 5 列（写死 4 列在 320dp 上会挤成两行竖排）
        val span = spanCount()
        val manager = GridLayoutManager(requireContext(), span)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == OaHallAdapter.TYPE_HEADER) span else 1
        }
        binding.recycler.layoutManager = manager
        binding.recycler.adapter = adapter

        binding.btnRetry.setOnClickListener { load() }
        binding.etSearch.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        load()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun spanCount(): Int {
        val density = resources.displayMetrics.density
        val usable = resources.displayMetrics.widthPixels - (GRID_PADDING_DP * 2 * density)
        return (usable / (CELL_MIN_DP * density)).toInt().coerceIn(3, 5)
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        if (_binding == null) return
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            // Fragment 的 getString / startActivity 都要求还挂在宿主上：
            // 协程体跑到一半视图可能已销毁，统一用 ctx 并对 UI 判空。
            val ctx = context ?: return@launch
            // 首屏缓存优先（2026-11-14 用户反馈「每次重开 App 宫格和图标都要重新加载一遍」）：
            // 进程重启后先渲染上次的宫格（图标走 Coil 磁盘缓存秒出、不闪加载圈）。
            var fromCache = false
            var fresh = false
            if (adapter.itemCount == 0) {
                val cached = withContext(Dispatchers.IO) { OaCache.load() }
                if (cached != null && _binding != null) {
                    fromCache = true
                    fresh = cached.fresh
                    loadFailed = false
                    rows = buildRows(ctx, cached.apps, cached.favorites)
                    applyFilter(_binding?.etSearch?.text?.toString().orEmpty())
                }
            }
            // 🔴 缓存还在新鲜期内（1 小时）→ 到此为止，一个网络请求都不发。
            // 这是「退出重进还是会刷新一遍」的正解：旧实现即使缓存命中也照样后台联网、
            // 再 notifyDataSetChanged 整表重绑，图标闪一遍，看着就是又加载了一次。
            // 此处 return 不经下面的 try/finally —— progress 也没展示过（fromCache=true 跳过了）。
            if (fresh) return@launch
            // 缓存命中时不转加载圈：老宫格已经在屏上，转圈反而像「又要重新加载」
            if (!fromCache) {
                _binding?.apply {
                    progress.show()
                    emptyGroup.isVisible = false
                }
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    // 收藏只是排序加分项，单独失败不影响宫格
                    OaApi.apps() to OaApi.favorites()
                }
                loadFailed = false
                // 内容没变就不重绑（OaRow 是 data class，结构相等即可判断）：
                // 静默刷新每次启动都会跑，无脑 notifyDataSetChanged 会让整屏图标闪一次
                val newRows = buildRows(ctx, result.first, result.second)
                if (newRows != rows) {
                    rows = newRows
                    applyFilter(_binding?.etSearch?.text?.toString().orEmpty())
                }
                withContext(Dispatchers.IO) { OaCache.save(result.first, result.second) }
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(ctx, ctx.getString(R.string.oa_hall_tag), e)
                if (adapter.itemCount > 0) {
                    // 已有宫格（缓存首屏后静默刷新失败）：别把已经显示的内容换成错误页
                    ctx.toast(
                        ctx.getString(
                            if (fromCache) R.string.oa_hall_offline_toast
                            else R.string.oa_hall_offline_stale
                        )
                    )
                    return@launch
                }
                // 走到这里说明上面缓存首屏也没命中（没有或已过期），缓存兜底这条路不必再走一遍
                loadFailed = true
                adapter.submit(emptyList())
                val screen = _binding ?: return@launch
                screen.tvEmpty.text = e.userMessage(ctx, R.string.oa_hall_load_failed)
                screen.emptyGroup.isVisible = true
            } finally {
                _binding?.progress?.hide()
            }
        }
    }

    /**
     * 摊平成「标题 + 应用」行。
     *
     * 排序：收藏优先，其次服务端 `order_num`。去重是必要的——同一个应用可能同时出现在
     * 基础应用和某个分类里，重复渲染会让用户以为有两个入口。
     */
    private fun buildRows(ctx: android.content.Context, all: OaAppsAll, favorites: List<String>): List<OaRow> {
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

        section(ctx.getString(R.string.oa_hall_basic), all.type1?.apps)
        section(ctx.getString(R.string.oa_hall_other), all.type2?.apps)
        all.type2?.types
            ?.sortedBy { it.orderNum ?: Int.MAX_VALUE }
            ?.forEach { group ->
                section(
                    group.typeName.orEmpty().ifBlank { ctx.getString(R.string.oa_hall_other) },
                    group.apps
                )
            }
        // 「赣青二课」（2026-11-14 用户要求接入「工作」）：江西省高校第二课堂成绩单平台，
        // 校外独立站点（`gqhw.jxmediacenter.com`），服务端 `/apps/all` 里没有它，所以本地补一条。
        // 不做原生页：那套系统是若依 + uni-app 的几十页 H5，且全程要登录态，WebView 承载就是完整接入。
        // 排在最后而不是顶部：常用应用要一眼可见，二课属于低频入口（搜索也找得到）。
        out += OaRow.Header(ctx.getString(R.string.oa_hall_club_section))
        out += OaRow.App(
            OaApp(
                id = OaNativeApps.GQHW_APP_ID,
                name = ctx.getString(R.string.gqhw_name),
                description = ctx.getString(R.string.gqhw_desc),
                homePageUrl = OaNativeApps.GQHW_URL,
                orderNum = 0
            )
        )
        return out
    }

    private fun applyFilter(query: String) {
        val screen = _binding ?: return
        val q = query.trim()
        val visible = if (q.isEmpty()) rows else filterRows(q)
        adapter.submit(visible)
        val empty = visible.isEmpty()
        screen.emptyGroup.isVisible = empty
        if (empty) {
            screen.tvEmpty.setText(if (loadFailed) R.string.oa_hall_load_failed_hint else R.string.oa_hall_empty)
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
        val ctx = context ?: return
        // 「赣青二课」是校外独立站点：**不走 OA 网关、不取 appToken**。
        // 取来的 Token 头对校外站毫无意义，带过去反而可能被当成非法客户端。
        if (appId == OaNativeApps.GQHW_APP_ID) {
            OaWebViewActivity.open(ctx, OaNativeApps.GQHW_URL, appToken = null, title = app.name)
            return
        }
        val cached = app.homePageUrl?.takeIf { it.isNotBlank() }
        if (cached != null && cached.startsWith("rce://")) {
            toast(R.string.oa_hall_need_official)
            return
        }
        opening = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    // 宫格里已经带了落地页就不再查一次；没有才回源（下架的应用会在这一步失败）
                    val url = cached ?: OaApi.appInfo(appId).homePageUrl
                    if (url.isNullOrBlank() || url.startsWith("rce://")) {
                        throw ApiException(-1, TextRef.Res(R.string.oa_hall_no_page))
                    }
                    url to (OaApi.appToken(appId) ?: OaApi.freshLdapToken())
                }
                OaWebViewActivity.open(ctx, target.first, target.second, app.name)
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(ctx, ctx.getString(R.string.oa_hall_tag), e)
                ctx.toast(e.userMessage(ctx, R.string.oa_hall_open_failed))
            } finally {
                opening = false
            }
        }
    }


    private companion object {
        /** 宫格左右各留 12dp（item 自带 4dp margin，视觉上与 16dp 页面留白对齐） */
        const val GRID_PADDING_DP = 12

        /** 单个格子最小宽度，低于这个值就少一列 */
        const val CELL_MIN_DP = 84
    }
}
