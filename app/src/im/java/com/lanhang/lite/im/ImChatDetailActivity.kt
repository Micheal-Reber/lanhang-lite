package com.lanhang.lite.im

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.toast
import com.lanhang.lite.databinding.ActivityImChatDetailBinding
import com.lanhang.lite.moments.UserInfoCache
import kotlinx.coroutines.launch

/**
 * 聊天详情（2026-11-15，对齐官方 `PrivateChatDetailActivity` + `ChatDetailActivity`）。
 *
 * 版式与行为都照官方那一页：
 * 头像 + 名字（点进个人主页）→ 聊天内容（文件 / 图片 / 搜索）→ 添加新成员 →
 * 消息免打扰 → 会话置顶 → 清除聊天记录。
 *
 * **没有「举报」**：官方那个按钮点下去只起一个 2 秒定时器然后弹「举报成功，我们会认真核实」，
 * 不发任何请求（反编译 `ui/ReportActivity.java` 里看得一清二楚）。本应用不做假提交 ——
 * 想举报请走官方渠道。
 *
 * 三个开关类操作（免打扰 / 置顶 / 清记录）都直接落到融云，**不自己存状态**：
 * 这些设置会同步到服务端，换设备登录也应该一致。
 */
class ImChatDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PEER = "peer_id"
        private const val EXTRA_GROUP = "is_group"
        private const val EXTRA_TITLE = "title"

        /** 清过聊天记录：调用方（聊天页）据此重绘，否则它读的还是内存缓存 */
        const val RESULT_CLEARED = 2001

        fun intent(context: Context, peerId: String, isGroup: Boolean, title: String): Intent =
            Intent(context, ImChatDetailActivity::class.java)
                .putExtra(EXTRA_PEER, peerId)
                .putExtra(EXTRA_GROUP, isGroup)
                .putExtra(EXTRA_TITLE, title)
    }

    private lateinit var binding: ActivityImChatDetailBinding
    private var peerId: String = ""
    private var isGroup = false
    private var title: String = ""

    /**
     * 程序化设置开关时会触发 `OnCheckedChangeListener` 与真实点击无法区分，
     * 用这个标记把「回填状态」那一轮屏蔽掉，否则页面一打开就会把设置又写回去一遍。
     */
    private var bindingSwitches = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImChatDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER).orEmpty()
        isGroup = intent.getBooleanExtra(EXTRA_GROUP, false)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (peerId.isBlank()) {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0,
            )
            insets
        }

        bindHeader()
        binding.cellFile.setOnClickListener { openHistory(ImChatHistoryActivity.MODE_FILE) }
        binding.cellImage.setOnClickListener { openHistory(ImChatHistoryActivity.MODE_IMAGE) }
        binding.cellSearch.setOnClickListener { openHistory(ImChatHistoryActivity.MODE_SEARCH) }

        // 单聊的「添加新成员」= 把这个会话变成群聊（官方也是跳建群页，预选当前对方）
        binding.rowAddMember.setOnClickListener {
            if (isGroup) {
                toast(R.string.im_chat_add_member_group)
            } else {
                ImCreateGroupActivity.startWith(this, peerId, title)
            }
        }

        binding.swMute.setOnCheckedChangeListener { _, checked ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            ImClient.setConversationMuted(isGroup, peerId, checked) { ok ->
                if (!ok) {
                    toast(R.string.im_chat_switch_failed)
                    // 失败要把开关恢复成服务端的状态，不能满足于「看起来开了」
                    loadSwitches()
                }
            }
        }
        binding.swPin.setOnCheckedChangeListener { _, checked ->
            if (bindingSwitches) return@setOnCheckedChangeListener
            ImClient.setConversationTop(isGroup, peerId, checked) { ok ->
                if (!ok) {
                    toast(R.string.im_chat_switch_failed)
                    loadSwitches()
                }
            }
        }

        binding.btnClear.setOnClickListener { confirmClear() }

        loadSwitches()
    }

    private fun bindHeader() {
        binding.tvName.text = title
        if (isGroup) {
            // 群基本没设过 portrait_url，画群图标即可（与会话列表同一套）
            binding.ivAvatar.bindGroup(title, null)
            return
        }
        binding.ivAvatar.bind(title, UserInfoCache.get(peerId)?.bestPortrait)
        // 缓存里没有就去补一次，补到再刷（第一次从聊天页进来时可能还没拉过这个人）
        lifecycleScope.launch {
            runCatching { UserInfoCache.resolve(listOf(peerId)) }
            binding.ivAvatar.bind(title, UserInfoCache.get(peerId)?.bestPortrait)
        }
        binding.headerBox.setOnClickListener {
            com.lanhang.lite.moments.UserProfileActivity.start(this, peerId)
        }
    }

    /** 读一次服务端状态回填两个开关；读失败就保持关着并提示，不假装「设置成功」 */
    private fun loadSwitches() {
        ImClient.conversationSwitches(isGroup, peerId) { top, muted, error ->
            if (isFinishing || isDestroyed) return@conversationSwitches
            bindingSwitches = true
            binding.swMute.isChecked = muted
            binding.swPin.isChecked = top
            bindingSwitches = false
            if (error != null) toast(R.string.im_chat_switch_failed)
        }
    }

    private fun openHistory(mode: String) {
        ImChatHistoryActivity.start(this, peerId, isGroup, title, mode)
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.im_chat_clear_confirm_title)
            .setMessage(R.string.im_chat_clear_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                ImClient.clearConversation(isGroup, peerId) { ok ->
                    if (isFinishing || isDestroyed) return@clearConversation
                    if (ok) {
                        toast(R.string.im_chat_clear_done)
                        setResult(RESULT_CLEARED)
                    } else {
                        toast(R.string.im_chat_switch_failed)
                    }
                }
            }
            .show()
    }
}
