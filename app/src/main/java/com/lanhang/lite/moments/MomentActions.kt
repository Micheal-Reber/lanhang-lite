package com.lanhang.lite.moments

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单条动态上的交互：点赞、评论/回复、删除自己的评论、删除自己发布的动态。
 *
 * 抽出来是因为详情页和搜索页都要用**完全相同**的一套行为：
 * 复制两份的话，迟早会出现「详情页能删评论、搜索页点了没反应」这种不对称的 bug。
 * 工作圈列表（[MomentsFragment]）暂时保留自己的实现 —— 它还有 FAB 重绘等页面内的副作用。
 */
class MomentActions(
    private val activity: AppCompatActivity,
    private val adapter: MomentAdapter
) {

    fun toggleLike(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        val liked = item.likedByMe
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (liked) MomentsApi.cancelLike(feedId) else MomentsApi.like(feedId)
                }
                if (liked) {
                    item.likes.removeIf { it.user_id == Session.userId }
                } else {
                    item.likes.add(
                        LikeEntry(
                            like_id = "local_${System.currentTimeMillis()}",
                            feed_id = feedId,
                            user_id = Session.userId,
                            create_dt = System.currentTimeMillis()
                        )
                    )
                }
                notifyItem(feedId)
            } catch (e: Exception) {
                toast(e.userMessage(activity, R.string.moments_action_failed))
            }
        }
    }

    fun askComment(item: MomentItem, replyToUserId: String? = null) {
        val feedId = item.feed.feed_id ?: return
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.moments_comment_hint)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (replyToUserId.isNullOrEmpty()) activity.getString(R.string.moments_comment_title)
                else activity.getString(
                    R.string.moments_reply_title,
                    UserInfoCache.nameOf(replyToUserId) ?: UserInfoCache.fallbackName(activity)
                )
            )
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                activity.lifecycleScope.launch {
                    try {
                        // 评论成功后回刷一次单条动态，把服务端生成的评论 id 拿到手（否则删不掉）
                        val fresh = withContext(Dispatchers.IO) {
                            MomentsApi.postComment(feedId, text, replyToUserId)
                            runCatching { MomentsApi.getFeed(feedId) }.getOrNull()
                        }
                        if (fresh != null) {
                            item.comments.clear()
                            item.comments.addAll(fresh.comments.orEmpty())
                            item.likes.clear()
                            item.likes.addAll(fresh.likes.orEmpty())
                        } else {
                            item.comments.add(MomentsApi.localComment(feedId, text))
                        }
                        notifyItem(feedId)
                        toast(activity.getString(R.string.moments_sent))
                    } catch (e: Exception) {
                        toast(e.userMessage(activity, R.string.moments_send_failed))
                    }
                }
            }
            .show()
    }

    /**
     * 长按评论区：列出自己发过的评论，可删除（`DELETE /comment/{feed}/{comment}`）。
     *
     * 这是删除评论的**唯一入口**（2026-10-30 去掉每条评论下方的「删除」键）：
     * 只有自己的、且服务端已生成 comment_id 的评论会出现在列表里。
     */
    fun showMyComments(item: MomentItem) {
        val mine = item.comments.filter {
            it.user_id == Session.userId && !it.comment_id.isNullOrEmpty()
        }
        if (mine.isEmpty()) {
            toast(activity.getString(R.string.moments_no_deletable))
            return
        }
        val labels = mine.map {
            activity.getString(R.string.moments_delete_item, it.content.orEmpty().take(40))
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.moments_my_comments)
            .setItems(labels) { _, which -> confirmDeleteComment(item, mine[which]) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteComment(item: MomentItem, comment: CommentEntry) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.moments_delete_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                val feedId = item.feed.feed_id ?: return@setPositiveButton
                val commentId = comment.comment_id ?: return@setPositiveButton
                activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { MomentsApi.deleteComment(feedId, commentId) }
                        item.comments.removeIf { it.comment_id == commentId }
                        notifyItem(feedId)
                        toast(activity.getString(R.string.moments_deleted))
                    } catch (e: Exception) {
                        toast(e.userMessage(activity, R.string.moments_delete_failed))
                    }
                }
            }
            .show()
    }

    /**
     * 删除自己发布的动态（`DELETE /feed/{feed_id}`）。
     *
     * 二次确认 → 调接口 → 成功才从列表里摘掉；失败保留原卡片并提示原因。
     * 详情页借 [onDeleted] 关闭页面，过滤/搜索页借它更新「找到 N 条」的计数。
     *
     * @param onDeleted 接口成功且 adapter 已移除后回调，参数是 feed_id
     */
    fun askDeletePost(item: MomentItem, onDeleted: (String) -> Unit = {}) {
        val feedId = item.feed.feed_id ?: return
        // 服务端只允许删自己的动态，先拦一道，避免无意义的失败请求
        if (item.feed.user_id != Session.userId) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.moments_delete_post)
            .setMessage(R.string.moments_delete_post_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { MomentsApi.deleteFeed(feedId) }
                        adapter.remove(feedId)
                        toast(activity.getString(R.string.moments_deleted))
                        onDeleted(feedId)
                    } catch (e: Exception) {
                        toast(e.userMessage(activity, R.string.moments_delete_failed))
                    }
                }
            }
            .show()
    }

    private fun notifyItem(feedId: String) {
        adapter.notifyItemChanged(adapter.indexOf(feedId).coerceAtLeast(0))
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
