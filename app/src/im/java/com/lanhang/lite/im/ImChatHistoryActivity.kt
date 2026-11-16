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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.toast
import com.lanhang.lite.databinding.ActivityImHistoryBinding
import com.lanhang.lite.databinding.ItemImFileRowBinding
import com.lanhang.lite.databinding.ItemImImageCellBinding
import com.lanhang.lite.moments.ImagePreviewActivity
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 会话内历史：文件 / 图片 / 搜索三合一（2026-11-15，对齐官方
 * `ConversationMessageSearchActivity` / `ConversationPicturesPreviewActivity`）。
 *
 * 三者骨架一样（顶栏 + 列表 + 空态 + 进度圈），差别只在「取哪些消息」和「怎么排」，
 * 所以合成一个页面。文件点开走和聊天页**完全相同**的 [ImFileOpen] 流程，
 * 不另写一套（否则命名规则一漂移，每次点开都要重下一遍）。
 */
class ImChatHistoryActivity : AppCompatActivity() {

    companion object {
        const val MODE_FILE = "file"
        const val MODE_IMAGE = "image"
        const val MODE_SEARCH = "search"

        private const val EXTRA_PEER = "peer_id"
        private const val EXTRA_GROUP = "is_group"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, peerId: String, isGroup: Boolean, title: String, mode: String) {
            context.startActivity(
                Intent(context, ImChatHistoryActivity::class.java)
                    .putExtra(EXTRA_PEER, peerId)
                    .putExtra(EXTRA_GROUP, isGroup)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_MODE, mode)
            )
        }
    }

    private lateinit var binding: ActivityImHistoryBinding
    private var peerId: String = ""
    private var isGroup = false
    private var title: String = ""
    private var mode: String = MODE_FILE

    private val fileAdapter = FileAdapter()
    private val imageAdapter = ImageAdapter()

    /** 正在下载/打开的文件 key：连点同一个文件不重复发起 */
    private val opening = mutableSetOf<String>()

    /** 搜索防抖：每敲一个字都查一次会把 SDK 拖垮 */
    private var searchJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER).orEmpty()
        isGroup = intent.getBooleanExtra(EXTRA_GROUP, false)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FILE
        if (peerId.isBlank()) {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(
            when (mode) {
                MODE_IMAGE -> R.string.im_chat_images
                MODE_SEARCH -> R.string.im_chat_search
                else -> R.string.im_chat_files
            }
        )

        val baseBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
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
                baseBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        when (mode) {
            MODE_IMAGE -> {
                val span = 3
                val cell = (resources.displayMetrics.widthPixels -
                    resources.getDimensionPixelSize(R.dimen.screen_padding_h) * 2) / span
                imageAdapter.cellSize = cell
                binding.recycler.layoutManager = GridLayoutManager(this, span)
                binding.recycler.adapter = imageAdapter
                loadImages()
            }

            MODE_SEARCH -> {
                binding.searchBox.isVisible = true
                binding.recycler.layoutManager = LinearLayoutManager(this)
                binding.recycler.adapter = fileAdapter
                fileAdapter.showText = true
                binding.etQuery.doAfterTextChanged { query ->
                    searchJob?.cancel()
                    val key = query?.toString().orEmpty().trim()
                    if (key.isEmpty()) {
                        fileAdapter.submit(emptyList())
                        showEmpty(R.string.im_chat_search_empty, visible = false)
                        binding.progress.hide()
                        return@doAfterTextChanged
                    }
                    // 输入框每变一次都查会打爆 SDK；停手 300ms 再查
                    searchJob = lifecycleScope.launch {
                        delay(300)
                        binding.progress.show()
                        ImClient.searchInConversation(isGroup, peerId, key) { list ->
                            if (isFinishing || isDestroyed) return@searchInConversation
                            binding.progress.hide()
                            fileAdapter.submit(list)
                            showEmpty(R.string.im_chat_search_empty, list.isEmpty())
                        }
                    }
                }
            }

            else -> {
                binding.recycler.layoutManager = LinearLayoutManager(this)
                binding.recycler.adapter = fileAdapter
                loadFiles()
            }
        }
    }

    private fun loadFiles() {
        binding.progress.show()
        ImClient.loadMessagesOfType(isGroup, peerId, "RC:FileMsg") { list ->
            if (isFinishing || isDestroyed) return@loadMessagesOfType
            binding.progress.hide()
            fileAdapter.submit(list)
            showEmpty(R.string.im_chat_files_empty, list.isEmpty())
        }
    }

    private fun loadImages() {
        binding.progress.show()
        ImClient.loadMessagesOfType(isGroup, peerId, "RC:ImgMsg") { list ->
            if (isFinishing || isDestroyed) return@loadMessagesOfType
            binding.progress.hide()
            imageAdapter.submit(list)
            showEmpty(R.string.im_chat_images_empty, list.isEmpty())
        }
    }

    private fun showEmpty(textRes: Int, visible: Boolean) {
        binding.tvEmpty.isVisible = visible
        if (visible) binding.tvEmpty.setText(textRes)
    }

    // ==================== 文件打开 ====================

    /** 点文件：下载（没有本地副本时）→ 复制到接收目录 → 交给系统打开 */
    private fun openFile(message: ImMessage) {
        if (!opening.add(message.key)) return
        // 已经有本地副本：直接开，不走网络
        ImFileOpen.recvFile(this, message.key, message.fileName)
            .takeIf { it.isFile && (message.fileSize <= 0 || it.length() == message.fileSize) }
            ?.let { openLocal(it, message) }
            ?: run {
                fileAdapter.setProgress(message.key, -1)
                ImClient.downloadFile(
                    uid = message.key,
                    remoteUrl = message.mediaUrl,
                    fileName = message.fileName,
                    fileSize = message.fileSize,
                    onProgress = { pct -> fileAdapter.setProgress(message.key, pct) },
                    onResult = { path ->
                        fileAdapter.setProgress(message.key, null)
                        val file = path?.let { java.io.File(it) }
                        if (file == null) {
                            opening.remove(message.key)
                            toast(R.string.im_file_download_failed)
                        } else {
                            openLocal(file, message)
                        }
                    },
                )
            }
    }

    private fun openLocal(downloaded: java.io.File, message: ImMessage) {
        val safe = ImFileOpen.ensureInRecvDir(this, downloaded, message.key, message.fileName)
        opening.remove(message.key)
        if (safe == null) {
            toast(R.string.im_file_open_failed)
            return
        }
        if (!ImFileOpen.openBestEffort(this, safe, message.fileName, message.fileMime)) {
            toast(R.string.im_file_no_app)
        }
    }

    // ==================== 列表 ====================

    /** 文件 / 搜索共用：一行「名字 + 大小·时间」，搜索模式把大小换成消息正文 */
    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {

        private val items = mutableListOf<ImMessage>()
        private val progress = mutableMapOf<String, Int>()

        /** 搜索模式：正文优先（文件行本来就该显示大小） */
        var showText = false

        fun submit(list: List<ImMessage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun setProgress(key: String, pct: Int?) {
            if (pct == null) progress.remove(key) else progress[key] = pct
            val index = items.indexOfFirst { it.key == key }
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemImFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemImFileRowBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(message: ImMessage) {
                // 点击统一在这里挂：文件列表点开走「下载 → 复制 → 交给系统」，
                // 搜索结果直接进会话（命中哪一条要精确定位得改聊天页入口，这里不做）
                b.root.setOnClickListener {
                    if (showText) {
                        // 带上 key + 时间：聊天页据此滚到这一条并高亮，
                        // 时间锚点留给「最近 40 条里没有它、需要翻历史」的情况
                        ImChatActivity.start(
                            itemView.context, peerId, isGroup, title,
                            focusKey = message.key,
                            focusTime = message.time,
                        )
                    } else {
                        openFile(message)
                    }
                }

                if (showText) {
                    // 搜索结果：图标按类型分开（2026-11-16 用户要求，文字消息顶着文件图标很误导），
                    // 正文当标题，没有正文的给类型占位，别留空行
                    b.ivIcon.setImageResource(iconOf(message.kind))
                    b.tvName.text = searchTitle(message)
                    b.tvMeta.text = com.lanhang.lite.core.TimeText.clock(message.time)
                    return
                }

                b.ivIcon.setImageResource(R.drawable.ic_im_file)
                b.tvName.text = message.fileName.orEmpty().ifBlank { itemView.context.getString(R.string.im_chat_files) }
                val meta = buildString {
                    append(humanSize(message.fileSize))
                    val at = com.lanhang.lite.core.TimeText.clock(message.time)
                    if (at.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(at)
                    }
                }
                val pct = progress[message.key]
                b.tvMeta.text = when {
                    pct == null -> meta
                    pct < 0 -> itemView.context.getString(R.string.im_file_downloading_unknown)
                    else -> itemView.context.getString(R.string.im_file_downloading, pct)
                }
            }
        }
    }

    private inner class ImageAdapter : RecyclerView.Adapter<ImageAdapter.VH>() {

        private val items = mutableListOf<ImMessage>()

        /** 单元格边长（像素），由 Activity 按屏宽三等分算好传进来 */
        var cellSize = 0

        fun submit(list: List<ImMessage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemImImageCellBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemImImageCellBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(message: ImMessage) {
                b.root.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    cellSize,
                )
                b.ivThumb.load(message.imageUrl ?: message.fullImageUrl) {
                    crossfade(true)
                }
                b.root.setOnClickListener {
                    val urls = items.mapNotNull { it.fullImageUrl ?: it.imageUrl }
                    val index = items.indexOfFirst { it.key == message.key }.coerceAtLeast(0)
                    if (urls.isNotEmpty()) ImagePreviewActivity.start(itemView.context, urls, index)
                }
            }
        }
    }

    /** 搜索结果的行图标：按消息类型分开（文件列表那条分支另走固定文件图标） */
    private fun iconOf(kind: Int): Int = when (kind) {
        ImMessage.KIND_IMAGE -> R.drawable.ic_photo
        ImMessage.KIND_VIDEO -> R.drawable.ic_im_video
        ImMessage.KIND_VOICE -> R.drawable.ic_im_mic
        ImMessage.KIND_LOCATION -> R.drawable.ic_im_location
        ImMessage.KIND_FILE -> R.drawable.ic_im_file
        else -> R.drawable.ic_im_message
    }

    /**
     * 搜索结果的标题。
     *
     * 只有文字消息自带正文；图片/语音/视频/位置这些 `text` 是空的，直接 `ifBlank` 会得到
     * 一行空白（比显示「[图片]」还难看出发生了什么），所以按类型给占位。
     * 文件优先显示文件名 —— 比「文件」两个字有用。
     */
    private fun searchTitle(message: ImMessage): String {
        message.text.takeIf { it.isNotBlank() }?.let { return it }
        message.fileName?.takeIf { it.isNotBlank() }?.let { return it }
        return getString(
            when (message.kind) {
                ImMessage.KIND_IMAGE -> R.string.im_preview_image
                ImMessage.KIND_VIDEO -> R.string.im_preview_video
                ImMessage.KIND_VOICE -> R.string.im_preview_voice
                ImMessage.KIND_FILE -> R.string.im_attach_file
                ImMessage.KIND_LOCATION -> R.string.im_attach_location
                else -> R.string.im_notify_body_placeholder
            }
        )
    }

    /** 文件大小给人看的样子（与聊天页同一口径：<1KB 给字节，其余一位小数） */
    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
