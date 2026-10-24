package com.lanhang.lite.moments

import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.databinding.FragmentMomentsBinding
import com.lanhang.lite.net.ApiException
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.session.Session
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.LanhangTheme
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MomentsFragment : Fragment() {

    private var _binding: FragmentMomentsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MomentAdapter
    private var fromFeedId: String? = null
    private var loading = false
    private var ended = false
    private var publishing = false
    private val selectedImages = mutableListOf<Uri>()
    private var publishImagesRow: LinearLayout? = null

    /** 发布面板当前状态的刷新入口（字数、按钮可用性），面板关闭后置空 */
    private var publishRefreshState: (() -> Unit)? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PUBLISH_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 追加而不是替换：再次点「添加」不能把已经选好的图冲掉。
            // 同一个 Uri 重复选中只保留一份，总数仍受 9 张上限约束。
            uris.forEach { uri ->
                if (selectedImages.size < MAX_PUBLISH_IMAGES && selectedImages.none { it == uri }) {
                    selectedImages.add(uri)
                }
            }
            renderSelectedImages()
        }
    }

    private val onFabScroll = android.view.ViewTreeObserver.OnScrollChangedListener {
        _binding?.fabPublish?.invalidate()
    }
    private val onFabLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        _binding?.fabPublish?.invalidate()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMomentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MomentAdapter(
            onLike = { item -> toggleLike(item) },
            onComment = { item -> askComment(item) },
            onImage = { urls, index ->
                ImagePreviewActivity.start(requireContext(), urls, index)
            },
            onReply = { item, userId -> askComment(item, userId) },
            onCommentsLongPress = { item -> showMyComments(item) }
        )
        setupGlassFab()
        binding.root.viewTreeObserver.addOnScrollChangedListener(onFabScroll)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(onFabLayout)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || ended) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val total = lm.itemCount
                val last = lm.findLastVisibleItemPosition()
                if (last >= total - 3) load(reset = false)
            }
        })
        binding.swipe.setOnRefreshListener { load(reset = true) }

        // 视图重建（进程恢复/主题切换）后 adapter 必为空，不能因 savedInstanceState 跳过加载
        if (savedInstanceState == null || adapter.itemCount == 0) {
            binding.progress.isVisible = true
            load(reset = true)
        }
    }

    /** 底栏选中「工作圈」时回调：列表为空则自动刷新（兜底全黑） */
    fun onTabSelected() {
        if (_binding == null) return
        binding.fabPublish.invalidate()
        if (loading || adapter.itemCount > 0) return
        binding.progress.isVisible = true
        load(reset = true)
    }

    override fun onDestroyView() {
        binding.root.viewTreeObserver.removeOnScrollChangedListener(onFabScroll)
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(onFabLayout)
        super.onDestroyView()
        _binding = null
    }

    /** 与底栏同款：Compose Canvas 录一层内容做实时 backdrop，玻璃 FAB 从中采样 */
    private fun setupGlassFab() {
        val fab = binding.fabPublish
        val source = binding.swipe
        val publishLabel = getString(R.string.moments_cd_publish)
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        fab.setContent {
            LanhangTheme(darkTheme = night) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop),
                    ) {
                        val sourceLocation = IntArray(2)
                        val hostLocation = IntArray(2)
                        source.getLocationInWindow(sourceLocation)
                        fab.getLocationInWindow(hostLocation)
                        drawIntoCanvas { canvas ->
                            val nativeCanvas = canvas.nativeCanvas
                            val saveCount = nativeCanvas.save()
                            nativeCanvas.translate(
                                (sourceLocation[0] - hostLocation[0]).toFloat(),
                                (sourceLocation[1] - hostLocation[1]).toFloat(),
                            )
                            source.draw(nativeCanvas)
                            nativeCanvas.restoreToCount(saveCount)
                        }
                    }
                    LiquidGlassFab(
                        backdrop = backdrop,
                        contentDescription = publishLabel,
                        onClick = { showPublishDialog() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun load(reset: Boolean) {
        if (loading) return
        if (!reset && ended) return
        loading = true
        if (reset) {
            ended = false
            fromFeedId = null
            binding.swipe.isRefreshing = true
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
                val items = page.map { MomentItem(it) }
                if (reset) adapter.clear()
                if (page.size < MomentsApi.PAGE_SIZE) ended = true
                fromFeedId = page.lastOrNull()?.feed_id ?: fromFeedId
                adapter.append(items)
                binding.fabPublish.invalidate()
                binding.tvEmpty.isVisible = adapter.itemCount == 0
                if (adapter.itemCount == 0) binding.tvEmpty.text = getString(R.string.moments_empty)
            } catch (e: ApiException) {
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_load), e)
                if (reset && adapter.itemCount == 0) {
                    binding.tvEmpty.isVisible = true
                    binding.tvEmpty.text = getString(R.string.moments_load_failed, e.message.orEmpty())
                } else {
                    toast(getString(R.string.moments_load_failed, e.message.orEmpty()))
                }
            } catch (e: Exception) {
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_load), e)
                if (reset && adapter.itemCount == 0) {
                    binding.tvEmpty.isVisible = true
                    binding.tvEmpty.text = getString(R.string.moments_network_error)
                } else {
                    toast(getString(R.string.moments_network_error))
                }
            }
            loading = false
            binding.progress.isVisible = false
            binding.swipe.isRefreshing = false
        }
    }

    private fun toggleLike(item: MomentItem) {
        val feedId = item.feed.feed_id ?: return
        val liked = item.likedByMe
        viewLifecycleOwner.lifecycleScope.launch {
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
                adapter.notifyItemChanged(adapter.indexOf(feedId).coerceAtLeast(0))
                binding.fabPublish.invalidate()
            } catch (e: Exception) {
                toast(getString(R.string.moments_action_failed, e.message.orEmpty()))
            }
        }
    }

    private fun showPublishDialog() {
        selectedImages.clear()
        val ctx = requireContext()
        val content = layoutInflater.inflate(R.layout.dialog_publish_moment, null)
        val input = content.findViewById<EditText>(R.id.etContent)
        val counter = content.findViewById<TextView>(R.id.tvCounter)
        val imageRow = content.findViewById<LinearLayout>(R.id.imageRow)
        val btnCancel = content.findViewById<MaterialButton>(R.id.btnCancel)
        val btnPublish = content.findViewById<MaterialButton>(R.id.btnPublish)
        val btnClose = content.findViewById<ImageButton>(R.id.btnClose)
        publishImagesRow = imageRow

        val limit = PUBLISH_MAX_LENGTH
        val colorVariant = MaterialColors.getColor(
            counter,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val colorError = MaterialColors.getColor(
            counter,
            com.google.android.material.R.attr.colorError
        )

        // 字数与主按钮可用性一起刷新：空内容时「发表」置灰，不靠点击后弹提示
        val refreshState = {
            val length = input.text.length
            counter.text = getString(R.string.moments_publish_counter, length, limit)
            counter.setTextColor(if (length >= limit) colorError else colorVariant)
            btnPublish.isEnabled = length > 0 || selectedImages.isNotEmpty()
        }
        publishRefreshState = refreshState

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(s: Editable?) = refreshState()
        })

        renderSelectedImages()
        refreshState()

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(content, 0, 0, 0, 0)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnClose.setOnClickListener { dialog.dismiss() }
        btnPublish.setOnClickListener {
            val text = input.text.toString().trim()
            val images = selectedImages.toList()
            when {
                text.isEmpty() && images.isEmpty() ->
                    toast(getString(R.string.moments_publish_empty))

                text.length > limit ->
                    toast(getString(R.string.moments_publish_too_long))

                else -> {
                    dialog.dismiss()
                    publish(text, images)
                }
            }
        }

        dialog.setOnShowListener {
            dialog.window?.apply {
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.93f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // 键盘弹出时压缩面板高度，而不是把整块顶到屏幕外
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        dialog.setOnDismissListener {
            publishImagesRow = null
            publishRefreshState = null
        }
        dialog.show()
    }

    /** 缩略图 + 末尾的「添加」方块；选满 9 张后添加方块自动消失 */
    private fun renderSelectedImages() {
        val row = publishImagesRow ?: return
        row.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val tileSize = dp(78)
        val radius = 14f * density
        // 用随明暗主题切换的强调色（日间深蓝 / 夜间浅蓝），
        // colorPrimary 在夜间仍是 #084168，压在深色面板上会看不清
        val accent = ContextCompat.getColor(ctx, R.color.week_pill_text)

        selectedImages.forEachIndexed { index, uri ->
            val tile = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                    marginEnd = dp(10)
                }
                isClickable = true
                isFocusable = true
                // 点缩略图看大图：复用工作圈的全屏预览（多图可左右滑、双击缩放、单击关闭）
                setOnClickListener {
                    if (index in selectedImages.indices) {
                        ImagePreviewActivity.start(
                            ctx,
                            selectedImages.map { it.toString() },
                            index
                        )
                    }
                }
            }
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
                load(uri)
            }
            tile.addView(image, FrameLayout.LayoutParams(-1, -1))

            val remove = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(android.graphics.Color.WHITE)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xC2000000.toInt())
                }
                contentDescription = getString(R.string.moments_remove_image)
                setOnClickListener {
                    if (index in selectedImages.indices) {
                        selectedImages.removeAt(index)
                        renderSelectedImages()
                    }
                }
            }
            tile.addView(
                remove,
                FrameLayout.LayoutParams(
                    dp(22),
                    dp(22),
                    android.view.Gravity.TOP or android.view.Gravity.END
                ).apply {
                    topMargin = dp(4)
                    marginEnd = dp(4)
                }
            )
            row.addView(tile)
        }

        if (selectedImages.size < MAX_PUBLISH_IMAGES) {
            val add = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_publish_add_tile)
                layoutParams = LinearLayout.LayoutParams(tileSize, tileSize)
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.moments_add_image_short)
                setOnClickListener {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            add.addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_add)
                    setColorFilter(accent)
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
            )
            add.addView(
                TextView(ctx).apply {
                    text = getString(R.string.moments_add_image_short)
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(accent)
                    setPadding(0, dp(4), 0, 0)
                },
                // 显式居中：文字与上面的加号保持同一条竖直中线
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
            )
            row.addView(add)
        }

        // 图片增减会影响「发表」按钮的可用性
        publishRefreshState?.invoke()
    }

    private fun publish(text: String, images: List<Uri>) {
        if (publishing) return
        publishing = true
        binding.progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appContext = requireContext().applicationContext
                withContext(Dispatchers.IO) {
                    val media = images.map { MomentImageUploader.upload(appContext, it) }
                    check(MomentsApi.publishFeed(text, media) != null) {
                        getString(R.string.moments_server_no_id)
                    }
                }
                selectedImages.clear()
                toast(getString(R.string.moments_publish_done))
                load(reset = true)
            } catch (e: Exception) {
                Diagnostics.record(requireContext(), getString(R.string.moments_tag_publish), e)
                toast(getString(R.string.moments_publish_failed, e.message.orEmpty()))
            } finally {
                publishImagesRow = null
                publishRefreshState = null
                publishing = false
                binding.progress.isVisible = false
            }
        }
    }

    private fun askComment(item: MomentItem, replyToUserId: String? = null) {
        val feedId = item.feed.feed_id ?: return
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.moments_comment_hint)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (replyToUserId.isNullOrEmpty()) getString(R.string.moments_comment_title)
                else getString(R.string.moments_reply_title, UserInfoCache.nameOf(replyToUserId))
            )
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_send) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
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
                        adapter.notifyItemChanged(adapter.indexOf(feedId).coerceAtLeast(0))
                binding.fabPublish.invalidate()
                        toast(getString(R.string.moments_sent))
                    } catch (e: Exception) {
                        toast(getString(R.string.moments_send_failed, e.message.orEmpty()))
                    }
                }
            }
            .show()
    }

    /** 长按评论区：列出自己发过的评论，可删除（DELETE /comment/{feed}/{comment}） */
    private fun showMyComments(item: MomentItem) {
        val mine = item.comments.filter {
            it.user_id == Session.userId && !it.comment_id.isNullOrEmpty()
        }
        if (mine.isEmpty()) {
            toast(getString(R.string.moments_no_deletable))
            return
        }
        val labels = mine.map {
            getString(R.string.moments_delete_item, it.content.orEmpty().take(40))
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moments_my_comments)
            .setItems(labels) { _, which -> confirmDeleteComment(item, mine[which]) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteComment(item: MomentItem, comment: CommentEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.moments_delete_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                val feedId = item.feed.feed_id ?: return@setPositiveButton
                val cid = comment.comment_id ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { MomentsApi.deleteComment(feedId, cid) }
                        item.comments.removeIf { it.comment_id == cid }
                        adapter.notifyItemChanged(adapter.indexOf(feedId).coerceAtLeast(0))
                binding.fabPublish.invalidate()
                        toast(getString(R.string.moments_deleted))
                    } catch (e: Exception) {
                        toast(getString(R.string.moments_delete_failed, e.message.orEmpty()))
                    }
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 一次最多 9 张图（与朋友圈一致） */
        const val MAX_PUBLISH_IMAGES = 9

        /** 正文上限，与服务端校验保持一致 */
        const val PUBLISH_MAX_LENGTH = 2000
    }
}
