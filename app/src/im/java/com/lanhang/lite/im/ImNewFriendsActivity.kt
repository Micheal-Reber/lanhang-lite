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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.TimeText
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.toast
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityImNewFriendsBinding
import com.lanhang.lite.databinding.ItemImFriendRequestBinding
import com.lanhang.lite.moments.UserProfileActivity
import com.lanhang.lite.net.FriendRequestInfo
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.showFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新朋友（好友申请列表，IM 版专属）。
 *
 * 数据只有一处来源：`GET /friends/request_list`（官方 `FriendTask`）。**不做本地表** ——
 * `read_state` 是服务端字段，我们没有官方那套 `t_friend_request` 的同步与版本机制，
 * 自建一份必然和服务端对不上（详见 `docs/im-friend-requests-research.md` §3）。
 *
 * 进页面拉一次；只要列表里有未读的待处理申请，拉成功后就 `PUT /friends/clear_unread`
 * 置已读，返回私信列表时顶栏那个数字随之归零。
 *
 * **长按一条记录 = 删除**（`DELETE /friends/request_list/{requestId}`，官方 `FriendTask`），
 * 走二次确认；只删申请记录，不影响已成立的好友关系。
 *
 * 实时性靠 `RCE:ContactNtfy` 自定义消息（[ImClient] 收到后回调 [ImClient.addFriendObserver]）：
 * 停在这一页时来事件当场重拉，不在前台就弹独立的「好友申请」通知 —— 仍然**不轮询**。
 */
class ImNewFriendsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImNewFriendsBinding
    private val adapter = Adapter()

    /** 防连点：同意是写操作，重复提交服务端会回 10401 */
    private var accepting = false

    /** 防连点：删除同理，连点会发两次 DELETE（第二次服务端已无该记录，只多一条错误日志） */
    private var deleting = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImNewFriendsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val baseBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tb = binding.toolbar
            tb.setPadding(tb.paddingLeft, bars.top, tb.paddingRight, 0)
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
        binding.btnRetry.setOnClickListener { load() }
        load()
    }

    private fun load() {
        binding.progress.isVisible = true
        binding.stateBox.isVisible = false
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { RceApi.friendRequests() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@ImNewFriendsActivity, TAG, e)
                binding.progress.isVisible = false
                binding.stateBox.isVisible = true
                binding.btnRetry.isVisible = true
                binding.tvState.showFailure(e, R.string.im_new_friends_failed)
                return@launch
            }
            binding.progress.isVisible = false
            binding.btnRetry.isVisible = false
            adapter.submit(order(list))
            binding.stateBox.isVisible = list.isEmpty()
            binding.tvState.setText(R.string.im_new_friends_empty)
            if (list.any { it.isUnreadPending }) clearUnread()
        }
    }

    /**
     * 待处理的排最前，其余按最近更新倒序。
     * 官方是按本地表倒序 + 单独的未读角标，我们没有本地表，直接在展示层排一次即可。
     */
    private fun order(list: List<FriendRequestInfo>): List<FriendRequestInfo> =
        list.sortedWith(
            compareByDescending<FriendRequestInfo> { it.isPending }
                .thenByDescending { it.update_dt ?: it.create_dt ?: 0L }
        )

    /** 置已读失败不影响阅读，只打日志 —— 下次进页面还会再试一次 */
    private fun clearUnread() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RceApi.clearFriendUnread() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                Diagnostics.record(this@ImNewFriendsActivity, TAG, e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 正停在这一页：好友事件不再弹通知（列表会当场重拉），进页面时撤掉旧的那条
        ImClient.newFriendsVisible = true
        ImNotify.cancelFriend(this)
        ImClient.addFriendObserver(onFriendEvent)
    }

    override fun onPause() {
        ImClient.removeFriendObserver(onFriendEvent)
        ImClient.newFriendsVisible = false
        super.onPause()
    }

    /** 收到好友事件（别人申请加我 / 同意了我）时当场重拉；置已读也在这里生效 */
    private val onFriendEvent: () -> Unit = { load() }

    private fun accept(request: FriendRequestInfo) {
        val requestId = request.requestId?.takeIf { it.isNotBlank() } ?: return
        if (accepting) return
        accepting = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RceApi.acceptFriend(requestId) }
                toast(getString(R.string.im_friend_accepted))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@ImNewFriendsActivity, TAG, e)
                toast(e.userMessage(this@ImNewFriendsActivity, R.string.im_friend_accept_failed))
            } finally {
                accepting = false
            }
        }
    }

    /**
     * 长按一条记录 → 二次确认 → `DELETE /friends/request_list/{requestId}` → 重拉。
     *
     * 写操作必须二次确认（与删除好友、下线一个口径）：记录删了服务端就没了，
     * 没有「撤销」可言。同意用的 `accepting` 防连点这里复用同一套 [deleting] 标志。
     */
    private fun confirmDelete(request: FriendRequestInfo) {
        val requestId = request.requestId?.takeIf { it.isNotBlank() } ?: return
        if (deleting) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.im_friend_request_delete)
            .setMessage(R.string.im_friend_request_delete_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ -> delete(requestId) }
            .show()
    }

    private fun delete(requestId: String) {
        if (deleting) return
        deleting = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RceApi.deleteFriendRequest(requestId) }
                toast(getString(R.string.im_friend_request_deleted))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@ImNewFriendsActivity, TAG, e)
                toast(e.userMessage(this@ImNewFriendsActivity, R.string.im_friend_request_delete_failed))
                // 删除失败不要静默：列表保持原样，用户能再试一次
            } finally {
                deleting = false
            }
        }
    }

    /** 申请记录的状态文案；未知状态返回 null（两个控件都藏起来，不猜一个「已同意」糊上去） */
    private fun statusOf(state: Int?): Int? = when (state) {
        FriendRequestInfo.STATE_INVITE -> R.string.im_friend_waiting
        FriendRequestInfo.STATE_ACCEPT,
        FriendRequestInfo.STATE_ACCEPTED -> R.string.im_friend_accepted
        FriendRequestInfo.STATE_TIME_OUT -> R.string.im_friend_timeout
        else -> null
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        private val items = mutableListOf<FriendRequestInfo>()

        /** 列表规模很小（实测个位数），直接全量重绑，不上 DiffUtil */
        fun submit(list: List<FriendRequestInfo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemImFriendRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemImFriendRequestBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(request: FriendRequestInfo) {
                val name = request.name?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.moments_anonymous)
                b.tvName.text = name

                val content = request.content?.trim().orEmpty()
                b.tvContent.text = content
                b.tvContent.isVisible = content.isNotEmpty()

                val time = request.update_dt ?: request.create_dt ?: 0L
                b.tvTime.isVisible = time > 0
                b.tvTime.text = if (time > 0) TimeText.dayLabel(this@ImNewFriendsActivity, time) else ""

                val statusRes = statusOf(request.state)
                b.tvStatus.isVisible = statusRes != null
                statusRes?.let { b.tvStatus.setText(it) }
                b.btnAccept.isVisible = request.isPending

                b.ivAvatar.bind(name, request.portrait_url?.takeIf { it.isNotBlank() })

                val uid = request.uid.orEmpty()
                b.root.setOnClickListener {
                    if (uid.isNotBlank()) UserProfileActivity.start(b.root.context, uid)
                }
                b.btnAccept.setOnClickListener { accept(request) }

                // 长按整行 = 删除这条申请记录（二次确认见 confirmDelete）。
                // 没有 requestId 就不挂监听：删不了的东西不该让长按「有反应却没反应」
                val requestId = request.requestId?.takeIf { it.isNotBlank() }
                b.root.setOnLongClickListener {
                    if (requestId == null) return@setOnLongClickListener false
                    confirmDelete(request)
                    true
                }
            }
        }
    }

    companion object {
        private const val TAG = "新朋友申请"

        /** 点好友通知进这里用的 Intent（[ImNotify] 复用，避免两处各拼一份 extra 键名） */
        fun intentOf(context: Context): Intent = Intent(context, ImNewFriendsActivity::class.java)

        fun start(context: Context) {
            context.startActivity(intentOf(context))
        }
    }
}
