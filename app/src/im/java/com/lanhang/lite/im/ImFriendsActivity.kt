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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.toast
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityImForwardBinding
import com.lanhang.lite.databinding.ItemImForwardTargetBinding
import com.lanhang.lite.net.RceApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的好友（2026-11-22，用户要求）。
 *
 * 之前好友只以「会话」的形式出现过 —— 没聊过的人在任何列表里都看不到，
 * 用户说「我都不知道我的好友有哪些」。数据源就是 [RceApi.friends]（建群页、
 * 转发页用的是同一份），点一行直接进聊天。
 *
 * 布局复用 `activity_im_forward.xml` / `item_im_forward_target.xml`：
 * 两页都是「搜索框 + 头像名行」，差别只在点下去做什么，没必要复制一套 XML。
 */
class ImFriendsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ImFriendsActivity::class.java))
        }
    }

    private data class Friend(val id: String, val name: String, val portrait: String?)

    private lateinit var binding: ActivityImForwardBinding
    private val adapter = FriendAdapter()

    private var all: List<Friend> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImForwardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.im_my_friends)
        binding.toolbar.setNavigationOnClickListener { finish() }
        // 布局是共用那一份，文案在这里改成「好友」口径
        binding.searchBox.hint = getString(R.string.im_create_group_search_hint)

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

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { RceApi.friends() }
            } catch (e: Throwable) {
                // 读操作，取消也要按取消重新抛（协程取消后继续跑收尾会碰已销毁的视图）
                if (e.isCancellation) throw e
                emptyList()
            }
            all = list.mapNotNull { f ->
                val id = f.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Friend(
                    id = id,
                    name = f.name.orEmpty().ifBlank { id },
                    portrait = f.portrait_url?.takeIf { it.isNotBlank() },
                )
            }
            filter(binding.etQuery.text?.toString().orEmpty())
        }
    }

    private fun filter(query: String) {
        val q = query.trim()
        val shown = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
        adapter.submit(shown)
        binding.tvEmpty.isVisible = shown.isEmpty()
        // 空态分清两种情况：本来就没好友 / 只是搜索没命中
        binding.tvEmpty.setText(
            if (q.isEmpty()) R.string.im_create_group_empty_friends else R.string.im_create_group_no_match
        )
    }

    private inner class FriendAdapter : RecyclerView.Adapter<FriendAdapter.VH>() {

        private val items = mutableListOf<Friend>()

        fun submit(list: List<Friend>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemImForwardTargetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemImForwardTargetBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(friend: Friend) {
                b.tvName.text = friend.name
                // 共用行布局里副标题是常驻的，这里没有副标题可显示，必须收掉
                b.tvSubtitle.isVisible = false
                b.ivAvatar.bind(friend.name, friend.portrait)
                b.root.setOnClickListener {
                    ImChatActivity.start(this@ImFriendsActivity, friend.id, false, friend.name)
                }
                // 长按删除好友：入口只放这里，点行进聊天（删除是破坏性操作，不做成可误触的按钮）
                b.root.setOnLongClickListener {
                    confirmDelete(friend)
                    true
                }
            }
        }
    }

    /**
     * 删除好友，二次确认。
     *
     * 接口与个人主页底部那颗「删除好友」按钮是同一个（`RceApi.deleteFriend`），
     * 但那条路径要先「进会话 → ⋮ → 聊天详情 → 点头像 → 滚到底」才看得到，
     * 在好友列表里直接长按更顺手。
     */
    private fun confirmDelete(friend: Friend) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friend_delete)
            .setMessage(getString(R.string.friend_delete_message, friend.name))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ -> delete(friend) }
            .show()
    }

    private fun delete(friend: Friend) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RceApi.deleteFriend(friend.id) }
                all = all.filterNot { it.id == friend.id }
                filter(binding.etQuery.text?.toString().orEmpty())
                toast(getString(R.string.im_friend_removed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                toast(e.userMessage(this@ImFriendsActivity, R.string.friend_delete_failed))
            }
        }
    }
}
