package com.lanhang.lite.moments

import android.content.Context
import android.content.SharedPreferences

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

    /** 行内字段分隔；用不可见字符，正常昵称里不会出现 */
    private const val FIELD = '\u0001'
    private const val ROW = '\n'

    /** 上限：再多就不是「不看某几个人」而是把时间线整个关掉了 */
    const val MAX_USERS = 200

    private var sp: SharedPreferences? = null

    /**
     * 解析结果缓存。
     *
     * 列表每 bind 一条都要判一次屏蔽，每次都去解一遍 SP 字符串（几十到几百项）
     * 会在滚动时变成实打实的卡顿；名单只在增删时变，缓存失效点很明确。
     */
    private var cache: List<BlockedUser>? = null
    private var idCache: Set<String>? = null

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        invalidate()
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
        invalidate()
    }
}
