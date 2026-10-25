package com.lanhang.lite.moments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import com.lanhang.lite.R
import com.lanhang.lite.net.ApiException
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.MainActivity
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.miuix.LanhangMiuixTheme
import com.lanhang.lite.ui.miuix.isNightMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作圈宿主。视觉层换成 Compose + Miuix（见 [MomentsScreen]），
 * 这里只保留数据、翻页与增删改动作。
 */
class MomentsFragment : Fragment() {

    private var items by mutableStateOf<List<MomentItem>>(emptyList())
    private var loading by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var ended by mutableStateOf(false)
    private var publishing by mutableStateOf(false)
    private var emptyText by mutableStateOf<String?>(null)

    private var fromFeedId: String? = null

    /** 点赞/评论会改 MomentItem 里的普通可变列表，靠它触发重组 */
    private var revision by mutableStateOf(0)

    /** 已选图片（Compose 状态，选图回调直接改它） */
    private val pickedImages = mutableStateListOf<Uri>()

    /** 是否停在「发表动态」页（独立页面，不再是弹窗） */
    private var publishPage by mutableStateOf(false)

    /** 发表页正文 */
    private var publishText by mutableStateOf("")

    /** 物理返回键：发表页里先退页面 */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePublish()
    }

    /**
     * 选图入口：**每次点「+」都按剩余名额限制**（2026-10-25 用户要求）。
     *
     * `PickMultipleVisualMedia(maxItems)` 只能在注册时固定上限，做不到「已选 8 张时
     * 只允许再选 1 张」，所以这里自己发 Intent：
     *  · Android 13+：`ACTION_PICK_IMAGES` + `EXTRA_PICK_IMAGES_MAX = 剩余名额`
     *  · 更低版本：`ACTION_OPEN_DOCUMENT`（多选），回来后仍按剩余名额截断
     */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = buildList {
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
            }
            if (isEmpty()) data.data?.let { add(it) }
        }
        appendPickedImages(uris)
    }

    /** 剩余可选名额 */
    private fun remainingImageSlots(): Int = (MAX_MOMENT_IMAGES - pickedImages.size).coerceAtLeast(0)

    private fun launchImagePicker() {
        val remaining = remainingImageSlots()
        if (remaining <= 0) {
            toast(getString(R.string.moments_images_full, MAX_MOMENT_IMAGES))
            return
        }
        val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, remaining)
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        runCatching { imagePicker.launch(intent) }
            .onFailure { toast(getString(R.string.moments_action_failed, it.message.orEmpty())) }
    }

    private fun appendPickedImages(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            // 2026-10-25 用户反馈：已经加过图后再点「+」选图，应该是**追加**到已有图片后面，
            // 而不是把之前选的图全清掉、换成这一批。这里做追加 + 去重 + 仍以 9 张为上限。
            uris.forEach { uri ->
                if (pickedImages.size < 9 && pickedImages.none { it == uri }) {
                    pickedImages.add(uri)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LanhangMiuixTheme(darkTheme = LocalContext.current.isNightMode()) {
                // 发表动态是「新页面」：左右滑动切页（往左进新页，退回时反过来）
                val publishCurve = spring<IntOffset>(dampingRatio = 1f, stiffness = 146f)
                AnimatedContent(
                    targetState = publishPage,
                    // 与设置页二级页同一套 Miuix 推入几何：新页整幅推入 + 圆角裁剪，
                    // 被覆盖页视差 25% 让位并淡到 0.9，进度走临界阻尼弹簧
                    transitionSpec = {
                        if (targetState) {
                            slideInHorizontally(publishCurve) { it } togetherWith
                                (slideOutHorizontally(publishCurve) { -it / 4 } + fadeOut(tween(520), targetAlpha = 0.9f))
                        } else {
                            (slideInHorizontally(publishCurve) { -it / 4 } + fadeIn(tween(520), initialAlpha = 0.9f)) togetherWith
                                slideOutHorizontally(publishCurve) { it }
                        }
                    },
                    label = "momentsPage",
                ) { onPublishPage ->
                    if (onPublishPage) {
                        PublishMomentScreen(
                            text = publishText,
                            onTextChange = { publishText = it },
                            images = pickedImages,
                            onPickImages = { launchImagePicker() },
                            onRemoveImage = { index ->
                                if (index in pickedImages.indices) pickedImages.removeAt(index)
                            },
                            onPreviewImage = { index ->
                                ImagePreviewActivity.start(
                                    requireContext(),
                                    pickedImages.map { it.toString() },
                                    index,
                                )
                            },
                        )
                    } else {
                        MomentsScreen(
                            items = items,
                            loading = loading,
                            refreshing = refreshing,
                            ended = ended,
                            emptyText = emptyText,
                            revision = revision,
                            onRefresh = { load(reset = true) },
                            onLoadMore = { load(reset = false) },
                            onLike = { toggleLike(it) },
                            onPostComment = { item, replyTo, text -> postComment(item, replyTo, text) },
                            onDeleteComment = { item, comment -> deleteComment(item, comment) },
                            onDeletePost = { item -> deletePost(item) },
                            onImage = { urls, index, rect ->
                                // rect = 缩略图在窗口里的位置，预览页据此做「从缩略图放大 / 缩回原位」
                                ImagePreviewActivity.start(requireContext(), urls, index, rect)
                            },
                            onOpenPublish = { openPublish() },
                        )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // 视图重建（进程恢复/主题切换）后列表必为空，不能因 savedInstanceState 跳过加载
        if (items.isEmpty()) load(reset = true)
    }

    override fun onResume() {
        super.onResume()
        syncToolbar()
    }

    /** 发表页 = 二级页（返回箭头 + 右上角「发表」）；列表页 = 主 Tab。 */
    fun syncToolbar() {
        val act = activity as? MainActivity ?: return
        if (isHidden || !isAdded) return
        if (publishPage) {
            act.showToolbarSub(
                getString(R.string.moments_publish_title),
                { closePublish() },
                if (publishing) null else getString(R.string.moments_publish),
                { publish(publishText) },
            )
        } else {
            act.showToolbarMain(getString(R.string.tab_moments))
        }
    }

    private fun openPublish() {
        publishText = ""
        pickedImages.clear()
        emptyText = null
        publishPage = true
        backCallback.isEnabled = true
        syncToolbar()
    }

    private fun closePublish() {
        if (!publishPage) return
        publishPage = false
        backCallback.isEnabled = false
        syncToolbar()
    }

    /** 底栏选中「工作圈」时回调：列表为空则自动刷新（兜底全黑） */
    fun onTabSelected() {
        if (view == null) return
        // 已经在列表页时再点「工作圈」= 从发表页退回（微信同款手势心智）
        if (publishPage) closePublish()
        syncToolbar()
        if (loading || items.isNotEmpty()) return
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        if (loading) return
        if (!reset && ended) return
        loading = true
        if (reset) {
            ended = false
            fromFeedId = null
            refreshing = true
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val ids = MomentsApi.getTimeLine(if (reset) null else fromFeedId)
                    val feeds = MomentsApi.getFeeds(ids)
                    val userIds = feeds.flatMap { f ->
                        listOfNotNull(f.user_id) +
                            (f.likes?.mapNotNull { it.user_id } ?: emptyList()) +
                            (f.comments?.mapNotNull { it.user_id } ?: emptyList())
                    }
                    UserInfoCache.resolve(userIds)
                    feeds
                }
                val newItems = page.map { MomentItem(it) }
                items = if (reset) newItems else items + newItems
                if (page.size < MomentsApi.PAGE_SIZE) ended = true
                fromFeedId = page.lastOrNull()?.feed_id ?: fromFeedId
                emptyText = if (items.isEmpty()) getString(R.string.moments_empty) else null
            } catch (e: ApiException) {
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_load), e)
                handleLoadError(getString(R.string.moments_load_failed, e.message.orEmpty()))
            } catch (e: Exception) {
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_load), e)
                handleLoadError(getString(R.string.moments_network_error))
            }
            loading = false
            refreshing = false
        }
    }

    private fun handleLoadError(message: String) {
        if (items.isEmpty()) emptyText = message else toast(message)
    }

    /**
     * 点赞 / 取消点赞。
     *
     * 2026-10-25 用户反馈「点赞后再点应该取消点赞，而不是又点一次赞」：
     * 之前是「先请求、成功后才改本地状态」，请求期间（或请求失败、或服务端返回的
     * 点赞列表里 user_id 对不上）本地 `likedByMe` 一直还是 false，于是第二次点击
     * 又走了一遍「点赞」。现在改成**先乐观改本地状态再请求**，失败回滚，
     * 这样第二次点击一定是取消。
     */
    private fun toggleLike(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        val liked = item.likedByMe
        val meId = Session.userId.ifBlank { "local_me" }
        fun markLiked(on: Boolean) {
            if (on) {
                item.likes.add(
                    LikeEntry(
                        like_id = "local_${System.currentTimeMillis()}",
                        feed_id = feedId,
                        user_id = meId,
                        create_dt = System.currentTimeMillis(),
                    )
                )
            } else {
                item.likes.removeAll { it.user_id == meId || (it.like_id ?: "").startsWith("local_") }
            }
            revision++
            items = items.toList()
        }
        // 乐观更新：手指抬起来 UI 立刻变
        markLiked(!liked)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (liked) MomentsApi.cancelLike(feedId) else MomentsApi.like(feedId)
                }
            } catch (e: Exception) {
                markLiked(liked)   // 请求失败：把乐观更新回滚回去
                toast(getString(R.string.moments_action_failed, e.message.orEmpty()))
            }
        }
    }

    private fun publish(text: String) {
        if (publishing) return
        val images = pickedImages.toList()
        if (text.isEmpty() && images.isEmpty()) {
            toast(getString(R.string.moments_publish_empty))
            return
        }
        if (text.length > 2000) {
            toast(getString(R.string.moments_publish_too_long))
            return
        }
        publishing = true
        syncToolbar()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appContext = requireContext().applicationContext
                withContext(Dispatchers.IO) {
                    val media = images.map { MomentImageUploader.upload(appContext, it) }
                    check(MomentsApi.publishFeed(text, media) != null) {
                        getString(R.string.moments_server_no_id)
                    }
                }
                pickedImages.clear()
                publishText = ""
                toast(getString(R.string.moments_publish_done))
                closePublish()
                load(reset = true)
            } catch (e: Exception) {
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_publish), e)
                toast(getString(R.string.moments_publish_failed, e.message.orEmpty()))
            } finally {
                publishing = false
                syncToolbar()
            }
        }
    }

    private fun postComment(item: MomentItem, replyToUserId: String?, text: String) {
        val feedId = item.feed.feed_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
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
                revision++
                items = items.toList()
                toast(getString(R.string.moments_sent))
            } catch (e: Exception) {
                toast(getString(R.string.moments_send_failed, e.message.orEmpty()))
            }
        }
    }

    private fun deleteComment(item: MomentItem, comment: CommentEntry) {
        val feedId = item.feed.feed_id ?: return
        val commentId = comment.comment_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { MomentsApi.deleteComment(feedId, commentId) }
                item.comments.removeIf { it.comment_id == commentId }
                revision++
                items = items.toList()
                toast(getString(R.string.moments_deleted))
            } catch (e: Exception) {
                toast(getString(R.string.moments_delete_failed, e.message.orEmpty()))
            }
        }
    }

    /** 删除自己发布的动态：服务端成功后才从列表里摘掉，失败给出提示 */
    private fun deletePost(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { MomentsApi.deleteFeed(feedId) }
                items = items.filterNot { it.feed.feed_id == feedId }
                revision++
                toast(getString(R.string.moments_deleted))
            } catch (e: Exception) {
                toast(getString(R.string.moments_delete_failed, e.message.orEmpty()))
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

/** 一条动态最多 9 张图 */
private const val MAX_MOMENT_IMAGES = 9
