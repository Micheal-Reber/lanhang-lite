package com.lanhang.lite.moments

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.HorizontalScrollView
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
    private var publishImageCount: TextView? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImages.clear()
            selectedImages.addAll(uris.take(9))
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
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.moments_publish_hint)
            setPadding(48, 32, 48, 16)
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val imageCount = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(requireContext().getColor(com.google.android.material.R.color.material_on_surface_emphasis_medium))
            setPadding(16, 8, 16, 4)
        }
        val imageRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 4, 12, 8)
        }
        val imageScroller = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(imageRow, FrameLayout.LayoutParams(-1, -2))
        }
        publishImagesRow = imageRow
        publishImageCount = imageCount
        val addImages = TextView(requireContext()).apply {
            text = getString(R.string.moments_add_image)
            textSize = 14f
            setTextColor(requireContext().getColor(com.lanhang.lite.R.color.primary))
            setPadding(16, 12, 16, 12)
            setOnClickListener {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(imageCount, LinearLayout.LayoutParams(-1, -2))
            addView(imageScroller, LinearLayout.LayoutParams(-1, -2))
            addView(addImages, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moments_publish_title)
            .setView(content)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.moments_publish, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                val images = selectedImages.toList()
                if (text.isEmpty() && images.isEmpty()) {
                    toast(getString(R.string.moments_publish_empty))
                    return@setOnClickListener
                }
                if (text.length > 2000) {
                    toast(getString(R.string.moments_publish_too_long))
                    return@setOnClickListener
                }
                dialog.dismiss()
                publish(text, images)
            }
        }
        dialog.show()
    }

    private fun renderSelectedImages() {
        val row = publishImagesRow ?: return
        row.removeAllViews()
        selectedImages.forEachIndexed { index, uri ->
            val tile = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(72.dp, 72.dp).apply { marginEnd = 8.dp }
            }
            val image = ImageView(requireContext()).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(uri)
            }
            tile.addView(image, FrameLayout.LayoutParams(-1, -1))
            val remove = TextView(requireContext()).apply {
                text = "×"
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                setOnClickListener {
                    selectedImages.removeAt(index)
                    renderSelectedImages()
                }
            }
            tile.addView(remove, FrameLayout.LayoutParams(24.dp, 24.dp, android.view.Gravity.TOP or android.view.Gravity.END))
            row.addView(tile)
        }
        publishImageCount?.text = if (selectedImages.isEmpty()) {
            getString(R.string.moments_image_hint)
        } else {
            getString(R.string.moments_image_selected, selectedImages.size)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

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
                publishImageCount = null
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
}
