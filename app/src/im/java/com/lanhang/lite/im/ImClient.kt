package com.lanhang.lite.im

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lanhang.lite.R
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.moments.UserInfoCache
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.session.Session
import io.rong.imlib.IRongCallback
import io.rong.imlib.RongIMClient
import io.rong.imlib.model.Conversation
import io.rong.imlib.model.Group
import io.rong.imlib.model.Message
import io.rong.imlib.model.MessageContent
import io.rong.message.FileMessage
import io.rong.message.HQVoiceMessage
import io.rong.message.ImageMessage
import io.rong.message.LocationMessage
import io.rong.message.ReadReceiptMessage
import io.rong.message.RecallNotificationMessage
import io.rong.message.SightMessage
import io.rong.message.TextMessage
import io.rong.message.VoiceMessage
import okhttp3.Request
import org.json.JSONObject
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
    enum class PreviewKind { TEXT, IMAGE, VIDEO, VOICE, OTHER }
}

/** 聊天页的一条消息（文本 / 图片 / 文件 / 位置 / 视频 / 语音，2026-11-15 补齐后两种） */
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
    /** 时长（秒，[kind] 是 [KIND_VIDEO] / [KIND_VOICE] 时有值） */
    val duration: Int = 0,
    /** 视频/文件的媒体地址（本地路径或媒体服务器地址，点开时要用） */
    val mediaUrl: String? = null,
    /**
     * 对方是否已读这条消息（由 `SentStatus == READ` 派生，**只有 [mine] 的消息才看它**）。
     * 群聊不回执已读，这里的值恒为 false，界面也不展示。
     */
    val read: Boolean = false,
    /**
     * 发送失败的**具体原因**（只在 [state] == [STATE_FAILED] 时有值）。
     *
     * 气泡上那行小字优先显示它：只写「发送失败」，用户无从判断是网络断了还是内容超限，
     * 视频发不出去时尤其需要一句人话（见 `ImClient.describeSendError`）。
     */
    val errorText: String? = null,
    /**
     * 这条已经被撤回（自己撤的，或对方撤的）。
     *
     * 撤回后 SDK 会把库里这条的内容换成 [io.rong.message.RecallNotificationMessage]，
     * 所以它同时决定了 [toImMessage] 的分支和气泡的显示（「你/对方撤回了一条消息」）。
     */
    val recalled: Boolean = false,
    /**
     * 引用：被引用的那条「谁说的 + 说了什么」。
     *
     * 融云 IMLib 2.9 没有 `ReferenceMessage`（只有 IMKit 那层有），所以引用信息塞在
     * `TextMessage.extra` 里（JSON，键 `lq`）。**官方客户端不认这个约定**，它只会显示正文，
     * 引用块会在官方 App 里丢掉 —— 双方都用本客户端时才是完整的。
     */
    val quote: Quote? = null,
) {

    /** 引用块的内容：[sender] 是原作者显示名，[text] 是原文摘要 */
    data class Quote(val sender: String, val text: String)
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
        /** 语音（`RC:VcMsg`；高清语音 `RC:HQVCMsg` 同样映射到这里，只是落盘方式不同） */
        const val KIND_VOICE = 5
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
     * 断线后自动重连的退避封顶。
     *
     * 为什么必须有这套东西（2026-11-15 用户反馈「不打开 App 就没有私信通知」）：
     * 融云是长连接，只有 `connect()` 那一次会主动建连；后台断网、系统回收 socket、
     * 弱网超时之后，旧代码把状态停在 FAILED，**没有任何人再连一次** —— 直到用户手动
     * 打开私信页。没有厂商推送通道时，这就等于「不打开 App 永远收不到消息」。
     */
    private const val RECONNECT_MAX_DELAY_MS = 60_000L

    /**
     * 补「发送者姓名」的等待上限：拿不到就保持占位通知，不把更新吊死在网络上。
     * `/staffs/batch` 正常几百毫秒，3s 足够覆盖弱网，再久用户早看到通知了。
     */
    private const val PROFILE_TIMEOUT_MS = 3_000L

    /**
     * 建连看门狗：connect 发出后超过这么久还没有任何回调，判定卡死。
     * 实测（2026-11-15，HyperOS）：App 在后台时 ROM 拦截新建 socket —— 融云 navi
     * HTTP 探测可达，但 `connect()` 的回调**永远不来**，状态机永久停在 CONNECTING；
     * 同一网络切回前台 200ms 内连上。看门狗触发即标记 [bgRestricted]、断掉卡死的
     * 连接、走 [fail] 的常规退避重试，别干等一个不会来的回调。
     */
    private const val CONNECT_TIMEOUT_MS = 45_000L

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

    /** 断线重连的退避次数（2s → 4s → … → 60s 封顶），连上即归零 */
    private var reconnectAttempt = 0

    /** 已经排了一颗重连任务（防止断线回调 + 失败回调各排一颗，越排越密） */
    private var reconnectScheduled = false

    /** 每次 connect() 递增；看门狗只认启动时记录的那一代，避免误杀后来的连接 */
    private var connectAttemptId = 0
    private var watchdogAttempt = 0

    /**
     * 后台联网被 ROM 限制过的痕迹（看门狗触发即置真，连上即清零）。
     * 私信列表据此把「重试」换成「去允许后台运行」——这种失败重试多少次都没用，
     * 必须引导用户去系统里放开省电策略 / 自启动。
     */
    var bgRestricted = false
        private set

    /** 重连任务实体：留着引用才能在网络恢复时把延迟的那颗撤掉、立刻连 */
    private val reconnectTask = Runnable {
        reconnectScheduled = false
        val ctx = app ?: return@Runnable
        if (status == Status.CONNECTED || status == Status.CONNECTING) return@Runnable
        // ensureStarted 在 FAILED 之外的状态会直接复用，这里显式回到 IDLE 让它走建连
        status = Status.IDLE
        inFlight = false
        ensureStarted(ctx)
    }

    /**
     * 建连看门狗：[CONNECT_TIMEOUT_MS] 后还是 CONNECTING、且仍是自己盯的那一代尝试 →
     * 判定 connect 卡死（典型：ROM 在后台拦截新建 socket，connect 永不回调）。
     * 标记 [bgRestricted]、断开卡死的连接、走 [fail]（内部按退避重排重试）。
     */
    private val connectWatchdog = Runnable {
        if (status != Status.CONNECTING || connectAttemptId != watchdogAttempt) return@Runnable
        Log.w(TAG, "connect stuck ${CONNECT_TIMEOUT_MS}ms, reset (background networking likely blocked)")
        bgRestricted = true
        runCatching { RongIMClient.getInstance().disconnect() }
        fail(text(R.string.im_err_connect_failed, "timeout"))
    }

    /** 默认网络回调（只注册一次）：网络恢复时立刻重连，不用等退避到点 */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

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
        watchNetwork(appCtx)
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
            main.post { fail(text(R.string.im_err_config_no_im), retryable = false) }
            return
        }
        val appKey = im.appKey?.takeIf { it.isNotBlank() }
        if (appKey == null) {
            main.post { fail(text(R.string.im_err_config_no_key), retryable = false) }
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
            // 视频同理，而且更隐蔽：`initSDK` 的默认注册表里有 Voice/HQVoice，**唯独没有
            // SightMessage**（aar 字节码核对过）—— 不注册就解成 UnknownMessage，
            // toImMessage 直接返回 null，对方发来的视频被静默丢掉（连通知都不弹）。
            // 同样单独 catch：注册失败只影响视频，不能连 SDK 都不起。
            runCatching { RongIMClient.registerMessageType(SightMessage::class.java) }
                .onFailure { Log.e(TAG, "registerMessageType sight failed", it) }
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
        // 挂看门狗：后台被 ROM 拦 socket 时 connect 永不回调，靠它把状态机从
        // CONNECTING 里拽出来（见 connectWatchdog 注释）
        main.removeCallbacks(connectWatchdog)
        connectAttemptId++
        watchdogAttempt = connectAttemptId
        main.postDelayed(connectWatchdog, CONNECT_TIMEOUT_MS)
        RongIMClient.connect(token, object : RongIMClient.ConnectCallback() {
            override fun onSuccess(userId: String?) {
                Log.i(TAG, "connected userId=$userId")
                main.removeCallbacks(connectWatchdog)
                bgRestricted = false
                status = Status.CONNECTED
                error = null
                inFlight = false
                retryAttempt = 0
                reconnectAttempt = 0
                reconnectScheduled = false
                main.removeCallbacks(reconnectTask)
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
                main.removeCallbacks(connectWatchdog)
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
                main.removeCallbacks(connectWatchdog)
                if (!allowTokenRefresh) {
                    fail(text(R.string.im_err_token_rejected), retryable = false)
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
                            fail(text(R.string.im_err_token_rejected), retryable = false)
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

    private fun fail(message: String, retryable: Boolean = true) {
        status = Status.FAILED
        error = message
        inFlight = false
        notifyChanged()
        // 瞬时失败（网络、网关抖动）也要自己爬起来：后台没有 UI，没人替它点「重试」。
        // 永久性失败（学校没开私信、token 被拒）不传 retryable，免得每分钟空打一次配置接口
        if (retryable) scheduleReconnect()
    }

    /** 排一颗延迟重连（退避 2s 起、60s 封顶）；已排过或已连上就不再排 */
    private fun scheduleReconnect() {
        val ctx = app ?: return
        if (Session.token.isBlank() && Session.imToken.isBlank()) return // 已退出登录
        if (reconnectScheduled) return
        reconnectScheduled = true
        val delay = (2_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempt++
        Log.i(TAG, "reconnect scheduled in ${delay}ms attempt=$reconnectAttempt")
        main.postDelayed(reconnectTask, delay)
    }

    /** 立刻重连：撤掉还在等退避的那颗，马上建连（网络恢复时用） */
    private fun reconnectNow() {
        if (app == null) return // 还没建连过，没有上下文可用
        if (status == Status.CONNECTED || status == Status.CONNECTING) return
        if (Session.token.isBlank() && Session.imToken.isBlank()) return
        main.removeCallbacks(reconnectTask)
        reconnectScheduled = false
        main.post(reconnectTask)
    }

    /**
     * 系统层面的默认网络还活着吗（区分「真断网」与「ROM 冻结后台联网」）。
     * ROM 冻结是按 UID 拦 socket，系统网络本身完好 —— 所以用
     * NET_CAPABILITY_VALIDATED 判系统网络，能识别出「网在、只有我们被掐」。
     */
    private fun deviceNetworkAlive(): Boolean {
        val ctx = app ?: return false
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 监听默认网络：熄屏 / 切网之后长连接常常已经死了，但断线回调未必及时到。
     * 网络一恢复就主动重连，比干等退避到点快得多。
     *
     * 只在进程存活期内有效（不持久注册、不唤醒设备），不额外耗电。
     */
    private fun watchNetwork(ctx: Context) {
        if (networkCallback != null) return
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                reconnectNow()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    /**
     * 退出登录：断开融云、撤掉重连任务、清空本进程的会话与已读水位线。
     *
     * 只清 `Session` 不断连接的话，旧身份的长连接还在收消息，通知会发到下一个
     * 登录者的机器上（串号）。由 `ImBridge.logout` 在 `Logout.clear` 里调用。
     */
    fun shutdown() {
        main.removeCallbacks(reconnectTask)
        main.removeCallbacks(connectWatchdog)
        reconnectScheduled = false
        reconnectAttempt = 0
        retryAttempt = 0
        inFlight = false
        status = Status.IDLE
        error = null
        conversations = emptyList()
        threads.clear()
        joinedGroups = emptyList()
        readUntil.clear()
        readVerified.clear()
        visibleThreadKey = null
        runCatching { RongIMClient.getInstance().logout() }
            .onFailure { Log.w(TAG, "rong logout failed", it) }
    }

    // ---------------------------------------------------------------- 监听

    private fun installListeners() {
        if (listenersInstalled) return
        listenersInstalled = true
        // 长连接状态：断线必须自己连回去，否则后台期间断一次就永远收不到消息
        RongIMClient.setConnectionStatusListener { connectionStatus ->
            Log.i(TAG, "connection status=$connectionStatus")
            when (connectionStatus) {
                RongIMClient.ConnectionStatusListener.ConnectionStatus.CONNECTED -> {
                    reconnectAttempt = 0
                    reconnectScheduled = false
                    main.removeCallbacks(reconnectTask)
                    // 连上即证明后台联网此刻没被封，清掉「被冻结」痕迹
                    bgRestricted = false
                    notifyChanged()
                }
                RongIMClient.ConnectionStatusListener.ConnectionStatus.KICKED_OFFLINE_BY_OTHER_CLIENT -> {
                    // 被另一台设备顶下线：**不能**自动重连，否则两台设备互相踢成死循环。
                    // 留在 FAILED，等用户打开私信页手动重连
                    status = Status.FAILED
                    error = text(R.string.im_err_kicked)
                    inFlight = false
                    notifyChanged()
                }
                // CONNECTING：自己正在连，不排重连（实测会多排一颗 2s 的废任务）
                RongIMClient.ConnectionStatusListener.ConnectionStatus.CONNECTING -> Unit
                else -> {
                    // DISCONNECTED / NETWORK_UNAVAILABLE / SOCKET_DISCONNECTED …
                    // 先离开 CONNECTED（UI 显示「连接中」而不是继续假装在线），再排重连
                    if (status == Status.CONNECTED) status = Status.IDLE
                    // 「系统网络明明活着、SDK 却报断线/网络不可用」= ROM 冻结了本应用的
                    // 后台联网（HyperOS PowerKeeper 实测信号，13:22 熄屏 4 分钟后
                    // NETWORK_UNAVAILABLE、TCP socket 还在但 SDK 已认为断网，被广播
                    // 唤醒后 360ms 重连成功）。与看门狗一样标记 bgRestricted，但**不用等
                    // 45s 超时** —— 私信列表立刻弹「去设置放开」横幅。只认
                    // NETWORK_UNAVAILABLE：DISCONNECTED 在前台 WiFi 抖动也会误报。
                    if (connectionStatus ==
                        RongIMClient.ConnectionStatusListener.ConnectionStatus.NETWORK_UNAVAILABLE &&
                        deviceNetworkAlive()
                    ) {
                        if (!bgRestricted) {
                            Log.w(TAG, "NETWORK_UNAVAILABLE while system network alive → background networking frozen")
                        }
                        bgRestricted = true
                        notifyChanged()
                    }
                    scheduleReconnect()
                }
            }
        }
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

        // 撤回（`RC:RcCmd`）：SDK 收到后会把**本地库**里那条的内容替换成
        // `RecallNotificationMessage`，并回调这里告诉我们哪一条被撤了（给的是内部 messageId）。
        // 我们按 id 反查 uId，再把内存里那份标成 recalled —— 不处理的话界面会一直显示原文，
        // 直到下次重启重新读库才「突然变成已撤回」。
        // 用旧版 `setRecallMessageListener`（给 int）而不是 `setOnRecallMessageListener`
        // （给 Message、返回 boolean 表示是否拦截默认处理）：我们要的就是 SDK 那套默认替换，
        // 自己去改库反而容易和它打架。
        RongIMClient.setRecallMessageListener { messageId, _ ->
            RongIMClient.getInstance().getMessage(
                messageId,
                object : RongIMClient.ResultCallback<Message>() {
                    override fun onSuccess(message: Message?) {
                        message?.uId?.let { uid -> main.post { markRecalled(uid) } }
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        Log.w(TAG, "recall lookup failed id=$messageId err=$e")
                    }
                }
            )
        }
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

    // ---------------------------------------------------------------- 撤回

    /** 撤回窗口（融云默认 2 分钟，服务端可配得更短）。客户端只用来提前把菜单项藏掉 */
    const val RECALL_WINDOW_MS = 2L * 60L * 1000L

    /** 引用信息塞进 `TextMessage.extra`：`{"lq":{"s":"张三","t":"原文摘要"}}` */
    private fun encodeQuote(quote: ImMessage.Quote): String = JSONObject()
        .put("lq", JSONObject().put("s", quote.sender).put("t", quote.text))
        .toString()

    /**
     * 解析引用。不是我们写的 extra（官方客户端发的、或格式变了）一律当没有引用 ——
     * 宁可少显示一个引用块，也不能因为一个畸形 JSON 让整条消息渲染失败。
     */
    private fun parseQuote(extra: String?): ImMessage.Quote? {
        if (extra.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(extra) }.getOrNull() ?: return null
        val lq = obj.optJSONObject("lq") ?: return null
        val body = lq.optString("t")
        if (body.isBlank()) return null
        return ImMessage.Quote(sender = lq.optString("s").ifBlank { "…" }, text = body)
    }

    /**
     * 找到这条消息在 SDK 里的 `Message` 对象（撤回、删除都要它）。
     *
     * 🔴 **不能只靠 `getMessageByUid`**（2026-11-21 用户报「刚发的消息撤回失败，找不到这条」）：
     * 本地 `Message.obtain` 拿到的 uId 与真正入库后的不一定对得上，`getMessageByUid` 对刚发出去的
     * 消息经常直接回 null。所以这里两级兜底：
     * ① 先试 uid，命中就最准；
     * ② 失败则在**最近 60 条**里按「我自己发的 + 时间窗 ±2 分钟 + 内容相符」找 ——
     *    撤回和删除的对象都是刚发的消息，60 条足够覆盖。
     */
    private fun findSdkMessage(
        peerId: String,
        isGroup: Boolean,
        target: ImMessage,
        onFound: (Message?) -> Unit,
    ) {
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE

        fun scan() {
            RongIMClient.getInstance().getLatestMessages(
                type, peerId, 60,
                object : RongIMClient.ResultCallback<List<Message>>() {
                    override fun onSuccess(messages: List<Message>?) {
                        val list = messages.orEmpty()
                        val hit = list.firstOrNull { it.uId == target.key }
                            ?: list.firstOrNull { m ->
                                m.senderUserId == Session.userId &&
                                    kotlin.math.abs(m.sentTime - target.time) <= 120_000L &&
                                    sameContent(m, target)
                            }
                        if (hit == null) {
                            Log.w(TAG, "find message miss kind=${target.kind} t=${target.time}")
                        }
                        main.post { onFound(hit) }
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        Log.w(TAG, "find message failed ${e?.name}")
                        main.post { onFound(null) }
                    }
                }
            )
        }

        RongIMClient.getInstance().getMessageByUid(
            target.key,
            object : RongIMClient.ResultCallback<Message>() {
                override fun onSuccess(message: Message?) {
                    if (message != null) main.post { onFound(message) } else scan()
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    scan()
                }
            }
        )
    }

    /**
     * 内容是否同一条：文字比正文、文件比文件名、位置比地名；媒体类只比类型
     * （同一个 2 分钟窗口里，我自己发的同类媒体消息重复到分不清的概率可以忽略）。
     */
    private fun sameContent(message: Message, target: ImMessage): Boolean {
        val c = message.content
        return when (target.kind) {
            ImMessage.KIND_TEXT -> (c as? TextMessage)?.content == target.text
            ImMessage.KIND_FILE -> (c as? FileMessage)?.name == target.fileName
            ImMessage.KIND_LOCATION -> (c as? LocationMessage)?.poi == target.poi
            ImMessage.KIND_IMAGE -> c is ImageMessage
            ImMessage.KIND_VOICE -> c is VoiceMessage || c is HQVoiceMessage
            ImMessage.KIND_VIDEO -> c is SightMessage
            else -> false
        }
    }

    /**
     * 删除一条消息（**只删本机**：融云的 `deleteMessages` 删的是本地库，服务端仍保留）。
     * 微信的「删除消息」也是这个语义，所以确认框里要写清「对方仍能看到」。
     */
    fun deleteMessage(
        peerId: String,
        isGroup: Boolean,
        target: ImMessage,
        onDone: (Boolean) -> Unit,
    ) {
        findSdkMessage(peerId, isGroup, target) { message ->
            if (message == null) {
                // 库里没有也不影响把内存里那份摘掉 —— 用户看到的结果一样
                removeFromThreads(target.key)
                onDone(true)
                return@findSdkMessage
            }
            RongIMClient.getInstance().deleteMessages(
                intArrayOf(message.messageId),
                object : RongIMClient.ResultCallback<Boolean>() {
                    override fun onSuccess(ok: Boolean?) {
                        removeFromThreads(target.key)
                        refreshConversations()
                        onDone(true)
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        Log.w(TAG, "delete message failed key=${target.key} err=$e")
                        onDone(false)
                    }
                }
            )
        }
    }

    private fun removeFromThreads(uid: String) {
        var changed = false
        threads.values.forEach { list ->
            if (list.removeAll { it.key == uid }) changed = true
        }
        if (changed) notifyChanged()
    }

    // ---------------------------------------------------------------- 转发

    /**
     * 转发一条消息到另一个会话。
     *
     * 富消息（图片/视频/文件/语音）的发送接口只吃**本地 uri**，所以先确保本地有文件：
     * 已落盘的直接用，没有的走 [downloadFile]（复用源消息的 uid，和「点开文件时下载」
     * 是同一条路径）下到 cache 再发。
     *
     * @param onDone 是否已发起 + 失败原因。富消息的最终成败仍看目标会话里的气泡状态
     */
    fun forward(
        message: ImMessage,
        peerId: String,
        isGroup: Boolean,
        onDone: (Boolean, String?) -> Unit,
    ) {
        if (peerId.isBlank() || status != Status.CONNECTED) {
            onDone(false, text(R.string.im_forward_failed))
            return
        }
        when (message.kind) {
            ImMessage.KIND_TEXT -> {
                sendText(peerId, isGroup, message.text)
                onDone(true, null)
            }

            ImMessage.KIND_LOCATION -> {
                sendLocation(peerId, isGroup, message.lat, message.lng, message.poi.orEmpty())
                onDone(true, null)
            }

            else -> {
                val local = localFileOf(message)
                if (local != null) {
                    sendRichForward(message, local, peerId, isGroup, onDone)
                } else {
                    downloadFile(
                        uid = message.key,
                        remoteUrl = message.mediaUrl,
                        fileName = message.fileName ?: fallbackNameOf(message),
                        fileSize = message.fileSize,
                        onProgress = {},
                        onResult = { path ->
                            val file = path?.let { File(it) }?.takeIf { it.isFile }
                            main.post {
                                if (file == null) {
                                    onDone(false, text(R.string.im_forward_failed))
                                } else {
                                    sendRichForward(message, file, peerId, isGroup, onDone)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    /** 已经是本地文件的才返回（`file://` 或裸路径且真在盘上），远端地址一律返回 null */
    private fun localFileOf(message: ImMessage): File? {
        val path = message.mediaUrl ?: return null
        val raw = when {
            path.startsWith("file://") -> android.net.Uri.parse(path).path
            path.startsWith("/") -> path
            else -> null
        } ?: return null
        return File(raw).takeIf { it.isFile }
    }

    /** 转发时给个兜底文件名（对端保存时用它，没有原名总比一串时间戳好） */
    private fun fallbackNameOf(message: ImMessage): String = when (message.kind) {
        ImMessage.KIND_IMAGE -> "image_${message.time}.jpg"
        ImMessage.KIND_VIDEO -> "video_${message.time}.mp4"
        ImMessage.KIND_VOICE -> "voice_${message.time}.amr"
        else -> "file_${message.time}"
    }

    private fun sendRichForward(
        message: ImMessage,
        file: File,
        peerId: String,
        isGroup: Boolean,
        onDone: (Boolean, String?) -> Unit,
    ) {
        val uri = android.net.Uri.fromFile(file)
        when (message.kind) {
            ImMessage.KIND_IMAGE -> sendImage(peerId, isGroup, uri)
            // 视频没有随行封面：转发的这条在对方那边是占位块，点开仍能播（封面不是必需品）
            ImMessage.KIND_VIDEO -> sendVideo(peerId, isGroup, uri, null, message.duration)
            ImMessage.KIND_VOICE -> sendVoice(peerId, isGroup, file, message.duration)
            ImMessage.KIND_FILE -> sendFile(
                peerId, isGroup, uri, message.fileName.orEmpty(), message.fileSize
            )

            else -> {
                onDone(false, text(R.string.im_forward_failed))
                return
            }
        }
        onDone(true, null)
    }

    /**
     * 撤回一条**自己发的**消息（`recallMessage` → 服务端下发 `RC:RcCmd`）。
     *
     * 真正的约束全在服务端：只能撤自己的、只在窗口内、且学校私有云要开通这个能力。
     * 客户端这几行只是把「点了没反应」变成一句人话。
     *
     * @param onDone 是否成功 + 失败原因（成功时原因为 null）
     */
    fun recall(
        peerId: String,
        isGroup: Boolean,
        target: ImMessage,
        onDone: (Boolean, String?) -> Unit,
    ) {
        if (status != Status.CONNECTED) {
            onDone(false, text(R.string.im_err_not_connected))
            return
        }
        findSdkMessage(peerId, isGroup, target) { message ->
            if (message == null) {
                // 60 条里都找不到：清过记录、或者根本不是这个会话的消息。撤不回来，只能直说
                onDone(false, text(R.string.im_recall_no_message))
                return@findSdkMessage
            }
            runCatching {
                RongIMClient.getInstance().recallMessage(
                    message, "",
                    object : RongIMClient.ResultCallback<RecallNotificationMessage>() {
                        override fun onSuccess(result: RecallNotificationMessage?) {
                            // 成功时 SDK 会自己改库并回调 recall listener；这里再标一次是保险 ——
                            // listener 晚到时用户会看到「点了撤回原文还在」，那是最难解释的一种表现
                            markRecalled(target.key)
                            onDone(true, null)
                        }

                        override fun onError(e: RongIMClient.ErrorCode?) {
                            Log.w(TAG, "recall failed key=${target.key} err=$e")
                            onDone(false, e?.name)
                        }
                    }
                )
            }.onFailure {
                Log.w(TAG, "recall threw key=${target.key}", it)
                onDone(false, it.javaClass.simpleName)
            }
        }
    }

    /**
     * 把内存里的某条标成已撤回（正文一并清掉，撤回气泡不显示原文）。
     *
     * 找不到是正常的：消息可能已被清记录、或者根本不在当前缓存的会话里。
     */
    private fun markRecalled(uid: String) {
        var changed = false
        threads.values.forEach { list ->
            val i = list.indexOfFirst { it.key == uid }
            if (i >= 0 && !list[i].recalled) {
                // 引用块也要清掉：撤回后上面还挂着「引用 张三：……」会很怪
                list[i] = list[i].copy(recalled = true, text = "", quote = null)
                changed = true
            }
        }
        if (changed) notifyChanged()
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
        // 正文按消息类型选：语音/图片/视频没有文字，不能都落到占位的「[消息]」
        // （用户看到的会是「来了一条私信」这种没信息量的提醒）
        val body = when (val content = message.content) {
            is TextMessage -> content.content.orEmpty()
            is VoiceMessage, is HQVoiceMessage -> ctx.getString(R.string.im_push_voice)
            is SightMessage -> ctx.getString(R.string.im_push_video)
            is ImageMessage -> ctx.getString(R.string.im_push_image)
            is FileMessage -> content.name.orEmpty()
            else -> ""
        }
        val text = body.ifBlank { ctx.getString(R.string.im_notify_body_placeholder) }
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
     * 认六类：文本、图片、文件、位置、视频、语音。其余（官方在「同意好友」时插的系统气泡、
     * 没注册成功的自定义消息）返回 null —— 界面上不显示，也**不弹通知**
     * （收消息回调里对 null 直接跳过，见那里「静默」的注释）。
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
            is TextMessage -> base.copy(
                kind = ImMessage.KIND_TEXT,
                text = content.content.orEmpty(),
                quote = parseQuote(content.extra),
            )
            is ImageMessage -> {
                val thumb = thumbSourceOf(content)
                val full = fullSourceOf(content) ?: thumb
                base.copy(
                    kind = ImMessage.KIND_IMAGE,
                    imageUrl = thumb ?: full,
                    fullImageUrl = full,
                )
            }
            is FileMessage -> {
                // 远端地址**不一定在 mediaUrl**（2026-11-15 实测设备库里的原始 JSON）：
                //   {"name":"s73c.png","size":153702,"type":"bin","fileUrl":"https://...:1446//v4/...png"}
                // 对端把远端地址塞进了 `fileUrl`，`mediaUrl` 是空的。我们原来只认
                // `localPath ?: mediaUrl`，于是 SDK 的 downloadMediaMessage 拿不到远端地址、
                // 必然失败 —— 就是「文件点开提示下载失败，检查网络后重试」。
                // 所以这里两个字段都要认，且只把**真的在盘上**的本地文件当本地。
                val local = content.localPath
                    ?.takeIf { it.scheme == "file" }
                    ?.path
                    ?.takeIf { File(it).isFile }
                val remote = sequenceOf(content.mediaUrl, content.fileUrl)
                    .mapNotNull { it?.toString() }
                    .firstOrNull { it.startsWith("http", ignoreCase = true) }
                base.copy(
                    kind = ImMessage.KIND_FILE,
                    fileName = content.name.orEmpty(),
                    fileSize = content.size,
                    fileMime = content.type,
                    mediaUrl = local?.let { "file://$it" } ?: remote ?: content.localPath?.toString(),
                    text = content.name.orEmpty(),
                )
            }
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
                fileSize = content.size,
                // 气泡里显示封面（对端把封面 base64 内联，decode 时已落盘成 .jpg）。
                // 🔴 **只认封面，不回落到 mediaUrl** —— 那是 .mp4 的地址，coil 解不出位图，
                // 结果是「复用的 holder 还留着上一条的图」这种串图。没封面就走占位块
                // （`bindMedia` 的空地址分支，视频照样可点开）。
                imageUrl = pick(content.thumbUri?.toString()),
                // 点开播放：**本地已有的排第一**（视频不像图片要区分缩略/原图，
                // 本地那份就是完整视频），没有才用媒体服务器地址（点开时先下载）
                mediaUrl = pick(content.localPath?.toString(), content.mediaUrl?.toString()),
            )
            // 普通语音：base64 内联，decode 时直接落盘成 `<msgId>.amr` 并填进 uri，
            // **没有下载环节**（所以不用挂 ensureImageDownloaded）
            is VoiceMessage -> base.copy(
                kind = ImMessage.KIND_VOICE,
                duration = content.duration,
                mediaUrl = pick(content.uri?.toString()),
                text = "",
            )
            // 高音质语音：走媒体服务器（`sendMediaMessage`），本地可能还没下
            is HQVoiceMessage -> base.copy(
                kind = ImMessage.KIND_VOICE,
                duration = content.duration,
                mediaUrl = pick(content.localPath?.toString(), content.mediaUrl?.toString()),
                text = "",
            )
            // 撤回通知（`RC:RcCmd`）：SDK 把本地库里原消息的内容整个换成了这个，
            // 重启后从库里读到的也是它。必须显式认 —— 走 else 会被丢掉，
            // 结果是「撤回的那条凭空消失」或「重启后又冒出来」。
            // 左右位置仍按原消息的 senderUserId（base 已算），不去看撤回操作者：
            // 单聊里撤回操作者必然是作者本人，群管理员撤回他人消息的情况这里按作者显示更自然。
            is RecallNotificationMessage -> base.copy(recalled = true, text = "")
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
     * 分五类而不是「是不是图片」：语音/视频/文件/位置这些也没文本，混进「没有消息」会显示成
     * 「还没有私信」，看着像 bug —— 那是空会话的文案。
     */
    private fun previewKindOf(c: Conversation): ImConversation.PreviewKind = when (c.latestMessage) {
        null -> ImConversation.PreviewKind.TEXT
        is TextMessage -> ImConversation.PreviewKind.TEXT
        is ImageMessage -> ImConversation.PreviewKind.IMAGE
        is SightMessage -> ImConversation.PreviewKind.VIDEO
        is VoiceMessage, is HQVoiceMessage -> ImConversation.PreviewKind.VOICE
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

    /**
     * 向服务端要 [beforeTime] 之前的一页历史，并**并进**当前会话。
     *
     * 给「聊天记录搜索 → 跳转定位」用：目标消息不在最近 [loadRecent] 那 40 条里时，
     * 先把那一段拉下来，列表里才有得滚。
     *
     * 合并而不是替换：最新的那几张已经上屏，整体换掉会让用户眼前的消息凭空消失。
     * 走 `getRemoteHistoryMessages`（唯一语义明确的那支，见 [loadRemoteHistory]）；
     * 学校没开通云端历史时回空 / 报错 → 回调 false，界面保持原样。
     *
     * 注意**不碰已读水位线**：翻历史不等于「读到最新」，抬水位会让会话列表的未读红点消失。
     */
    fun loadHistoryBefore(
        peerId: String,
        isGroup: Boolean,
        beforeTime: Long,
        count: Int,
        onDone: (Boolean) -> Unit,
    ) {
        if (status != Status.CONNECTED || peerId.isBlank()) {
            onDone(false)
            return
        }
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        runCatching {
            RongIMClient.getInstance().getRemoteHistoryMessages(
                type, peerId, beforeTime, count,
                object : RongIMClient.ResultCallback<List<Message>>() {
                    override fun onSuccess(messages: List<Message>?) {
                        val raw = messages.orEmpty()
                        val mapped = raw.mapNotNull { toImMessage(it) }.sortedBy { it.time }
                        Log.i(TAG, "history before $beforeTime n=${mapped.size}")
                        if (mapped.isEmpty()) {
                            main.post { onDone(false) }
                            return
                        }
                        main.post {
                            val key = threadKey(peerId, isGroup)
                            val existing = threads[key].orEmpty()
                            val known = existing.mapTo(HashSet()) { it.key }
                            threads[key] = (mapped.filterNot { it.key in known } + existing)
                                .sortedBy { it.time }
                                .toMutableList()
                            notifyChanged()
                            onDone(true)
                        }
                        raw.forEach { ensureImageDownloaded(it) }
                    }

                    override fun onError(e: RongIMClient.ErrorCode?) {
                        Log.w(TAG, "history before $beforeTime error=$e")
                        main.post { onDone(false) }
                    }
                }
            )
        }.onFailure {
            Log.w(TAG, "history before failed: ${it.javaClass.simpleName}")
            main.post { onDone(false) }
        }
    }

    fun sendText(
        peerId: String,
        isGroup: Boolean,
        body: String,
        quote: ImMessage.Quote? = null,
    ) {
        val text = body.trim()
        if (text.isEmpty() || peerId.isBlank()) return
        if (status != Status.CONNECTED) {
            error = text(R.string.im_err_not_connected)
            notifyChanged()
            return
        }
        val type = if (isGroup) Conversation.ConversationType.GROUP
        else Conversation.ConversationType.PRIVATE
        val content = TextMessage.obtain(text)
        quote?.let { content.extra = encodeQuote(it) }
        val message = Message.obtain(peerId, type, content)
        val key = message.uId ?: System.currentTimeMillis().toString()
        val list = threads.getOrPut(threadKey(peerId, isGroup)) { mutableListOf() }
        list += ImMessage(
            key = key,
            text = text,
            mine = true,
            time = System.currentTimeMillis(),
            state = ImMessage.STATE_SENDING,
            senderId = Session.userId,
            quote = quote,
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
                    markState(peerId, isGroup, key, ImMessage.STATE_FAILED, describeSendError(e))
                    error = text(R.string.im_err_send_failed, e?.name ?: text(R.string.im_unknown_error))
                }
            }
        )
    }


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

    /**
     * 发文件（**任意类型**，没有白名单）。
     *
     * 官方也没有白名单：`RceExtensionModule` 里的「文件」是 IMKit 的 `FilePlugin`，
     * 打开它自带的 `FileManagerActivity`（浏览本机任意文件），一次可多选，逐个发一条消息。
     *
     * 🔴 `type` 要填**后缀**（`doc` / `pdf` / `mp3`…），不是 MIME：官方就是
     * `fileMessage.setType(fileInfo.getSuffix())`。官方客户端的文件列表/气泡按这个字段分图标
     * （`FileListActivity` 判 `type.startsWith("doc")`、`equals("mp3")` 等），
     * 留空一律显示通用文件图标；反过来我们收到官方发来的文件时，`type` 也是后缀，
     * 所以 `ImFileOpen.resolveMime` 只认带斜杠的声明值 —— 两边语义必须一致。
     */
    fun sendFile(peerId: String, isGroup: Boolean, uri: android.net.Uri, name: String, size: Long) {
        val content = FileMessage.obtain(uri) ?: run {
            Log.w(TAG, "sendFile obtain failed uri=$uri")
            error = text(R.string.im_err_send_failed, text(R.string.im_unknown_error))
            notifyChanged()
            return
        }
        // `obtain` 内部已经按 File 填好 name/size。这里：
        // - name 必须覆盖成**展示名**（缓存文件名带时间戳前缀，不能让对方看到那个前缀）；
        // - size **绝不能用调用方传来的 OpenableColumns 值覆盖**：SAF 经常回 SIZE=0，
        //   覆盖成 0 字节会被服务端拒收（参考项目同款结论）。只有 SDK 读出来是 0 时才回退。
        content.name = name
        content.size = content.size.takeIf { it > 0 } ?: size
        content.type = suffixOf(name)
        val actualSize = content.size
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
                fileSize = actualSize,
            )
        }
    }

    /**
     * 把一个文件消息的真实文件拿到本地（点开 / 保存之前调）。
     *
     * SDK **不会自动下载文件消息**（只有图片会顺手下一张缩略图）：对方发来的文件，
     * `localPath` 是空的、只有一个远端地址，所以「点一下就打开」必须先下载。
     *
     * 🔴 **不要只靠 `downloadMediaMessage`**（2026-11-15 修）：它内部用的是
     * `FileMessage.mediaUrl`，而实测对端发来的文件消息常常只填了 `fileUrl`（值为 https 地址），
     * `mediaUrl` 为空 → SDK 直接失败，用户看到的就是「文件下载失败，检查网络后重试」。
     * 现在自己按 URL 下（[ApiClient.http]，带会话 Cookie），SDK 只作为「连远端地址都拿不到」
     * 时的兜底。落点与 [ImChatActivity.copyToRecvDir] 同一套命名（`<key>_<文件名>`），
     * 所以下完之后那次复制会命中已有文件、不再重复拷贝，下次点开也不必再联网。
     *
     * @param remoteUrl 调用方从气泡上带过来的远端地址（`ImMessage.mediaUrl`，可能是 https 或本地 file://）
     * @param onProgress 0..100，保证在主线程
     * @param onResult 本地绝对路径；拿不到为 null（调用方负责提示）
     */
    fun downloadFile(
        uid: String,
        remoteUrl: String?,
        fileName: String?,
        fileSize: Long,
        onProgress: (Int) -> Unit,
        onResult: (String?) -> Unit,
    ) {
        val ctx = app
        if (ctx == null) {
            main.post { onResult(null) }
            return
        }
        RongIMClient.getInstance().getMessageByUid(
            uid,
            object : RongIMClient.ResultCallback<Message>() {
                override fun onSuccess(message: Message?) {
                    proceed(message)
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    // 消息查不到（uid 为空 / 已被清理）不代表下不了：URL 就在手上，照样下
                    Log.w(TAG, "getMessageByUid failed uid=$uid code=${e?.value}")
                    proceed(null)
                }

                private fun proceed(message: Message?) {
                    val content = message?.content as? FileMessage
                    // 自己发的、或之前下过的：localPath 已经有效，不必再走网络
                    val cached = content?.let { localFileOf(it) }
                    if (cached != null) {
                        main.post { onResult(cached) }
                        return
                    }
                    val remote = remoteOf(content)
                        ?: remoteUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
                    if (remote != null) {
                        io.launch {
                            val path = runCatching {
                                fetchToRecvDir(
                                    ctx = ctx,
                                    key = uid,
                                    url = remote,
                                    name = fileName?.takeIf { it.isNotBlank() } ?: content?.name,
                                    expectSize = if (fileSize > 0) fileSize else content?.size ?: 0,
                                    onProgress = onProgress,
                                )
                            }.getOrNull()
                            main.post { onResult(path) }
                        }
                        return
                    }
                    if (message != null) {
                        // 连远端地址都拿不到时，最后再让 SDK 试一次（内部可能有别的寻址方式）
                        downloadViaSdk(message, onProgress, onResult)
                    } else {
                        Log.w(TAG, "download file: no url and no message uid=$uid")
                        main.post { onResult(null) }
                    }
                }
            }
        )
    }

    /** 从 FileMessage 里挑出可下载的远端地址：`mediaUrl` 优先，其次 `fileUrl`（对端常把它当远端用） */
    private fun remoteOf(content: FileMessage?): String? {
        if (content == null) return null
        return sequenceOf(content.mediaUrl, content.fileUrl)
            .mapNotNull { it?.toString() }
            .firstOrNull { it.startsWith("http", ignoreCase = true) }
    }

    /** 走 SDK 下载（[downloadFile] 的兜底分支，只在拿不到任何 http 地址时用） */
    private fun downloadViaSdk(
        message: Message,
        onProgress: (Int) -> Unit,
        onResult: (String?) -> Unit,
    ) {
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
                    Log.w(TAG, "download file failed code=${e?.value}")
                    main.post { onResult(null) }
                }

                override fun onCanceled(message: Message?) {
                    main.post { onResult(null) }
                }
            }
        )
    }

    /**
     * 自己把远端文件下到 `filesDir/im/recv/<key>_<文件名>`。
     *
     * 与 [ImChatActivity.copyToRecvDir] 用同一套命名，好处有两个：下完那次「复制到接收目录」
     * 会看到目标文件已存在（长度相同）直接返回，不会白拷一遍；下次点开同一个文件时
     * 目标文件还在，就不用再联网。
     *
     * @return 本地绝对路径；失败为 null（临时文件会清掉，不留半截）
     */
    private fun fetchToRecvDir(
        ctx: Context,
        key: String,
        url: String,
        name: String?,
        expectSize: Long,
        onProgress: (Int) -> Unit,
    ): String? {
        val dir = File(ctx.filesDir, "im/recv").apply { mkdirs() }
        val dst = File(dir, "${key.take(32)}_${safeFileName(name.orEmpty())}")
        if (dst.isFile && (expectSize <= 0 || dst.length() == expectSize)) return dst.absolutePath

        val tmp = File(dir, "${dst.name}.part")
        val request = Request.Builder().url(url).get().build()
        return try {
            ApiClient.http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "download http=${resp.code} url=$url")
                    return null
                }
                val body = resp.body ?: return null
                val total = body.contentLength()
                var written = 0L
                var lastPct = -1
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
                if (total > 0 && written != total) {
                    Log.w(TAG, "download truncated $written/$total url=$url")
                    tmp.delete()
                    return null
                }
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
                main.post { onProgress(100) }
                dst.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed url=$url", e)
            tmp.delete()
            null
        }
    }

    /**
     * 文件名清洗：对方发来的名字**不可信**。
     *
     * `../` 会被拼进路径（路径穿越），`:` `*` 之类在某些分区上直接建不出文件。
     * 统一换下划线，再掐个长度上限。与 [ImChatActivity.copyToRecvDir] 共用同一规则，
     * 否则下载落点和复制落点会不一致，每次点开都要重新下载。
     */
    fun safeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
            .trim('.')
            .take(80)
        return cleaned.ifBlank { "file" }
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

    // ---------------------------------------------------------------- 会话设置（聊天详情页）

    /**
     * 读会话的两个开关：置顶、消息免打扰。
     *
     * 两个接口是分开的（`getConversation` / `getConversationNotificationStatus`），
     * 都回调了才把结果交出去；任一失败就按「关」处理并带上原因，页面据此提示，
     * 不要静默显示成关着（那样用户会以为设置被重置了）。
     */
    fun conversationSwitches(
        isGroup: Boolean,
        peerId: String,
        onResult: (top: Boolean, muted: Boolean, error: String?) -> Unit,
    ) {
        val type = typeOf(isGroup)
        var top = false
        var muted = false
        var error: String? = null
        var pending = 2
        fun done() {
            pending--
            if (pending == 0) main.post { onResult(top, muted, error) }
        }
        RongIMClient.getInstance().getConversation(
            type, peerId,
            object : RongIMClient.ResultCallback<Conversation>() {
                override fun onSuccess(conversation: Conversation?) {
                    top = conversation?.isTop ?: false
                    done()
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    error = e?.message ?: "读取会话设置失败"
                    done()
                }
            }
        )
        RongIMClient.getInstance().getConversationNotificationStatus(
            type, peerId,
            object : RongIMClient.ResultCallback<Conversation.ConversationNotificationStatus>() {
                override fun onSuccess(status: Conversation.ConversationNotificationStatus?) {
                    muted = status == Conversation.ConversationNotificationStatus.DO_NOT_DISTURB
                    done()
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    error = e?.message ?: "读取免打扰状态失败"
                    done()
                }
            }
        )
    }

    /** 会话置顶（融云会把它同步到服务端，换设备也在） */
    fun setConversationTop(isGroup: Boolean, peerId: String, top: Boolean, onDone: (Boolean) -> Unit) {
        RongIMClient.getInstance().setConversationToTop(
            typeOf(isGroup), peerId, top,
            object : RongIMClient.ResultCallback<Boolean>() {
                override fun onSuccess(ok: Boolean?) {
                    if (ok == true) refreshConversations()
                    main.post { onDone(ok == true) }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "setConversationToTop failed ${e?.value}")
                    main.post { onDone(false) }
                }
            }
        )
    }

    /** 消息免打扰：只影响通知，不影响未读与消息本身 */
    fun setConversationMuted(
        isGroup: Boolean,
        peerId: String,
        muted: Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        val status = if (muted) {
            Conversation.ConversationNotificationStatus.DO_NOT_DISTURB
        } else {
            Conversation.ConversationNotificationStatus.NOTIFY
        }
        RongIMClient.getInstance().setConversationNotificationStatus(
            typeOf(isGroup), peerId, status,
            object : RongIMClient.ResultCallback<Conversation.ConversationNotificationStatus>() {
                override fun onSuccess(s: Conversation.ConversationNotificationStatus?) {
                    main.post { onDone(s != null) }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "setConversationNotificationStatus failed ${e?.value}")
                    main.post { onDone(false) }
                }
            }
        )
    }

    /**
     * 清除聊天记录。
     *
     * 只清这个会话的本地消息（`clearMessages`），**不动**服务端漫游 —— 与官方行为一致。
     * 清完必须把内存里那条 `threads` 也清掉再通知，否则聊天页 `render()` 还是旧数据
     * （它读的是内存缓存，不会自己去查库）。
     */
    fun clearConversation(isGroup: Boolean, peerId: String, onDone: (Boolean) -> Unit) {
        RongIMClient.getInstance().clearMessages(
            typeOf(isGroup), peerId,
            object : RongIMClient.ResultCallback<Boolean>() {
                override fun onSuccess(ok: Boolean?) {
                    val key = threadKey(peerId, isGroup)
                    main.post {
                        threads.remove(key)
                        notifyChanged()
                        onDone(ok == true)
                        refreshConversations()
                    }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "clearMessages failed ${e?.value}")
                    main.post { onDone(false) }
                }
            }
        )
    }

    /**
     * 按类型取这个会话的历史消息（文件 / 图片格子点进去用）。
     *
     * 用 SDK 的 `objectName` 过载，别在本地全量拉一遍再过滤：会话长了以后
     * 「最近 100 条」里可能一条文件都没有。
     *
     * @param objectName `RC:FileMsg` 或 `RC:ImgMsg`
     */
    fun loadMessagesOfType(
        isGroup: Boolean,
        peerId: String,
        objectName: String,
        limit: Int = 100,
        onResult: (List<ImMessage>) -> Unit,
    ) {
        RongIMClient.getInstance().getHistoryMessages(
            typeOf(isGroup), peerId, objectName, 0, limit,
            object : RongIMClient.ResultCallback<List<Message>>() {
                override fun onSuccess(messages: List<Message>?) {
                    val list = messages.orEmpty().mapNotNull { toImMessage(it) }
                        .sortedByDescending { it.time }
                    main.post { onResult(list) }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "getHistoryMessages($objectName) failed ${e?.value}")
                    main.post { onResult(emptyList()) }
                }
            }
        )
    }

    /**
     * 会话内搜索（文本关键词）。
     *
     * 融云自己按关键词查库，比自己遍历历史稳（历史可能很长、还可能没全在本地）。
     * 结果按时间倒序给出去。
     */
    fun searchInConversation(
        isGroup: Boolean,
        peerId: String,
        keyword: String,
        limit: Int = 50,
        onResult: (List<ImMessage>) -> Unit,
    ) {
        val key = keyword.trim()
        if (key.isEmpty()) {
            main.post { onResult(emptyList()) }
            return
        }
        RongIMClient.getInstance().searchMessages(
            typeOf(isGroup), peerId, key, limit, 0L,
            object : RongIMClient.ResultCallback<List<Message>>() {
                override fun onSuccess(messages: List<Message>?) {
                    val list = messages.orEmpty().mapNotNull { toImMessage(it) }
                        .sortedByDescending { it.time }
                    main.post { onResult(list) }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "searchMessages failed ${e?.value}")
                    main.post { onResult(emptyList()) }
                }
            }
        )
    }

    private fun typeOf(isGroup: Boolean): Conversation.ConversationType =
        if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE

    /**
     * 调试：把某个会话最近若干条消息的关键字段打进 logcat（只在 debug 包生效）。
     *
     * 2026-11-15 加。起因是「文件消息点开提示下载失败」，而 `downloadFile` 的几条失败分支里
     * 有一条**不打日志**（`getMessageByUid` 回 null），光看 logcat 分不清是「找不到消息」
     * 还是「SDK 下载失败」。这里直接把 uid / mediaUrl / localPath 打出来，一次就能定位。
     *
     * 用法（包名与 receiver 见 [ImDebugReceiver]）：
     * ```
     * adb shell run-as com.lanhang.lite.im am broadcast --user 0 \
     *   -n com.lanhang.lite.im/com.lanhang.lite.im.ImDebugReceiver \
     *   -a com.lanhang.lite.im.DEBUG_DUMP_CHAT --es peer <targetId>
     * ```
     */
    fun dumpConversation(peerId: String, isGroup: Boolean = false) {
        val type = if (isGroup) Conversation.ConversationType.GROUP else Conversation.ConversationType.PRIVATE
        RongIMClient.getInstance().getLatestMessages(
            type, peerId, 60,
            object : RongIMClient.ResultCallback<List<Message>>() {
                override fun onSuccess(messages: List<Message>?) {
                    Log.i(TAG, "dump peer=${peerId.take(8)} count=${messages?.size ?: 0}")
                    messages.orEmpty().forEach { m ->
                        val c = m.content
                        when (c) {
                            is FileMessage -> {
                                val local = c.localPath?.path
                                Log.i(
                                    TAG,
                                    "dump FILE uid=${m.uId} dir=${m.messageDirection} name=${c.name} " +
                                        "size=${c.size} type=${c.type} media=${c.mediaUrl} " +
                                        "local=$local exists=${local != null && File(local).isFile} " +
                                        "received=${m.receivedStatus}"
                                )
                            }
                            is ImageMessage -> Log.i(
                                TAG,
                                "dump IMG uid=${m.uId} dir=${m.messageDirection} " +
                                    "media=${c.mediaUrl} thum=${c.thumUri} local=${c.localPath?.path}"
                            )
                            else -> Log.i(TAG, "dump ${c?.javaClass?.simpleName} uid=${m.uId}")
                        }
                    }
                }

                override fun onError(e: RongIMClient.ErrorCode?) {
                    Log.w(TAG, "dump failed peer=${peerId.take(8)} code=${e?.value}")
                }
            }
        )
    }

    /**
     * 文件名后缀（`报告.pdf` → `pdf`）。
     *
     * 只接受 1–8 位字母数字：有的文件名带点（`v1.2`）或没后缀，
     * 那种情况宁可为空（官方按空处理成通用图标），也不要把 `2` 当后缀发出去。
     */
    private fun suffixOf(name: String): String {
        val suffix = name.substringAfterLast('.', "").lowercase(java.util.Locale.US)
        return suffix.takeIf { it.isNotEmpty() && it.length <= 8 && it.all { c -> c.isLetterOrDigit() } }
            .orEmpty()
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
     * SDK 不会替视频生成缩略图（拿不到封面时它才会退回去用 `ThumbnailUtils` 自己补一帧）。
     *
     * 🔴 **必须用两参 `obtain(video, durationSec)`**（aar 字节码核对过）：
     * - 两参版内部走 `SightMessage(File, Uri, int)`，会把 `name` / `size` 按文件填上；
     * - 三参版 `obtain(thumb, local, duration)` 只设 `thumbUri` + `localPath` + `duration`，
     *   **不填 name/size** → 服务端判 `PARAMETER_ERROR`，视频发不出去。
     * 填完再单独把封面挂上去，两件事不能合在一个调用里。
     *
     * 🔴 **封面绝不能退回「封面 = 视频本身」**：发送链路会把 `thumbUri` 的字节整个 base64
     * 塞进消息体，而消息体上限只有 128KB —— 那等于把整段视频塞进去，必爆
     * `RC_MSG_SIZE_OUT_OF_LIMIT`。拿不到合规封面就不设，让 SDK 自己补。
     */
    fun sendVideo(
        peerId: String,
        isGroup: Boolean,
        video: android.net.Uri,
        thumb: android.net.Uri?,
        durationSec: Int,
    ) {
        val content = SightMessage.obtain(video, durationSec) ?: run {
            // 只有「不是 file:// / 文件不在盘上」才会走到这里；真发生也别硬发一条残缺消息
            Log.w(TAG, "sendVideo obtain failed uri=$video")
            error = text(R.string.im_err_send_failed, text(R.string.im_unknown_error))
            notifyChanged()
            return
        }
        // 缓存文件名带 13 位时间戳前缀（防同名覆盖，见 `ImChatActivity.copyToCache`），
        // 发到对端的 name 要还原成原始文件名 —— 对方保存视频时用的就是它
        content.name = stripCachePrefix(content.name)
        val cover = thumb?.let { ImVideoCover.ensureSmall(it) }
        cover?.let { content.thumbUri = it }
        sendMedia(peerId, isGroup, content, push = text(R.string.im_push_video)) { key ->
            ImMessage(
                key = key,
                text = "",
                mine = true,
                time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING,
                senderId = Session.userId,
                kind = ImMessage.KIND_VIDEO,
                // 封面只认 thumb：回落到视频文件会让 coil 去解 mp4（解不出，显示成空白）
                imageUrl = cover?.toString(),
                duration = durationSec,
                mediaUrl = video.toString(),
            )
        }
    }

    /**
     * 去掉 [ImChatActivity.copyToCache] 给缓存文件加的 `<13 位时间戳>_` 前缀。
     *
     * 只在形态完全吻合（13 个数字 + `_` + 还剩至少 1 个字符）时才截，
     * 免得把原始文件名里本来就有的数字前缀误伤掉。
     */
    private fun stripCachePrefix(name: String?): String? {
        val n = name ?: return null
        if (n.length < 15 || n[13] != '_') return n
        if (!n.take(13).all { it.isDigit() }) return n
        return n.substring(14)
    }

    /**
     * 发语音（`RC:VcMsg`，普通音质）。
     *
     * 🔴 **必须走 `sendPlain`（`sendMessage`），不能走 `sendMedia`**：`VoiceMessage` 继承的是
     * `MessageContent`，没有媒体上传环节 —— 它的 `VoiceMessageHandler.encodeMessage` 自己把
     * 文件读成 base64 塞进消息体。走 `sendMediaMessage` 只会白等一个不存在的上传（官方
     * `AudioRecordManager:660` 也是 `sendMessage`）。高清语音 `RC:HQVCMsg` 才是
     * `MediaMessageContent`、才走 `sendMediaMessage`，这一版先不做发送。
     *
     * @param file 录音落盘的 `.amr`（调用方保证在盘上，`encodeMessage` 要按 uri 读字节）
     * @param durationSec 秒；界面按它显示时长
     */
    fun sendVoice(peerId: String, isGroup: Boolean, file: java.io.File, durationSec: Int) {
        val content = VoiceMessage.obtain(android.net.Uri.fromFile(file), durationSec)
        sendPlain(peerId, isGroup, content, push = text(R.string.im_push_voice)) { key ->
            ImMessage(
                key = key,
                text = "",
                mine = true,
                time = System.currentTimeMillis(),
                state = ImMessage.STATE_SENDING,
                senderId = Session.userId,
                kind = ImMessage.KIND_VOICE,
                duration = durationSec,
                mediaUrl = "file://${file.absolutePath}",
            )
        }
    }

    /** 不走上传通道的消息（文本 / 位置 / 语音）：`sendMessage` 与 `sendLocationMessage` 长一个样 */
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
                markState(peerId, isGroup, key, ImMessage.STATE_FAILED, describeSendError(e))
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
                    markState(peerId, isGroup, key, ImMessage.STATE_FAILED, describeSendError(e))
                    error = text(R.string.im_err_send_failed, e?.name ?: text(R.string.im_unknown_error))
                }
            }
        )
    }


    /**
     * 建群成功后的本机登记（学校 `POST /groups` 已经把群建好了，这里只让本机认得它）。
     *
     * 为什么必须有这一步：融云的会话列表是**本地 sqlite**，新群在里面既没有会话记录、
     * 也没有群资料；而 `syncGroup` 只有在已连接时才会把群名写进本地库。两件事都做了，
     * 聊天页标题和列表里的群名才不会是空的（与 `joinGroup` 那个坑同源）。
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

    private fun markState(
        peerId: String,
        isGroup: Boolean,
        key: String,
        state: Int,
        errorText: String? = null,
    ) {
        main.post {
            val list = threads[threadKey(peerId, isGroup)] ?: return@post
            val index = list.indexOfFirst { it.key == key }
            if (index >= 0) list[index] = list[index].copy(state = state, errorText = errorText)
            notifyChanged()
        }
    }

    /**
     * 把 SDK 的错误码翻成人话；认不出来的返回 null（界面退回通用的「发送失败」）。
     *
     * 只翻译**我们真的知道怎么回事**的那一个：`RC_MSG_SIZE_OUT_OF_LIMIT` 是消息体超过 128KB，
     * 在我们这儿几乎只可能是视频封面没压住（见 `ImVideoCover`），不提示的话用户只会反复重发。
     */
    private fun describeSendError(e: RongIMClient.ErrorCode?): String? = when (e?.name) {
        "RC_MSG_SIZE_OUT_OF_LIMIT", "MSG_SIZE_OUT_OF_LIMIT" ->
            text(R.string.im_err_msg_too_large)
        else -> null
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
