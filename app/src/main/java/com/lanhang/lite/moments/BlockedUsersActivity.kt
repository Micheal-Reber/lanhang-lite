package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.attachDoubleTapToTop
import com.lanhang.lite.databinding.ActivityBlockedUsersBinding
import com.lanhang.lite.databinding.ItemBlockedUserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「不看 TA 的动态」名单管理。
 *
 * 入口在「设置 → 工具 → 工作圈过滤 → 不看的人」。列表里能逐个恢复 ——
 * 屏蔽是个负面操作，必须有一个明确的后悔入口，否则用户只能靠重装应用。
 * 恢复这一下同样给了 Snackbar 撤销：误点之后不用再翻半天去找那个人。
 *
 * 进页面时会异步刷新一次昵称与头像：名单里存的是屏蔽那一刻的快照，
 * 对方改名后这里会显示旧名（名字只是给用户认人用的，不参与任何判定）。
 */
class BlockedUsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedUsersBinding
    private val adapter = Adapter()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // insets 会多次回调，基准值必须先取
        val baseBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 顶栏高度是 wrap_content + minHeight，这里的 paddingTop 会把高度撑开；
            // 写死 actionBarSize 的话这段 padding 在固定高度内部占位，标题会被压没
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
                baseBottom + bars.bottom
            )
            insets
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.attachDoubleTapToTop()

        render()
        refreshProfiles()
    }

    override fun onResume() {
        super.onResume()
        // 从个人主页回来可能又加/减了人
        render()
    }

    private fun render() {
        val list = BlockedUserStore.all()
        adapter.submit(list)
        val empty = list.isEmpty()
        binding.emptyView.isVisible = empty
        binding.recycler.isVisible = !empty
    }

    /** 昵称/头像快照可能已经过期，进页面时用缓存与网络补一次 */
    private fun refreshProfiles() {
        val ids = BlockedUserStore.all().map { it.id }
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { UserInfoCache.resolve(ids) }
            }
            // 只在真的变了才重绘：以前只要有缓存就整表刷一遍，滚动位置没事但会闪
            var changed = false
            ids.forEach { id ->
                val brief = UserInfoCache.get(id) ?: return@forEach
                val before = BlockedUserStore.all().firstOrNull { it.id == id } ?: return@forEach
                BlockedUserStore.refresh(id, brief.name, brief.bestPortrait)
                val after = BlockedUserStore.all().firstOrNull { it.id == id }
                if (after != null && after != before) changed = true
            }
            if (changed) render()
        }
    }

    private fun unblock(user: BlockedUserStore.BlockedUser) {
        val label = user.name.takeIf { it.isNotBlank() }
            ?: getString(R.string.moments_anonymous)
        BlockedUserStore.unblock(user.id)
        render()
        // 恢复=撤销屏蔽，误点一次就得重新翻主页找人，所以给一次反悔机会
        val snackbar = Snackbar.make(
            binding.root,
            getString(R.string.moments_blocked_restored, label),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.moments_blocked_undo) {
            BlockedUserStore.block(user)
            render()
        }
        // edge-to-edge 下 Snackbar 默认贴着屏幕物理底边，会压在导航条上（还容易被手势条挡住点击），
        // 这里手动补一个导航条高度的底部外边距
        val navBottom = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        if (navBottom > 0) {
            (snackbar.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = navBottom
                snackbar.view.layoutParams = it
            }
        }
        snackbar.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        private val items = mutableListOf<BlockedUserStore.BlockedUser>()

        fun submit(list: List<BlockedUserStore.BlockedUser>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemBlockedUserBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemBlockedUserBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(user: BlockedUserStore.BlockedUser) {
                val label = user.name.takeIf { it.isNotBlank() }
                    ?: b.root.context.getString(R.string.moments_anonymous)
                b.tvName.text = label
                b.ivAvatar.bind(label, user.avatar)
                // 整行进主页：名单里存的是旧昵称，点一下才能确认是谁
                b.root.setOnClickListener {
                    UserProfileActivity.start(b.root.context, user.id)
                }
                b.btnUnblock.setOnClickListener { unblock(user) }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, BlockedUsersActivity::class.java))
        }
    }
}
