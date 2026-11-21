package com.lanhang.lite.im

import com.lanhang.lite.core.toast
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.util.Log
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
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
import io.rong.imlib.RongIMClient

/**
 * 聊天页（IM 版专属）：文本 / 图片 / 视频 / 文件 / 位置 / 语音六种消息的收发。
 *
 * 进入时拉最近 40 条并清未读；发出去的消息先以「发送中」入列，SDK 回调成功/失败再改状态，
 * 失败不自动重发（避免网络抖动时重复投递），只把气泡下面标一行红字（带具体原因）。
 *
 * 输入条两态（文字 / 语音）与录音浮层的约定见 AGENTS.md §IM「输入条两态」。
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

    /** 正在引用的那条消息（输入条上方显示引用条）；发出、或点 × 之后置空 */
    private var quoted: ImMessage? = null

    /**
     * 从聊天记录搜索跳过来时要定位的消息 key（null = 没有定位任务，正常滚到底）。
     * 定位完成 / 放弃后置空，见 [tryFocus]。
     */
    private var focusKey: String? = null

    /** 目标消息的时间锚点（毫秒）：最近 40 条里没有它时，用它向服务端翻一页历史 */
    private var focusTime = 0L

    /** 已经为定位翻过一次历史（只翻一次，避免反复请求） */
    private var focusAsked = false

    /** 刚展开面板、键盘还在收起动画里：这几帧的 `ime.bottom > 0` 不能当成「用户要键盘」 */
    private var panelOpening = false

    private val onChanged: () -> Unit = { if (::binding.isInitialized) render() }

    /** 图片选择：系统相册（Photo Picker，不需要读存储权限） */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) sendPickedImage(uri) }

    /**
     * 文件选择：SAF，**任意类型 + 可多选**（都不需要存储权限）。
     *
     * 对齐官方：它那边是 IMKit 的 `FilePlugin` → `FileManagerActivity`，同样没有类型白名单，
     * 一次可选多个文件，**每个文件单独发一条消息**。传入通配类型就是「全部类型」，
     * 别为了「只看文档」去加 MIME 过滤 —— 用户要发 zip/apk/日志都能发。
     */
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> uris.forEach { sendPickedFile(it) } }

    /**
     * 聊天详情页返回：在那边「清除聊天记录」后要把这一页重绘，否则读的还是内存里的旧列表。
     * （[ImClient.clearConversation] 已经把 `threads` 清空了，这里只要再 render 一次。）
     */
    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ImChatDetailActivity.RESULT_CLEARED) render()
    }

    /** 待保存的文件：另存为对话框返回后才知道往哪写，中间先放在这里 */
    private var pendingSaveFile: File? = null

    /** key → 下载进度（-1 = 已发起、还没有进度回调）；有值就在气泡上显示「下载中」 */
    private val downloading = mutableMapOf<String, Int>()

    // ---------------------------------------------------------------- 语音（2026-11-15，2026-11-16 改微信式）

    /** 录音器：按住说话期间一直拿着，页面切走必须 [ImVoiceRecorder.cancel] */
    private val voiceRecorder = ImVoiceRecorder(this)

    /**
     * 输入条当前是不是语音模式（中间换成「按住 说话」长条，最左那颗键变成键盘图标）。
     *
     * 与「有没有在录音」是两件事：模式是常驻状态，录音只是其中一次按下。
     */
    private var voiceMode = false

    /** 手指正按在「按住 说话」上且录音进行中（权限弹窗期间是 false，见 [startVoice]） */
    private var recordingVoice = false

    /** 按下时的 Y：往上滑出一段距离 = 取消这次录音 */
    private var voiceStartRawY = 0f

    /** 上滑取消的当前状态（只在跨过阈值时改，用来切浮层提示语） */
    private var voiceSlideCancel = false

    /** 这次录音的起点（`elapsedRealtime`，不受用户改系统时间影响），浮层计时用 */
    private var voiceStartAt = 0L

    /** 60 秒上限：到点自动松手发送（官方 `AudioRecordManager.RECORD_INTERVAL = 60`） */
    private val voiceMaxTask = Runnable { if (recordingVoice) finishVoice() }

    /**
     * 录音浮层的秒表（200ms 一跳）。
     *
     * 用 200ms 而不是 1000ms：跳秒时的边界抖动肉眼可见（0:01 停 1.2 秒才跳），
     * 而这点刷新开销在录音期间可以忽略。
     */
    private val voiceTickTask = object : Runnable {
        override fun run() {
            if (!recordingVoice) return
            val seconds = ((SystemClock.elapsedRealtime() - voiceStartAt) / 1000L).toInt()
            binding.tvVoiceTime.text = durationText(seconds)
            mainHandler.postDelayed(this, 200L)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 正在播放的语音 key（同一时刻只放一条）；气泡按它显示 ▶ / ■ */
    private var playingKey: String? = null

    private var player: MediaPlayer? = null

    /**
     * 「保存到手机」：交给系统的另存为对话框，**目录和文件名都由用户自己挑**。
     *
     * 不往 `Downloads` 里硬写：Android 10+ 写公共目录要走 MediaStore，10 以下又要
     * `WRITE_EXTERNAL_STORAGE` —— 三条分支还容易被系统拦。另存为不需要任何权限，
     * 全版本一条路，并且落在哪里完全由用户决定。
     *
     * MIME 与建议文件名**按这次要存的文件动态给**（所以用自定义 Contract，不用
     * `ActivityResultContracts.CreateDocument` —— 它的 MIME 是构造时写死的，只能一律用
     * 通配类型，另存为对话框就会建议一个 `.txt` 之类的后缀，图片类型也过滤不出来）。
     * 保存完还要把**真实落点**告诉用户，否则「存哪了」还是不知道。
     */
    private val fileSavePicker = registerForActivityResult(SaveDocument()) { uri ->
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
            toast(
                if (ok) getString(R.string.im_file_saved_to, savedLocationOf(uri))
                else getString(R.string.im_file_save_failed)
            )
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

    /**
     * 录音权限（**只有 im 变体的清单里有这条**，见 `src/im/AndroidManifest.xml`）。
     *
     * 🔴 授权回来**不自动开始录音**：弹权限框那一下手指早就抬起来了，
     * 自动开录只会在没人按着的情况下录 60 秒再误发一条。提示一句「按住说话」，
     * 用户再按一次即可 —— 少一次惊喜比少一次点击重要。
     */
    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast(R.string.im_voice_hold_to_speak)
        else toast(getString(R.string.im_voice_denied))
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
        focusKey = intent.getStringExtra(EXTRA_FOCUS_KEY)?.takeIf { it.isNotBlank() }
        focusTime = intent.getLongExtra(EXTRA_FOCUS_TIME, 0L)
        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbar.setNavigationOnClickListener { finish() }
        // NoActionBar 主题下菜单只能挂在布局上（app:menu），图标默认是黑色，按主题色染一次
        binding.toolbar.menu.findItem(R.id.action_chat_detail)?.icon?.mutate()?.apply {
            setTint(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.toolbar,
                    com.google.android.material.R.attr.colorOnSurface
                )
            )
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_chat_detail) {
                openDetail()
                true
            } else {
                false
            }
        }

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
            // 键盘一露头就把面板收掉：三者（键盘 / 表情 / 附件）同屏会把输入区顶掉半屏。
            // 刚点开面板的那 400ms 不算（键盘正在收起，ime 还是旧值），否则面板会被自己关掉
            if (ime.bottom > 0 && !panelOpening) hidePanels()
            insets
        }

        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recycler.layoutManager = layoutManager
        binding.recycler.adapter = adapter

        buildEmojiPanel()
        binding.btnEmoji.setOnClickListener {
            // 只有文字模式能看到这个键（语音模式下它是 GONE），所以这里只会走「文字态 ⇄ 面板」两条路
            if (binding.emojiPanel.isVisible) {
                hidePanels()
                showKeyboard()
            } else {
                showEmojiPanel()
            }
        }
        binding.btnAttach.setOnClickListener {
            // 同表情键：附件面板也是就地展开，不改输入条形态
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

        // 输入条两态：文字模式（输入框 + 有字才出的发送键）/ 语音模式（按住说话 + ＋）
        syncComposerButtons()
        binding.editText.doAfterTextChanged { syncComposerButtons() }
        binding.btnVoiceMode.setOnClickListener { setVoiceMode(!voiceMode) }
        binding.btnHold.setOnTouchListener { _, event -> onVoiceTouch(event) }

        binding.btnSend.setOnClickListener { send() }
        binding.btnQuoteClose.setOnClickListener { clearQuote() }
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

    /** 打开「聊天详情」（头像/名字、文件、图片、搜索、免打扰、置顶、清除记录） */
    private fun openDetail() {
        detailLauncher.launch(
            ImChatDetailActivity.intent(
                this,
                peerId = peerId,
                isGroup = isGroup,
                title = binding.toolbar.title?.toString().orEmpty(),
            )
        )
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
        // 录到一半切走：直接作废（回来得重新按住，不会有半截语音留在后台）
        if (recordingVoice) cancelVoice()
        stopVoicePlayback()
        // 只清自己那一份：用户可能已经跳到另一条会话（那边 onResume 先于这里执行）
        if (ImClient.visibleThreadKey == ImClient.threadKey(peerId, isGroup)) {
            ImClient.visibleThreadKey = null
        }
        ImClient.removeObserver(onChanged)
        super.onPause()
    }

    // ---------------------------------------------------------------- 语音（2026-11-15，2026-11-16 改微信式）

    /**
     * 输入条两态（2026-11-16 用户拿微信截图要求对齐）：
     * - 文字模式：`[麦克风] [输入框] [表情] [＋ 或 发送]`
     * - 语音模式：`[键盘]   [按住 说话]        [表情] [＋]`
     *
     * 「＋」与发送键叠在同一个 FrameLayout 里互斥：有字才出发送键，语音模式下一律出「＋」
     * —— 那个模式里发送是松手完成的，再摆一个发送键会让人以为还得点一下。
     */
    private fun syncComposerButtons() {
        if (!::binding.isInitialized) return
        val hasText = !binding.editText.text.isNullOrEmpty()
        binding.editText.isVisible = !voiceMode
        binding.btnHold.isVisible = voiceMode
        binding.btnVoiceMode.setImageResource(
            if (voiceMode) R.drawable.ic_im_keyboard else R.drawable.ic_im_mic
        )
        binding.btnVoiceMode.contentDescription = getString(
            if (voiceMode) R.string.im_switch_to_keyboard else R.string.im_switch_to_voice
        )
        // 表情键只在文字模式出现（2026-11-16 用户要求）：「按住 说话」要把表情键那块位置也吃下来，
        // 语音模式下藏掉它，长条靠 weight 自动延伸过去 —— 否则中间空一格，看着像断了一截。
        // 语音模式进不来表情面板（setVoiceMode 里 hidePanels），所以不存在「面板开着却关不掉」。
        binding.btnEmoji.isVisible = !voiceMode
        binding.btnSend.isVisible = hasText && !voiceMode
        binding.btnAttach.isVisible = !hasText || voiceMode
    }

    /**
     * 切「语音 ⇄ 键盘」：进语音先把键盘收起，回文字直接把键盘弹出来。
     *
     * @param popKeyboard 回文字态时是否立刻弹键盘。点表情 / 「＋」时传 false —— 那两个面板
     *   要占键盘的位置，弹出来又被 `ime.bottom > 0` 的 inset 回调收掉，会闪一下。
     */
    private fun setVoiceMode(on: Boolean, popKeyboard: Boolean = true) {
        if (voiceMode == on) return
        voiceMode = on
        if (on) {
            hidePanels()
            hideKeyboard()
            // 焦点必须挪走：只 hide 键盘的话输入框仍是焦点，某些 ROM 会在下一次布局时把键盘再弹回来，
            // 那正好和「按住说话」叠在一起
            binding.editText.clearFocus()
        }
        syncComposerButtons()
        if (!on && popKeyboard) {
            val edit = binding.editText
            edit.requestFocus()
            edit.setSelection(edit.text?.length ?: 0)
            // post 一帧：这一帧才刚把输入框从 GONE 翻成可见，立刻 showSoftInput 会被系统忽略
            edit.post { showKeyboard() }
        }
        scrollToBottom()
    }

    /**
     * 「按住 说话」的手势。
     *
     * DOWN 开录、UP 发送、**上滑超过阈值取消**（手指往上挪一段就能反悔，微信同款）。
     * 全程返回 true：DOWN 一旦被系统改判成别的（长按选字之类），后面的 MOVE/UP 就再也
     * 送不进来 —— 松手既不发也不停，录音会一直挂着。
     */
    private fun onVoiceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                voiceStartRawY = event.rawY
                voiceSlideCancel = false
                startVoice()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!recordingVoice) return true
                val cancel = event.rawY < voiceStartRawY - slideCancelDistance()
                if (cancel != voiceSlideCancel) {
                    voiceSlideCancel = cancel
                    // 滑进取消区就换提示语：不给反馈的话，用户不知道抬手会不会发出去
                    applyVoiceOverlayHint()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!recordingVoice) return true
                if (voiceSlideCancel) cancelVoice() else finishVoice()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (recordingVoice) cancelVoice()
                return true
            }
        }
        // 多指按下之类的其它 action：必须继续吞掉，否则这一串手势会被系统中途收走
        return true
    }

    /** 上滑多少算「取消」：60dp（按下后要明显挪一段，避免正常抖动就作废） */
    private fun slideCancelDistance(): Float = resources.displayMetrics.density * 60f

    private fun startVoice() {
        hidePanels()
        if (ImClient.status != ImClient.Status.CONNECTED) {
            toast(R.string.im_err_not_connected)
            return
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!voiceRecorder.start()) {
            toast(R.string.im_voice_record_failed)
            return
        }
        recordingVoice = true
        voiceSlideCancel = false
        voiceStartAt = SystemClock.elapsedRealtime()
        binding.btnHold.setBackgroundResource(R.drawable.bg_im_hold_active)
        showVoiceOverlay()
        mainHandler.removeCallbacks(voiceMaxTask)
        mainHandler.postDelayed(voiceMaxTask, VOICE_MAX_MS)
    }

    /** 录音浮层：提示语复位 + 计时归零，然后开始跳秒 */
    private fun showVoiceOverlay() {
        binding.tvVoiceTime.text = durationText(0)
        applyVoiceOverlayHint()
        binding.voiceOverlay.isVisible = true
        mainHandler.removeCallbacks(voiceTickTask)
        mainHandler.post(voiceTickTask)
    }

    /** 提示语两态：正常（白，上滑可取消）/ 已滑进取消区（浅红，松手即作废） */
    private fun applyVoiceOverlayHint() {
        binding.tvVoiceTip.setText(
            if (voiceSlideCancel) R.string.im_voice_release_to_cancel
            else R.string.im_voice_slide_up_cancel
        )
        binding.tvVoiceTip.setTextColor(
            if (voiceSlideCancel) TIP_CANCELLING_COLOR else Color.WHITE
        )
    }

    /** 收起浮层，并把「按住 说话」长条还原成未按下的底色 */
    private fun hideVoiceOverlay() {
        mainHandler.removeCallbacks(voiceTickTask)
        binding.voiceOverlay.isVisible = false
        binding.btnHold.setBackgroundResource(R.drawable.bg_im_hold)
    }

    /** 松手发送：太短/失败各自提示，成功直接交给 ImClient（乐观上屏 + 回调改状态） */
    private fun finishVoice() {
        mainHandler.removeCallbacks(voiceMaxTask)
        recordingVoice = false
        voiceSlideCancel = false
        hideVoiceOverlay()
        when (val result = voiceRecorder.stop()) {
            is ImVoiceRecorder.VoiceResult.Ok ->
                ImClient.sendVoice(peerId, isGroup, result.file, result.seconds)

            ImVoiceRecorder.VoiceResult.TooShort -> toast(R.string.im_voice_too_short)
            ImVoiceRecorder.VoiceResult.Failed -> toast(R.string.im_voice_record_failed)
        }
    }

    /** 上滑取消 / 页面切走：不提示（用户主动反悔的，不用强调） */
    private fun cancelVoice() {
        mainHandler.removeCallbacks(voiceMaxTask)
        recordingVoice = false
        voiceSlideCancel = false
        hideVoiceOverlay()
        voiceRecorder.cancel()
    }

    /** 点语音气泡：▶ 开始播放，■ 停止（同一时刻只放一条，切到别的先停上一条） */
    private fun toggleVoice(message: ImMessage) {
        if (playingKey == message.key) {
            stopVoicePlayback()
            return
        }
        val file = localPathOf(message.mediaUrl)
        if (file == null) {
            // 普通语音是 base64 内联落盘的，正常一定在；不在 = 缓存被清，只能直说
            toast(R.string.im_voice_play_failed)
            return
        }
        stopVoicePlayback()
        val p = MediaPlayer()
        try {
            p.setDataSource(file.absolutePath)
        } catch (e: Throwable) {
            Log.w("LanhangIM", "voice setDataSource failed", e)
            runCatching { p.release() }
            toast(R.string.im_voice_play_failed)
            return
        }
        p.setOnPreparedListener { mp ->
            // prepare 期间用户可能已经切到别的语音 / 页面切走了（那时 player 已被换掉或释放）
            if (player !== mp) return@setOnPreparedListener
            playingKey = message.key
            refreshBubble(message.key)
            runCatching { mp.start() }
        }
        p.setOnCompletionListener { stopVoicePlayback() }
        p.setOnErrorListener { _, _, _ ->
            stopVoicePlayback()
            toast(R.string.im_voice_play_failed)
            true
        }
        player = p
        p.prepareAsync()
    }

    private fun stopVoicePlayback() {
        val key = playingKey
        val p = player
        playingKey = null
        player = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        key?.let { refreshBubble(it) }
    }

    /** 语音气泡正文：▶ 待播放 / ■ 播放中（两个字形都在 Roboto 里，不会变豆腐块） */
    private fun voiceText(message: ImMessage): String {
        val icon = if (message.key == playingKey) "■" else "▶"
        return if (message.duration > 0) "$icon ${durationText(message.duration)}" else icon
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
        // 只有文字模式能打开表情面板（语音模式下 btnEmoji 是 GONE），所以这里不需要再判 voiceMode
        val edit = binding.editText
        val start = edit.selectionStart.coerceAtLeast(0)
        val end = edit.selectionEnd.coerceAtLeast(0)
        edit.text.replace(minOf(start, end), maxOf(start, end), emoji)
    }

    // ---------------------------------------------------------------- 面板互斥

    private fun showEmojiPanel() {
        openPanel()
        binding.attachPanel.isVisible = false
        binding.emojiPanel.isVisible = true
        applyComposerInsets()
        scrollToBottom()
    }

    private fun showAttachPanel() {
        openPanel()
        binding.emojiPanel.isVisible = false
        binding.attachPanel.isVisible = true
        applyComposerInsets()
        scrollToBottom()
    }

    /**
     * 开面板的统一动作：收键盘 + 给 [panelOpening] 打个短标记。
     *
     * 🔴 为什么要有这个标记（2026-11-16 查「点表情没反应」时揪出来的）：
     * `hideKeyboard()` 只是**发起**收起，键盘动画那几帧里 `ime.bottom` 仍然是旧值，
     * inset 回调会命中 `if (ime.bottom > 0) hidePanels()` —— 刚展开的面板当场被自己关掉。
     * 键盘收完（约 300ms）之前屏蔽那次收起即可。
     */
    private fun openPanel() {
        hideKeyboard()
        panelOpening = true
        binding.root.postDelayed({ panelOpening = false }, 400L)
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
     *
     * 🔴 **抽出来的帧必须压过再发**（见 [ImVideoCover]）：封面会被整个 base64 进消息体，
     * 而消息体上限只有 128KB —— 以前直接把全分辨率首帧 JPEG 发出去，视频必然发不出去。
     * 抽帧失败改成给**占位封面**，不再退回「封面 = 视频本身」（那等于把整段视频塞进消息体）。
     */
    private fun sendPickedVideo(uri: Uri) {
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) { prepareVideo(uri) }
            if (prepared == null) {
                toast(getString(R.string.im_attach_failed))
                return@launch
            }
            val (file, thumb, seconds) = prepared
            // 时长上限是服务端下发的（`getVideoLimitTime`，默认 300 秒）：超限的话
            // NativeClient 发送时会直接拒，与其等到那时才报一个看不懂的错误码，
            // 不如在这里按人话拦下，并把刚复制的那份丢掉（视频很大，别占着 cache）
            val limit = videoLimitSeconds()
            if (seconds > limit) {
                runCatching { file.delete() }
                runCatching { thumb?.delete() }
                toast(getString(R.string.im_video_too_long, limit))
                return@launch
            }
            ImClient.sendVideo(
                peerId,
                isGroup,
                Uri.fromFile(file),
                thumb?.let(Uri::fromFile),
                seconds,
            )
        }
    }

    /**
     * 服务端允许的视频时长（秒）。
     *
     * 取不到（还没连上、SDK 内部抛异常）时退回300 —— 与
     * `NavigationCacheHelper.DEFAULT_VIDEO_TIME` 一致，宁可按默认值拦，
     * 也不要因为读不到就放行一条注定发不出去的视频。
     */
    private fun videoLimitSeconds(): Int = runCatching {
        RongIMClient.getInstance().videoLimitTime
    }.getOrDefault(0).takeIf { it > 0 } ?: DEFAULT_VIDEO_LIMIT_S

    /** 复制视频 + 抽封面 + 读时长；复制失败返回 null（IO 线程调用） */
    private fun prepareVideo(uri: Uri): Triple<File, File?, Int>? = runCatching {
        val name = (queryName(uri) ?: "video_${System.currentTimeMillis()}.mp4")
            .replace('/', '_')
        val video = copyToCache(uri, name) ?: return null
        val dir = File(cacheDir, "im").apply { mkdirs() }
        val retriever = android.media.MediaMetadataRetriever()
        val ms = try {
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
        // 抽帧 → 压到 ≤360px / ≤48KB；抽不到就给占位封面。两条路都保证有一个**够小**的封面，
        // 绝不能退成「封面 = 视频文件」——那会把整段视频 base64 进消息体（见 ImVideoCover 注释）。
        val frame = runCatching {
            val r2 = android.media.MediaMetadataRetriever()
            try {
                r2.setDataSource(video.absolutePath)
                r2.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { r2.release() }
            }
        }.getOrNull()
        val stamp = "${System.currentTimeMillis()}"
        val thumb = if (frame != null) {
            try {
                ImVideoCover.write(frame, File(dir, "thumb_$stamp.jpg"))
            } finally {
                frame.recycle()
            }
        } else {
            ImVideoCover.placeholder(File(dir, "thumb_$stamp.jpg"))
        }
        // 时长读不到时按 1 秒兜底（照参考项目）：0 秒在部分机型上会被发送端直接判为非法参数，
        // 宁可发一条「1 秒」也不要因此整条发不出去。
        Triple(video, thumb, (ms / 1000L).toInt().coerceAtLeast(1))
    }.getOrNull()

    private fun sendPickedFile(uri: Uri) {
        lifecycleScope.launch {
            val name = withExtension(uri, queryName(uri))
            val file = withContext(Dispatchers.IO) { copyToCache(uri, name) }
            if (file == null) {
                toast(getString(R.string.im_attach_failed))
                return@launch
            }
            // 字节数以**落盘的那份**为准：SAF 的 OpenableColumns.SIZE 经常回 0，
            // 拿 0 去发服务端会判「0 字节文件」直接拒（`sendFile` 里还会再兜一次）
            ImClient.sendFile(peerId, isGroup, Uri.fromFile(file), name, file.length())
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

    /**
     * 把选中的 `content://` 复制进 cache/im/，返回落盘文件（IO 线程调用）。
     *
     * 两个坑（都踩过 / 参考项目第七十三轮同款）：
     * 1. **文件名必须带时间戳前缀**：不带的话同一个文件第二次发会覆盖上一份，
     *    而上传是异步的 —— SDK 再去读时内容已经换了，必失败。
     * 2. **`?` `#` 要换掉**：SDK 的 `obtain(uri)` 是拿 `uri.toString().substring(7)` 还原路径的，
     *    文件名里带这两个字符会把 query/fragment 当成路径的一部分 → 判「文件不存在」→ 发不出去。
     */
    private fun copyToCache(uri: Uri, name: String? = null): File? = runCatching {
        val dir = File(cacheDir, "im").apply { mkdirs() }
        val base = (name ?: "im")
            .replace('/', '_')
            .replace('?', '_')
            .replace('#', '_')
            .take(60)
        val out = File(dir, "${System.currentTimeMillis()}_$base")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * 保证文件名带扩展名。
     *
     * 有些 provider（截图分享、云盘直链）给的 `DISPLAY_NAME` 没有后缀，原样发出去对方
     * 既没法按类型给图标、也没法推断该用什么应用打开 —— 官方是拿本机文件，天然带后缀。
     * 这里用 `ContentResolver.getType(uri)` 反查扩展名补上；查不到就保持原样。
     */
    private fun withExtension(uri: Uri, name: String?): String {
        val base = name?.takeIf { it.isNotBlank() } ?: "file"
        if (base.contains('.')) return base
        val ext = runCatching {
            contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return base
        return "$base.$ext"
    }

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

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
     * 点视频 → 拿到本地文件交给系统播放器。
     *
     * 不内置播放器：一个能用的播放器要处理解码、全屏、音频焦点、横竖屏切换，
     * 成本和「看一个聊天视频」不匹配；系统播放器 / 相册都能直接放。
     *
     * 🔴 **本地没有就先下载**（2026-11-15 之前直接把 https 地址丢给 ACTION_VIEW，
     * 系统播放器没有网络权限读外链，表现就是「点了没反应」或「无法播放」）。
     * 下载与文件消息共用 `ImClient.downloadFile`（自己按 URL 下，SDK 的
     * `downloadMediaMessage` 只认 `mediaUrl`，实测常常拿不到地址）。
     */
    private fun openVideo(message: ImMessage) {
        localPathOf(message.mediaUrl)?.let { return playVideoFile(it, message) }
        if (message.mediaUrl.isNullOrBlank()) {
            toast(R.string.im_video_open_failed)
            return
        }
        if (downloading.containsKey(message.key)) return
        downloading[message.key] = -1
        refreshBubble(message.key)
        ImClient.downloadFile(
            uid = message.key,
            remoteUrl = message.mediaUrl,
            fileName = videoNameOf(message),
            fileSize = message.fileSize,
            onProgress = { percent ->
                downloading[message.key] = percent
                refreshBubble(message.key)
            },
            onResult = { path ->
                downloading.remove(message.key)
                refreshBubble(message.key)
                if (path == null) toast(R.string.im_file_download_failed)
                else playVideoFile(File(path), message)
            },
        )
    }

    /** 视频文件名：远端地址最后一段（去掉查询串），拿不出后缀就补一个 `.mp4` */
    private fun videoNameOf(message: ImMessage): String {
        val fromUrl = message.mediaUrl
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            .orEmpty()
        return if (fromUrl.contains('.')) fromUrl else "video.mp4"
    }

    /**
     * 播放本地视频：先复制进 `filesDir/im/recv/`（FileProvider 白名单在那里，
     * cache 里的文件直接给出去会抛 IllegalArgumentException），复制放 IO 线程
     * —— 视频几十 MB，主线程拷一次就够卡一下。
     */
    private fun playVideoFile(file: File, message: ImMessage) {
        lifecycleScope.launch {
            val name = videoNameOf(message)
            val safe = withContext(Dispatchers.IO) {
                ImFileOpen.ensureInRecvDir(this@ImChatActivity, file, message.key, name)
            }
            if (safe == null) {
                toast(R.string.im_file_open_failed)
                return@launch
            }
            if (!ImFileOpen.openBestEffort(this@ImChatActivity, safe, name, "video/mp4")) {
                toast(R.string.im_video_open_failed)
            }
        }
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
    /**
     * 长按消息的菜单，顺序照微信：**复制 / 引用 / 转发 / 撤回 / 删除**。
     *
     * - 复制、引用只对**文字消息**给：图片/语音没有正文可复制，引用它们也只能得到一句
     *   「[图片]」，与其给一个点了没内容的项，不如不显示。
     * - 转发对所有类型都给，按类型重发（富消息先确保本地文件，见 `ImClient.forward`）。
     * - 撤回只在 2 分钟内、自己的、已发出的消息上出现；超时由服务端最终决定。
     * - 删除是**只删本机**（融云 deleteMessages 的语义），所以确认框里写清「对方仍能看到」。
     * - 文件另外补「打开 / 保存到手机 / 用其他应用打开」三项，这是文件才有的动作。
     */
    private fun showMessageMenu(message: ImMessage) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (message.kind == ImMessage.KIND_TEXT && message.text.isNotBlank()) {
            labels += getString(R.string.im_copy_message)
            actions += { copyMessage(message) }
            labels += getString(R.string.im_quote_message)
            actions += { startQuote(message) }
        }
        labels += getString(R.string.im_forward_message)
        actions += { forwardMessage(message) }
        if (canRecall(message)) {
            labels += getString(R.string.im_recall)
            actions += { confirmRecall(message) }
        }
        labels += getString(R.string.im_delete_message)
        actions += { confirmDeleteMessage(message) }
        if (message.kind == ImMessage.KIND_FILE) {
            labels += getString(R.string.im_file_open)
            actions += { openFile(message) }
            labels += getString(R.string.im_file_save)
            actions += { saveFile(message) }
            labels += getString(R.string.im_file_open_with)
            actions += { openFileWith(message) }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (message.kind == ImMessage.KIND_FILE) safeFileName(message.fileName.orEmpty())
                else getString(R.string.im_message)
            )
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** 撤回条件：自己的、已发出、没撤过、还在窗口内（服务端另有更严格的限制） */
    private fun canRecall(message: ImMessage): Boolean =
        message.mine && !message.recalled &&
            message.state != ImMessage.STATE_FAILED &&
            message.state != ImMessage.STATE_SENDING &&
            (System.currentTimeMillis() - message.time) in 0..ImClient.RECALL_WINDOW_MS

    private fun confirmRecall(message: ImMessage) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.im_recall_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.im_recall) { _, _ ->
                ImClient.recall(peerId, isGroup, message) { ok, reason ->
                    if (isFinishing || isDestroyed) return@recall
                    if (!ok) toast(getString(R.string.im_recall_failed, reason.orEmpty()))
                }
            }
            .show()
    }

    /** 保存到手机：先确保文件在盘上，再交给系统的另存为对话框（目录与文件名都由用户挑） */
    private fun saveFile(message: ImMessage) {
        if (downloading.containsKey(message.key)) return
        ensureLocalFile(message) { file ->
            if (file == null) {
                toast(R.string.im_file_download_failed)
                return@ensureLocalFile
            }
            val src = ImFileOpen.ensureInRecvDir(this, file, message.key, message.fileName)
            if (src == null) {
                toast(R.string.im_file_save_failed)
                return@ensureLocalFile
            }
            pendingSaveFile = src
            // 建议文件名带上扩展名：另存为对话框不会替我们补，缺了就变成打不开的文件
            val name = safeFileName(message.fileName ?: src.name).let {
                if (it.contains('.')) it else "$it.${src.extension.ifBlank { "bin" }}"
            }
            runCatching { fileSavePicker.launch(name to ImFileOpen.resolveMime(name, message.fileMime)) }
                .onFailure {
                    pendingSaveFile = null
                    toast(R.string.im_file_save_failed)
                }
        }
    }

    /**
     * 把另存为返回的 Uri 翻译成人能看懂的位置。
     *
     * 主存储（`primary:Download/s73c.png`）→ `Download/s73c.png`；
     * SD 卡等其它卷 → `<卷名>/路径`；其余 provider（网盘、第三方文件管理器）拿不到真实路径，
     * 就只报文件名 —— 反正用户是自己在那个界面点的，知道点的是哪。
     */
    private fun savedLocationOf(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
        val name = docId.substringAfterLast(':').substringAfterLast('/')
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
        if (docId.isBlank() || uri.authority != "com.android.externalstorage.documents") {
            return name.ifBlank { getString(R.string.im_file_saved) }
        }
        val parts = docId.split(':', limit = 2)
        val path = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return name
        return if (parts[0].equals("primary", ignoreCase = true)) path else "${parts[0]}/$path"
    }

    /**
     * 第一次成功打开文件时提示一句「文件只在应用内，想要自己的副本得另存为」。
     *
     * 只提示一次（每个安装一次）：用户原话是「我都不知道给我自动下哪了」——
     * 长按菜单本来就有保存入口，只是没人知道。
     */
    private fun maybeHintFileSave() {
        val sp = getSharedPreferences("lanhang_im", Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_FILE_SAVE_HINT, false)) return
        sp.edit().putBoolean(KEY_FILE_SAVE_HINT, true).apply()
        toast(R.string.im_file_save_hint)
    }

    /**
     * 保证文件已在本地：命中直接给，没命中就下载（进度显示在气泡第三行）。
     *
     * 回调不直接碰 View，只改 [downloading] —— 页面切走时回调用不着 View，也就不会崩。
     */
    private fun ensureLocalFile(message: ImMessage, onReady: (File?) -> Unit) {
        localPathOf(message.mediaUrl)?.let { return onReady(it) }

        downloading[message.key] = -1
        refreshBubble(message.key)
        ImClient.downloadFile(
            uid = message.key,
            // 远端地址可能只在 fileUrl 里（mediaUrl 为空），两个都由 ImClient 兜住
            remoteUrl = message.mediaUrl,
            fileName = message.fileName,
            fileSize = message.fileSize,
            onProgress = { percent ->
                downloading[message.key] = percent
                refreshBubble(message.key)
            },
            onResult = { path ->
                downloading.remove(message.key)
                refreshBubble(message.key)
                onReady(path?.let(::File))
            },
        )
    }

    /** 某条气泡要重绑：下载进度、语音播放状态（▶/■）都走它 */
    private fun refreshBubble(key: String) {
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
     * 先复制到 `filesDir/im/recv/`（SDK 的下载落点可能在 cache、也不在 FileProvider 白名单里），
     * 再按 MIME 拉起 —— 具体规则统一在 [ImFileOpen] 里，文件列表页用的是同一套。
     */
    private fun launchFileViewer(file: File, message: ImMessage, anyApp: Boolean = false) {
        val safe = ImFileOpen.ensureInRecvDir(this, file, message.key, message.fileName)
        if (safe == null) {
            toast(R.string.im_file_open_failed)
            return
        }
        val ok = if (anyApp) {
            ImFileOpen.viewWith(this, safe, "*/*", chooser = true)
        } else {
            ImFileOpen.openBestEffort(this, safe, message.fileName, message.fileMime)
        }
        if (!ok) {
            toast(R.string.im_file_no_app)
            return
        }
        maybeHintFileSave()
    }

    /**
     * 文件名清洗：与下载落点共用同一规则（[ImClient.safeFileName]）。
     *
     * 两边规则必须一致 —— 一样名字才能让「刚下完的那份」被复制步骤直接命中，
     * 否则每次点开都会重新下载一遍。
     */
    private fun safeFileName(raw: String): String = ImClient.safeFileName(raw)

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
        // 引用只在这次发送里有效：发完就清掉，否则下一条会莫名其妙还带着引用
        val quote = quoted?.let { quoteOf(it) }
        ImClient.sendText(peerId, isGroup, text, quote)
        clearQuote()
        binding.editText.setText("")
    }

    // ---------------------------------------------------------------- 长按菜单用的动作

    /** 引用块里的「谁」：自己发的写「我」，别人的取缓存里的姓名，取不到写「对方」 */
    private fun displayNameOf(message: ImMessage): String {
        if (message.mine) return getString(R.string.im_quote_me)
        return com.lanhang.lite.moments.UserInfoCache.nameOf(message.senderId)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.im_quote_other)
    }

    /**
     * 把一条消息压成引用块。
     *
     * 摘要按**类型**给：图片/语音/视频没有正文，直接取 `text` 会得到空字符串，
     * 引用块就变成「引用 张三：」这种半截话。
     */
    private fun quoteOf(message: ImMessage): ImMessage.Quote {
        val body = when (message.kind) {
            ImMessage.KIND_TEXT -> message.text
            ImMessage.KIND_IMAGE -> getString(R.string.im_preview_image)
            ImMessage.KIND_VIDEO -> getString(R.string.im_preview_video)
            ImMessage.KIND_VOICE -> getString(R.string.im_preview_voice)
            ImMessage.KIND_FILE -> message.fileName.orEmpty()
            ImMessage.KIND_LOCATION -> message.poi.orEmpty()
            else -> message.text
        }.take(80)
        return ImMessage.Quote(sender = displayNameOf(message), text = body)
    }

    private fun startQuote(message: ImMessage) {
        // 引用只能配文字发出去：语音模式下先把输入框叫回来（不弹键盘，下面统一弹）
        if (voiceMode) setVoiceMode(false, popKeyboard = false)
        quoted = message
        binding.tvQuoteBar.text = getString(R.string.im_quote_bar, displayNameOf(message))
        binding.quoteBar.isVisible = true
        binding.editText.requestFocus()
        binding.editText.post { showKeyboard() }
    }

    private fun clearQuote() {
        quoted = null
        if (::binding.isInitialized) binding.quoteBar.isVisible = false
    }

    private fun copyMessage(message: ImMessage) {
        val body = message.text
        if (body.isBlank()) return
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", body))
        // Android 13+ 系统自己有「已复制」浮层，不再重复提示
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            toast(R.string.im_copy_done)
        }
    }

    /** 转发：先选会话。排除当前会话 —— 转给自己正在看的这个没有任何意义 */
    private fun forwardMessage(message: ImMessage) {
        val targets = ImClient.conversations.filterNot {
            it.peerId == peerId && it.isGroup == isGroup
        }
        if (targets.isEmpty()) {
            toast(R.string.im_forward_no_target)
            return
        }
        val labels = targets.map { if (it.isGroup) "👥 ${it.title}" else it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.im_forward_to)
            .setItems(labels) { _, which ->
                val target = targets[which]
                ImClient.forward(message, target.peerId, target.isGroup) { ok, _ ->
                    if (isFinishing || isDestroyed) return@forward
                    toast(if (ok) R.string.im_forward_done else R.string.im_forward_failed)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDeleteMessage(message: ImMessage) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.im_delete_message_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.im_delete_message) { _, _ ->
                ImClient.deleteMessage(peerId, isGroup, message) { ok ->
                    if (isFinishing || isDestroyed) return@deleteMessage
                    toast(if (ok) R.string.im_delete_message_done else R.string.im_delete_message_failed)
                }
            }
            .show()
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
        // 有定位任务时不滚到底：刚滚到目标又被拉回最新，等于白滚
        if (focusKey != null) {
            tryFocus()
            return
        }
        if (grew) binding.recycler.scrollToPosition(messages.lastIndex)
    }

    /**
     * 定位到从搜索跳过来的那条消息。
     *
     * 两种来源：① 目标就在最近 40 条里 → 直接滚；② 不在 → 用 [focusTime] 作锚点向服务端
     * 翻一页历史（只翻一次），翻到了再滚。都没有就提示一句「没找到」并放弃 ——
     * 那句提示是有用的：否则用户只会看到一个停在最新消息的会话页，以为跳转没生效。
     */
    private fun tryFocus() {
        val key = focusKey ?: return
        // 首帧列表还没回来（loadRecent 是异步的）：这时候翻历史会跟它抢同一个线程列表 ——
        // loadRecent 的写法是整体替换，先合并进来的历史会被它冲掉。等它回来再判断。
        if (adapter.itemCount == 0 && !focusAsked) return
        val index = adapter.indexOf(key)
        if (index >= 0) {
            focusKey = null
            revealAt(index, key)
            return
        }
        if (focusAsked || focusTime <= 0L) {
            focusKey = null
            toast(R.string.im_chat_locate_failed)
            return
        }
        focusAsked = true
        // +1s：服务端接口是「取 sentTime 之前（含）」，锚点抬一档才不会把目标自己排除掉
        ImClient.loadHistoryBefore(peerId, isGroup, focusTime + 1_000L, HISTORY_PAGE) { ok ->
            if (!ok) {
                focusKey = null
                toast(R.string.im_chat_locate_failed)
            }
            // 成功时 ImClient 会 notifyChanged → 再走一次 render/tryFocus
        }
    }

    /**
     * 滚到第 [index] 条并短暂高亮。
     *
     * 用 `scrollToPositionWithOffset` 把目标放在屏幕上方 1/3 处（而不是贴顶）：
     * 上面留一点上下文，用户才能看出这条前后是什么话。
     */
    private fun revealAt(index: Int, key: String) {
        val lm = binding.recycler.layoutManager as? LinearLayoutManager
        binding.recycler.post {
            lm?.scrollToPositionWithOffset(index, binding.recycler.height / 3)
            adapter.highlightKey = key
            adapter.notifyItemChanged(index)
            mainHandler.postDelayed({
                if (adapter.highlightKey == key) {
                    adapter.highlightKey = null
                    refreshBubble(key)
                }
            }, HIGHLIGHT_MS)
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        private var items: List<ImMessage> = emptyList()

        /** 定位后短暂高亮的那一条（聊天记录搜索跳转用）；null = 不高亮 */
        var highlightKey: String? = null

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
                // 搜索跳转定位：整行换个底色，滚过去才看得出是哪条（1.8s 后由 revealAt 清掉）。
                // 每次 bind 都要重设，holder 复用时不重设就会把上一条的高亮带过来
                if (message.key == highlightKey) {
                    b.row.setBackgroundColor(
                        MaterialColors.getColor(
                            b.row,
                            com.google.android.material.R.attr.colorSecondaryContainer
                        )
                    )
                } else {
                    b.row.background = null
                }
                val gravity = if (message.mine) Gravity.END else Gravity.START
                // 引用块：有 quote 才显示。复用 holder 必须显式设 GONE，否则上一条的引用会串过来
                val quote = message.quote
                b.tvQuote.isVisible = quote != null
                if (quote != null) {
                    b.tvQuote.text = "${quote.sender}：${quote.text}"
                    b.tvQuote.maxWidth =
                        (ctx.resources.displayMetrics.widthPixels * 0.72f).toInt()
                }
                val isVideo = message.kind == ImMessage.KIND_VIDEO
                // 有媒体（图片 / 视频封面）和文字气泡二选一：同一个位置只能有一件东西在
                val hasMedia = message.isImage || isVideo
                b.tvBubble.isVisible = !hasMedia
                b.mediaBox.isVisible = hasMedia
                b.tvPlay.isVisible = isVideo
                // 视频下载时把进度顶到时长胶囊上（气泡是封面图，没有第三行可用）
                val progress = downloading[message.key]
                b.tvDuration.isVisible = isVideo && (message.duration > 0 || progress != null)
                b.tvDuration.text = when {
                    !isVideo -> ""
                    progress == null -> durationText(message.duration)
                    progress < 0 -> ctx.getString(R.string.im_file_downloading_unknown)
                    else -> ctx.getString(R.string.im_file_downloading, progress)
                }

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
                    // 失败时带上具体原因（如「内容超限」）；认不出的原因退回通用文案
                    ImMessage.STATE_FAILED -> message.errorText
                        ?.let { ctx.getString(R.string.im_err_send_failed, it) }
                        ?: ctx.getString(R.string.im_send_failed)
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
                // 已撤回：只留一句占位，不显示原文、不可复制、不可点（撤回后气泡不该再能操作）
                if (message.recalled) {
                    bindRecalled(message)
                    return
                }
                // 🔴 语音气泡必须关掉文本选择（2026-11-16 用户反馈「只能点播放按钮，点气泡没反应」）：
                // `textIsSelectable=true` 的 TextView 把单击当「开始选字」吞掉，OnClickListener 不触发。
                // 文字/文件/位置仍然保留可选中复制 —— 只有语音整块是「点击即播放」的按钮。
                // 注意：setTextIsSelectable 会重置 clickable，所以必须在挂监听之前调。
                val selectable = message.kind != ImMessage.KIND_VOICE
                if (b.tvBubble.isTextSelectable != selectable) {
                    b.tvBubble.setTextIsSelectable(selectable)
                }
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
                    ImMessage.KIND_VOICE -> voiceText(message)
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

                // 文件：点一下打开（本地没有就先下载）
                if (message.kind == ImMessage.KIND_FILE) {
                    b.tvBubble.setOnClickListener { openFile(message) }
                } else if (message.kind == ImMessage.KIND_LOCATION) {
                    // 位置：点一下交给地图 App（坐标在里面转 GCJ02，否则会偏几百米）
                    b.tvBubble.setOnClickListener { openLocation(message) }
                } else if (message.kind == ImMessage.KIND_VOICE) {
                    // 语音：点一下播放、再点停止（普通语音是 base64 内联落盘的，没有下载环节）
                    b.tvBubble.setOnClickListener { toggleVoice(message) }
                }
                // 长按统一出菜单：自己的消息有「撤回」，文件还有「打开 / 保存 / 用其他应用打开」。
                // 对方的消息在 showMessageMenu 里直接返回，不出空菜单
                b.tvBubble.setOnLongClickListener {
                    showMessageMenu(message)
                    true
                }

                if (message.kind == ImMessage.KIND_VOICE) {
                    // 气泡宽度随时长增长（微信同款）：短语音是个小方块，60 秒拉满一行。
                    // 这不只是好看 —— 语音气泡整块可点，收得只剩「▶ 1"」时很难点准。
                    val dp = ctx.resources.displayMetrics.density
                    val stretch = message.duration.coerceIn(0, 60) * 1.4f
                    b.tvBubble.minWidth = ((72 + stretch) * dp).toInt()
                    // 播放中加粗：■ 已经换了字形，再加一层重量，一眼看出哪条在放
                    b.tvBubble.setTypeface(
                        null, if (message.key == playingKey) Typeface.BOLD else Typeface.NORMAL
                    )
                } else {
                    // 复用来的 holder 必须清掉上一条语音留下的宽度与字重，否则文字气泡被撑开
                    b.tvBubble.minWidth = 0
                    b.tvBubble.setTypeface(null, Typeface.NORMAL)
                }
            }

            /**
             * 已撤回的消息：只留一句占位。
             *
             * 原文**一定要清掉**（`ImClient.markRecalled` / `toImMessage` 都清了），否则「撤回」
             * 只是加了个标签，内容还挂在屏幕上。颜色用 onSurfaceVariant + 常规字重，
             * 和正文区分开；不挂任何点击，也就不会有人去点开一条已撤回的语音/文件。
             */
            private fun bindRecalled(message: ImMessage) {
                val ctx = b.root.context
                b.tvBubble.setTextIsSelectable(false)
                b.tvBubble.setOnClickListener(null)
                b.tvBubble.setOnLongClickListener(null)
                b.tvBubble.minWidth = 0
                b.tvBubble.setTypeface(null, Typeface.NORMAL)
                b.tvBubble.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt()
                b.tvBubble.text = ctx.getString(
                    if (message.mine) R.string.im_msg_recalled_self
                    else R.string.im_msg_recalled_other
                )
                b.tvBubble.setTextColor(
                    MaterialColors.getColor(
                        b.tvBubble,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                b.tvBubble.setBackgroundResource(
                    if (message.mine) R.drawable.bg_im_bubble_mine
                    else R.drawable.bg_im_bubble_other
                )
            }

            /**
             * 媒体消息（图片 / 视频封面）：宽度上限 66% 屏宽、高度上限 40% 屏高，等比缩放。
             *
             * 地址为空说明图还没落盘（刚收到、或本地缓存被清），此时给一块底色占位，
             * 不显示破图。视频点开先确保本地有文件（没有就下），再交系统播放器。
             */
            private fun bindMedia(
                message: ImMessage,
                metrics: android.util.DisplayMetrics,
                isVideo: Boolean,
            ) {
                b.ivImage.maxWidth = (metrics.widthPixels * 0.66f).toInt()
                b.ivImage.maxHeight = (metrics.heightPixels * 0.40f).toInt()
                // 长按图片 / 视频 → 同一个菜单（自己的消息能撤回）。挂在这里是因为它有
                // 两处 return（无地址的占位块 / 正常图），统一在入口设一次不会漏
                b.mediaBox.setOnLongClickListener {
                    showMessageMenu(message)
                    true
                }
                val url = message.imageUrl
                val lp = b.ivImage.layoutParams
                if (url.isNullOrBlank()) {
                    b.ivImage.setImageDrawable(null)
                    // 复用来的 holder 可能还挂着上一条的点击 —— 必须清掉，否则点空块会打开别人的图
                    b.ivImage.setOnClickListener(null)
                    // 视频没有封面也得能点开（封面是对方内联的 base64，可能解不出来）：
                    // 这时占位块本身就是入口，清掉监听就变成「看得到、点不动」
                    if (isVideo) {
                        b.mediaBox.setOnClickListener { openVideo(message) }
                    } else {
                        b.mediaBox.setOnClickListener(null)
                    }
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
                    b.mediaBox.setOnClickListener { openVideo(message) }
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

    /**
     * 「另存为」的 Contract：内容就是 `ACTION_CREATE_DOCUMENT`，但要能按文件动态给
     * MIME 和建议文件名，所以不能直接用 `ActivityResultContracts.CreateDocument`（MIME 写死）。
     */
    private class SaveDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second)
                .putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent.takeIf { resultCode == Activity.RESULT_OK }?.data
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

        /** 「文件只在应用内、想要副本请另存为」这句提示只弹一次的标记（SP 键） */
        private const val KEY_FILE_SAVE_HINT = "file_save_hint_shown"

        /** 录音浮层进入「取消区」时的提示色：浅红，压在深灰卡片上仍看得清 */
        private val TIP_CANCELLING_COLOR = Color.parseColor("#FF8A80")

        /** 单条语音时长上限（官方 `AudioRecordManager.RECORD_INTERVAL = 60`） */
        private const val VOICE_MAX_MS = 60_000L

        /** 视频时长上限兜底秒数（`NavigationCacheHelper.DEFAULT_VIDEO_TIME = 300`） */
        private const val DEFAULT_VIDEO_LIMIT_S = 300

        internal const val EXTRA_PEER = "peer_id"
        internal const val EXTRA_GROUP = "is_group"
        internal const val EXTRA_TITLE = "title"

        /** 从聊天记录搜索跳过来时要定位的那条消息（key + 时间；时间用于判断要不要翻历史） */
        private const val EXTRA_FOCUS_KEY = "focus_key"
        private const val EXTRA_FOCUS_TIME = "focus_time"

        /** 定位时向服务端补拉的消息条数（够覆盖一屏多一些，不用一次拉太多） */
        private const val HISTORY_PAGE = 50

        /** 定位后高亮多久（够看清楚是哪条，又不至于一直亮着） */
        private const val HIGHLIGHT_MS = 1800L

        /** 点通知进会话用的 Intent（[ImNotify] 复用，避免两处各拼一份 extra 键名） */
        internal fun intentOf(
            context: Context,
            peerId: String,
            isGroup: Boolean,
            title: String,
            focusKey: String? = null,
            focusTime: Long = 0L,
        ): Intent = Intent(context, ImChatActivity::class.java)
            .putExtra(EXTRA_PEER, peerId)
            .putExtra(EXTRA_GROUP, isGroup)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_FOCUS_KEY, focusKey)
            .putExtra(EXTRA_FOCUS_TIME, focusTime)

        /**
         * @param focusKey 搜索结果里那一条的 key；给了就在进页面后滚到它并高亮
         * @param focusTime 那一条的时间（毫秒）。最近 40 条里找不到它时，用它作为时间锚点
         *   向服务端翻一页历史 —— 没有锚点就只能放弃定位
         */
        fun start(
            context: Context,
            peerId: String,
            isGroup: Boolean,
            title: String,
            focusKey: String? = null,
            focusTime: Long = 0L,
        ) {
            context.startActivity(intentOf(context, peerId, isGroup, title, focusKey, focusTime))
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
