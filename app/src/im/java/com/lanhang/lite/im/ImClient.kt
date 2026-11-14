package com.lanhang.lite.im

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lanhang.lite.R
import com.lanhang.lite.core.isCancellation
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
import io.rong.message.ReadReceiptMessage
import io.rong.message.SightMessage
import io.rong.message.TextMessage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /** 群头像（`/groups/mine` 的 portrait_url）；单聊为 null，头像按 peerId 查人员资料 */
    val portrait: String? = null,
    /**
     * 最后一条是**我发的**（与是否已读无关，列表用它筛出「该显示已读状态」的会话）。
     */
    val lastMine: Boolean = false,
    /**
     * 最后一条是**我发的、且对方已读**（`RC:ReadNtf` 回执把 `SentStatus` 改成了 READ）。
     * 只在单聊里有意义 —— 群聊没有逐条已读展示（见 [sendReadReceipt]），一律 false。
     */
    val read: Boolean = false,
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
    /**
     * 文件的 MIME（`FileMessage.getType()`），空值时按通配 MIME 尝试打开。
     *
     * 打开文件要用它：`ACTION_VIEW` 不带 type 时系统会挑错应用，
     * 而「保存到本地」不关心这个。
     */
    val fileMime: String? = null,
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
    /**
     * 对方是否已读这条消息（由 `SentStatus == READ` 派生，**只有 [mine] 的消息才看它**）。
     * 群聊不回执已读，这里的值恒为 false，界面也不展示。
     */
    val read: Boolean = false,
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

    /**
     * 补「发送者姓名」的等待上限：拿不到就保持占位通知，不把更新吊死在网络上。
     * `/staffs/batch` 正常几百毫秒，3s 足够覆盖弱网，再久用户早看到通知了。
     */
    private const val PROFILE_TIMEOUT_MS = 3_000L

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

    /**
     * 用户正停在「新朋友」页时置真：那一页的列表会当场重拉，再弹通知只是重复打扰。
     * 由 `ImNewFriendsActivity.onResume/onPause` 维护（SDK 回调线程会读，故 `@Volatile`）。
     */
    @Volatile
    var newFriendsVisible: Boolean = false

    /** 会话列表（已按时间倒序） */
    var conversations: List<ImConversation> = emptyList()

    /**
     * 「我加入的群」（来自 `GET /groups/mine`）；本地没有消息的群也靠它才能出现在列表里。
     * 在 IO 线程写入、在 SDK 回调线程读取，所以要 `@Volatile`。
     */
    @Volatile
    private var joinedGroups: List<ImConversation> = emptyList()
        private set

    /** 每个会话最近的消息；key 见 [threadKey] */
    private val threads = mutableMapOf<String, MutableList<ImMessage>>()

    /**
     * 每个单聊「对方已读到哪」的水位线（peerId → 回执里的 `lastMessageSendTime`）。
     *
     * 为什么不能只看 `Conversation.getSentStatus()`：官方蓝航收到回执时也是**手动**把
     * 内存里那条 `UIConversation.sentStatus` 改成 READ（`ConversationListFragment:396`），
     * 而不是重新查一遍会话表。实测（2026-11-12）也印证了这点：收到回执后重启 App，
     * 会话行的 `sentStatus` 仍是 SENT —— native 的 `UpdateMessageReceiptStatus`
     * 只保证改**消息表**（气泡那侧因此是对的）。
     * 水位线落一份到 SP，重启后列表标签才不会凭空消失。
     */
    private val readUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [readUntil] 的持久化（`lanhang_im_read`，只有「已读到哪」一个 long/人，量级可忽略） */
    private var readStore: android.content.SharedPreferences? = null

    private fun loadReadUntil(ctx: Context) {
        if (readStore != null) return
        val sp = ctx.getSharedPreferences("lanhang_im_read", Context.MODE_PRIVATE)
        readStore = sp
        sp.all.forEach { (peer, value) -> if (value is Long && value > 0L) readUntil[peer] = value }
    }

    private fun rememberReadUntil(peerId: String, until: Long) {
        if (until <= (readUntil[peerId] ?: 0L)) return
        readUntil[peerId] = until
        (readStore ?: return)?.edit()?.putLong(peerId, until)?.apply()
    }

    /** 已经去消息表核过「到底读没读」的会话（每个进程只核一次，免得每次刷新都查库） */
    private val readVerified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 连上后把「最后一条是我发的、却还标着未读」的单聊拿去**消息表**核一遍。
     *
     * 为什么必须核：会话行的 `sent_status` 不会被回执更新（见 [readUntil] 注释），
     * 而水位线只在本进程收到回执后才有 —— 上个进程里收到的回执，光看这两个都发现不了。
     * 消息表里 `SentStatus == READ` 才是权威。每个会话查最近 20 条、纯本地、每进程一次。
     */
    private fun verifyReadStates(list: List<ImConversation>) {
        if (status != Status.CONNECTED) return
        val candidates = list.filter {
            !it.isGroup && it.lastMine && !it.read && readVerified.add(it.peerId)
        }
        if (candidates.isEmpty()) return
        Log.i(TAG, "verify read candidates=${candidates.joinToString { it.peerId.take(6) }}")
        candidates.forEach { conversation ->
            RongIMClient.getInstance().getLatestMessages(
                Conversation.ConversationType.PRIVATE, conversation.peerId, 20,
                object : RongIMClient.ResultCallback<List<Message>>() {
                    override fun onSuccess(messages: List<Message>?) {
                        val mine = messages.orEmpty().filter {
                            it.senderUserId == Session.userId
                        }
                        // 水位线 = 我发出的、被标成 READ 的那条里最晚的时间
                        val readAt = mine.filter {
                            it.sentStatus == Message.SentStatus.READ
                        }.maxOfOrNull { it.sentTime } ?: 0L
                        Log.i(
                            TAG,
                            "verify read peer=${conversation.peerId.take(6)} " +
                                "mine=${mine.size} readAt=$readAt " +
                                "latest=${mine.maxOfOrNull { it.sentTime } ?: 0L}"
                        )
                        if (readAt <= 0) return
                        val before = readUntil[conversation.peerId] ?: 0L
                        rememberReadUntil(conversation.peerId, readAt)
                        if (readAt > before) refreshConversations()
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) = Unit
                }
            )
        }
    }

    /**
     * 拉气泡时顺手把水位线对齐到「消息表里已读的那条」。
     *
     * 正常路径回执会自己写水位线，这里兜的是别处改过消息表的情况
     * （换机同步、`syncConversationReadStatus`），保证列表标签和气泡永远一致。
     */
    private fun seedReadUntil(peerId: String, isGroup: Boolean, messages: List<ImMessage>): Boolean {
        if (isGroup) return false // 群没有已读水位线（也不展示）
        val readAt = messages.filter { it.mine && it.read }.maxOfOrNull { it.time } ?: 0L
        if (readAt <= 0) return false
        val before = readUntil[peerId] ?: 0L
        rememberReadUntil(peerId, readAt)
        return readAt > before
    }

    private val observers = mutableListOf<() -> Unit>()

    private val friendObservers = mutableListOf<() -> Unit>()

    fun threadKey(peerId: String, isGroup: Boolean): String =
        (if (isGroup) "g:" else "p:") + peerId

    fun thread(peerId: String, isGroup: Boolean): List<ImMessage> =
        threads[threadKey(peerId, isGroup)].orEmpty()

    // ---------------------------------------------------------------- 观察者

    fun addObserver(block: () -> Unit) = watch(observers, block)

    fun removeObserver(block: () -> Unit) = unwatch(observers, block)

    /** 好友事件观察者（申请列表变了）——与会话变化分开，免得每条私信都触发一次网络刷新 */
    fun addFriendObserver(block: () -> Unit) = watch(friendObservers, block)

    fun removeFriendObserver(block: () -> Unit) = unwatch(friendObservers, block)

    private fun watch(list: MutableList<() -> Unit>, block: () -> Unit) {
        if (!list.contains(block)) list += block
    }

    private fun unwatch(list: MutableList<() -> Unit>, block: () -> Unit) {
        list -= block
    }

    private fun notifyChanged() = dispatch(observers)

    /** 好友申请列表可能变了（收到事件、置了已读…），私信页副标题靠它刷新 */
    fun notifyFriendsChanged() = dispatch(friendObservers)

    private fun dispatch(list: MutableList<() -> Unit>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            list.toList().forEach { it() }
        } else {
            main.post { list.toList().forEach { it() } }
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
        val appCtx = context.applicationContext
        app = appCtx
        loadReadUntil(appCtx)
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
            // 好友事件消息必须**在 init 之前**注册（先入 mRegCache，init 时 flush 给 native）。
            // 不注册 → SDK 只解得出 UnknownMessage → 收消息回调分不清「好友事件」和「私信」，
            // 好友事件就被当成私信弹一条点进去空白的「新私信」（2026-11-12 用户反馈的根因）。
            // 单独 catch：注册失败只是退化成「读不出事件内容」，不能连 SDK 都不起。
            runCatching { RongIMClient.registerMessageType(ContactNtfyMessage::class.java) }
                .onFailure { Log.e(TAG, "registerMessageType failed", it) }
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
                // 会话列表是本地库，本地没有的群要另外问服务端（见 loadGroups）
                loadGroups()
                // 进聊天页时还没连上 → 那次回执没发出去，连上后补一次
                val visible = visibleThreadKey
                if (visible != null && visible.startsWith("p:")) {
                    sendReadReceipt(visible.removePrefix("p:"))
                }
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
            // ① 好友事件：消费掉 —— 不进气泡、不刷会话、更不弹「新私信」
            if (isFriendNotify(message)) {
                handleFriendNotify(message)
                return@setOnReceiveMessageListener true
            }
            val type = message.conversationType
            if (type != Conversation.ConversationType.PRIVATE &&
                type != Conversation.ConversationType.GROUP
            ) {
                return@setOnReceiveMessageListener false
            }
            val isGroup = type == Conversation.ConversationType.GROUP
            val key = threadKey(message.targetId.orEmpty(), isGroup)
            val item = toImMessage(message)
            if (item == null) {
                // ② **认不出来的一律静默**：语音、官方在「同意好友」时插的系统气泡、
                // 没注册成功的自定义消息……这些都进不了气泡（toImMessage 返回 null），
                // 以前却照样 refresh + notify，于是收一条就多一条点进去空白的「新私信」。
                // 通知的正文还会退成占位的「[消息]」——用户 2026-11-12 报的就是它。
                // 既然界面上根本展示不出来，弹通知就只是把人引到空处，所以这里直接不提醒。
                Log.i(TAG, "skip unrendered message objectName=${message.objectName}")
                return@setOnReceiveMessageListener false
            }
            if (item.isImage && item.imageUrl == null) ensureImageDownloaded(message)
            val list = threads.getOrPut(key) { mutableListOf() }
            if (list.none { it.key == item.key }) list += item
            notifyChanged()
            val mine = message.senderUserId == Session.userId
            // 收到新消息后会话列表的预览/时间/未读都变了
            refreshConversations()
            // 正在看着这个单聊 → 当场回执「已读」，对方那边的气泡立刻变已读
            // （对齐官方 ConversationFragment 收到新消息时 sendReadReceiptMessage）
            if (!mine && !isGroup && key == visibleThreadKey) {
                sendReadReceipt(message.targetId.orEmpty(), message.sentTime)
            }
            // 自己发出去的消息（多端同步）不提醒；正在看的那个会话也不提醒
            if (!mine && key != visibleThreadKey) notifyMessage(message, isGroup)
            true
        }

        // 已读回执（`RC:ReadNtf`）：它被 SDK 的 handleCmdMessages 吃掉并返回 true，
        // **不会**进上面的收消息回调，所以「对方读了」的刷新只能挂在这里。
        // 官方蓝航同理（imkit `RongIM.setReadReceiptListener` → Event.ReadReceiptEvent）。
        RongIMClient.setReadReceiptListener(object : RongIMClient.ReadReceiptListener {
            override fun onReadReceiptReceived(message: Message) {
                if (message.conversationType != Conversation.ConversationType.PRIVATE) return
                applyReadReceipt(message)
            }

            // 群聊的逐人回执（请求 / 应答）：没有展示需求，收下即可
            override fun onMessageReceiptRequest(
                type: Conversation.ConversationType,
                targetId: String?,
                messageUId: String?,
            ) = Unit

            override fun onMessageReceiptResponse(
                type: Conversation.ConversationType,
                targetId: String?,
                messageUId: String?,
                respondMap: HashMap<String, Long>?,
            ) = Unit
        })
    }

    /**
     * 对方读到哪了：把我发出的、时间不晚于回执的那条标成已读。
     *
     * SDK 收到 `RC:ReadNtf` 时已经把**本地库**改成 `SentStatus.READ`
     * （`handleReadReceiptMessage` → `updateMessageReceiptStatus`），所以这里只要把
     * 正在内存里展示的那份同步过来，再让会话列表重取一次（列表的「已读」标签读的是
     * `Conversation.getSentStatus()`，得跟着刷新才变）。
     */
    private fun applyReadReceipt(message: Message) {
        val peerId = message.targetId.orEmpty()
        val until = (message.content as? ReadReceiptMessage)?.lastMessageSendTime
            ?: message.sentTime
        Log.i(TAG, "read receipt peer=$peerId until=$until")
        if (peerId.isNotBlank()) rememberReadUntil(peerId, until)
        val key = threadKey(peerId, false)
        main.post {
            val list = threads[key] ?: return@post
            var changed = false
            for (i in list.indices) {
                val item = list[i]
                if (item.mine && !item.read && item.time <= until) {
                    list[i] = item.copy(read = true)
                    changed = true
                }
            }
            if (changed) notifyChanged()
        }
        refreshConversations()
    }

    /**
     * 回执「我看到你这些消息了」（发 `RC:ReadNtf`，对方那边已发出的气泡会变已读）。
     *
     * 只发**单聊**：官方对群聊走的是另一套逐人回执（`ReadReceiptRequest/Response`），
     * 我们没有群已读展示，就不发。
     *
     * @param at 已读到哪条消息（该时刻**及之前**我发出的消息会被对方标成已读）；
     *   传 0 表示「整个会话都读过了」，取本地会话的最新消息时间。
     */
    fun sendReadReceipt(peerId: String, at: Long = 0L) {
        if (status != Status.CONNECTED || peerId.isBlank()) return
        val time = if (at > 0) at else latestMessageTime(peerId)
        if (time <= 0) return
        runCatching {
            RongIMClient.getInstance()
                .sendReadReceiptMessage(Conversation.ConversationType.PRIVATE, peerId, time)
        }.onFailure { Log.w(TAG, "sendReadReceipt failed: ${it.javaClass.simpleName}") }
    }

    /** 这个单聊里最新的消息时间：先看内存里的气泡，再退回会话列表（刚进页时气泡还没加载） */
    private fun latestMessageTime(peerId: String): Long {
        val fromThread = threads[threadKey(peerId, false)]?.maxOfOrNull { it.time } ?: 0L
        if (fromThread > 0) return fromThread
        return conversations.firstOrNull { it.peerId == peerId && !it.isGroup }?.time ?: 0L
    }

    /** 是不是好友事件（`RCE:ContactNtfy`）：既看类型也看 objectName，注册失败时靠后者兜底 */
    private fun isFriendNotify(message: Message): Boolean =
        message.content is ContactNtfyMessage ||
            message.objectName == ContactNtfyMessage.OBJECT_NAME

    /**
     * 处理一条好友事件。
     *
     * 语义对齐官方 `FriendTask.onInit` 的 ReceiveMessageRouter（`decompiled/.../FriendTask.java:86`）：
     * 消息只是触发器，**HTTP `GET /friends/request_list` 才是权威**，所以这里只做两件事 ——
     * ① 决定要不要弹「好友申请」通知；② 通知页面层去重拉列表。
     *
     * 另外两条路径都收敛到 [notifyFriendsChanged]：私信列表的副标题数字、新朋友页的列表
     * 都靠它刷新，不会出现「通知弹了、数字没动」。
     */
    private fun handleFriendNotify(message: Message) {
        val content = message.content as? ContactNtfyMessage
        if (content == null) {
            // 注册没生效（拿到 UnknownMessage）：事件内容读不出来，退回 HTTP 拿权威列表。
            // 只在「确有未读待处理申请」时才弹通用提示 —— 同意/拒绝也会发这条消息，
            // 那时列表里没有待处理项，就不会误报「有人申请加你」。
            Log.w(TAG, "friend notify not decoded objectName=${message.objectName}")
            refreshFriendRequestsByHttp()
            return
        }
        Log.i(TAG, "friend notify action=${content.actionType}")
        when (content.actionType) {
            ContactNtfyMessage.ACTION_ADD -> {
                // target 是被加的人。这条消息也可能发给对方那份，只有「加我」才提醒；
                // 字段缺失时按「与我有关」处理（消息既然送到我这台机器上，多半就是给我的）
                val target = content.targetUserId
                if (target.isNullOrBlank() || target == Session.userId) {
                    notifyFriendRequest(content.operatorName)
                }
            }
            ContactNtfyMessage.ACTION_ACCEPT -> {
                // operator 是按下「同意」的那个人：我同意对方时不给自己弹（官方是往会话插
                // 一条「你已同意 X 的好友申请」气泡，我们不做气泡），对方同意我才提醒
                if (!isMe(content.operatorUserId)) notifyFriendAccepted(content.operatorName)
            }
            // REJECT(3) / DELETE(4)：静默。拒绝和删好友都不该弹横幅，列表刷新就够了
            else -> Unit
        }
        notifyFriendsChanged()
    }

    private fun isMe(userId: String?): Boolean = !userId.isNullOrBlank() && userId == Session.userId

    /** 注册失败时的兜底：事件读不出来，只能问服务端要权威列表 */
    private fun refreshFriendRequestsByHttp() {
        val ctx = app ?: return
        io.launch {
            val pending = try {
                RceApi.friendRequests().count { it.isUnreadPending }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Log.w(TAG, "friendRequests failed", e)
                return@launch
            }
            main.post {
                if (pending > 0 && !newFriendsVisible) {
                    ImNotify.showFriendRequest(
                        ctx,
                        ctx.getString(R.string.im_notify_friend_title),
                        ctx.getString(R.string.im_notify_friend_generic),
                    )
                }
                notifyFriendsChanged()
            }
        }
    }

    /** 弹「好友申请」通知；正在新朋友页就免了（列表当场会刷新，没必要再响一次） */
    private fun notifyFriendRequest(operatorName: String?) {
        if (newFriendsVisible) return
        val ctx = app ?: return
        val who = operatorName?.takeIf { it.isNotBlank() }
        ImNotify.showFriendRequest(
            ctx,
            ctx.getString(R.string.im_notify_friend_title),
            if (who != null) {
                ctx.getString(R.string.im_notify_friend_requested, who)
            } else {
                ctx.getString(R.string.im_notify_friend_generic)
            },
        )
    }

    /** 对方同意了我的申请 */
    private fun notifyFriendAccepted(operatorName: String?) {
        if (newFriendsVisible) return
        val ctx = app ?: return
        val who = operatorName?.takeIf { it.isNotBlank() }
        ImNotify.showFriendRequest(
            ctx,
            ctx.getString(R.string.im_notify_friend_title),
            if (who != null) {
                ctx.getString(R.string.im_notify_friend_accepted, who)
            } else {
                ctx.getString(R.string.im_notify_friend_accepted_generic)
            },
        )
    }

    /**
     * 弹一条系统通知（[ImNotify]）。
     *
     * 名字优先取工作圈那套缓存（`UserInfoCache`：`/staffs/batch` 一次批量拿）。
     *
     * 🔴 **缓存没有名字时先弹占位、再补真名**（2026-11-14 用户反馈「只会显示来了一条私信，
     * 不显示是谁发的」）：`UserInfoCache` 是纯内存缓存，进程刚起来（收后台消息时多半如此）
     * 或用户没逛过工作圈/成员录时必然落空；而旧代码把 `resolve` 挂在 `ImNotify.show`
     * **之后**异步跑 —— 这条通知注定只能是「新私信」，下一条才轮得到真名。
     * 现在两步走：
     *  1. 立刻弹（缓存命中就是真名，没命中是占位）—— 不为等网络把提醒拖慢；
     *  2. 缺名字时后台 `resolve`，拿到真名后**原地更新同一条通知**（`alert=false` →
     *     `onlyAlertOnce`，不二次响铃、不二次弹横幅），且先 [ImNotify.isActive] 问一句，
     *     用户已划掉/点开过的不再顶回来。
     *
     * 群聊没有群名来源（2.9 的 `Message` 不带 conversationTitle），退到会话列表里的名字。
     */
    private fun notifyMessage(message: Message, isGroup: Boolean) {
        val ctx = app ?: return
        val peerId = message.targetId.orEmpty()
        val senderId = message.senderUserId.orEmpty()
        val text = (message.content as? TextMessage)?.content.orEmpty()
            .ifBlank { ctx.getString(R.string.im_notify_body_placeholder) }
        // 单聊看发送者、群聊也要发送者（正文里带「谁说了什么」）
        val known = UserInfoCache.get(senderId)?.name.orEmpty()
        ImNotify.show(
            ctx, peerId, isGroup,
            notifyTitle(ctx, peerId, isGroup, known),
            notifyBody(ctx, isGroup, known, text),
        )
        // 已有真名、或压根不知道该找谁（sender 缺失的群消息）→ 没有第二步
        // 单聊的 targetId 就是对方 userId：sender 偶发为空时用它兜底，别让通知停在占位名
        val resolveId = senderId.ifBlank { if (isGroup) "" else peerId }
        if (known.isNotBlank() || resolveId.isBlank()) return
        io.launch {
            withTimeoutOrNull(PROFILE_TIMEOUT_MS) { UserInfoCache.resolve(listOf(resolveId)) }
            val name = UserInfoCache.get(resolveId)?.name.orEmpty()
            if (name.isBlank()) return@launch
            if (!ImNotify.isActive(ctx, peerId)) return@launch
            ImNotify.show(
                ctx, peerId, isGroup,
                notifyTitle(ctx, peerId, isGroup, name),
                notifyBody(ctx, isGroup, name, text),
                alert = false,
            )
        }
    }

    /** 通知标题：群聊用群名（拿不到退「群聊」），单聊用发送者姓名（拿不到退「新私信」） */
    private fun notifyTitle(ctx: Context, peerId: String, isGroup: Boolean, senderName: String): String =
        if (isGroup) {
            conversations.firstOrNull { it.peerId == peerId && it.isGroup }?.title
                ?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.im_group_suffix)
        } else {
            senderName.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.im_notify_title)
        }

    /** 通知正文：群聊带「谁说了什么」，单聊直接是消息内容 */
    private fun notifyBody(ctx: Context, isGroup: Boolean, senderName: String, text: String): String =
        if (isGroup && senderName.isNotBlank()) {
            ctx.getString(R.string.im_notify_group_body, senderName, text)
        } else {
            text
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
            // 对方读过（SDK 已把本地库改成 READ）→ 气泡下面那行显示「已读」
            read = message.sentStatus == Message.SentStatus.READ,
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
                fileMime = content.type,
                // 本地路径优先：自己发的文件本来就在本地；收到的文件下载完 SDK 会把路径写回
                // localPath。都没有时留远端地址，点开时再去下载（见 downloadFile）。
                mediaUrl = (content.localPath ?: content.mediaUrl)?.toString(),
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

    /**
     * 后台把私聊对端的姓名/头像补进 [UserInfoCache]（与私信列表页 `resolveProfiles` 同一套）。
     *
     * 命中缓存时 [UserInfoCache.resolve] 连网络都不发，所以会话列表每次刷新都调一遍是安全的 ——
     * 收益是**进程刚起来的第一条私信通知就能带上真名**，不必等「先占位再改」那一步。
     */
    private fun resolvePeerNames(list: List<ImConversation>) {
        val ids = list.filter { !it.isGroup && it.peerId.isNotBlank() }.map { it.peerId }
        if (ids.isEmpty()) return
        io.launch { UserInfoCache.resolve(ids) }
    }

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
                            // 「已读」标签 = 最后一条是我发的 + 对方读到了这条
                            // （消息表的 READ，或本进程记下的已读水位线已经盖过会话时间）
                            val lastMine = !isGroup && c.senderUserId == Session.userId
                            val latest = maxOf(c.sentTime, c.receivedTime)
                            val peerRead = c.sentStatus == Message.SentStatus.READ ||
                                (readUntil[c.targetId] ?: 0L) >= latest
                            // 群会话刚生成时 conversationTitle 常为空（本地库还没同步群资料），
                            // 用 /groups/mine 拿到真名顶上，否则列表里只剩「群聊 · 1a2b」
                            val known = if (isGroup) {
                                joinedGroups.firstOrNull { it.peerId == c.targetId }
                            } else {
                                null
                            }
                            ImConversation(
                                peerId = c.targetId.orEmpty(),
                                isGroup = isGroup,
                                title = c.conversationTitle.orEmpty().ifBlank { known?.title.orEmpty() },
                                preview = previewOf(c),
                                time = maxOf(c.sentTime, c.receivedTime),
                                unread = c.unreadMessageCount,
                                previewKind = previewKindOf(c),
                                portrait = known?.portrait,
                                lastMine = lastMine,
                                read = lastMine && peerRead,
                            )
                        }
                        .filter { it.peerId.isNotBlank() }
                    val merged = mergeGroups(mapped)
                    // 顺手核一遍「最后一条是我发的」的已读状态（每会话每进程只查一次）
                    verifyReadStates(mapped)
                    // 把会话里的人名补进 UserInfoCache（纯内存缓存，进程重启即空）：
                    // 不补的话下一条私信通知只能先弹「新私信」再原地改成真名，见 notifyMessage
                    resolvePeerNames(mapped)
                    Log.i(
                        TAG,
                        "conversations total=${list?.size ?: 0} kept=${mapped.size} " +
                            "groups=${joinedGroups.size} merged=${merged.size} " +
                            // read= 已读标签数；单聊逐条打出 read 标志，用来验证
                            // native 有没有把会话行的 sentStatus 改成 READ（见 readUntil 注释）
                            "read=${mapped.count { it.read }} " +
                            mapped.filter { !it.isGroup }
                                .joinToString(" ") { "${it.peerId.take(6)}:${if (it.read) "Y" else "n" }" }
                    )
                    main.post {
                        conversations = merged
                        notifyChanged()
                    }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "conversations error=$e")
                }
            }
        )
    }

    /**
     * 拉「我加入的群」，合进会话列表。
     *
     * 为什么必须有这一步：融云的会话列表是**本地 sqlite** 里的记录，只有往来过消息的会话才存在。
     * 全新装的客户端本地库是空的 —— 用户在官方 App 里早就加入的群，只要最近没有新消息
     * （或离线消息补偿时限已过），`getConversationList` 里根本没有它，界面上就是「我的群不见了」。
     *
     * SDK 2.9 又**没有** `getGroups()`（只有 join / quit / sync 三个），群清单只能问学校：
     * `GET /groups/mine`（官方 `GroupTask.GROUP_LIST`）。
     */
    private fun loadGroups() {
        io.launch {
            val raw = try {
                RceApi.myGroupsRaw()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "groups failed: ${e.javaClass.simpleName} ${e.message}")
                return@launch
            }
            if (raw == null) {
                Log.w(TAG, "groups blank body")
                return@launch
            }
            var groups = parseGroups(raw)
            Log.i(
                TAG,
                "groups total=${groups.size} portraits=${groups.count { !it.portrait.isNullOrBlank() }} -> " +
                    groups.joinToString { "${it.peerId}:${it.title}:${it.portrait?.take(60)}" }.take(400)
            )
            if (groups.isEmpty()) {
                // type=0 解析不出来时：先把原文留一行（限长，不刷屏），再用空 type 试一次 ——
                // 官方常量是 `/groups/mine?...&type=%s`，type 的取值没有文档，只能实测
                Log.w(TAG, "groups raw(type=0)=${raw.take(400)}")
                val retry = runCatching { RceApi.myGroupsRaw(type = "") }.getOrNull()
                if (retry != null && retry != raw) {
                    groups = parseGroups(retry)
                    Log.i(TAG, "groups retry(type=) total=${groups.size} raw=${retry.take(300)}")
                }
            }
            joinedGroups = groups
            main.post { notifyChanged() }
            refreshConversations()
        }
    }

    /**
     * 解析 `/groups/mine`。
     *
     * 结构没得抄（官方反编译里这个常量没有任何引用），所以按「常见形状」兜底：
     * `result` 直接是数组，或 `result.data / groups / list / items` 里的数组。
     * 单群字段也兼容 `id|group_id`、`name|group_name`、`portrait_url|portrait`。
     */
    private fun parseGroups(raw: String): List<ImConversation> {
        val root = runCatching {
            com.google.gson.JsonParser.parseString(raw).asJsonObject
        }.getOrNull() ?: return emptyList()
        val result = root.get("result") ?: return emptyList()
        val array = when {
            result.isJsonArray -> result.asJsonArray
            result.isJsonObject -> {
                val obj = result.asJsonObject
                listOf("data", "groups", "list", "items", "records")
                    .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
            }
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.text("id") ?: obj.text("group_id") ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            ImConversation(
                peerId = id,
                isGroup = true,
                title = obj.text("name") ?: obj.text("group_name").orEmpty(),
                preview = "",
                // time = 0 表示本地还没有消息，排序时会落到有消息的会话后面
                time = 0L,
                unread = 0,
                portrait = obj.text("portrait_url") ?: obj.text("portrait"),
            )
        }
    }

    private fun com.google.gson.JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /**
     * 把「本地没消息的群」补进会话列表。
     *
     * 有消息的会话以本地数据为准（预览、时间、未读都是真的），只把本地缺的群追加进去。
     * 排序：按时间倒序，`time = 0` 的群自然落在最后，同一档内按群名。
     */
    private fun mergeGroups(base: List<ImConversation>): List<ImConversation> {
        if (joinedGroups.isEmpty()) return base.sortedByDescending { it.time }
        val known = base.mapTo(mutableSetOf()) { it.peerId to it.isGroup }
        val extra = joinedGroups.filter { (it.peerId to it.isGroup) !in known }
        return (base + extra).sortedWith(
            compareByDescending<ImConversation> { it.time }.thenBy { it.title }
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
                    val seeded = seedReadUntil(peerId, isGroup, mapped)
                    // 水位线刚被抬高 → 会话列表的「已读」标签要跟着重取一次
                    if (seeded) refreshConversations()
                    main.post {
                        threads[threadKey(peerId, isGroup)] = mapped.toMutableList()
                        notifyChanged()
                    }
                    // 历史里的图片可能没落盘（换机、清过缓存），补一次下载，下完界面自动出图
                    list.forEach { ensureImageDownloaded(it) }
                    // 本地一条都没有（刚加入的群、换过设备）→ 问服务端要一页
                    if (list.isEmpty()) loadRemoteHistory(peerId, isGroup, count)
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "latest messages error=$e")
                }
            }
        )
    }

    /**
     * 本地没有历史时向服务端要一页（云端历史消息）。
     *
     * 用 `getRemoteHistoryMessages`，别用那几个 `getHistoryMessages` 重载：
     * 2.9 里它们的参数是 `(type, targetId, oldestMessageId, count, ???)`，
     * 第 5 个 int 的含义反编译看不出、也没有文档，猜错就是「一条都不返回」。
     * `getRemoteHistoryMessages(type, targetId, sentTime, count, cb)` 语义明确：
     * 取 `sentTime` 之前（含）的 count 条，且一定走服务端。
     *
     * 学校没开通「历史消息云存储」时会回空或报错，界面保持空态即可，不影响发新消息。
     */
    private fun loadRemoteHistory(peerId: String, isGroup: Boolean, count: Int) {
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        runCatching {
            RongIMClient.getInstance().getRemoteHistoryMessages(
                type, peerId, System.currentTimeMillis(), count,
                object : RongIMClient.ResultCallback<List<Message>>() {
                    override fun onSuccess(messages: List<Message>?) {
                        val list = messages.orEmpty()
                        Log.i(TAG, "remote history ${threadKey(peerId, isGroup)} n=${list.size}")
                        if (list.isEmpty()) return
                        val mapped = list.mapNotNull { toImMessage(it) }.sortedBy { it.time }
                        main.post {
                            threads[threadKey(peerId, isGroup)] = mapped.toMutableList()
                            notifyChanged()
                        }
                        list.forEach { ensureImageDownloaded(it) }
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        Log.w(TAG, "remote history error=$e")
                    }
                }
            )
        }.onFailure { Log.w(TAG, "remote history failed: ${it.javaClass.simpleName}") }
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
     * 把一个文件消息的真实文件拿到本地（点开 / 保存之前调）。
     *
     * SDK **不会自动下载文件消息**（只有图片会顺手下一张缩略图）：对方发来的文件，
     * `localPath` 是空的、只有 `mediaUrl` 一个 CDN 地址，所以「点一下就打开」必须先下载。
     *
     * 这里按 uId 找回原始 [Message]，让 SDK 去下载 —— 下载完成后它会把路径写回
     * `FileMessage.localPath`，下次进来就不用再下。
     *
     * @param onProgress 0..100，保证在主线程
     * @param onResult 本地绝对路径；拿不到为 null（调用方负责提示）
     */
    fun downloadFile(uid: String, onProgress: (Int) -> Unit, onResult: (String?) -> Unit) {
        RongIMClient.getInstance().getMessageByUid(
            uid,
            object : RongIMClient.ResultCallback<Message>() {
                override fun onSuccess(message: Message?) {
                    val content = message?.content as? FileMessage
                    if (message == null || content == null) {
                        main.post { onResult(null) }
                        return
                    }
                    // 自己发的、或之前下过的：localPath 已经有效，不必再走网络
                    val cached = localFileOf(content)
                    if (cached != null) {
                        main.post { onResult(cached) }
                        return
                    }
                    RongIMClient.getInstance().downloadMediaMessage(
                        message,
                        object : IRongCallback.IDownloadMediaMessageCallback {
                            override fun onSuccess(message: Message?) {
                                val path = (message?.content as? FileMessage)?.let(::localFileOf)
                                main.post { onResult(path) }
                            }

                            override fun onProgress(message: Message?, progress: Int) {
                                main.post { onProgress(progress) }
                            }

                            override fun onError(message: Message?, e: RongIMClient.ErrorCode?) {
                                Log.w(TAG, "download file failed uid=$uid code=${e?.value}")
                                main.post { onResult(null) }
                            }

                            override fun onCanceled(message: Message?) {
                                main.post { onResult(null) }
                            }
                        }
                    )
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "getMessageByUid failed uid=$uid code=${e?.value}")
                    main.post { onResult(null) }
                }
            }
        )
    }

    /** 放弃下载（用户点了取消 / 页面被关掉） */
    fun cancelDownloadFile(uid: String) {
        runCatching {
            RongIMClient.getInstance().getMessageByUid(
                uid,
                object : RongIMClient.ResultCallback<Message>() {
                    override fun onSuccess(message: Message?) {
                        if (message != null) {
                            RongIMClient.getInstance()
                                .cancelDownloadMediaMessage(message, null)
                        }
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) = Unit
                }
            )
        }
    }

    /**
     * localPath 存在、且文件**真的在盘上**才算数。
     *
     * 只判 `localPath != null` 不够：清过缓存、或在另一台设备登录过之后，
     * SDK 的本地记录还在，但那个路径早就不存在了 —— 直接打开会报错。
     */
    private fun localFileOf(content: FileMessage): String? {
        val path = content.localPath?.path ?: return null
        return path.takeIf { File(it).isFile }
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

    /**
     * 建群成功后的本机登记（学校 `POST /groups` 已经把群建好了，这里只让本机认得它）。
     *
     * 为什么必须有这一步：融云的会话列表是**本地 sqlite**，新群在里面既没有会话记录、
     * 也没有群资料；而 `syncGroup` 只有在已连接时才会把群名写进本地库。两件事都做了，
     * 聊天页标题和列表里的群名才不会是空的（同 [joinGroup] 的坑）。
     *
     * `time = 0` 表示还没有消息，排序时会落到有消息的会话后面（与「我加入的群」一致）。
     */
    fun registerCreatedGroup(groupId: String, name: String) {
        val id = groupId.trim()
        if (id.isBlank()) return
        val title = name.trim()
        main.post {
            joinedGroups = listOf(
                ImConversation(
                    peerId = id,
                    isGroup = true,
                    title = title,
                    preview = "",
                    time = 0L,
                    unread = 0,
                )
            ) + joinedGroups.filter { it.peerId != id }
            notifyChanged()
            if (status != Status.CONNECTED) return@post
            runCatching {
                RongIMClient.getInstance().syncGroup(
                    listOf(Group(id, title, null)),
                    object : RongIMClient.OperationCallback() {
                        override fun onSuccess() = refreshConversations()
                        override fun onError(e: RongIMClient.ErrorCode?) = refreshConversations()
                    }
                )
            }
            refreshConversations()
        }
    }

    private fun markState(peerId: String, isGroup: Boolean, key: String, state: Int) {
        main.post {
            val list = threads[threadKey(peerId, isGroup)] ?: return@post
            val index = list.indexOfFirst { it.key == key }
            if (index >= 0) list[index] = list[index].copy(state = state)
            notifyChanged()
        }
    }

    /**
     * 进聊天页把未读清零（否则会话列表的徽标一直挂着），并回执「已读」。
     *
     * 回执这一步对齐官方 `MainActivity` 清未读时的 `sendReadReceiptMessage(type, targetId, sentTime)`：
     * 不发的话，对方那边我发出的气泡会一直停在「未读」。
     */
    fun markRead(peerId: String, isGroup: Boolean) {
        if (status != Status.CONNECTED || peerId.isBlank()) return
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        runCatching { RongIMClient.getInstance().clearMessagesUnreadStatus(type, peerId) }
        if (!isGroup) sendReadReceipt(peerId)
    }
}
