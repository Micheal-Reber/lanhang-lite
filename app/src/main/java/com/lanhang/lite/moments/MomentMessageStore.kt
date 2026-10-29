package com.lanhang.lite.moments

import android.content.Context
import android.content.SharedPreferences

/**
 * 工作圈互动消息的**本地状态**：已读集合 + 已通知基线。
 *
 * 为什么存在本地而不是全靠服务端：
 * - 服务端确实有自己的已读状态（`/message/unread` 只回未读那批），但官方客户端的「已读」
 *   是**清本地库**，对应的写接口在反编译里没找到可验证的形态。本项目不猜写接口——
 *   猜错就是把记录从服务端历史里抹掉，消息直接丢，代价远大于「已读状态只在本地生效」。
 * - 因此：界面上的红点 = `服务端未读 ∩ 本地未读`。用户点了「已读」立刻生效并持久化；
 *   之后即使服务端还把那批算作未读，也不会再显示红点。
 *
 * 已通知集合用来做**去重**：定时检查是轮询，同一条互动会被反复读到，不能每次都弹通知。
 * 首次运行只建基线（把当时的未读全部记为「已通知」），不补弹历史消息——
 * 否则装上这个版本的第一秒就会蹦出十几条陈年通知。
 *
 * 两个集合都按插入顺序保存，超过 [MAX_IDS] 丢最早的，避免 SP 无限膨胀。
 */
object MomentMessageStore {

    private const val FILE = "moment_messages"
    private const val KEY_READ = "read_ids"
    private const val KEY_NOTIFIED = "notified_ids"
    private const val KEY_BASELINE = "notified_baseline"

    /** 单个集合最多保留的条数（一条 id 约 22 字符，500 条约 11KB） */
    private const val MAX_IDS = 500

    private const val SEPARATOR = '\n'

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ 已读

    fun readIds(): Set<String> = load(KEY_READ)

    /** 单条已读/未读切换（长按菜单用） */
    fun markRead(messageId: String?, read: Boolean) {
        val id = messageId?.takeIf { it.isNotBlank() } ?: return
        val ids = load(KEY_READ).toMutableSet()
        val changed = if (read) ids.add(id) else ids.remove(id)
        if (changed) save(KEY_READ, ids)
    }

    /** 「全部已读」：把当前列表里的消息 id 一次性记为已读 */
    fun markAllRead(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val ids = load(KEY_READ).toMutableSet()
        ids.addAll(messageIds)
        save(KEY_READ, ids)
    }

    // ---------------------------------------------------------------- 已通知

    fun notifiedIds(): Set<String> = load(KEY_NOTIFIED)

    fun rememberNotified(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val ids = load(KEY_NOTIFIED).toMutableSet()
        ids.addAll(messageIds)
        save(KEY_NOTIFIED, ids)
    }

    /** 是否已经建立过「已通知」基线；false 表示这是第一次检查 */
    var baselineSeeded: Boolean
        get() = sp.getBoolean(KEY_BASELINE, false)
        set(value) = sp.edit().putBoolean(KEY_BASELINE, value).apply()

    /**
     * 「是否申请过 `POST_NOTIFICATIONS`」**已迁到 [com.lanhang.lite.core.NotifPermission]**。
     *
     * 原来这里有一份按功能存的标记，上课提醒那边又有另一份，于是同一次拒绝在两个页面
     * 得到不同结论（一个还继续弹框、一个直接跳设置页）。现在统一用
     * `NotifPermission.wasAsked()` —— 它存在全局 SP 里，和登录账号无关，也不该被
     * [clear] 清掉（换账号不影响系统权限状态）。
     */

    /** 登出时清空：换账号后旧 id 既没有意义，还会让新账号的消息被误判成「已通知」 */
    fun clear() {
        sp.edit().clear().apply()
    }

    // ------------------------------------------------------------------ 内部

    private fun load(key: String): LinkedHashSet<String> {
        val raw = sp.getString(key, "").orEmpty()
        if (raw.isEmpty()) return LinkedHashSet()
        return LinkedHashSet(raw.split(SEPARATOR).filter { it.isNotBlank() })
    }

    private fun save(key: String, ids: Set<String>) {
        val ordered = if (ids.size > MAX_IDS) {
            // 超出上限：只留最后 MAX_IDS 条（load 出来的顺序就是写入顺序）
            ids.drop(ids.size - MAX_IDS).toCollection(LinkedHashSet())
        } else {
            ids
        }
        sp.edit().putString(key, ordered.joinToString(SEPARATOR.toString())).apply()
    }
}
