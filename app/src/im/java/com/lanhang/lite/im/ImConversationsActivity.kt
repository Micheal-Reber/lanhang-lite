package com.lanhang.lite.im

import com.lanhang.lite.core.toast
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.NotifPermission
import com.lanhang.lite.databinding.ActivityImConversationsBinding
import com.lanhang.lite.databinding.ItemImConversationBinding
import com.lanhang.lite.moments.UserInfoCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 私信列表（IM 版专属）。
 *
 * 进页面才连（[ImClient.ensureStarted] 幂等）：融云 SDK 起一个 `:ipc` 进程 + 本地 sqlite，
 * 没打算聊天的人不该为它付出内存。连接状态直接反映在界面上 —— 连不上时给原因和「重试」，
 * 不做「转圈转到天荒地老」。
 */
class ImConversationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImConversationsBinding
    private val adapter = Adapter()

    /** ImClient 的变更回调（连接状态 / 会话列表 / 新消息都会触发） */
    private val onChanged: () -> Unit = { if (::binding.isInitialized) render() }

    /** 只在第一次进私信页时问一次通知权限（之后交给用户自己在设置里管，不反复弹） */
    private var notifyAsked = false

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ImNotify.ensureChannel(this)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImConversationsBinding.inflate(layoutInflater)
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

        // 菜单在布局里用 app:menu 挂（NoActionBar 主题下 onCreateOptionsMenu 不会被调用）
        val tint = MaterialColors.getColor(
            binding.toolbar,
            com.google.android.material.R.attr.colorOnSurface
        )
        val menu = binding.toolbar.menu
        for (i in 0 until menu.size()) menu.getItem(i).icon?.mutate()?.setTint(tint)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_im_refresh -> {
                    ImClient.retry(this)
                    render()
                    true
                }
                R.id.action_im_join_group -> {
                    showJoinGroupDialog()
                    true
                }
                else -> false
            }
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnRetry.setOnClickListener {
            ImClient.retry(this)
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        ImClient.addObserver(onChanged)
        ImClient.ensureStarted(this)
        requestNotifyPermissionOnce()
        render()
    }

    /**
     * 通知权限：只在**还能弹系统框**时问一次。
     *
     * 不弹的情况（用户拒绝且不再询问 / 通知总开关被关 / 渠道被单独关）一律不打扰 ——
     * [NotifPermission.request] 那几种状态会弹一个「去设置」的对话框，每次进页面都弹就成了骚扰。
     * 用户想要通知，自己会去设置里开（设置 → 私信 那行的未读摘要也会随之为准）。
     */
    private fun requestNotifyPermissionOnce() {
        if (notifyAsked) return
        notifyAsked = true
        ImNotify.ensureChannel(this)
        if (NotifPermission.state(this, ImNotify.CHANNEL_ID) == NotifPermission.State.ASKABLE) {
            NotifPermission.request(this, notifyPermission, ImNotify.CHANNEL_ID)
        }
    }

    override fun onPause() {
        ImClient.removeObserver(onChanged)
        super.onPause()
    }

    /**
     * 加入群聊：只做「加入已知群号」。
     *
     * IMLib 2.9 的客户端 API **没有建群能力**（建群要走融云 Server API，需要 app secret），
     * 所以这里不提供「新建群」—— 群由学校侧创建后把群号发给大家。
     * 群名优先由学校 `GET /groups/{id}` 换出来，用户填的只作兜底（见 [ImClient.joinGroup]）。
     */
    private fun showJoinGroupDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val inputId = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.im_join_group_id)
        }
        val editId = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        inputId.addView(editId)
        val inputName = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.im_join_group_name)
        }
        val editName = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        inputName.addView(editName)
        container.addView(inputId)
        container.addView(
            inputName,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.im_join_group)
            .setView(container)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.im_join_group) { _, _ ->
                val id = editId.text?.toString().orEmpty().trim()
                if (id.isBlank()) return@setPositiveButton
                ImClient.joinGroup(id, editName.text?.toString().orEmpty()) { ok, info ->
                    if (ok) {
                        toast(
                            if (info.isNotBlank()) {
                                getString(R.string.im_join_group_ok, info)
                            } else {
                                getString(R.string.im_join_group_ok_unnamed)
                            }
                        )
                        render()
                    } else {
                        toast(joinErrorText(info))
                    }
                }
            }
            .show()
    }

    /** 把 [ImClient.joinGroup] 回的原因码翻成文案（码里不带中文，避免 i18n 漏翻） */
    private fun joinErrorText(code: String): String = when (code) {
        ImClient.RESULT_EMPTY_ID -> getString(R.string.im_join_group_id_required)
        ImClient.RESULT_OFFLINE -> getString(R.string.im_join_group_offline)
        else -> getString(R.string.im_join_group_failed, code)
    }

    /** 失败原因可能是空串（比如 SDK 只回了错误码），空就不弹，避免一个空气泡 */
    private fun toast(text: String) {
        if (text.isBlank()) return
        toast(text as CharSequence)
    }

    private fun render() {
        val list = ImClient.conversations
        val status = ImClient.status
        adapter.submit(list)
        val loading = status == ImClient.Status.CONNECTING && list.isEmpty()
        binding.progress.isVisible = loading
        binding.stateBox.isVisible = !loading && list.isEmpty()
        binding.btnRetry.isVisible = status == ImClient.Status.FAILED
        binding.tvState.text = when {
            status == ImClient.Status.FAILED -> ImClient.error
                ?: getString(R.string.im_connecting)
            status == ImClient.Status.CONNECTED -> getString(R.string.im_empty)
            else -> getString(R.string.im_connecting)
        }
        if (list.isNotEmpty()) resolveProfiles(list)
    }

    /** 会话里的人名/头像走工作圈那套缓存；命中缓存时不发请求 */
    private fun resolveProfiles(list: List<ImConversation>) {
        val ids = list.filter { !it.isGroup }.map { it.peerId }
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            try {
                UserInfoCache.resolve(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    private fun titleOf(conversation: ImConversation): String {
        if (conversation.isGroup) {
            return conversation.title.takeIf { it.isNotBlank() }
                ?: "${getString(R.string.im_group_suffix)} · ${conversation.peerId.takeLast(4)}"
        }
        val brief = UserInfoCache.get(conversation.peerId)
        return brief?.name?.takeIf { it.isNotBlank() }
            ?: conversation.peerId.takeIf { it.isNotBlank() }
            ?: getString(R.string.im_unknown_peer)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        private val items = mutableListOf<ImConversation>()

        /**
         * DiffUtil 增量更新（原来 `notifyDataSetChanged()`）：只有真正变化的行才重绑，
         * 未读徽标/时间变了的那几行单独刷新，头像不会被反复重建。
         */
        fun submit(list: List<ImConversation>) {
            if (items == list) return
            val old = items.toList()
            items.clear()
            items.addAll(list)
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = list.size

                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos].peerId == list[newPos].peerId &&
                        old[oldPos].isGroup == list[newPos].isGroup

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos] == list[newPos]
            }).dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemImConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemImConversationBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(conversation: ImConversation) {
                val title = titleOf(conversation)
                b.tvTitle.text = title
                // 图片/语音这些没有文本预览，换成对应文案（本地化，别在数据层存成品文案）
                b.tvPreview.text = when (conversation.previewKind) {
                    ImConversation.PreviewKind.IMAGE -> getString(R.string.im_preview_image)
                    ImConversation.PreviewKind.OTHER -> getString(R.string.im_preview_other)
                    ImConversation.PreviewKind.TEXT -> conversation.preview.ifBlank {
                        // 群可能只是本地还没有消息（从「我加入的群」补进来的），
                        // 那不是说「还没有私信」——群是存在的
                        if (conversation.isGroup) getString(R.string.im_no_message)
                        else getString(R.string.im_empty)
                    }
                }
                b.tvTime.text = formatTime(conversation.time)
                b.tvUnread.isVisible = conversation.unread > 0
                b.tvUnread.text = if (conversation.unread > 99) "99+" else conversation.unread.toString()
                if (conversation.isGroup) {
                    // 群：有 portrait_url 显示照片，没有就画群图标（学校基本都没设，
                    // 官方也是拿成员头像拼的，见 AvatarView.bindGroup 注释）
                    b.ivAvatar.bindGroup(title, conversation.portrait)
                } else {
                    val brief = UserInfoCache.get(conversation.peerId)
                    b.ivAvatar.bind(title, brief?.bestPortrait)
                }
                b.root.setOnClickListener {
                    ImChatActivity.start(
                        b.root.context,
                        conversation.peerId,
                        conversation.isGroup,
                        title
                    )
                }
            }
        }
    }

    /** 会话列表行尾的时间（今天 HH:mm / 昨天 / M/d / yyyy/M/d，口径见 [TimeText.dayLabel]） */
    private fun formatTime(millis: Long): String =
        com.lanhang.lite.core.TimeText.dayLabel(this, millis)

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ImConversationsActivity::class.java))
        }
    }
}
