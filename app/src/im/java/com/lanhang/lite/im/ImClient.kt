package com.lanhang.lite.im

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lanhang.lite.R
import com.lanhang.lite.moments.UserInfoCache
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.session.Session
import io.rong.imlib.IRongCallback
import io.rong.imlib.RongIMClient
import io.rong.imlib.model.Conversation
import io.rong.imlib.model.Group
import io.rong.imlib.model.Message
import io.rong.imlib.model.MessageContent
import io.rong.message.FileMessage
import io.rong.message.ImageMessage
import io.rong.message.LocationMessage
import io.rong.message.SightMessage
import io.rong.message.TextMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 一条会话（私信列表的一行）。群聊时 [peerId] 是群 id，[title] 是群名。 */
data class ImConversation(
    val peerId: String,
    val isGroup: Boolean,
    val title: String,
    val preview: String,
    val time: Long,
    val unread: Int,
    /** 最后一条消息的类型：文本才有 [preview]，图片/其它由界面换成对应文案 */
    val previewKind: PreviewKind = PreviewKind.TEXT,
) {
    enum class PreviewKind { TEXT, IMAGE, OTHER }
}

/** 聊天页的一条消息（文本 / 图片 / 文件 / 位置四种，2026-10-31 补齐后三种） */
data class ImMessage(
    val key: String,
    val text: String,
    val mine: Boolean,
    val time: Long,
    val state: Int,
    val senderId: String,
    val kind: Int = KIND_TEXT,
    /** 气泡里显示的地址（缩略图优先：小、下得快）；文本消息为 null */
    val imageUrl: String? = null,
    /**
     * 点开放大时用的地址（**原图**：本地已下载的原图 → 媒体服务器上的原图）。
     *
     * 和 [imageUrl] 必须分开：气泡用缩略图，全屏还用缩略图就会被放成一团糊
     * （2026-10-31 用户实测反馈「放大看很模糊」）。工作圈也是这么分的：
     * 九宫格显示 `displayUrl`（缩略），预览传 `url`（原图）。
     */
    val fullImageUrl: String? = null,
    /** 文件名（[kind] == [KIND_FILE] 时有值） */
    val fileName: String? = null,
    /** 文件字节数 */
    val fileSize: Long = 0L,
    /** 位置消息的地名（[kind] == [KIND_LOCATION] 时有值） */
    val poi: String? = null,
    /** 位置消息的纬度 */
    val lat: Double = 0.0,
    /** 位置消息的经度 */
    val lng: Double = 0.0,
    /** 视频时长（秒，[kind] == [KIND_VIDEO] 时有值） */
    val duration: Int = 0,
    /** 视频/文件的媒体地址（本地路径或媒体服务器地址，点开时要用） */
    val mediaUrl: String? = null,
) {
    val isImage: Boolean get() = kind == KIND_IMAGE

    companion object {
        const val STATE_SENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2

        const val KIND_TEXT = 0
        const val KIND_IMAGE = 1
        const val KIND_FILE = 2
        const val KIND_LOCATION = 3
        const val KIND_VIDEO = 4
    }
}

/**
 * 私信核心（2026-10-31 首次落地，只做文本）。
 *
 * 服务端事实（反编译官方 `cn.rongcloud.rce.lib` + 实测 `GET /api/configuration/all` 双向确认）：
 * - appKey / navi / media 全在 `features.im` 里，**公开接口就下发**，不需要登录；
 * - `RongIMClient.setServerInfo(navi, media)` **必须在 `init()` 之前**，否则 SDK 会去连
 *   默认的 `nav.cn.ronghub.com`（这网络下根本不通）；
 * - 连接用的 token 就是**主 API 登录下发的那个**，不是单独的 IM token；
 *   只有回调 `onTokenIncorrect()` 才 `POST /user/refresh_token` 换一个。
 *
 * 生命周期：SDK 是进程级单例，[ensureStarted] 幂等；UI 通过 [addObserver] 拿变更通知。
 */
object ImClient {

    enum class Status { IDLE, CONNECTING, CONNECTED, FAILED }

    private const val TAG = "LanhangIM"

    /** IPC_DISCONNECT 这类瞬时错误自动重试的上限（0.8s / 3.2s / 7.2s） */
    private const val MAX_AUTO_RETRY = 3

    private val main = Handler(Looper.getMainLooper())

    /** 读配置 / 换 token 用的 IO 作用域（这些接口都是同步 OkHttp，不能碰主线程） */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var status: Status = Status.IDLE
        private set

    @Volatile
    var error: String? = null
        private set

    private var sdkReady = false
    private var inFlight = false
    private var retryAttempt = 0

    /** 建连用的 application context（收消息时要弹通知，得有个长期存活的 Context） */
    @Volatile
    private var app: Context? = null

    /**
     * 当前正被用户看着的会话（`ImChatActivity.onResume/onPause` 维护）。
     * 与它相同的会话收到消息时不弹通知 —— 内容已经在眼前了。
     */
    @Volatile
    var visibleThreadKey: String? = null

    /** 会话列表（已按时间倒序） */
    var conversations: List<ImConversation> = emptyList()
        private set

    /** 每个会话最近的消息；key 见 [threadKey] */
    private val threads = mutableMapOf<String, MutableList<ImMessage>>()

    private val observers = mutableListOf<() -> Unit>()

    fun threadKey(peerId: String, isGroup: Boolean): String =
        (if (isGroup) "g:" else "p:") + peerId

    fun thread(peerId: String, isGroup: Boolean): List<ImMessage> =
        threads[threadKey(peerId, isGroup)].orEmpty()

    // ---------------------------------------------------------------- 观察者

    fun addObserver(block: () -> Unit) {
        if (!observers.contains(block)) observers += block
    }

    fun removeObserver(block: () -> Unit) {
        observers -= block
    }

    private fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            observers.toList().forEach { it() }
        } else {
            main.post { observers.toList().forEach { it() } }
        }
    }

    /**
     * 取本地化文案。
     *
     * 这里刻意保留 `app` 为空时返回空串：调用点全在「已连接 / 正在连接」路径上，
     * 那时 `app` 一定已赋值（[ensureStarted] 第一行就存了），真为空也是还没起来，不需要文案。
     */
    private fun text(@androidx.annotation.StringRes res: Int, vararg args: Any): String =
        app?.getString(res, *args).orEmpty()

    // ---------------------------------------------------------------- 连接

    /**
     * 进程起来后静默建连（App 启动时由 [com.lanhang.lite.core.ImBridge.warmUp] 调用）。
     *
     * 为什么要它：融云是长连接，**只有连上才收得到消息**。早先只在进私信页时连，
     * 结果「切到后台就收不到、也没有系统通知」——收通知的前提是先有个在线客户端。
     * 这里不区分场景，直接走 [ensureStarted]（幂等）；失败只是让 `status=FAILED`，
     * 没有 UI 观察者时不会有任何打扰，进私信页再重试即可。
     */
    fun warmUp(context: Context) {
        if (Session.token.isBlank()) return // 没登录：连了也只会拿到 token 错误
        ensureStarted(context)
    }

    /** 进私信页调用：初始化 SDK 并连接；已连上就只刷新会话列表 */
    fun ensureStarted(context: Context) {
        app = context.applicationContext
        when (status) {
            Status.CONNECTED -> {
                refreshConversations()
                return
            }
            Status.CONNECTING -> return
            else -> Unit
        }
        if (inFlight) return
        inFlight = true
        status = Status.CONNECTING
        error = null
        notifyChanged()
        // 读配置 / 换 token 都是**同步 OkHttp**，必须离开主线程：
        // 之前直接在主线程调 configurationAll() → NetworkOnMainThreadException，
        // 又被 runCatching 吞成「学校未下发私信配置」（2026-10-31 真机第一次就踩了）
        io.launch { prepare(context.applicationContext) }
    }

    /** 用户点了重试：把自动重试次数归还 */
    fun retry(context: Context) {
        retryAttempt = 0
        status = Status.IDLE
        inFlight = false
        ensureStarted(context)
    }

    /** IO 线程：读学校配置 → 解析出 appKey / navi / media → 拿 token，然后回主线程起 SDK */
    private suspend fun prepare(app: Context) {
        val config = try {
            RceApi.configurationAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "configurationAll failed", e)
            main.post { fail(text(R.string.im_err_config_read, e.javaClass.simpleName)) }
            return
        }
        val im = config.features?.im
        Log.i(
            TAG,
            "config features=${config.features != null} im=${im != null} " +
                "appKey=${im?.appKey} navi=${im?.naviUrls?.firstOrNull()}"
        )
        // 先判 im 再判 appKey：`appKey` 是从 `im` 里取的，合在一起写会让「im == null」那段
        // 变成恒假分支（编译器也是这么提示的），真出问题时反而看不出走的是哪条
        if (im == null) {
            main.post { fail(text(R.string.im_err_config_no_im)) }
            return
        }
        val appKey = im.appKey?.takeIf { it.isNotBlank() }
        if (appKey == null) {
            main.post { fail(text(R.string.im_err_config_no_key)) }
            return
        }

        // token：平时直接用登录下发的那个；只有连接报 token 无效时才换
        var token = Session.imToken.takeIf { it.isNotBlank() }
            ?: Session.token.takeIf { it.isNotBlank() }
        if (token.isNullOrBlank()) {
            token = try {
                RceApi.refreshImToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "refreshImToken failed", e)
                null
            }?.also { Session.imToken = it }
        }
        if (token.isNullOrBlank()) {
            main.post { fail(text(R.string.im_err_no_token)) }
            return
        }

        val navi = im.naviUrls?.firstOrNull { it.isNotBlank() }
        val media = im.mediaUrls?.firstOrNull { it.isNotBlank() }
        main.post { startSdk(app, appKey, navi, media, token) }
    }

    /** 主线程：`setServerInfo` → `init` → `connect`（顺序不能变，见类注释） */
    private fun startSdk(
        app: Context,
        appKey: String,
        navi: String?,
        media: String?,
        token: String
    ) {
        if (!sdkReady) {
            val ok = runCatching {
                if (navi != null) RongIMClient.setServerInfo(navi, media.orEmpty())
                RongIMClient.init(app, appKey)
            }.onFailure { Log.e(TAG, "init failed", it) }.isSuccess
            if (!ok) {
                fail(text(R.string.im_err_init_failed))
                return
            }
            sdkReady = true
            Log.i(TAG, "init appKey=$appKey navi=$navi media=$media")
        }
        installListeners()
        connectWith(token, allowTokenRefresh = true)
    }

    private fun connectWith(token: String, allowTokenRefresh: Boolean) {
        Log.i(TAG, "connect tokenLen=${token.length}")
        RongIMClient.connect(token, object : RongIMClient.ConnectCallback() {
            override fun onSuccess(userId: String?) {
                Log.i(TAG, "connected userId=$userId")
                status = Status.CONNECTED
                error = null
                inFlight = false
                retryAttempt = 0
                notifyChanged()
                refreshConversations()
            }

            override fun onError(e: RongIMClient.ErrorCode?) {
                Log.w(TAG, "connect error=$e tokenLen=${token.length}")
                // IPC_DISCONNECT 之类的瞬时错误（刚杀过进程、SDK 内部服务还在重启）重连即可，
                // 直接判失败会让用户以为功能坏了
                if (retryAttempt < MAX_AUTO_RETRY && isRecoverable(e)) {
                    retryAttempt++
                    status = Status.CONNECTING
                    error = text(R.string.im_err_reconnecting, retryAttempt)
                    notifyChanged()
                    main.postDelayed({
                        if (status != Status.CONNECTED) connectWith(token, allowTokenRefresh)
                    }, 800L * retryAttempt * retryAttempt)
                    return
                }
                fail(text(R.string.im_err_connect_failed, e?.name ?: text(R.string.im_unknown_error)))
            }

            override fun onTokenIncorrect() {
                Log.w(TAG, "onTokenIncorrect")
                if (!allowTokenRefresh) {
                    fail(text(R.string.im_err_token_rejected))
                    return
                }
                Session.imToken = ""
                // 换 token 也是同步 OkHttp，SDK 回调可能落在主线程 → 挪到 IO
                io.launch {
                    val fresh = try {
                        RceApi.refreshImToken()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "refreshImToken failed", e)
                        null
                    }
                    main.post {
                        if (!fresh.isNullOrBlank()) {
                            Session.imToken = fresh
                            connectWith(fresh, allowTokenRefresh = false)
                        } else {
                            fail(text(R.string.im_err_token_rejected))
                        }
                    }
                }
            }
        })
    }

    private fun isRecoverable(e: RongIMClient.ErrorCode?): Boolean {
        val name = e?.name ?: return false
        return name.contains("IPC") || name.contains("NETWORK") || name.contains("TIMEOUT")
    }

    private fun fail(message: String) {
        status = Status.FAILED
        error = message
        inFlight = false
        notifyChanged()
    }

    // ---------------------------------------------------------------- 监听

    private fun installListeners() {
        if (listenersInstalled) return
        listenersInstalled = true
        RongIMClient.setOnReceiveMessageListener { message, _ ->
            val type = message.conversationType
            if (type != Conversation.ConversationType.PRIVATE &&
                type != Conversation.ConversationType.GROUP
            ) {
                return@setOnReceiveMessageListener false
            }
            val isGroup = type == Conversation.ConversationType.GROUP
            val key = threadKey(message.targetId.orEmpty(), isGroup)
            // 非文本消息（图片/文件…）MVP 还没渲染，但**照样要提醒**，
            // 否则对方发张图，这边静悄悄地什么反应都没有
            val item = toImMessage(message)
            if (item != null) {
                if (item.isImage && item.imageUrl == null) ensureImageDownloaded(message)
                val list = threads.getOrPut(key) { mutableListOf() }
                if (list.none { it.key == item.key }) list += item
                notifyChanged()
            }
            // 收到新消息后会话列表的预览/时间/未读都变了
            refreshConversations()
            // 自己发出去的消息（多端同步）不提醒；正在看的那个会话也不提醒
            val mine = message.senderUserId == Session.userId
            if (!mine && key != visibleThreadKey) notifyMessage(message, isGroup)
            true
        }
    }

    /**
     * 弹一条系统通知（[ImNotify]）。
     *
     * 名字优先取工作圈那套缓存（`UserInfoCache`：`/staffs/batch` 一次批量拿），
     * 缓存没有时先用「新私信」占位并**顺手把这个人补进缓存**，下一条消息就能显示真名。
     * 群聊没有群名来源（2.9 的 `Message` 不带 conversationTitle），退到会话列表里的名字。
     */
    private fun notifyMessage(message: Message, isGroup: Boolean) {
        val ctx = app ?: return
        val peerId = message.targetId.orEmpty()
        val senderId = message.senderUserId.orEmpty()
        val text = (message.content as? TextMessage)?.content.orEmpty()
            .ifBlank { ctx.getString(R.string.im_notify_body_placeholder) }
        // 单聊看发送者、群聊也要发送者（正文里带「谁说了什么」）
        val senderName = UserInfoCache.get(senderId)?.name.orEmpty()
        if (senderName.isBlank() && senderId.isNotBlank()) {
            // 后台补一次资料：失败无所谓，只是这条通知还是占位名，下条就好了
            io.launch {
                try {
                    UserInfoCache.resolve(listOf(senderId))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        val title = if (isGroup) {
            conversations.firstOrNull { it.peerId == peerId && it.isGroup }?.title
                ?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.im_group_suffix)
        } else {
            senderName.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.im_notify_title)
        }
        val body = if (isGroup && senderName.isNotBlank()) {
            ctx.getString(R.string.im_notify_group_body, senderName, text)
        } else {
            text
        }
        ImNotify.show(ctx, peerId, isGroup, title, body)
    }

    private var listenersInstalled = false

    /**
     * 把 SDK 的消息转成界面用的 [ImMessage]。
     *
     * 目前认两类：文本、图片。其余类型（语音/文件/位置）返回 null —— 界面上不显示，
     * 但**照样会弹通知**（收消息回调里单独处理），不会「静悄悄地没反应」。
     */
    private fun toImMessage(message: Message): ImMessage? {
        val content = message.content
        val base = ImMessage(
            key = message.uId ?: "${message.sentTime}",
            text = "",
            mine = message.senderUserId == Session.userId,
            time = message.sentTime,
            state = ImMessage.STATE_SENT,
            senderId = message.senderUserId.orEmpty(),
        )
        return when (content) {
            is TextMessage -> base.copy(kind = ImMessage.KIND_TEXT, text = content.content.orEmpty())
            is ImageMessage -> {
                val thumb = thumbSourceOf(content)
                val full = fullSourceOf(content) ?: thumb
                base.copy(
                    kind = ImMessage.KIND_IMAGE,
                    imageUrl = thumb ?: full,
                    fullImageUrl = full,
                )
            }
            is FileMessage -> base.copy(
                kind = ImMessage.KIND_FILE,
                fileName = content.name.orEmpty(),
                fileSize = content.size,
                text = content.name.orEmpty(),
            )
            is LocationMessage -> base.copy(
                kind = ImMessage.KIND_LOCATION,
                poi = content.poi.orEmpty(),
                text = content.poi.orEmpty(),
                lat = content.lat,
                lng = content.lng,
            )
            is SightMessage -> base.copy(
                kind = ImMessage.KIND_VIDEO,
                duration = content.duration,
                // 气泡里显示封面（对方发来的封面是远端地址，coil 直接下）
                imageUrl = content.thumbUri?.toString(),
                mediaUrl = (content.mediaUrl ?: content.localPath)?.toString(),
            )
            else -> return null
        }
    }

    /**
     * 气泡里显示哪张：**缩略图优先**（小、下得快，气泡本来也不大）。
     * 顺序：已落盘的缩略图 → 远端缩略图 → 已落盘的原图 → 远端原图。
     */
    private fun thumbSourceOf(content: ImageMessage): String? {
        val thum = content.thumUri?.toString()
        val local = content.localUri?.toString()
        val remote = content.remoteUri?.toString()
        return pick(thum, local, remote)
    }

    /**
     * 点开放大时用哪张：**原图优先**。
     *
     * 顺序：已落盘的原图 → 远端原图 → 已落盘的缩略图 → 远端缩略图。
     * 全屏还用缩略图就会被放大成一团糊（2026-10-31 用户实测），所以这里必须优先原图。
     */
    private fun fullSourceOf(content: ImageMessage): String? {
        val local = content.localUri?.toString()
        val remote = content.remoteUri?.toString()
        val thum = content.thumUri?.toString()
        // 🔴 **远端原图必须排第一**（2026-10-31 第二次踩）：SDK 收图时会把「缩略图」下到本地，
        // 并把它填进 `localUri`，所以「本地优先」等价于「拿缩略图去放大」—— 仍然糊。
        // 参考项目（miuix）的映射也是 `remoteUri ?: localUri`，远端在前。
        val picked = pick(remote, local, thum)
        Log.i(TAG, "image src remote=$remote local=$local thumb=$thum -> $picked")
        return picked
    }

    /**
     * 按给定顺序挑第一个**能用**的地址，顺序即优先级。
     *
     * 🔴 **不要在内部按 scheme 重新排序**（2026-10-31 连踩两次的真凶）：早先这里是
     * 「先扫一遍所有 file://，再扫 http」，于是调用方写 `pick(remote, local, thum)` 也白搭 ——
     * 本地那个缩略图文件永远被优先选中，预览还是糊。现在严格从左到右。
     *
     * 「能用」= 已落盘的 `file://` / `http(s)` / `content://`。指向不存在文件的 `file://` 直接跳过，
     * 全都不行才返回 null（界面画占位块，而不是把死链丢给 coil 变一片空白）。
     */
    private fun pick(vararg candidates: String?): String? {
        for (raw in candidates) {
            val c = raw?.takeIf { it.isNotBlank() } ?: continue
            when {
                c.startsWith("file://") -> {
                    if (java.io.File(c.removePrefix("file://")).exists()) return c
                }
                c.startsWith("http") || c.startsWith("content://") -> return c
            }
        }
        return null
    }

    /**
     * 图还没落盘时让 SDK 把图片下下来，下完把线程里那条换成带地址的版本。
     *
     * 为什么需要它：融云只在**部分**场景自动下载媒体文件，历史消息（换机、清过缓存）与
     * 刚连上时的离线补发经常拿到一个「指向不存在文件」的 Uri —— 那样界面只能一直显示占位块。
     * 下载失败不重试、不提示（弱网下反复弹错误更烦），下次进这个会话 loadRecent 会再试一次。
     */
    private fun ensureImageDownloaded(message: Message) {
        if (message.content !is ImageMessage) return
        RongIMClient.getInstance().downloadMediaMessage(
            message,
            object : IRongCallback.IDownloadMediaMessageCallback {
                override fun onSuccess(m: Message?) {
                    val done = m ?: return
                    val item = toImMessage(done) ?: return
                    val key = threadKey(done.targetId.orEmpty(),
                        done.conversationType == Conversation.ConversationType.GROUP)
                    main.post {
                        val list = threads[key] ?: return@post
                        val index = list.indexOfFirst { it.key == item.key }
                        if (index >= 0) list[index] = item else list += item
                        notifyChanged()
                    }
                }

                override fun onProgress(m: Message?, progress: Int) = Unit

                override fun onError(m: Message?, e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "download image failed: ${e?.name}")
                }

                override fun onCanceled(m: Message?) = Unit
            }
        )
    }

    // ---------------------------------------------------------------- 会话

    fun refreshConversations() {
        if (status != Status.CONNECTED) return
        RongIMClient.getInstance().getConversationList(
            object : RongIMClient.ResultCallback<List<Conversation>>() {
                override fun onSuccess(list: List<Conversation>?) {
                    val mapped = list.orEmpty()
                        .filter {
                            it.conversationType == Conversation.ConversationType.PRIVATE ||
                                it.conversationType == Conversation.ConversationType.GROUP
                        }
                        .map { c ->
                            val isGroup = c.conversationType == Conversation.ConversationType.GROUP
                            ImConversation(
                                peerId = c.targetId.orEmpty(),
                                isGroup = isGroup,
                                title = c.conversationTitle.orEmpty(),
                                preview = previewOf(c),
                                time = maxOf(c.sentTime, c.receivedTime),
                                unread = c.unreadMessageCount,
                                previewKind = previewKindOf(c),
                            )
                        }
                        .filter { it.peerId.isNotBlank() }
                        .sortedByDescending { it.time }
                    Log.i(TAG, "conversations total=${list?.size ?: 0} kept=${mapped.size}")
                    main.post {
                        conversations = mapped
                        notifyChanged()
                    }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "conversations error=$e")
                }
            }
        )
    }

    private fun previewOf(c: Conversation): String {
        // 2.9 的 Conversation.getLatestMessage() 返回的是 MessageContent（不是 Message）
        return when (val content = c.latestMessage) {
            null -> ""
            is TextMessage -> content.content.orEmpty()
            else -> ""
        }
    }

    /**
     * 会话预览该按哪种类型渲染。
     *
     * 分三类而不是「是不是图片」：语音/文件/位置这些也没文本，混进「没有消息」会显示成
     * 「还没有私信」，看着像 bug —— 那是空会话的文案。
     */
    private fun previewKindOf(c: Conversation): ImConversation.PreviewKind = when (c.latestMessage) {
        null -> ImConversation.PreviewKind.TEXT
        is TextMessage -> ImConversation.PreviewKind.TEXT
        is ImageMessage -> ImConversation.PreviewKind.IMAGE
        else -> ImConversation.PreviewKind.OTHER
    }

    // ---------------------------------------------------------------- 消息

    /** 拉最近 [count] 条（首次进聊天页够用；更早的历史留到后续版本） */
    fun loadRecent(peerId: String, isGroup: Boolean, count: Int = 40) {
        if (status != Status.CONNECTED || peerId.isBlank()) return
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        RongIMClient.getInstance().getLatestMessages(
            type, peerId, count,
            object : RongIMClient.ResultCallback<List<Message>>() {
                override fun onSuccess(messages: List<Message>?) {
                    val list = messages.orEmpty()
                    val mapped = list.mapNotNull { toImMessage(it) }.sortedBy { it.time }
                    main.post {
                        threads[threadKey(peerId, isGroup)] = mapped.toMutableList()
                        notifyChanged()
                    }
                    // 历史里的图片可能没落盘（换机、清过缓存），补一次下载，下完界面自动出图
                    list.forEach { ensureImageDownloaded(it) }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "latest messages error=$e")
                }
            }
        )
    }

    fun sendText(peerId: String, isGroup: Boolean, body: String) {
        val text = body.trim()
        if (text.isEmpty() || peerId.isBlank()) return
        if (status != Status.CONNECTED) {
            error = text(R.string.im_err_not_connected)
            notifyChanged()
            return
        }
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        val message = Message.obtain(peerId, type, TextMessage.obtain(text))
        val key = message.uId ?: System.currentTimeMillis().toString()
        val list = threads.getOrPut(threadKey(peerId, isGroup)) { mutableListOf() }
        list += ImMessage(
            key = key,
            text = text,
            mine = true,
            time = System.currentTimeMillis(),
            state = ImMessage.STATE_SENDING,
            senderId = Session.userId,
        )
        notifyChanged()
        RongIMClient.getInstance().sendMessage(
            message, text, text,
            object : io.rong.imlib.IRongCallback.ISendMessageCallback {
                override fun onAttached(message: Message?) = Unit

                override fun onSuccess(message: Message?) {
                    markState(peerId, isGroup, key, ImMessage.STATE_SENT)
                }

                override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "send error=$e")
                    markState(peerId, isGroup, key, ImMessage.STATE_FAILED)
                    error = text(R.string.im_err_send_failed, e?.name ?: text(R.string.im_unknown_error))
                }
            }
        )
    }

    /** [joinGroup] 的失败原因码：**只回码，文案由界面用自己的字符串资源拼**（i18n） */
    const val RESULT_EMPTY_ID = "EMPTY_ID"
    const val RESULT_OFFLINE = "OFFLINE"
    const val RESULT_UNKNOWN = "UNKNOWN"

    // ---------------------------------------------------------------- 富消息（2026-10-31）

    /**
     * 发图片：本地 uri 直接交给 SDK，上传由 `sendMediaMessage` 负责。
     *
     * 缩略图参数也传同一个 uri —— SDK 会自己生成缩略图上传（参考项目 miuix 同样处理）。
     */
    fun sendImage(peerId: String, isGroup: Boolean, uri: android.net.Uri) {
        val content = ImageMessage.obtain(uri, uri)
        sendMedia(peerId, isGroup, content, push = text(R.string.im_push_image)) { key ->
            ImMessage(
                key = key,
                text = "",
                mine = true,
                time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING,
                senderId = Session.userId,
                kind = ImMessage.KIND_IMAGE,
                imageUrl = uri.toString(),
                fullImageUrl = uri.toString(),
            )
        }
    }

    /** 发文件（任意本地文件）：文件名与大小都要带上，对方列表才显示得出来 */
    fun sendFile(peerId: String, isGroup: Boolean, uri: android.net.Uri, name: String, size: Long) {
        val content = FileMessage.obtain(uri).apply {
            this.name = name
            this.size = size
        }
        sendMedia(peerId, isGroup, content, push = name) { key ->
            ImMessage(
                key = key,
                text = name,
                mine = true,
                time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING,
                senderId = Session.userId,
                kind = ImMessage.KIND_FILE,
                fileName = name,
                fileSize = size,
            )
        }
    }

    /**
     * 发位置：经纬度 + 地名（地图缩略图留空，官方客户端会用自家静态图服务补）。
     *
     * 🔴 **位置不能走 `sendMediaMessage`**（2026-10-31 踩过：点了没反应、只回错误码）。
     * 位置消息没有媒体上传环节，SDK 给它单独的入口 `sendLocationMessage`，
     * 回调类型也回到普通的 `ISendMessageCallback`。图片/文件才是 `sendMediaMessage`。
     */
    fun sendLocation(peerId: String, isGroup: Boolean, lat: Double, lng: Double, poi: String) {
        val content = LocationMessage.obtain(lat, lng, poi, null)
        sendPlain(peerId, isGroup, content, push = poi) { key ->
            ImMessage(
                key = key,
                text = poi,
                mine = true,
                time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING,
                senderId = Session.userId,
                kind = ImMessage.KIND_LOCATION,
                poi = poi,
                lat = lat,
                lng = lng,
            )
        }
    }

    /**
     * 发小视频（`SightMessage`）。
     *
     * 和图片一样走 `sendMediaMessage`（要上传）。封面必须自己抽一帧传进来 ——
     * SDK 不会替视频生成缩略图，不传的话对方列表里就是一块空白。
     */
    fun sendVideo(
        peerId: String,
        isGroup: Boolean,
        video: android.net.Uri,
        thumb: android.net.Uri?,
        durationSec: Int,
    ) {
        val content = SightMessage.obtain(video, thumb ?: video, durationSec)
        sendMedia(peerId, isGroup, content, push = text(R.string.im_push_video)) { key ->
            ImMessage(
                key = key,
                text = "",
                mine = true,
                time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING,
                senderId = Session.userId,
                kind = ImMessage.KIND_VIDEO,
                imageUrl = (thumb ?: video).toString(),
                duration = durationSec,
                mediaUrl = video.toString(),
            )
        }
    }

    /** 不走上传通道的消息（文本 / 位置）：`sendMessage` 与 `sendLocationMessage` 长一个样 */
    private fun sendPlain(
        peerId: String,
        isGroup: Boolean,
        content: MessageContent,
        push: String,
        echo: (String) -> ImMessage,
    ) {
        if (peerId.isBlank()) return
        if (status != Status.CONNECTED) {
            error = text(R.string.im_err_not_connected)
            notifyChanged()
            return
        }
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        val message = Message.obtain(peerId, type, content)
        val key = message.uId ?: System.currentTimeMillis().toString()
        threads.getOrPut(threadKey(peerId, isGroup)) { mutableListOf() } += echo(key)
        notifyChanged()
        val callback = object : IRongCallback.ISendMessageCallback {
            override fun onAttached(message: Message?) = Unit

            override fun onSuccess(message: Message?) {
                markState(peerId, isGroup, key, ImMessage.STATE_SENT)
                refreshConversations()
            }

            override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                Log.w(TAG, "send plain error=$e")
                markState(peerId, isGroup, key, ImMessage.STATE_FAILED)
                error = text(R.string.im_err_send_failed, e?.name ?: text(R.string.im_unknown_error))
            }
        }
        if (content is LocationMessage) {
            RongIMClient.getInstance().sendLocationMessage(message, push, null, callback)
        } else {
            RongIMClient.getInstance().sendMessage(message, push, null, callback)
        }
    }

    /**
     * 富消息统一出口：先乐观上屏，再由 SDK 上传发送，失败标红。
     *
     * 和文本的区别是走 `sendMediaMessage`（要做上传），以及回调是 `ISendMediaMessageCallback`
     * —— 它有 `onProgress`/`onAttached`/`onCanceled`，少写一个方法编译就不过（接口是抽象类集合）。
     */
    private fun sendMedia(
        peerId: String,
        isGroup: Boolean,
        content: MessageContent,
        push: String,
        echo: (String) -> ImMessage,
    ) {
        if (peerId.isBlank()) return
        if (status != Status.CONNECTED) {
            error = text(R.string.im_err_not_connected)
            notifyChanged()
            return
        }
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        val message = Message.obtain(peerId, type, content)
        val key = message.uId ?: System.currentTimeMillis().toString()
        threads.getOrPut(threadKey(peerId, isGroup)) { mutableListOf() } += echo(key)
        notifyChanged()
        RongIMClient.getInstance().sendMediaMessage(
            message, push, null,
            object : IRongCallback.ISendMediaMessageCallback {
                override fun onProgress(message: Message?, progress: Int) = Unit

                override fun onCanceled(message: Message?) {
                    markState(peerId, isGroup, key, ImMessage.STATE_FAILED)
                }

                override fun onAttached(message: Message?) = Unit

                override fun onSuccess(message: Message?) {
                    markState(peerId, isGroup, key, ImMessage.STATE_SENT)
                    refreshConversations()
                }

                override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "send media error=$e")
                    markState(peerId, isGroup, key, ImMessage.STATE_FAILED)
                    error = text(R.string.im_err_send_failed, e?.name ?: text(R.string.im_unknown_error))
                }
            }
        )
    }

    // ---------------------------------------------------------------- 群聊

    /**
     * 加入一个已有群聊。
     *
     * ⚠️ IMLib 2.9 的客户端 API **没有** `createGroup`（建群是融云服务端 / Server API 的活，
     * 要 app secret），所以这里只做「加入已知群 id」。
     *
     * 群名的坑：`joinGroup(id, name)` 的第二个参数会被 SDK 当成群名写进本地库，
     * 拿真名之前**不要传占位名**。顺序：先问学校 `GET /groups/{id}` → 用户填的名字 → 空。
     *
     * @param onResult 主线程回调（成功带群名，失败带错误码）
     */
    fun joinGroup(
        groupId: String,
        typedName: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        val id = groupId.trim()
        if (id.isBlank()) {
            onResult(false, RESULT_EMPTY_ID)
            return
        }
        if (status != Status.CONNECTED) {
            onResult(false, RESULT_OFFLINE)
            return
        }
        io.launch {
            val serverName = try {
                RceApi.groupInfo(id)?.name?.trim().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "groupInfo failed: ${e.javaClass.simpleName}")
                ""
            }
            val name = serverName.ifBlank { typedName.trim() }
            main.post { doJoinGroup(id, name, onResult) }
        }
    }

    private fun doJoinGroup(id: String, name: String, onResult: (Boolean, String) -> Unit) {
        RongIMClient.getInstance().joinGroup(
            id,
            name,
            object : RongIMClient.OperationCallback() {
                override fun onSuccess() {
                    Log.i(TAG, "joinGroup ok id=$id name=$name")
                    // 把群名写进本地库：之后 conversationTitle 才不是空的
                    runCatching {
                        RongIMClient.getInstance().syncGroup(
                            listOf(Group(id, name, null)),
                            object : RongIMClient.OperationCallback() {
                                override fun onSuccess() = refreshConversations()
                                override fun onError(e: RongIMClient.ErrorCode?) =
                                    refreshConversations()
                            }
                        )
                    }
                    refreshConversations()
                    onResult(true, name)
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "joinGroup error=$e id=$id")
                    onResult(false, e?.name ?: RESULT_UNKNOWN)
                }
            }
        )
    }

    private fun markState(peerId: String, isGroup: Boolean, key: String, state: Int) {
        main.post {
            val list = threads[threadKey(peerId, isGroup)] ?: return@post
            val index = list.indexOfFirst { it.key == key }
            if (index >= 0) list[index] = list[index].copy(state = state)
            notifyChanged()
        }
    }

    /** 进聊天页把未读清零（否则会话列表的徽标一直挂着） */
    fun markRead(peerId: String, isGroup: Boolean) {
        if (status != Status.CONNECTED || peerId.isBlank()) return
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        runCatching { RongIMClient.getInstance().clearMessagesUnreadStatus(type, peerId) }
    }
}
