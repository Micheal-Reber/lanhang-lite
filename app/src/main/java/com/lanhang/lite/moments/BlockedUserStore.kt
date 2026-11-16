package com.lanhang.lite.moments

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * 工作圈「不看某个人的动态」名单。
 *
 * 与关键词过滤分开存、分开判定，理由是两者的语义不同：
 * - 关键词过滤是「猜」——自动规则，所以内置了失物招领豁免，避免误杀；
 * - 屏蔽是「明说」——用户点名不想看这个人，**不该被任何豁免推翻**。
 *   所以屏蔽判定排在豁免之前（[MomentsFragment.isFiltered] 里先查这里，再查关键词）。
 *
 * 同样与关键词过滤的总开关独立：关掉自动过滤不等于原谅被屏蔽的人。
 *
 * 只影响本机渲染，不请求也不上报服务端 —— 与关键词过滤一个口径。
 *
 * **双写 + 自愈（2026-11-16）**：用户反馈「装一次新版本，名单就得重加一遍」。
 * 实测 `pm install -r` 覆盖安装**不会**动 `shared_prefs`，所以丢的多半是「SP 被外部清掉」
 * （清数据、某些机型的自动清理、或者装的是另一个签名的包而走过卸载重装）。
 * 现在每次保存同时往 `filesDir` 写一份纯文本，启动时若 SP 为空而备份有内容就写回 ——
 * 覆盖安装、清缓存这类情况都能自愈；真要卸载重装（连 filesDir 一起删）仍然救不了，
 * 那种情况只能靠把名单放进云端，不值得为一个本机过滤功能引后端。
 */
object BlockedUserStore {

    /** 名单里的一项。昵称与头像是**快照**：不存的话列表只能显示一串用户 id */
    data class BlockedUser(
        val id: String,
        val name: String,
        val avatar: String?
    )

    private const val FILE = "moments_blocked_users"
    private const val KEY_USERS = "users"

    /** SP 的备份副本（`filesDir`，不会被「清缓存」带走） */
    private const val BACKUP_FILE = "moments_blocked_users.bak"

    private const val TAG = "BlockedUserStore"

    /** 行内字段分隔；用不可见字符，正常昵称里不会出现 */
    private const val FIELD = '\u0001'
    private const val ROW = '\n'

    /** 上限：再多就不是「不看某几个人」而是把时间线整个关掉了 */
    const val MAX_USERS = 200

    private var sp: SharedPreferences? = null
    private var backupDir: File? = null

    /**
     * 解析结果缓存。
     *
     * 列表每 bind 一条都要判一次屏蔽，每次都去解一遍 SP 字符串（几十到几百项）
     * 会在滚动时变成实打实的卡顿；名单只在增删时变，缓存失效点很明确。
     */
    private var cache: List<BlockedUser>? = null
    private var idCache: Set<String>? = null

    fun init(context: Context) {
        val app = context.applicationContext
        sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        backupDir = app.filesDir
        restoreIfNeeded()
        syncBackup()
        invalidate()
        // 启动时留一条证据：下次再报「名单没了」，先看这里读到的条数
        Log.i(TAG, "loaded ${all().size} blocked user(s)")
    }

    private fun invalidate() {
        cache = null
        idCache = null
    }

    /** 名单，按加入顺序 */
    fun all(): List<BlockedUser> {
        cache?.let { return it }
        val raw = sp?.getString(KEY_USERS, "").orEmpty()
        val parsed = if (raw.isBlank()) {
            emptyList()
        } else {
            raw.split(ROW).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split(FIELD)
                val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BlockedUser(
                    id = id,
                    name = parts.getOrNull(1).orEmpty(),
                    avatar = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                )
            }
        }
        cache = parsed
        return parsed
    }

    fun count(): Int = all().size

    fun isBlocked(userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        return userId in ids()
    }

    /**
     * 判定用的快查集合（一次渲染要跑很多条）。
     *
     * 返回的是内部缓存，**不要修改**；需要改名单请走 [block] / [unblock]。
     */
    fun ids(): Set<String> {
        idCache?.let { return it }
        val set = all().mapTo(HashSet()) { it.id }
        idCache = set
        return set
    }

    enum class AddResult { OK, ALREADY, FULL, SELF }

    fun block(user: BlockedUser): AddResult {
        if (user.id.isBlank()) return AddResult.ALREADY
        if (user.id == com.lanhang.lite.session.Session.userId) return AddResult.SELF
        val current = all()
        if (current.any { it.id == user.id }) return AddResult.ALREADY
        if (current.size >= MAX_USERS) return AddResult.FULL
        // 没有昵称时先占位，进设置页时再刷新
        val name = user.name.ifBlank { user.id }
        save(current + user.copy(name = name))
        return AddResult.OK
    }

    fun unblock(userId: String) {
        val current = all()
        if (current.none { it.id == userId }) return
        save(current.filterNot { it.id == userId })
    }

    /** 用最新的昵称/头像覆盖快照（进管理页时异步刷新，防止对方改名后显示旧名） */
    fun refresh(userId: String, name: String?, avatar: String?) {
        if (userId.isBlank()) return
        val current = all()
        val index = current.indexOfFirst { it.id == userId }
        if (index < 0) return
        val old = current[index]
        val updated = old.copy(
            name = name?.takeIf { it.isNotBlank() } ?: old.name,
            avatar = avatar?.takeIf { it.isNotBlank() } ?: old.avatar
        )
        if (updated == old) return
        save(current.toMutableList().also { it[index] = updated })
    }

    private fun save(list: List<BlockedUser>) {
        val raw = list.joinToString(ROW.toString()) { u ->
            listOf(u.id, u.name, u.avatar.orEmpty()).joinToString(FIELD.toString())
        }
        sp?.edit()?.putString(KEY_USERS, raw)?.apply()
        // 同步写备份：只在增删名单时发生，最多 200 条约 20KB，内部存储写一次是亚毫秒级，
        // 换来的是「SP 被外部清掉」时下次启动能自己恢复
        writeBackup(raw)
        invalidate()
    }

    /**
     * SP 为空而备份有内容 → 把备份写回 SP。
     *
     * 只在 SP **空**时恢复：SP 里只要有一条，就说明那就是用户当前的最新意图，
     * 备份（可能更旧）不能盖掉它 —— 否则「取消屏蔽」会被下一次启动撤销。
     */
    private fun restoreIfNeeded() {
        val prefs = sp ?: return
        if (!prefs.getString(KEY_USERS, "").isNullOrBlank()) return
        val raw = runCatching { backupFile()?.takeIf { it.isFile }?.readText() }.getOrNull()
        if (raw.isNullOrBlank()) return
        // 用 commit：紧接着就要读名单去渲染，写盘失败的话这次恢复等于没做
        prefs.edit().putString(KEY_USERS, raw).commit()
        Log.w(TAG, "sp was empty, restored blocked users from backup")
    }

    private fun backupFile(): File? = backupDir?.let { File(it, BACKUP_FILE) }

    /**
     * 让备份跟上当前 SP。
     *
     * 主要是给「升级到这一版之前就已经有名单」的用户补一份：那些设备上 SP 非空、
     * 备份却还不存在，不补的话下一次 SP 被清仍然救不回来。
     */
    private fun syncBackup() {
        val raw = sp?.getString(KEY_USERS, "").orEmpty()
        if (raw.isBlank()) return
        val existing = runCatching { backupFile()?.takeIf { it.isFile }?.readText() }.getOrNull()
        if (existing == raw) return
        writeBackup(raw)
    }

    private fun writeBackup(raw: String) {
        val file = backupFile() ?: return
        runCatching { file.writeText(raw) }
            .onFailure { Log.w(TAG, "backup write failed", it) }
    }
}
