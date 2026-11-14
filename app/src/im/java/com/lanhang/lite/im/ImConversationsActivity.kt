package com.lanhang.lite.im

import com.lanhang.lite.core.toast
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityImConversationsBinding
import com.lanhang.lite.databinding.ItemImConversationBinding
import com.lanhang.lite.directory.DirectoryActivity
import com.lanhang.lite.moments.UserInfoCache
import com.lanhang.lite.moments.UserProfileActivity
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.oa.OaApi
import com.lanhang.lite.ui.OaWebViewActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 好友事件回调：有人申请加我 / 同意了我 → 顶栏那个数字当场跟着变 */
    private val onFriendEvent: () -> Unit = { if (::binding.isInitialized) refreshPendingRequests() }

    /** 只在第一次进私信页时问一次通知权限（之后交给用户自己在设置里管，不反复弹） */
    private var notifyAsked = false

    /**
     * 未读且待处理的好友申请条数（>0 时顶栏副标题提示）。
     *
     * 数的是服务端的 `state == 1 && read_state == 0`，**不是本地表** —— 打开新朋友页会
     * `PUT /friends/clear_unread` 置已读，回来这个数字就归零，两边不会对不上。
     */
    private var pendingRequests = 0

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
                R.id.action_im_add -> {
                    showAddMenu()
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
        ImClient.addFriendObserver(onFriendEvent)
        ImClient.ensureStarted(this)
        requestNotifyPermissionOnce()
        refreshPendingRequests()
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

    /**
     * 顶栏副标题提示「N 条好友申请」。
     *
     * 这是「有人加我」唯一的前置信号：申请不在会话列表里，不给个数字用户根本不知道要去哪看
     * （官方是在通讯录的「新朋友」行上挂同一个数字）。取数失败就保持原样 ——
     * 一次网络抖动不值得弹提示，入口（顶栏「+ → 新朋友」）始终在。
     */
    private fun refreshPendingRequests() {
        lifecycleScope.launch {
            val count = try {
                withContext(Dispatchers.IO) {
                    RceApi.friendRequests().count { it.isUnreadPending }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 取数失败就保持原样：入口（顶栏「+ → 新朋友」）始终在，不值得弹提示
                return@launch
            }
            if (count == pendingRequests) return@launch
            pendingRequests = count
            binding.toolbar.subtitle = if (count > 0) {
                getString(R.string.im_new_friends_count, count)
            } else {
                null
            }
        }
    }

    /** 「+」下拉菜单（自己画的 PopupWindow，见 [showAddMenu]）；离开页面时必须 dismiss */
    private var addMenu: PopupWindow? = null

    override fun onPause() {
        addMenu?.dismiss()
        ImClient.removeObserver(onChanged)
        ImClient.removeFriendObserver(onFriendEvent)
        super.onPause()
    }

    override fun onDestroy() {
        addMenu?.dismiss()
        super.onDestroy()
    }

    /**
     * 顶栏「+」的下拉菜单：发起群聊 / 添加好友 / 校园卡支付 / 新朋友 / 加入群聊。
     *
     * 对齐官方 `OptionMenu`（图标 + 文字的行，从按钮下方落下）。用自写的 [PopupWindow]
     * 而不是 appcompat 的 `PopupMenu`：菜单是挂在 toolbar 上的，`toolbar.menu` 给的是
     * `MenuItem`，它的 `ActionProvider`/`ShowAsAction` View 拿不到稳定的锚点 View，
     * 而 `PopupMenu(anchor)` 需要的就是那个 View。自己画反而更可控。
     *
     * 定位：`getLocationOnScreen(toolbar)` 拿顶栏位置，Y 落在顶栏正下方；
     * `Gravity.END` 让菜单右缘贴屏幕右侧（「+」本来就在顶栏最右）。
     */
    private fun showAddMenu() {
        addMenu?.dismiss()
        val density = resources.displayMetrics.density
        val pad = (4 * density).toInt()

        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // (图标, 文案, 动作)；顺序与官方顶栏加号菜单一致
        val items = listOf(
            Triple(R.drawable.ic_group_add, R.string.im_create_group, { ImCreateGroupActivity.start(this) }),
            Triple(
                R.drawable.ic_person_add,
                R.string.im_add_friend,
                { DirectoryActivity.start(this, focusSearch = true, title = getString(R.string.im_add_friend)) },
            ),
            Triple(R.drawable.ic_credit_card, R.string.im_campus_card_pay, { openCampusCardPay() }),
            Triple(R.drawable.ic_person, R.string.im_new_friends, { ImNewFriendsActivity.start(this) }),
            Triple(R.drawable.ic_group, R.string.im_join_group, { showJoinGroupDialog() }),
        )
        for ((icon, label, action) in items) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_im_add_menu, rows, false)
            row.findViewById<ImageView>(R.id.ivIcon).setImageResource(icon)
            row.findViewById<TextView>(R.id.tvLabel).setText(label)
            row.setOnClickListener {
                addMenu?.dismiss()
                action()
            }
            rows.addView(row)
        }

        val popup = PopupWindow(
            rows,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            // 圆角底 + 主题色（bg_im_add_menu），elevation 给 M3 的抬升阴影。
            // 必须写 setBackgroundDrawable：PopupWindow 只有 getBackground()，合成属性
            // 没有可写的 setter（写 `background = …` 会被当成给 val 赋值而编译失败）
            setBackgroundDrawable(getDrawable(R.drawable.bg_im_add_menu))
            elevation = 8 * density
            isOutsideTouchable = true
            setOnDismissListener { addMenu = null }
        }
        val loc = IntArray(2)
        binding.toolbar.getLocationOnScreen(loc)
        // 正值在 END 方向上是「向屏内收」，给个 8dp 让菜单右缘别死贴屏幕边
        popup.showAtLocation(
            binding.root,
            Gravity.TOP or Gravity.END,
            (8 * density).toInt(),
            loc[1] + binding.toolbar.height,
        )
        addMenu = popup
    }

    /**
     * 校园卡支付：官方直开 `go.nchu.edu.cn/?id=63&state=0018`，**Token 用 ldapToken**。
     *
     * 官方 `MyService` 定期 `GET /api/apps/getLdapToken` 写进 SP，`RceWebViewActivity`
     * 打开时读的就是它（不是一次性 appToken）。我们走 `OaApi.freshLdapToken()` ——
     * 它先判 JWT 有效期、过期才重取，不会把过期令牌塞进请求头（网关回 4000 且看不出原因）。
     */
    private fun openCampusCardPay() {
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { OaApi.freshLdapToken() }
                if (token.isNullOrBlank()) {
                    toast(getString(R.string.im_open_page_failed, getString(R.string.im_campus_card_pay)))
                    return@launch
                }
                OaWebViewActivity.open(
                    this@ImConversationsActivity,
                    CAMPUS_CARD_URL,
                    token,
                    getString(R.string.im_campus_card_pay),
                )
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                toast(e.userMessage(this@ImConversationsActivity, R.string.im_open_page_failed))
            }
        }
    }

    /**
     * 加入群聊：只做「加入已知群号」。
     *
     * 建群走另一条路（顶栏「+ → 发起群聊」，学校 `POST /groups`，见 [ImCreateGroupActivity]）——
     * IMLib 2.9 的客户端 API 本身没有 `createGroup`，但不需要它：群由学校服务端创建。
     * 这里保留「按群号加群」是给「别人把群号发给我」的场景用的。
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
                // 最后一条是我发的且对方已读 → 一行小字（群聊不回执，conversation.read 恒 false）
                b.tvRead.isVisible = conversation.read
                if (conversation.read) b.tvRead.text = getString(R.string.im_msg_read)
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
                // 头像单独一步：点人进个人资料，点整行才进聊天（与新朋友页、工作圈一致）。
                // ⚠️ 群没有个人资料页，必须把监听清掉 —— ViewHolder 会复用，
                // 不清就出现「群行的头像点开的是上一个单聊的人」。
                if (conversation.isGroup) {
                    b.ivAvatar.setOnClickListener(null)
                } else {
                    b.ivAvatar.setOnClickListener {
                        UserProfileActivity.start(it.context, conversation.peerId)
                    }
                }
            }
        }
    }

    /** 会话列表行尾的时间（今天 HH:mm / 昨天 / M/d / yyyy/M/d，口径见 [TimeText.dayLabel]） */
    private fun formatTime(millis: Long): String =
        com.lanhang.lite.core.TimeText.dayLabel(this, millis)

    companion object {
        /**
         * 校园卡支付的落地页（官方 `MainActivity` 的加号菜单里直开这个地址）。
         * 明文 http：清单里 `usesCleartextTraffic="true"` 是为这类地址保留的，别去掉。
         */
        private const val CAMPUS_CARD_URL = "http://go.nchu.edu.cn/?id=63&state=0018"

        fun start(context: Context) {
            context.startActivity(Intent(context, ImConversationsActivity::class.java))
        }
    }
}
