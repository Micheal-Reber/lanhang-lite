package com.lanhang.lite.core

import android.content.Context

/**
 * 私信入口的注入点（2026-10-31）。
 *
 * 为什么要这一层：私信代码与融云 IMLib 全在 `src/im/` 源集里，只有 im flavor 会编译它。
 * `src/main` 里如果直接 `import com.lanhang.lite.im.ImConversationsActivity`，
 * lite 变体就会编译失败（那个类不存在），或者反过来把 SDK 拖进轻量版。
 *
 * 所以 main 只认这个空壳：lite 版没有实现 → [canOpen] 恒 false，所有入口都不显示；
 * im 版由 `com.lanhang.lite.im.ImInitializer`（ContentProvider，进程启动即执行，
 * 早于任何 Activity）调 [install] 注入。
 *
 * 两个入口：
 * - [open]：私信会话列表（成员录首页那一行）；
 * - [openChat]：与某人直接开聊（个人主页的「发私信」按钮、工作圈点头像进主页后同样可达）。
 */
object ImBridge {

    @Volatile
    private var listLauncher: ((Context) -> Unit)? = null

    @Volatile
    private var chatLauncher: ((Context, String) -> Unit)? = null

    /** 未读总数提供者（im 版注入；lite 版恒 0） */
    @Volatile
    private var unreadProvider: (() -> Int)? = null

    /** 进程启动后静默建连（im 版注入；lite 版为 null，什么都不做） */
    @Volatile
    private var warmUpAction: ((Context) -> Unit)? = null

    fun install(
        list: (Context) -> Unit,
        chat: (Context, String) -> Unit,
        unread: () -> Int,
        warmUp: (Context) -> Unit,
    ) {
        listLauncher = list
        chatLauncher = chat
        unreadProvider = unread
        warmUpAction = warmUp
    }

    /**
     * App 启动时调一次，让私信在后台也保持在线（否则切到后台收不到消息、也就没有系统通知）。
     *
     * 幂等：内部是 `ImClient.ensureStarted`，已连上只刷一次会话列表。未登录时直接返回。
     * lite 版没有注入 → 空操作。
     */
    fun warmUp(context: Context) {
        warmUpAction?.invoke(context.applicationContext)
    }

    /** 未读私信条数（会话列表里的未读之和）；未注入时 0 */
    fun unread(): Int = unreadProvider?.invoke() ?: 0

    /** 入口是否可用（im 版注入成功后为 true） */
    fun canOpen(): Boolean = listLauncher != null

    /** 打开私信列表；没注入时返回 false，调用方自行提示 */
    fun open(context: Context): Boolean {
        val block = listLauncher ?: return false
        block(context)
        return true
    }

    /** 与 [peerId] 开聊；没注入时返回 false */
    fun openChat(context: Context, peerId: String): Boolean {
        if (peerId.isBlank()) return false
        val block = chatLauncher ?: return false
        block(context, peerId)
        return true
    }
}
