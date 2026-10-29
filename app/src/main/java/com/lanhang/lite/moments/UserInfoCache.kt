package com.lanhang.lite.moments

import android.content.Context
import com.lanhang.lite.R
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.net.StaffBrief
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.session.Session
import com.lanhang.lite.settings.Diagnostics
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作圈用户信息缓存：userId → 姓名/头像，批量解析走主 API `POST /staffs/batch`（code 10000）。
 *
 * 注意这里的 `userId` 是**融云 id**（如 `06thVqILTGApfXUX5xECeo`），不是学号；
 * 实测 `staffs/batch` 能直接按它解析，含无效 id 时只跳过不整体报错。
 */
object UserInfoCache {
    private val cache = ConcurrentHashMap<String, StaffBrief>()

    /** 已经为「缓存里没有头像」的记录补过一次的 id，避免列表每次刷新都重打网络 */
    private val retried = ConcurrentHashMap.newKeySet<String>()

    fun get(id: String?): StaffBrief? {
        val key = id?.takeIf { it.isNotBlank() } ?: return null
        cache[key]?.let { return it }
        // 兜底：自己的姓名登录时就有（`Session.name`），不必等网络。
        // 刻意**不写进 cache**——留着让 [resolve] 去取含头像的完整记录。
        return selfBrief(key)
    }

    /**
     * 姓名；解析不到时返回 `null`。
     *
     * 不返回占位文案是有意的：Application context 不受 `LocaleHelper` 影响，
     * 在这里 `getString` 会取到**系统语言**（App 内设成中文、系统是英文时就会出现
     * 中文界面里冒出 "User"）。占位词由 UI 层用 View 的 context 决定。
     */
    fun nameOf(id: String?): String? = get(id)?.name?.takeIf { it.isNotBlank() }

    /** UI 层拿不到名字时的统一兜底文案 */
    fun fallbackName(context: Context): String =
        context.getString(R.string.moments_anonymous)

    /**
     * 回填一条已知的完整资料（个人主页用 `GET /staffs/{id}` 拿到的那份）。
     * 这样工作圈列表再展示同一个人时不必为它单独 batch 一次。
     */
    fun remember(brief: StaffBrief) {
        if (brief.name.isNullOrBlank() && brief.bestPortrait.isBlank()) return
        put(brief)
    }

    /**
     * 写入缓存，**不允许「没头像」的记录顶掉「有头像」的记录**。
     *
     * `GET /staffs/{id}` 对部分账号两档画像字段都是空串（2026-10-26 真机实测 `某同学`：
     * `portrait_url=""`、`portrait_big_url=""`），而同一时刻 `/staffs/batch` 对同一个人
     * 两档都有。资料页把这份空的记录 `remember` 进来后，工作圈列表、设置页账号卡会跟着
     * 一起变成空白头像——覆盖时保留已有的地址，只更新其它字段。
     */
    private fun put(brief: StaffBrief) {
        val id = brief.id?.takeIf { it.isNotBlank() } ?: return
        val old = cache[id]
        cache[id] = if (old != null && old.bestPortrait.isNotBlank() && brief.bestPortrait.isBlank()) {
            brief.copy(portrait_url = old.portrait_url, portrait_big_url = old.portrait_big_url)
        } else {
            brief
        }
    }

    suspend fun resolve(ids: Collection<String>) {
        val missing = ids.filter { id ->
            if (id.isEmpty()) return@filter false
            val hit = cache[id] ?: return@filter true
            // 缓存里有记录但没头像（典型是 `/staffs/{id}` 那份空串版本）→ 允许补一次；
            // 用 [retried] 兜住，避免每次刷新列表都为同一个没照片的人重打一次网络
            hit.bestPortrait.isBlank() && retried.add(id)
        }.distinct()
        if (missing.isEmpty()) return
        try {
            withContext(Dispatchers.IO) {
                val rows = RceApi.staffsBatch(missing)?.data.orEmpty()
                rows.forEach { brief -> put(brief) }
                // 批量接口没把人解析全（含返回空 data）必须留痕：以前这里静默，
                // 表现是整屏头像空白却一条日志都没有，只能靠翻 coil 缓存反推
                if (rows.size < missing.size) {
                    Diagnostics.record(
                        AppContext.instance,
                        "UserInfoCache",
                        IllegalStateException(
                            "batch 不完整 请求=${missing.size} 返回=${rows.size} " +
                                "带头像=${rows.count { it.bestPortrait.isNotBlank() }}"
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // 以前这里静默吞掉，一旦失败整屏都是占位名且无从排查
            if (e.isCancellation) throw e
            Diagnostics.record(AppContext.instance, "UserInfoCache", e)
        }
    }

    /**
     * 自己的兜底资料：姓名登录时就有，头像用登录/设置页缓存的地址（`Session.avatarUrl`）。
     *
     * 有了头像就不用等 `/staffs/batch`：工作圈自己的那条动态、个人动态列表头一格
     * 都能立刻出图。先不动 cache，留给 [resolve] 去补完整字段。
     */
    private fun selfBrief(key: String): StaffBrief? {
        if (key != Session.userId) return null
        val name = Session.name
        val avatar = Session.avatarUrl.takeIf { it.isNotBlank() }
        if (name.isBlank() && avatar == null) return null
        return StaffBrief(
            id = key,
            name = name.takeIf { it.isNotBlank() },
            portrait_url = avatar,
            portrait_big_url = avatar,
        )
    }
}
