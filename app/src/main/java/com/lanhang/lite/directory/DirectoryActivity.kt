package com.lanhang.lite.directory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.databinding.ActivityDirectoryBinding
import com.lanhang.lite.moments.UserProfileActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「成员录」：按学校组织架构浏览成员 + 按姓名/学号/手机号搜索，直达个人主页。
 *
 * 不含私聊 / 好友关系等任何通信能力（那需要第三方 IM 库，明确不在本模块范围），
 * 唯一出口是 [UserProfileActivity] 个人主页。
 *
 * 数据流（对齐官方 `OrganizationTask` / `FriendTask`，端点见 [com.lanhang.lite.net.RceApi]）：
 * - 进入拉一次 `/departments/tree`（12h 磁盘缓存，见 [DirectoryRepository]），根层只列根节点；
 * - 点部门下钻 → `/departments/{id}/branches` 分页（6h 缓存），触底自动加载下一页；
 * - 搜索 260ms 防抖 → `/staffs/search`（姓名/学号/工号），11 位手机号再补 `/user/search/mobile`，
 *   本地树同步过滤出「部门」命中；
 * - 返回键：先清搜索 → 再退一级目录 → 根层才退出。
 */
class DirectoryActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：每个 Activity 自己包，漏了这页会一直跟系统语言
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityDirectoryBinding
    private lateinit var adapter: DirectoryAdapter

    /** 组织树；null = 未加载 */
    private var tree: List<Department>? = null
    private var treeError: String? = null
    private var byId: Map<String, Department> = emptyMap()

    /** 下钻栈：空 = 根层（只列根节点） */
    private val path = mutableListOf<Department>()

    /** 进程重建时先存 id，等树加载完再解析成 [path] */
    private var pendingPathIds: List<String> = emptyList()

    /** 当前下钻层的条目（子部门+成员）；null = 未加载。根层不走这里 */
    private var levelEntries: List<OrgEntry>? = null
    private var levelMore = true
    private var levelLoading = false
    private var levelError: String? = null

    /** 下钻层内存缓存：返回上一级时不闪「加载中」（LRU，进程结束即失效） */
    private val levelCache = object : LinkedHashMap<String, List<OrgEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OrgEntry>>?): Boolean =
            size > 24
    }

    /** id → 学号（branches 不带学号，每页用 /staffs/batch 补） */
    private val studentNos = mutableMapOf<String, String>()

    /** 搜索命中（人员）；部门命中直接从 [tree] 现算 */
    private var staffHits: List<StaffSearchHit> = emptyList()
    private var searching = false
    private var debounceJob: Job? = null

    /** 搜索代际：结果回来时关键词已变就丢弃 */
    private var searchGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDirectoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // insets 会多次回调，基准值必须先取：直接读当前 padding 会一次次累加
        val baseRecyclerBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 键盘也算「底部遮挡」：edge-to-edge 下 adjustResize 不改窗口高度，
            // 不补 ime 高度列表下半截会一直躲在键盘后面（与 MomentSearchActivity 同一处理）
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0,
            )
            binding.recycler.setPadding(
                binding.recycler.paddingLeft,
                binding.recycler.paddingTop,
                binding.recycler.paddingRight,
                baseRecyclerBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        adapter = DirectoryAdapter(
            onDeptClick = { openDept(it) },
            onMemberClick = { openProfile(it.id) },
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val last = lm.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 3) loadMore()
            }
        })

        binding.btnRetry.setOnClickListener { retry() }
        binding.etQuery.doAfterTextChanged { onQueryChanged() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = backOnce()
        })

        savedInstanceState?.let {
            pendingPathIds = it.getStringArrayList(STATE_PATH).orEmpty()
            val q = it.getString(STATE_QUERY).orEmpty()
            if (q.isNotBlank()) {
                binding.etQuery.setText(q)
                binding.etQuery.setSelection(q.length)
            }
        }

        loadTree()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_PATH, ArrayList(path.map { it.id }))
        outState.putString(STATE_QUERY, binding.etQuery.text?.toString().orEmpty())
    }

    // ------------------------------------------------------------------ 返回

    /**
     * 返回一步：**先退出搜索**，再退一级目录，最后才退出页面。
     * 搜索态算「一层」（输入了关键词按返回应回到刚才那页浏览，而不是直接退出）。
     */
    private fun backOnce() {
        if (binding.etQuery.text?.isNotBlank() == true) {
            binding.etQuery.setText("")
            return
        }
        if (path.isNotEmpty()) {
            path.removeAt(path.lastIndex)
            restoreLevelFromCache()
            render()
            if (levelEntries == null) loadLevel(reset = true)
            return
        }
        finish()
    }

    // ------------------------------------------------------------------ 组织树

    private fun loadTree() {
        render()
        lifecycleScope.launch {
            treeError = null
            val result = runCatching {
                withContext(Dispatchers.IO) { DirectoryRepository.tree() }
            }
            result.onSuccess { list ->
                tree = list
                byId = list.associateBy { it.id }
                // 进程重建后把下钻栈按 id 解析回来（树里有全量节点）
                if (pendingPathIds.isNotEmpty()) {
                    path.clear()
                    pendingPathIds.forEach { id -> byId[id]?.let { path.add(it) } }
                    pendingPathIds = emptyList()
                    restoreLevelFromCache()
                    if (levelEntries == null) loadLevel(reset = true)
                }
                render()
            }
            result.onFailure { e ->
                if (e.isCancellation) return@launch
                // 空串也行：它是「失败了」的标记，文案由渲染层兜底
                treeError = e.message ?: ""
                render()
            }
        }
    }

    private fun retry() {
        val q = binding.etQuery.text?.isNotBlank() == true
        when {
            tree == null -> loadTree()
            q -> onQueryChanged(force = true)
            path.isNotEmpty() -> loadLevel(reset = true)
            else -> loadTree()
        }
    }

    // ------------------------------------------------------------------ 下钻

    /** 当前层的缓存键：根层不缓存（根层来自树，不走 branches） */
    private fun currentLevelKey(): String =
        path.joinToString("/") { it.id }.ifBlank { "root" }

    private fun openDept(row: DirectoryRow.Dept) {
        // 搜索态点部门 = 「跳到该部门的下钻层」，不是「再往里钻一层」
        if (binding.etQuery.text?.isNotBlank() == true) {
            val dept = byId[row.id]
            if (dept != null) {
                jumpToDept(dept)
                return
            }
        }
        val dept = byId[row.id]
        if (dept != null) {
            path.add(dept)
        } else {
            // 树里没有的兜底（理论上不会发生）：按行数据造一个最小节点
            path.add(Department(id = row.id, name = row.name, fullName = row.pathLabel ?: row.name))
        }
        binding.etQuery.setText("")
        restoreLevelFromCache()
        render()
        if (levelEntries == null) loadLevel(reset = true)
    }

    /** 搜索态点部门：把下钻栈直接设成「根 → … → 该部门」 */
    private fun jumpToDept(dept: Department) {
        path.clear()
        path.addAll(ancestorsOf(dept))
        binding.etQuery.setText("")
        restoreLevelFromCache()
        render()
        if (levelEntries == null) loadLevel(reset = true)
    }

    /** 换层后先吃内存缓存（不闪加载态），错误位一并清掉 */
    private fun restoreLevelFromCache() {
        levelEntries = levelCache[currentLevelKey()]
        levelMore = true
        levelError = null
    }

    private fun loadLevel(reset: Boolean) {
        val dept = path.lastOrNull() ?: return
        if (levelLoading && !reset) return
        levelLoading = true
        if (reset) {
            levelEntries = null
            levelError = null
            render()
        }
        val offset = if (reset) 0 else (levelEntries?.size ?: 0)
        lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    DirectoryRepository.branches(dept.id, offset, DirectoryRepository.PAGE_SIZE)
                }
                val merged = if (reset) page.data else (levelEntries ?: emptyList()) + page.data
                levelEntries = merged
                levelCache[currentLevelKey()] = merged
                levelMore = page.data.isNotEmpty() &&
                    merged.size < (page.totalCount.takeIf { it > 0 } ?: merged.size)
                levelError = null
                // branches 不带学号：批量补一次，回来重绘（行副标题要显示）
                val ids = merged.filterNot { it.isDepartment }.map { it.id }
                val nos = withContext(Dispatchers.IO) { DirectoryRepository.studentNos(ids) }
                studentNos.putAll(nos)
                render()
            } catch (e: Throwable) {
                if (e.isCancellation) return@launch
                // 首屏失败才显示错误位；翻页失败保留已有内容，静默等下次触底
                if (reset) {
                    levelError = e.message ?: ""
                    levelEntries = emptyList()
                    render()
                }
            } finally {
                levelLoading = false
            }
        }
    }

    private fun loadMore() {
        if (binding.etQuery.text?.isNotBlank() == true) return
        if (path.isEmpty()) return
        if (levelLoading || !levelMore || levelEntries == null) return
        loadLevel(reset = false)
    }

    // ------------------------------------------------------------------ 搜索

    private fun onQueryChanged(force: Boolean = false) {
        val q = binding.etQuery.text?.toString()?.trim().orEmpty()
        searchGeneration++
        debounceJob?.cancel()
        if (q.isBlank()) {
            searching = false
            staffHits = emptyList()
            render()
            return
        }
        // 关键词一变立刻切到搜索态（本地部门命中即时可见），人员命中等防抖后的请求
        render()
        val gen = searchGeneration
        debounceJob = lifecycleScope.launch {
            if (!force) delay(DEBOUNCE_MS)
            if (gen != searchGeneration) return@launch
            searching = true
            render()
            try {
                val hits = withContext(Dispatchers.IO) { DirectoryRepository.search(q) }
                if (gen != searchGeneration) return@launch
                staffHits = hits
                // 移动号搜到的人可能缺学号，searchMembers 内部已补过 staffNo；再喂进本地缓存
                hits.forEach { h -> h.studentNo?.let { studentNos[h.id] = it } }
                searching = false
                render()
            } catch (e: Throwable) {
                if (e.isCancellation) return@launch
                if (gen != searchGeneration) return@launch
                // 搜索失败按「没找到」处理，不弹错误打断输入（与参考实现一致）
                searching = false
                render()
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val query = binding.etQuery.text?.toString()?.trim().orEmpty()
        val rows = mutableListOf<DirectoryRow>()
        var emptyText: String? = null
        var showRetry = false
        var showProgress = false

        if (query.isNotBlank()) {
            // ---- 搜索态
            val deptHits = (tree ?: emptyList())
                .filter { it.name.contains(query, true) || it.fullName.contains(query, true) }
                .sortedBy { it.memberCount }
                .take(80)
            when {
                staffHits.isEmpty() && deptHits.isEmpty() && searching -> showProgress = true
                staffHits.isEmpty() && deptHits.isEmpty() ->
                    emptyText = getString(com.lanhang.lite.R.string.directory_no_match)
            }
            if (staffHits.isNotEmpty()) {
                rows.add(DirectoryRow.Section(getString(com.lanhang.lite.R.string.directory_search_people)))
                staffHits.forEach { h ->
                    rows.add(
                        DirectoryRow.Member(
                            id = h.id,
                            name = h.name.ifBlank { "--" },
                            subtitle = listOfNotNull(
                                h.studentNo ?: studentNos[h.id],
                                h.dutyName.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").takeIf { it.isNotBlank() },
                            portraitUrl = h.portraitUrl.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
            if (deptHits.isNotEmpty()) {
                rows.add(DirectoryRow.Section(getString(com.lanhang.lite.R.string.directory_search_depts)))
                deptHits.forEach { d ->
                    rows.add(
                        DirectoryRow.Dept(
                            id = d.id,
                            name = d.name,
                            badge = null,
                            pathLabel = ancestorsOf(d).joinToString(" / ") { it.name },
                        ),
                    )
                }
            }
        } else when {
            // ---- 树未就绪
            tree == null && treeError == null -> showProgress = true
            tree == null -> {
                emptyText = treeError?.takeIf { it.isNotBlank() }
                    ?: getString(com.lanhang.lite.R.string.directory_load_failed)
                showRetry = true
            }
            // ---- 根层
            path.isEmpty() -> {
                val roots = tree.orEmpty().filter { it.parentId.isBlank() }
                if (roots.isEmpty()) {
                    emptyText = getString(com.lanhang.lite.R.string.directory_dept_empty)
                } else {
                    rows.add(DirectoryRow.Section(getString(com.lanhang.lite.R.string.directory_root_section)))
                    roots.forEach { d ->
                        rows.add(
                            DirectoryRow.Dept(
                                id = d.id,
                                name = d.name,
                                badge = d.memberCount.takeIf { it > 0 }
                                    ?.let { getString(com.lanhang.lite.R.string.directory_member_count, it) },
                            ),
                        )
                    }
                }
            }
            // ---- 下钻层
            else -> {
                val dept = path.last()
                rows.add(DirectoryRow.Section(dept.fullName.ifBlank { dept.name }))
                val entries = levelEntries
                when {
                    levelError != null -> {
                        emptyText = levelError?.takeIf { it.isNotBlank() }
                            ?: getString(com.lanhang.lite.R.string.directory_load_failed)
                        showRetry = true
                    }
                    entries == null -> showProgress = true
                    else -> {
                        val depts = entries.filter { it.isDepartment }
                        val members = entries.filterNot { it.isDepartment }
                        if (depts.isEmpty() && members.isEmpty()) {
                            emptyText = getString(com.lanhang.lite.R.string.directory_dept_empty)
                        }
                        if (depts.isNotEmpty()) {
                            rows.add(DirectoryRow.Section(getString(com.lanhang.lite.R.string.directory_sub_section)))
                            depts.forEach { e ->
                                rows.add(
                                    DirectoryRow.Dept(
                                        id = e.id,
                                        name = e.name,
                                        badge = e.memberCount.takeIf { it > 0 }
                                            ?.let { getString(com.lanhang.lite.R.string.directory_member_count, it) },
                                    ),
                                )
                            }
                        }
                        if (members.isNotEmpty()) {
                            rows.add(DirectoryRow.Section(getString(com.lanhang.lite.R.string.directory_member_section)))
                            members.forEach { e ->
                                rows.add(
                                    DirectoryRow.Member(
                                        id = e.id,
                                        name = e.name.ifBlank { "--" },
                                        subtitle = listOfNotNull(
                                            studentNos[e.id],
                                            e.dutyName.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ").takeIf { it.isNotBlank() },
                                        portraitUrl = e.portraitUrl.takeIf { it.isNotBlank() },
                                    ),
                                )
                            }
                            if (levelMore && !levelLoading) {
                                rows.add(DirectoryRow.LoadingMore)
                            }
                        }
                    }
                }
            }
        }

        adapter.submit(rows)
        val hasData = rows.any { it is DirectoryRow.Dept || it is DirectoryRow.Member }
        binding.progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility = if (emptyText != null && !hasData) View.VISIBLE else View.GONE
        binding.tvEmpty.text = emptyText.orEmpty()
        binding.btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
    }

    private fun openProfile(userId: String) {
        if (userId.isBlank()) return
        UserProfileActivity.start(this, userId)
    }

    /** 从根到该节点的完整路径（含自身），与参考实现同构 */
    private fun ancestorsOf(dept: Department): List<Department> {
        val out = ArrayList<Department>()
        var cursor: Department? = dept
        var guard = 0
        while (cursor != null && guard++ < 32) {
            out.add(0, cursor)
            val parentId = cursor.parentId
            cursor = if (parentId.isBlank()) null else byId[parentId]
        }
        return out
    }

    companion object {
        private const val STATE_PATH = "directory_path"
        private const val STATE_QUERY = "directory_query"

        /** 搜索防抖：连着敲字只打最后一次 */
        private const val DEBOUNCE_MS = 260L

        fun start(context: Context) {
            context.startActivity(Intent(context, DirectoryActivity::class.java))
        }
    }
}
