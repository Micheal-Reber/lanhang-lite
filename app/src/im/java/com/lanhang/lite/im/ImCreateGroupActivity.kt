package com.lanhang.lite.im

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.toast
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityImCreateGroupBinding
import com.lanhang.lite.databinding.ItemDirectorySectionBinding
import com.lanhang.lite.databinding.ItemImPickMemberBinding
import com.lanhang.lite.databinding.ItemImPickSelectedBinding
import com.lanhang.lite.directory.DirectoryRepository
import com.lanhang.lite.directory.StaffSearchHit
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发起群聊：多选成员 → 填群名 → `POST /groups` 建群 → 直接进聊天页。
 *
 * 数据源只有「我的好友 + 搜索」，**不做组织树浏览**：服务端对「成员里有非好友」回 10606
 * （官方 `SetGroupInfoActivity` 硬编码提示「非好友不能建群」），从组织树随手勾一个人
 * 大概率建不成，列表里摆一堆点不动的人只会让人困惑。搜索走成员录同一个接口
 * （[DirectoryRepository.search]），能不能建由服务端判定，失败时把 10606 翻成人话。
 *
 * 选 1 人不建群：官方 `CreateGroupActivity` 在「只选了一个人」时直接开单聊，
 * 这里照做（建一个两人群没有意义）。
 *
 * 与共享的 [com.lanhang.lite.directory.DirectoryActivity] 分开是刻意的：那页的文档写着
 * 「不含任何通信能力」，把建群塞进去会破坏那条边界。本页只在 im 变体里存在。
 */
class ImCreateGroupActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：每个 Activity 自己包，漏了这页会一直跟系统语言
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityImCreateGroupBinding
    private val adapter = Adapter()

    /** 一个可选成员（好友或搜索命中） */
    private data class Member(
        val id: String,
        val name: String,
        val portrait: String?,
        /** 副标题：搜索命中显示学号；好友不显示（[com.lanhang.lite.net.FriendInfo] 没有学号） */
        val subtitle: String?,
    )

    private sealed interface Row {
        data class Section(val text: String) : Row
        data class Person(val member: Member) : Row
    }

    private var friends: List<Member> = emptyList()
    private var friendsLoading = false

    private var results: List<Member> = emptyList()
    private var searching = false
    private var debounceJob: Job? = null

    /** 搜索代际：结果回来时关键词已变就丢弃（与成员录同一套防抖） */
    private var searchGeneration = 0

    /** 已勾选的成员，**保持勾选顺序**（默认群名按这个顺序拼） */
    private val selected = LinkedHashMap<String, Member>()

    /** 正在建群：期间锁住「创建」，防止连点开出两个群 */
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // insets 会多次回调，基准值必须先取：直接读当前 padding 会一次次累加
        val baseRecyclerBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 键盘也算「底部遮挡」（与成员录同一处理，否则列表下半截躲在键盘后面）
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val tb = binding.toolbar
            tb.setPadding(tb.paddingLeft, bars.top, tb.paddingRight, 0)
            binding.recycler.setPadding(
                binding.recycler.paddingLeft,
                binding.recycler.paddingTop,
                binding.recycler.paddingRight,
                baseRecyclerBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        // 菜单在布局里用 app:menu 挂（NoActionBar 主题下 onCreateOptionsMenu 不会被调用）
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_im_create_group) {
                onCreateClicked()
                true
            } else {
                false
            }
        }
        binding.etQuery.doAfterTextChanged { onQueryChanged() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        loadFriends()
    }

    // ------------------------------------------------------------------ 好友

    private fun loadFriends() {
        friendsLoading = true
        render()
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { RceApi.friends() }
            } catch (e: Throwable) {
                if (e.isCancellation) throw e
                // 拉不到好友就当没有：搜索这条路仍然可用，不弹错误打断选人
                emptyList()
            }
            friends = list.mapNotNull { f ->
                val id = f.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Member(
                    id = id,
                    name = f.name.orEmpty().ifBlank { id },
                    portrait = f.portrait_url?.takeIf { it.isNotBlank() },
                    subtitle = null,
                )
            }
            friendsLoading = false
            render()
        }
    }

    // ------------------------------------------------------------------ 搜索

    private fun onQueryChanged() {
        val q = binding.etQuery.text?.toString()?.trim().orEmpty()
        searchGeneration++
        debounceJob?.cancel()
        if (q.isBlank()) {
            searching = false
            results = emptyList()
            render()
            return
        }
        render()
        val gen = searchGeneration
        debounceJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            if (gen != searchGeneration) return@launch
            searching = true
            render()
            try {
                val hits = withContext(Dispatchers.IO) { DirectoryRepository.search(q) }
                if (gen != searchGeneration) return@launch
                results = hits.map { it.toMember() }
                searching = false
                render()
            } catch (e: Throwable) {
                if (e.isCancellation) return@launch
                if (gen != searchGeneration) return@launch
                // 搜索失败按「没找到」处理，不弹错误打断输入（与成员录一致）
                searching = false
                render()
            }
        }
    }

    private fun StaffSearchHit.toMember() = Member(
        id = id,
        name = name.ifBlank { id },
        portrait = portraitUrl.takeIf { it.isNotBlank() },
        // 学号走 studentNo：staff_no 可能是完整邮箱（红线见 AGENTS.md）
        subtitle = studentNo,
    )

    // ------------------------------------------------------------------ 选择

    private fun toggle(member: Member) {
        if (selected.containsKey(member.id)) selected.remove(member.id)
        else selected[member.id] = member
        adapter.notifyDataSetChanged()
        renderSelected()
        renderMenu()
    }

    /** 已选成员条：点一下取消选择（多选时来回滚动会找不到自己选了谁） */
    private fun renderSelected() {
        val row = binding.selectedRow
        row.removeAllViews()
        for (member in selected.values) {
            val cell = ItemImPickSelectedBinding
                .inflate(LayoutInflater.from(this), row, false)
            cell.ivAvatar.bind(member.name, member.portrait)
            cell.tvName.text = member.name
            cell.root.setOnClickListener { toggle(member) }
            row.addView(cell.root)
        }
        binding.selectedScroll.isVisible = selected.isNotEmpty()
    }

    /** 右侧「创建」动作带上人数：选了几个不用回头找已选条 */
    private fun renderMenu() {
        val item = binding.toolbar.menu.findItem(R.id.action_im_create_group) ?: return
        item.title = if (selected.isEmpty() || creating) {
            getString(R.string.im_create_group_confirm)
        } else {
            getString(R.string.im_create_group_confirm_n, selected.size)
        }
        item.isEnabled = !creating
    }

    // ------------------------------------------------------------------ 创建

    private fun onCreateClicked() {
        if (creating) return
        if (selected.isEmpty()) {
            toast(R.string.im_create_group_need_member)
            return
        }
        // 官方同样退化：只选一个人直接开单聊，不建两人群
        if (selected.size == 1) {
            val only = selected.values.first()
            ImChatActivity.start(this, only.id, false, only.name)
            finish()
            return
        }
        askGroupName()
    }

    private fun askGroupName() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        val input = TextInputLayout(this).apply {
            hint = getString(R.string.im_create_group_name)
        }
        val edit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setText(defaultGroupName())
            setSelection(text?.length ?: 0)
        }
        input.addView(edit)
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.im_create_group)
            .setView(container)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.im_create_group_do) { _, _ ->
                val name = edit.text?.toString()?.trim().orEmpty().ifBlank { defaultGroupName() }
                createGroup(name)
            }
            .show()
    }

    /**
     * 默认群名：成员姓名用「、」拼，总长不超过 16 字（照官方 `generateDefaultGroupName`）。
     *
     * 官方的规则是「拼到放不下就停」，不是截断 —— 直接 substring 会把最后一个人的名字
     * 砍成半截，比「少一个人」难看得多。
     */
    private fun defaultGroupName(): String {
        var name = ""
        for (member in selected.values) {
            val n = member.name
            if (n.isBlank() || n.length > 16) continue
            if (name.length + n.length > 16) break
            name += n + "、"
            if (name.length >= 16) break
        }
        return name.removeSuffix("、")
    }

    private fun createGroup(name: String) {
        if (creating) return
        creating = true
        renderMenu()
        lifecycleScope.launch {
            try {
                val groupId = withContext(Dispatchers.IO) {
                    // 🔴 member_ids 必须含自己：官方 CreateGroupActivity 会补当前 userId，
                    // 漏掉就建出一个自己不在里面的群（AGENTS 建群协议红线）
                    val ids = (listOf(Session.userId) + selected.keys).distinct()
                    RceApi.createGroup(name, ids)
                }
                if (groupId.isNullOrBlank()) {
                    toast(R.string.im_create_group_failed, getString(R.string.error_request_failed))
                    return@launch
                }
                ImClient.registerCreatedGroup(groupId, name)
                ImChatActivity.start(this@ImCreateGroupActivity, groupId, true, name)
                finish()
            } catch (e: Throwable) {
                if (e.isCancellation) return@launch
                val msg = if (e is ApiException && e.code == CODE_NOT_FRIEND) {
                    // 服务端 10606：成员里有非好友（官方界面硬编码「非好友不能建群」）
                    getString(R.string.im_create_group_not_friend)
                } else {
                    e.userMessage(this@ImCreateGroupActivity, R.string.im_create_group_failed)
                }
                toast(msg)
            } finally {
                creating = false
                renderMenu()
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val query = binding.etQuery.text?.toString()?.trim().orEmpty()
        val rows = mutableListOf<Row>()
        var emptyText: String? = null
        var showProgress = false

        if (query.isBlank()) {
            when {
                friendsLoading -> showProgress = true
                friends.isEmpty() -> emptyText = getString(R.string.im_create_group_empty_friends)
                else -> {
                    rows += Row.Section(getString(R.string.im_create_group_friends))
                    friends.forEach { rows += Row.Person(it) }
                }
            }
        } else {
            when {
                searching -> showProgress = true
                results.isEmpty() -> emptyText = getString(R.string.im_create_group_no_match)
                else -> {
                    rows += Row.Section(getString(R.string.im_create_group_results))
                    results.forEach { rows += Row.Person(it) }
                }
            }
        }

        adapter.submit(rows)
        binding.progress.isVisible = showProgress
        binding.tvEmpty.isVisible = emptyText != null
        binding.tvEmpty.text = emptyText
        renderSelected()
        renderMenu()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<Row>()

        /** 列表只在加载/搜索/切换选择时整体换，规模几十项，notifyDataSetChanged 足够 */
        fun submit(rows: List<Row>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.Section -> TYPE_SECTION
            is Row.Person -> TYPE_MEMBER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION -> SectionVH(ItemDirectorySectionBinding.inflate(inflater, parent, false))
                else -> MemberVH(ItemImPickMemberBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Section -> (holder as SectionVH).bind(row)
                is Row.Person -> (holder as MemberVH).bind(row)
            }
        }

        private inner class SectionVH(
            private val b: ItemDirectorySectionBinding,
        ) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row.Section) {
                b.tvSection.text = row.text
            }
        }

        private inner class MemberVH(
            private val b: ItemImPickMemberBinding,
        ) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row.Person) {
                val member = row.member
                b.ivAvatar.bind(member.name, member.portrait)
                b.tvName.text = member.name
                b.tvSubtitle.text = member.subtitle
                b.tvSubtitle.isVisible = !member.subtitle.isNullOrBlank()
                // 勾选状态跟着数据走：只靠卡片背景变色，色觉障碍下看不出来
                b.cbPick.isChecked = selected.containsKey(member.id)
                b.root.setOnClickListener { toggle(member) }
            }
        }
    }

    companion object {
        /** 服务端「成员里有非好友」的错误码（官方 `SetGroupInfoActivity` 的提示就是给它的） */
        private const val CODE_NOT_FRIEND = 10606

        /** 搜索防抖：连着敲字只打最后一次（与成员录同一值） */
        private const val DEBOUNCE_MS = 260L

        // 列表两种行（inner 类里不能放 companion object，只能挂在这儿）
        private const val TYPE_SECTION = 0
        private const val TYPE_MEMBER = 1

        fun start(context: Context) {
            context.startActivity(Intent(context, ImCreateGroupActivity::class.java))
        }
    }
}
