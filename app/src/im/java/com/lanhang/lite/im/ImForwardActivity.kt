package com.lanhang.lite.im

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.databinding.ActivityImForwardBinding
import com.lanhang.lite.databinding.ItemImForwardTargetBinding
import com.lanhang.lite.net.RceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 转发目标选择页（2026-11-22）。
 *
 * 为什么单独做一页：原来是在聊天页弹 `AlertDialog`，从「最近会话」里选。
 * 结果**只能选到群**（用户实测）—— 单聊会话只有「聊过」才会出现在会话列表里，
 * 而当前会话又被排除掉，如果跟好友只聊过这一个人，好友一个都不剩，只剩群。
 *
 * 现在把「最近会话 + 好友列表」合并成一个可搜索的列表：会话负责覆盖「最近聊过的」，
 * 好友列表负责覆盖「没聊过的」（以及当前会话被排除后的其他人）。
 * 群只能来自会话列表 —— 服务端没有「我加入的群」的完整列表接口（`loadGroups` 只给有消息的）。
 */
class ImForwardActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EXCLUDE_PEER = "exclude_peer"
        private const val EXTRA_EXCLUDE_GROUP = "exclude_group"

        const val RESULT_PEER = "result_peer"
        const val RESULT_GROUP = "result_group"

        fun intentOf(context: Context, excludePeer: String, excludeGroup: Boolean): Intent =
            Intent(context, ImForwardActivity::class.java)
                .putExtra(EXTRA_EXCLUDE_PEER, excludePeer)
                .putExtra(EXTRA_EXCLUDE_GROUP, excludeGroup)
    }

    /** 列表里的一行：单聊和群共用（[isGroup] 决定头像样式与副标题） */
    private data class Target(
        val peerId: String,
        val isGroup: Boolean,
        val name: String,
        val subtitle: String,
        val portrait: String?,
    )

    private lateinit var binding: ActivityImForwardBinding
    private val adapter = TargetAdapter()

    /** 全量列表（会话 + 好友），搜索在它上面做本地过滤 */
    private var all: List<Target> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImForwardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val baseBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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
                baseBottom + bars.bottom,
            )
            insets
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.etQuery.doAfterTextChanged { filter(it?.toString().orEmpty()) }

        build(
            excludePeer = intent.getStringExtra(EXTRA_EXCLUDE_PEER).orEmpty(),
            excludeGroup = intent.getBooleanExtra(EXTRA_EXCLUDE_GROUP, false),
        )
    }

    /**
     * 先把「最近会话」摆上屏（同步数据，立刻可用），再异步补好友。
     *
     * 不转圈：列表一进来就有内容，好友补进来只是变长。这也避免了「拉好友失败就整页空白」。
     */
    private fun build(excludePeer: String, excludeGroup: Boolean) {
        val targets = mutableListOf<Target>()
        val seen = HashSet<String>()
        fun key(id: String, group: Boolean) = if (group) "g:$id" else "u:$id"

        ImClient.conversations.forEach { c ->
            // 当前会话排除：转发给自己正在看的这个没有意义
            if (c.isGroup == excludeGroup && c.peerId == excludePeer) return@forEach
            if (!seen.add(key(c.peerId, c.isGroup))) return@forEach
            // 🔴 单聊的 `ImConversation.title` 常常是空的：SDK 的 conversationTitle 只有群聊才有，
            // 单聊要靠 UserInfoCache 反查姓名 —— 不补就是一行空白（2026-11-22 用户截图里那三个
            // 只有「最近会话」副标题、没名字的空卡片）。会话列表页的 `titleOf` 是同一套规则。
            val brief = if (c.isGroup) null else com.lanhang.lite.moments.UserInfoCache.get(c.peerId)
            val name = if (c.isGroup) {
                c.title.takeIf { it.isNotBlank() }
                    ?: "${getString(R.string.im_group_suffix)} · ${c.peerId.takeLast(4)}"
            } else {
                brief?.name?.takeIf { it.isNotBlank() }
                    ?: c.title.takeIf { it.isNotBlank() }
                    ?: c.peerId
            }
            targets += Target(
                peerId = c.peerId,
                isGroup = c.isGroup,
                name = name,
                subtitle = getString(
                    if (c.isGroup) R.string.im_forward_kind_group
                    else R.string.im_forward_kind_recent
                ),
                portrait = c.portrait ?: brief?.bestPortrait,
            )
        }
        render(targets)

        lifecycleScope.launch {
            val friends = try {
                withContext(Dispatchers.IO) { RceApi.friends() }
            } catch (e: Throwable) {
                if (e.isCancellation) throw e
                // 拉不到好友不算失败：会话那部分还在，用户至少能转给最近聊过的人
                emptyList()
            }
            friends.forEach { f ->
                val id = f.id?.takeIf { it.isNotBlank() } ?: return@forEach
                if (!seen.add(key(id, false))) return@forEach
                targets += Target(
                    peerId = id,
                    isGroup = false,
                    name = f.name.orEmpty().ifBlank { id },
                    subtitle = getString(R.string.im_forward_kind_friend),
                    portrait = f.portrait_url?.takeIf { it.isNotBlank() },
                )
            }
            render(targets)
        }
    }

    private fun render(list: List<Target>) {
        all = list
        filter(binding.etQuery.text?.toString().orEmpty())
    }

    private fun filter(query: String) {
        val q = query.trim()
        val shown = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
        adapter.submit(shown)
        binding.tvEmpty.isVisible = shown.isEmpty()
        binding.tvEmpty.setText(R.string.im_forward_no_target)
    }

    private fun pick(target: Target) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(RESULT_PEER, target.peerId)
                .putExtra(RESULT_GROUP, target.isGroup),
        )
        finish()
    }

    private inner class TargetAdapter : RecyclerView.Adapter<TargetAdapter.VH>() {

        private val items = mutableListOf<Target>()

        fun submit(list: List<Target>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemImForwardTargetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemImForwardTargetBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(target: Target) {
                b.tvName.text = target.name
                b.tvSubtitle.text = target.subtitle
                // 群用群样式（没头像时显示群图标占位），单聊显示姓名首字
                if (target.isGroup) {
                    b.ivAvatar.bindGroup(target.name, target.portrait)
                } else {
                    b.ivAvatar.bind(target.name, target.portrait)
                }
                b.root.setOnClickListener { pick(target) }
            }
        }
    }
}
