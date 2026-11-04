package com.lanhang.lite.moments

import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lanhang.lite.R
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.toast
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.SheetMomentCommentBinding
import com.lanhang.lite.net.MomentsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 评论 / 回复的输入弹层（M3 BottomSheet）。
 *
 * 所有入口共用这一份：工作圈列表、动态详情、搜索、我的动态、过滤结果。
 * 之前每个宿主各写一份「EditText + MaterialAlertDialog」，三份代码的发送、回刷、失败重试
 * 都是复制粘贴的，改一处很容易漏掉另外两处；现在连「评论成功后回刷单条动态拿 comment_id」
 * 这种细节也只有这里一份。
 *
 * 交互：
 * - 打开即聚焦、拉起键盘；空白内容时「发送」禁用（不会出现点了没反应、弹窗还关掉的情况）
 * - 发送中只锁「发送」按钮（不置灰输入框：那会失焦、收起键盘、弹层跟着跳）；
 *   失败保留弹层和已输入内容，只提示原因
 * - 成功后先更新 [MomentItem]（评论 id 用于长按删除），再回调宿主刷新列表
 *
 * @param onPosted 发送成功后的宿主刷新（notifyItemChanged / 玻璃 FAB 重绘等）
 */
object MomentCommentSheet {

    // SOFT_INPUT_ADJUST_RESIZE 在 API 30 起被标 deprecated（官方改用 WindowInsets 动画回调），
    // 但 26~29 上仍然只有它能让窗口自己让出键盘高度，所以保留。
    @Suppress("DEPRECATION")
    fun show(
        activity: ComponentActivity,
        item: MomentItem,
        replyToUserId: String? = null,
        onPosted: () -> Unit
    ) {
        val feedId = item.feed.feed_id ?: return

        val dialog = BottomSheetDialog(activity)
        val binding = SheetMomentCommentBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        // 回复态把对象写进标题；拿不到名字就退回通用昵称，不要空着「回复」
        val replyName = replyToUserId
            ?.takeIf { it.isNotBlank() }
            ?.let { UserInfoCache.nameOf(it) ?: UserInfoCache.fallbackName(activity) }
        binding.tvTitle.text = if (replyName == null) {
            activity.getString(R.string.moments_comment_title)
        } else {
            activity.getString(R.string.moments_reply_title, replyName)
        }

        var sending = false
        fun refreshSendState() {
            binding.btnSend.isEnabled = !sending && !binding.etComment.text.isNullOrBlank()
        }

        binding.etComment.doAfterTextChanged { refreshSendState() }
        refreshSendState()

        binding.btnCancel.setOnClickListener { dialog.dismiss() }

        binding.btnSend.setOnClickListener {
            if (sending) return@setOnClickListener
            val text = binding.etComment.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener

            // 只锁「发送」按钮：把输入框置灰会让它失焦、键盘随之收起，弹层高度当场跳一下
            sending = true
            refreshSendState()

            activity.lifecycleScope.launch {
                try {
                    val fresh = withContext(Dispatchers.IO) {
                        MomentsApi.postComment(feedId, text, replyToUserId)
                        // 成功后回刷一次单条动态：服务端生成的 comment_id 要拿到手，否则删不掉。
                        // 拉不到就用本地评论兜底，至少让内容立刻出现在列表里。
                        runCatching { MomentsApi.getFeed(feedId) }.getOrNull()
                    }
                    if (fresh != null) {
                        item.comments.clear()
                        item.comments.addAll(fresh.comments.orEmpty())
                        item.likes.clear()
                        item.likes.addAll(fresh.likes.orEmpty())
                        item.likeCount = maxOf(fresh.likeCount ?: 0, item.likes.size)
                    } else {
                        item.comments.add(MomentsApi.localComment(feedId, text))
                    }
                    dialog.dismiss()
                    onPosted()
                    activity.toast(activity.getString(R.string.moments_sent))
                } catch (e: Exception) {
                    if (e.isCancellation) return@launch
                    // 失败不能把用户写的字吞掉：弹层和内容都保留，只提示原因
                    sending = false
                    refreshSendState()
                    activity.toast(e.userMessage(activity, R.string.moments_send_failed))
                }
            }
        }

        // 打开就聚焦并弹键盘：评论是即时输入，少一步点击
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )

        dialog.setOnShowListener {
            binding.etComment.requestFocus()
        }

        dialog.behavior.skipCollapsed = true
        dialog.show()
        // BottomSheetDialog 的 Window 只有 show() 后才存在；之前在 show() 前设置
        // adjustResize 实际没有作用，键盘因此直接覆盖了弹层下半部分。
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
