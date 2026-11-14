package com.lanhang.lite.im

import com.lanhang.lite.core.toast
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.databinding.ActivityImChatBinding
import com.lanhang.lite.databinding.ItemImMessageBinding
import com.lanhang.lite.update.UpdateInstaller

/**
 * 聊天页（IM 版专属，MVP 只收发文本）。
 *
 * 进入时拉最近 40 条并清未读；发出去的文本先以「发送中」入列，SDK 回调成功/失败再改状态，
 * 失败不自动重发（避免网络抖动时重复投递），只把气泡下面标一行红字。
 */
class ImChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImChatBinding
    private val adapter = Adapter()
    private var navBottomInset = 0
    private var inputBaseBottom = 0
    private var emojiBaseBottom = 0
    private var attachBaseBottom = 0

    private var peerId: String = ""
    private var isGroup: Boolean = false

    private val onChanged: () -> Unit = { if (::binding.isInitialized) render() }

    /** 图片选择：系统相册（Photo Picker，不需要读存储权限） */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) sendPickedImage(uri) }

    /** 文件选择：SAF，任意类型（同样不需要存储权限） */
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) sendPickedFile(uri) }

    /** 待保存的文件：另存为对话框返回后才知道往哪写，中间先放在这里 */
    private var pendingSaveFile: File? = null

    /** key → 下载进度（-1 = 已发起、还没有进度回调）；有值就在气泡上显示「下载中」 */
    private val downloading = mutableMapOf<String, Int>()

    /**
     * 「保存到本地」：交给系统的另存为对话框，用户自己挑目录。
     *
     * 不往 `Downloads` 里硬写：Android 10+ 写公共目录要走 MediaStore，10 以下又要
     * `WRITE_EXTERNAL_STORAGE` —— 三条分支还容易被系统拦。另存为不需要任何权限，
     * 全版本一条路，并且目录由用户决定。
     */
    private val fileSavePicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val src = pendingSaveFile
        pendingSaveFile = null
        if (uri == null || src == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } != null
                }.getOrDefault(false)
            }
            toast(if (ok) R.string.im_file_saved else R.string.im_file_save_failed)
        }
    }

    /** 视频选择：系统相册只列出视频 */
    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) sendPickedVideo(uri) }

    /** 位置：要先拿到定位权限，拿不到就直说，绝不编坐标 */
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) sendLocationNow() else toast(getString(R.string.im_location_denied))
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER).orEmpty()
        isGroup = intent.getBooleanExtra(EXTRA_GROUP, false)
        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 带输入框的页面必须同时消费 ime inset：只靠 adjustResize 在高版本上不生效
        inputBaseBottom = binding.inputBar.paddingBottom
        emojiBaseBottom = binding.emojiPanel.paddingBottom
        attachBaseBottom = binding.attachPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            navBottomInset = maxOf(bars.bottom, ime.bottom)
            val tb = binding.toolbar
            tb.setPadding(tb.paddingLeft, bars.top, tb.paddingRight, 0)
            applyComposerInsets()
            // 键盘一露头就把面板收掉：三者（键盘 / 表情 / 附件）同屏会把输入区顶掉半屏
            if (ime.bottom > 0) hidePanels()
            insets
        }

        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recycler.layoutManager = layoutManager
        binding.recycler.adapter = adapter

        buildEmojiPanel()
        binding.btnEmoji.setOnClickListener {
            if (binding.emojiPanel.isVisible) {
                hidePanels()
                showKeyboard()
            } else {
                showEmojiPanel()
            }
        }
        binding.btnAttach.setOnClickListener {
            if (binding.attachPanel.isVisible) hidePanels() else showAttachPanel()
        }
        binding.attachImage.setOnClickListener {
            hidePanels()
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.attachVideo.setOnClickListener {
            hidePanels()
            videoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
        binding.attachFile.setOnClickListener {
            hidePanels()
            runCatching { filePicker.launch(arrayOf("*/*")) }
                .onFailure { toast(getString(R.string.im_attach_failed)) }
        }
        binding.attachLocation.setOnClickListener {
            hidePanels()
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                sendLocationNow()
            } else {
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        binding.btnSend.setOnClickListener { send() }
        binding.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }

        ImClient.loadRecent(peerId, isGroup)
        render()
    }

    override fun onResume() {
        super.onResume()
        ImClient.addObserver(onChanged)
        // 这个会话正被看着：同会话再来消息不弹通知，顺手撤掉之前挂着的那条
        ImClient.visibleThreadKey = ImClient.threadKey(peerId, isGroup)
        ImNotify.cancel(this, peerId)
        // 清未读 + 回执「已读」放在这里而不是 onCreate：从别的页面（看图、个人主页）
        // 回来也要补一次，中间到达的消息才算被读过
        ImClient.markRead(peerId, isGroup)
        render()
    }

    override fun onPause() {
        // 只清自己那一份：用户可能已经跳到另一条会话（那边 onResume 先于这里执行）
        if (ImClient.visibleThreadKey == ImClient.threadKey(peerId, isGroup)) {
            ImClient.visibleThreadKey = null
        }
        ImClient.removeObserver(onChanged)
        super.onPause()
    }

    // ---------------------------------------------------------------- 表情

    /**
     * 表情面板：**Unicode emoji**，不放位图也不引表情库（包体积零增量，系统字体直接渲染）。
     * 选中即插入到光标处；面板开着时键盘是收起的，所以插入后用户能继续点下一个。
     */
    private fun buildEmojiPanel() {
        val grid = binding.emojiGrid
        grid.removeAllViews()
        val side = (resources.displayMetrics.density * 44).toInt()
        EMOJIS.forEach { emoji ->
            val cell = TextView(this).apply {
                text = emoji
                textSize = 22f
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = side
                    height = side
                }
                setOnClickListener { insertEmoji(emoji) }
            }
            grid.addView(cell)
        }
    }

    private fun insertEmoji(emoji: String) {
        val edit = binding.editText
        val start = edit.selectionStart.coerceAtLeast(0)
        val end = edit.selectionEnd.coerceAtLeast(0)
        edit.text.replace(minOf(start, end), maxOf(start, end), emoji)
    }

    // ---------------------------------------------------------------- 面板互斥

    private fun showEmojiPanel() {
        hideKeyboard()
        binding.attachPanel.isVisible = false
        binding.emojiPanel.isVisible = true
        applyComposerInsets()
        scrollToBottom()
    }

    private fun showAttachPanel() {
        hideKeyboard()
        binding.emojiPanel.isVisible = false
        binding.attachPanel.isVisible = true
        applyComposerInsets()
        scrollToBottom()
    }

    private fun hidePanels() {
        binding.emojiPanel.isVisible = false
        binding.attachPanel.isVisible = false
        applyComposerInsets()
    }

    private fun applyComposerInsets() {
        val panelVisible = binding.emojiPanel.isVisible || binding.attachPanel.isVisible
        binding.inputBar.setPadding(
            binding.inputBar.paddingLeft,
            binding.inputBar.paddingTop,
            binding.inputBar.paddingRight,
            inputBaseBottom + if (panelVisible) 0 else navBottomInset
        )
        binding.emojiPanel.setPadding(
            binding.emojiPanel.paddingLeft,
            binding.emojiPanel.paddingTop,
            binding.emojiPanel.paddingRight,
            emojiBaseBottom + if (binding.emojiPanel.isVisible) navBottomInset else 0
        )
        binding.attachPanel.setPadding(
            binding.attachPanel.paddingLeft,
            binding.attachPanel.paddingTop,
            binding.attachPanel.paddingRight,
            attachBaseBottom + if (binding.attachPanel.isVisible) navBottomInset else 0
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.editText.windowToken, 0)
    }

    private fun showKeyboard() {
        binding.editText.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(binding.editText, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------------------------------------------------------------- 图片 / 文件 / 位置

    /**
     * 发图：先把 `content://` 复制到缓存目录再交给 SDK。
     *
     * 为什么不直接把 content uri 给 SDK：Photo Picker / SAF 给的是**临时读权限**，
     * 上传是异步的，进程被回收或授权失效后 SDK 再读就失败（表现为「图片发不出去」）。
     */
    private fun sendPickedImage(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyToCache(uri) }
            if (file == null) {
                toast(getString(R.string.im_attach_failed))
                return@launch
            }
            ImClient.sendImage(peerId, isGroup, Uri.fromFile(file))
        }
    }

    /**
     * 发视频：先把视频复制到缓存，再用 `MediaMetadataRetriever` 抽第一帧当封面、读时长。
     *
     * 两件事必须做全：① 复制（SAF/相册给的是临时授权，上传是异步的）；
     * ② 抽封面（SDK 不会给视频生成缩略图，不传的话对方列表里是一块空白，见 `sendVideo` 注释）。
     * 抽帧失败就退回「封面 = 视频本身」，至少 SDK 能拿到一个合法 Uri。
     */
    private fun sendPickedVideo(uri: Uri) {
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) { prepareVideo(uri) }
            if (prepared == null) {
                toast(getString(R.string.im_attach_failed))
                return@launch
            }
            val (file, thumb, seconds) = prepared
            ImClient.sendVideo(
                peerId,
                isGroup,
                Uri.fromFile(file),
                thumb?.let(Uri::fromFile),
                seconds,
            )
        }
    }

    /** 复制视频 + 抽封面 + 读时长；任一步失败返回 null（IO 线程调用） */
    private fun prepareVideo(uri: Uri): Triple<File, File?, Int>? = runCatching {
        val name = (queryName(uri) ?: "video_${System.currentTimeMillis()}.mp4")
            .replace('/', '_')
        val video = copyToCache(uri, name) ?: return null
        val retriever = android.media.MediaMetadataRetriever()
        val ms = try {
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
        val thumb = runCatching {
            val r2 = android.media.MediaMetadataRetriever()
            try {
                r2.setDataSource(video.absolutePath)
                val frame = r2.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let { bitmap ->
                    val out = File(File(cacheDir, "im"), "thumb_${System.currentTimeMillis()}.jpg")
                    out.outputStream().use { os ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, os)
                    }
                    out.takeIf { it.length() > 0 }
                }
            } finally {
                runCatching { r2.release() }
            }
        }.getOrNull()
        Triple(video, thumb, (ms / 1000L).toInt().coerceAtLeast(1))
    }.getOrNull()

    private fun sendPickedFile(uri: Uri) {
        lifecycleScope.launch {
            val name = queryName(uri) ?: "file"
            val size = querySize(uri)
            val file = withContext(Dispatchers.IO) { copyToCache(uri, name) }
            if (file == null) {
                toast(getString(R.string.im_attach_failed))
                return@launch
            }
            ImClient.sendFile(peerId, isGroup, Uri.fromFile(file), name, size)
        }
    }

    /**
     * 发位置：取**上次已知位置**（照抄参考项目 `ImMedia.currentLocation` 的做法）。
     *
     * 三个要点（参考项目都这么写，别自己发挥）：
     * 1. 只挑 **provider 已启用** 的那几个 —— 关掉的 provider 取出来要么抛 SecurityException
     *    要么是空，之前没判就经常一路变成「拿不到位置」；
     * 2. 多个 provider 里取 `time` 最新的那个（GPS 和网络各有各的 fix，不是每次都有 GPS）；
     * 3. `Geocoder` 反查地名放 **IO 线程**，它内部可能做网络请求，主线程调会被 StrictMode 拦；
     *    查不到就用「纬度, 经度」兜底，绝不编地名。
     */
    private fun sendLocationNow() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { currentLocation() }
            if (found == null) {
                toast(getString(R.string.im_location_unavailable))
                return@launch
            }
            Log.i("LanhangIM", "location ${found.first.latitude},${found.first.longitude} poi=${found.second}")
            ImClient.sendLocation(peerId, isGroup, found.first.latitude, found.first.longitude, found.second)
        }
    }

    /** @return 位置 + 地名；拿不到返回 null（IO 线程调用） */
    private fun currentLocation(): Pair<Location, String>? {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val best = providers.mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.onFailure { Log.w("LanhangIM", "location $provider failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        }.maxByOrNull { it.time } ?: return null
        val poi = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(this, Locale.CHINA)
                .getFromLocation(best.latitude, best.longitude, 1)
                ?.firstOrNull()
                ?.let {
                    listOfNotNull(it.locality, it.subLocality, it.thoroughfare, it.featureName)
                        .distinct()
                        .joinToString("")
                }
        }.getOrNull().orEmpty().ifBlank {
            String.format(Locale.US, "%.5f, %.5f", best.latitude, best.longitude)
        }
        return best to poi
    }

    /** 把选中的 `content://` 复制进 cache/im/，返回落盘文件（IO 线程调用） */
    private fun copyToCache(uri: Uri, name: String? = null): File? = runCatching {
        val dir = File(cacheDir, "im").apply { mkdirs() }
        val fileName = (name ?: "im_${System.currentTimeMillis()}")
            .replace('/', '_')
            .take(80)
        val out = File(dir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                0L
            }
        } ?: 0L
    }.getOrDefault(0L)

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.recycler.scrollToPosition(count - 1)
    }


    /**
     * 位置气泡正文：第一行地名，第二行**经纬度**（小一号的灰字）。
     *
     * 坐标必须显示：地名是 `Geocoder` 反查出来的，无网/无 Google 服务的机型经常拿不到，
     * 这时第一行会是「28.65810, 115.81966」这种兜底文案 —— 没有第二行就分不清
     * 是「地名没查到」还是「位置根本没取到」。
     */
    private fun locationText(message: ImMessage): CharSequence {
        val poi = message.poi.orEmpty().ifBlank { message.text }
        val hasCoords = message.lat != 0.0 || message.lng != 0.0
        if (!hasCoords) return "📍 $poi"
        val coords = String.format(Locale.US, "%.5f, %.5f", message.lat, message.lng)
        val text = android.text.SpannableString("📍 $poi\n$coords")
        text.setSpan(
            android.text.style.RelativeSizeSpan(0.85f),
            text.length - coords.length,
            text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    /** 视频时长：`0:07` / `1:23` */
    private fun durationText(seconds: Int): String =
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    /**
     * 点视频 → 交给系统播放器。
     *
     * 不内置播放器：一个能用的播放器要处理解码、全屏、音频焦点、横竖屏切换，
     * 成本和「看一个聊天视频」不匹配；系统播放器 / 相册都能直接放。
     */
    private fun openVideo(context: Context, message: ImMessage) {
        val url = message.mediaUrl ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { toast(getString(R.string.im_video_open_failed)) }
    }

    // ==================== 文件消息：打开 / 保存 ====================

    /**
     * 点位置气泡 → 交给手机上的地图 App。
     *
     * 坐标在 [GeoShare] 里转成 GCJ02 再发出去：系统定位给的是 WGS84，
     * 高德 / 腾讯要 GCJ02、百度要 BD09，不转的话打点会偏 300~500 米。
     * 一个地图都没装就退回高德网页版；连网页都打不开时把坐标复制走，至少还能自己粘。
     */
    private fun openLocation(message: ImMessage) {
        if (message.lat == 0.0 && message.lng == 0.0) {
            toast(R.string.im_location_no_coords)
            return
        }
        val poi = message.poi.orEmpty().ifBlank { message.text }
        if (GeoShare.open(this, message.lat, message.lng, poi)) return

        val coords = String.format(Locale.US, "%.6f, %.6f", message.lat, message.lng)
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.im_location_coords_label), coords)
            )
        toast(R.string.im_location_open_failed)
    }

    /**
     * 点文件气泡：本地有就直接开，没有就先下载再开。
     *
     * SDK **不会自动下载文件消息**（图片才会顺手下一张缩略图），所以第一次点必然要过一遍
     * 网络；下载完成后 SDK 会把路径写回 `localPath`，第二次点就直开了。
     */
    private fun openFile(message: ImMessage) {
        if (downloading.containsKey(message.key)) return
        ensureLocalFile(message) { file ->
            if (file == null) {
                toast(R.string.im_file_download_failed)
                return@ensureLocalFile
            }
            launchFileViewer(file, message)
        }
    }

    /** 长按文件气泡：打开 / 保存到本地 */
    private fun showFileMenu(message: ImMessage) {
        MaterialAlertDialogBuilder(this)
            .setTitle(safeFileName(message.fileName.orEmpty()))
            .setItems(
                arrayOf(
                    getString(R.string.im_file_open),
                    getString(R.string.im_file_save),
                    getString(R.string.im_file_open_with),
                )
            ) { _, which ->
                when (which) {
                    0 -> openFile(message)
                    1 -> saveFile(message)
                    2 -> openFileWith(message)
                }
            }
            .show()
    }

    /** 保存到本地：先确保文件在盘上，再交给系统的另存为对话框 */
    private fun saveFile(message: ImMessage) {
        if (downloading.containsKey(message.key)) return
        ensureLocalFile(message) { file ->
            if (file == null) {
                toast(R.string.im_file_download_failed)
                return@ensureLocalFile
            }
            val src = copyToRecvDir(file, message)
            if (src == null) {
                toast(R.string.im_file_save_failed)
                return@ensureLocalFile
            }
            pendingSaveFile = src
            runCatching { fileSavePicker.launch(src.name) }
                .onFailure {
                    pendingSaveFile = null
                    toast(R.string.im_file_save_failed)
                }
        }
    }

    /**
     * 保证文件已在本地：命中直接给，没命中就下载（进度显示在气泡第三行）。
     *
     * 回调不直接碰 View，只改 [downloading] —— 页面切走时回调用不着 View，也就不会崩。
     */
    private fun ensureLocalFile(message: ImMessage, onReady: (File?) -> Unit) {
        localPathOf(message.mediaUrl)?.let { return onReady(it) }

        downloading[message.key] = -1
        refreshFileBubble(message.key)
        ImClient.downloadFile(
            uid = message.key,
            onProgress = { percent ->
                downloading[message.key] = percent
                refreshFileBubble(message.key)
            },
            onResult = { path ->
                downloading.remove(message.key)
                refreshFileBubble(message.key)
                onReady(path?.let(::File))
            },
        )
    }

    /** 「下载中 xx%」那行要跟着变，重绑这一条就够 */
    private fun refreshFileBubble(key: String) {
        val index = adapter.indexOf(key)
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    /**
     * [ImMessage.mediaUrl] 只有在是**本地路径**时才拿来直接打开。
     *
     * 三种形态：`file:///…`（自己发的、或已下载）、`/data/…`（裸路径）、`https://…`
     * （对方的 CDN 地址）—— 最后一种必须下载，这里返回 null 让上层去下。
     */
    private fun localPathOf(url: String?): File? {
        if (url.isNullOrBlank()) return null
        val path = when {
            url.startsWith("file://") -> Uri.parse(url).path
            url.startsWith("/") -> url
            else -> null
        } ?: return null
        return File(path).takeIf { it.isFile }
    }

    /** 「用其他应用打开」：跳过精确 MIME，直接把通配交给选择器，让用户自己挑 */
    private fun openFileWith(message: ImMessage) {
        if (downloading.containsKey(message.key)) return
        ensureLocalFile(message) { file ->
            if (file == null) {
                toast(R.string.im_file_download_failed)
                return@ensureLocalFile
            }
            launchFileViewer(file, message, anyApp = true)
        }
    }

    /**
     * 交给系统应用打开。
     *
     * 先复制到 `filesDir/im/recv/`：SDK 下载落点可能在 cache（随时被清），也不在
     * FileProvider 的白名单里，直接塞进去会抛 IllegalArgumentException。
     */
    private fun launchFileViewer(file: File, message: ImMessage, anyApp: Boolean = false) {
        val safe = copyToRecvDir(file, message)
        if (safe == null) {
            toast(R.string.im_file_open_failed)
            return
        }
        if (anyApp) {
            if (!viewWith(safe, "*/*", chooser = true)) toast(R.string.im_file_no_app)
            return
        }
        val mime = resolveMime(message, safe)

        // apk 交给系统安装器：和「检查更新」复用同一套（含「未知来源」授权引导）。
        // 用 ACTION_VIEW 自己拼一份的话，没开未知来源时只会静默失败。
        if (mime == MIME_APK) {
            UpdateInstaller.install(this, safe)?.let { toast(it) }
            return
        }

        if (viewWith(safe, mime, chooser = false)) return
        // 精确 MIME 没有任何应用认领（压缩包、冷门格式）→ 退到通配，让用户自己挑。
        // 不这样做的话，能处理 `*/*` 的浏览器/文件管理器永远不会出现在列表里。
        if (mime != "*/*" && viewWith(safe, "*/*", chooser = true)) return
        toast(R.string.im_file_no_app)
    }

    /**
     * 推断要交给系统的 MIME。
     *
     * 🔴 SDK 的 `FileMessage.getType()` **不保证是 MIME**（自己发的文件我们根本没设过 type，
     * 收到别人发的可能是个扩展名）。直接拿去 `setDataAndType` 会匹配不到任何应用 ——
     * 用户实测 apk 只弹出「网易云音乐」一个选项，就是 MIME 不对。
     * 所以：只认带斜杠的声明值，否则按扩展名查系统表，再不行才用通配。
     */
    private fun resolveMime(message: ImMessage, file: File): String {
        val declared = message.fileMime?.trim().orEmpty()
        if (declared.contains('/')) return declared
        val name = message.fileName ?: file.name
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** 起一个 ACTION_VIEW；返回是否真的拉起了（ActivityNotFound 也算 false） */
    private fun viewWith(file: File, mime: String, chooser: Boolean): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 部分 ROM 只认 ClipData 里的授权，不看 flag
        intent.clipData = ClipData.newRawUri(file.name, uri)
        val target = if (chooser) {
            Intent.createChooser(intent, getString(R.string.im_file_open_with))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent
        }
        return runCatching { startActivity(target) }.isSuccess
    }

    /**
     * 复制到 `filesDir/im/recv/`，文件名带消息 key 前缀，避免重名互相覆盖。
     *
     * 已经复制过（长度相同）就复用，否则每点一次都把大文件抄一遍。
     */
    private fun copyToRecvDir(src: File, message: ImMessage): File? = runCatching {
        val dir = File(filesDir, "im/recv").apply { mkdirs() }
        val name = safeFileName(message.fileName ?: src.name)
        val dst = File(dir, "${message.key.take(32)}_$name")
        if (dst.isFile && dst.length() == src.length()) return dst
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
        dst
    }.getOrNull()

    /**
     * 文件名清洗：对方发来的名字**不可信**。
     *
     * `../` 会被拼进路径（路径穿越），`:` `*` 之类在某些分区上直接建不出文件。
     * 统一换下划线，再掐个长度上限。
     */
    private fun safeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
            .trim('.')
            .take(80)
        return cleaned.ifBlank { "file" }
    }

    /** 气泡下方的时间：`HH:mm`（口径见 [com.lanhang.lite.core.TimeText.clock]） */
    private fun clockTime(millis: Long): String = com.lanhang.lite.core.TimeText.clock(millis)

    /** 文件大小给人看的样子：小于 1KB 直接给字节，其余保留一位小数 */
    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun send() {
        val text = binding.editText.text?.toString().orEmpty()
        if (text.isBlank()) return
        ImClient.sendText(peerId, isGroup, text)
        binding.editText.setText("")
    }

    /**
     * 渲染一次。
     *
     * 🔴 必须传**快照**（`.toList()`）：`ImClient.thread()` 回的是内部那份可变列表，
     * 直接交给 DiffUtil 会让 old 与 new 是同一个对象 —— 收到新消息、收到已读回执时
     * 元素在原地被改掉，DiffUtil 逐项对比「自己 == 自己」永远判无变化，
     * 界面就停在上一帧（列表页没有这个问题，它每次都是 `conversations = merged` 换新列表）。
     */
    private fun render() {
        val messages = ImClient.thread(peerId, isGroup).toList()
        val grew = adapter.submit(messages)
        if (grew) binding.recycler.scrollToPosition(messages.lastIndex)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        private var items: List<ImMessage> = emptyList()

        /**
         * 用 DiffUtil 增量更新（原来是 `notifyDataSetChanged()`）。
         *
         * 每来一条消息就整表重绑的话：正在看的图片会被重新下载/重建、滚动位置抖动、
         * 连带 `load(url)` 的 crossfade 会闪一下。
         *
         * @return 列表是否增长（新消息 → 需要滚到底）
         */
        fun submit(list: List<ImMessage>): Boolean {
            val previous = items.size
            val old = items
            items = list
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = list.size

                // 消息唯一标识：SDK 的 uId（本地发送时用时间戳兜底），见 ImMessage.key
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos].key == list[newPos].key

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos] == list[newPos]
            }).dispatchUpdatesTo(this)
            return list.size > previous
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemImMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        /**
         * 这个会话里所有能看的图（预览页要能左右滑），按时间顺序。
         *
         * **取原图地址**（`fullImageUrl`）：预览是全屏 + 可缩放，用缩略图会被放成一团糊。
         * 工作圈也是这么分的（九宫格 `displayUrl` / 预览 `url`）。
         */
        fun imageUrls(): List<String> =
            items.filter { it.kind == ImMessage.KIND_IMAGE }
                .mapNotNull { it.fullImageUrl ?: it.imageUrl }
                .filter { it.isNotBlank() }

        /** 按消息 key 找下标；找不到返回 -1（刷新下载进度用） */
        fun indexOf(key: String): Int = items.indexOfFirst { it.key == key }

        inner class VH(private val b: ItemImMessageBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(message: ImMessage) {
                val ctx = b.root.context
                val gravity = if (message.mine) Gravity.END else Gravity.START
                val isVideo = message.kind == ImMessage.KIND_VIDEO
                // 有媒体（图片 / 视频封面）和文字气泡二选一：同一个位置只能有一件东西在
                val hasMedia = message.isImage || isVideo
                b.tvBubble.isVisible = !hasMedia
                b.mediaBox.isVisible = hasMedia
                b.tvPlay.isVisible = isVideo
                b.tvDuration.isVisible = isVideo && message.duration > 0
                b.tvDuration.text = if (isVideo) durationText(message.duration) else ""

                if (hasMedia) {
                    bindMedia(message, ctx.resources.displayMetrics, isVideo)
                } else {
                    bindText(message)
                }

                (b.tvBubble.layoutParams as LinearLayout.LayoutParams).gravity = gravity
                (b.mediaBox.layoutParams as LinearLayout.LayoutParams).gravity = gravity
                (b.tvState.layoutParams as LinearLayout.LayoutParams).gravity = gravity

                // 气泡下面那行小字：发送中 / 发送失败 / **发送时间 + 已读状态**
                //
                // 和参考项目一致（miuix `BubbleBody` 下方那行 Text）：成功发送后显示 `HH:mm`，
                // 而不是整行收起来 —— 聊天里没有时间戳的话，隔几小时再回来看不出哪句是几点说的。
                // 失败时优先显示「发送失败」并转成 error 色，时间暂时让位（比时间更重要）。
                // 单聊里我发的消息再接一截「已读 / 未读」（群聊不回执已读，不显示）。
                val stateText = when (message.state) {
                    ImMessage.STATE_SENDING -> ctx.getString(R.string.im_sending)
                    ImMessage.STATE_FAILED -> ctx.getString(R.string.im_send_failed)
                    else -> {
                        val at = clockTime(message.time)
                        if (message.mine && !isGroup) {
                            ctx.getString(
                                if (message.read) R.string.im_msg_state_read
                                else R.string.im_msg_state_unread,
                                at,
                            )
                        } else {
                            at
                        }
                    }
                }
                b.tvState.isVisible = stateText.isNotEmpty()
                b.tvState.text = stateText
                b.tvState.setTextColor(
                    if (message.state == ImMessage.STATE_FAILED) {
                        MaterialColors.getColor(b.tvState, com.google.android.material.R.attr.colorError)
                    } else {
                        MaterialColors.getColor(
                            b.tvState,
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    }
                )
            }

            private fun bindText(message: ImMessage) {
                val ctx = b.root.context
                // 文件和位置也用气泡承载，但正文是拼出来的：文件带大小，位置带地名
                // 复用来的 holder 可能还挂着上一条的监听（图片那边踩过同样的坑）：
                // 只有文件气泡可点，别让普通文字也能点开上一个文件的菜单
                b.tvBubble.setOnClickListener(null)
                b.tvBubble.setOnLongClickListener(null)

                b.tvBubble.text = when (message.kind) {
                    ImMessage.KIND_FILE -> buildString {
                        append("📄 ")
                        append(message.fileName.orEmpty().ifBlank { message.text })
                        if (message.fileSize > 0) {
                            append('\n')
                            append(humanSize(message.fileSize))
                        }
                        // 正在下载时把状态写在第三行：这里没有进度条可用，文字最省事
                        when (val progress = downloading[message.key]) {
                            null -> Unit
                            -1 -> append('\n').append(ctx.getString(R.string.im_file_downloading_unknown))
                            else -> append('\n')
                                .append(ctx.getString(R.string.im_file_downloading, progress))
                        }
                    }
                    ImMessage.KIND_LOCATION -> locationText(message)
                    else -> message.text
                }
                // 气泡最大宽度 = 屏幕 78%：窄屏上 280dp 固定值会顶到边
                b.tvBubble.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt()

                val color = MaterialColors.getColor(
                    b.tvBubble,
                    if (message.mine) com.google.android.material.R.attr.colorOnPrimaryContainer
                    else com.google.android.material.R.attr.colorOnSurface
                )
                b.tvBubble.setTextColor(color)
                b.tvBubble.setBackgroundResource(
                    if (message.mine) R.drawable.bg_im_bubble_mine
                    else R.drawable.bg_im_bubble_other
                )

                // 文件：点一下打开（本地没有就先下载），长按出「打开 / 保存到本地」
                if (message.kind == ImMessage.KIND_FILE) {
                    b.tvBubble.setOnClickListener { openFile(message) }
                    b.tvBubble.setOnLongClickListener {
                        showFileMenu(message)
                        true
                    }
                } else if (message.kind == ImMessage.KIND_LOCATION) {
                    // 位置：点一下交给地图 App（坐标在里面转 GCJ02，否则会偏几百米）
                    b.tvBubble.setOnClickListener { openLocation(message) }
                }
            }

            /**
             * 媒体消息（图片 / 视频封面）：宽度上限 66% 屏宽、高度上限 40% 屏高，等比缩放。
             *
             * 地址为空说明图还没落盘（刚收到、或本地缓存被清），此时给一块底色占位，
             * 不显示破图。视频点开交给系统播放器（`ACTION_VIEW`），我们不做内置播放器。
             */
            private fun bindMedia(
                message: ImMessage,
                metrics: android.util.DisplayMetrics,
                isVideo: Boolean,
            ) {
                b.ivImage.maxWidth = (metrics.widthPixels * 0.66f).toInt()
                b.ivImage.maxHeight = (metrics.heightPixels * 0.40f).toInt()
                val url = message.imageUrl
                val lp = b.ivImage.layoutParams
                if (url.isNullOrBlank()) {
                    b.ivImage.setImageDrawable(null)
                    // 复用来的 holder 可能还挂着上一条的点击 —— 必须清掉，否则点空块会打开别人的图
                    b.ivImage.setOnClickListener(null)
                    b.mediaBox.setOnClickListener(null)
                    lp.width = (metrics.widthPixels * 0.28f).toInt()
                    lp.height = (metrics.heightPixels * 0.12f).toInt()
                    b.ivImage.layoutParams = lp
                    return
                }
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                b.ivImage.layoutParams = lp
                // 不指定 size：coil 按 View 的测量尺寸取样，正好是 maxWidth/maxHeight 收住之后的大小
                b.ivImage.load(url) { crossfade(true) }

                if (isVideo) {
                    b.mediaBox.setOnClickListener { view -> openVideo(view.context, message) }
                } else {
                    b.mediaBox.setOnClickListener { view ->
                        val urls = adapter.imageUrls()
                        // 预览列表用原图地址，点中的这张也要按原图去找下标（气泡里是缩略图地址）
                        val index = urls.indexOf(message.fullImageUrl ?: url).coerceAtLeast(0)
                        com.lanhang.lite.moments.ImagePreviewActivity.start(
                            view.context, urls, index
                        )
                    }
                }
            }

        }
    }

    companion object {
        /**
         * 表情面板的内容：常用 Unicode emoji。
         *
         * 用字符而不是表情包位图：系统字体直接渲染，**包体积零增量**，也不用为每套皮肤维护资源。
         * 顺序按使用频率大致排（笑脸在前、手势在后），每行 8 个（见布局的 columnCount）。
         */
        private val EMOJIS = listOf(
            "😀", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃",
            "😉", "😊", "😍", "🥰", "😘", "😜", "🤪", "🤗",
            "🤔", "🤨", "😐", "😑", "😶", "🙄", "😏", "😴",
            "😪", "😌", "😔", "😢", "😭", "😤", "😠", "😡",
            "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "🤢",
            "🤮", "🤧", "😷", "🤒", "👍", "👎", "👌", "✌️",
            "🤝", "🙏", "💪", "👏", "🙌", "👋", "🤙", "❤️",
            "💔", "💯", "🔥", "🎉", "🎂", "☕", "🌙", "⭐",
        )

        /** apk 的 MIME：走安装器而不是普通 ACTION_VIEW */
        private const val MIME_APK = "application/vnd.android.package-archive"

        internal const val EXTRA_PEER = "peer_id"
        internal const val EXTRA_GROUP = "is_group"
        internal const val EXTRA_TITLE = "title"

        /** 点通知进会话用的 Intent（[ImNotify] 复用，避免两处各拼一份 extra 键名） */
        internal fun intentOf(
            context: Context,
            peerId: String,
            isGroup: Boolean,
            title: String,
        ): Intent = Intent(context, ImChatActivity::class.java)
            .putExtra(EXTRA_PEER, peerId)
            .putExtra(EXTRA_GROUP, isGroup)
            .putExtra(EXTRA_TITLE, title)

        fun start(context: Context, peerId: String, isGroup: Boolean, title: String) {
            context.startActivity(intentOf(context, peerId, isGroup, title))
        }

        /**
         * 与某人开聊（个人主页「发私信」用）：标题当场从缓存取，取不到先留空，
         * 进页面后 `render()` 不依赖标题，用户看到的是历史消息。
         */
        fun startWithPeer(context: Context, peerId: String) {
            val title = com.lanhang.lite.moments.UserInfoCache.get(peerId)?.name.orEmpty()
            start(context, peerId, false, title)
        }
    }
}
