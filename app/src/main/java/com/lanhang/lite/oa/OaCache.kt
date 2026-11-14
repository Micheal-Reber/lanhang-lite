package com.lanhang.lite.oa

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.lanhang.lite.net.ApiClient
import java.io.File

/**
 * 办事大厅应用宫格的离线快照。
 *
 * 应用列表变化极慢（上架 / 下架一学期也未必有一次），断网时显示上次的宫格
 * 比一片「加载失败」有用得多 —— 点进去会因为取不到 Token 而失败，但至少
 * 用户能看到「有这个应用」并知道要连网。
 *
 * 两档时效（2026-11-14 用户反馈「退出重进还是会刷新一遍」）：
 * - **新鲜期 [FRESH_MS]（1 小时）内完全不联网**：重进「工作」Tab 直接用上次的宫格，
 *   不发请求、不重绑列表 —— 这才是用户要的「持久化做好了」；
 * - 新鲜期过后、30 天以内：仍然先渲染缓存，再后台静默刷新替换（内容没变就不重绑，不闪）。
 *
 * 30 天是**可用上限**：过期就不用了，避免显示半年前已经下架的应用。
 * 应用列表本身变化极慢（上架 / 下架一学期也未必有一次），1 小时新鲜期足够覆盖
 * 「退出重进」这种高频动作，又不至于让新上架的应用等太久。
 */
object OaCache {

    private const val FILE = "oa_apps.json"

    /** 可用上限：超过这个年龄的快照直接不用 */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** 新鲜期：这段时间内重进页面完全不联网（见类注释） */
    private const val FRESH_MS = 60L * 60 * 1000

    private var file: File? = null

    private data class Snapshot(
        val savedAt: Long = 0,
        val apps: OaAppsAll? = null,
        val favorites: List<String> = emptyList()
    )

    /** 一份可用的快照；[fresh] = 保存于 [FRESH_MS] 内，此时不必联网 */
    data class Entry(
        val apps: OaAppsAll,
        val favorites: List<String>,
        val fresh: Boolean,
    )

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
    }

    fun save(apps: OaAppsAll, favorites: List<String>) {
        val f = file ?: return
        // 写盘失败一律静默：缓存只是兜底，不能反过来影响正常加载
        runCatching {
            f.writeText(ApiClient.gson.toJson(Snapshot(System.currentTimeMillis(), apps, favorites)))
        }
    }

    /** 读快照；没有、损坏、或超过可用上限都返回 null */
    fun load(): Entry? {
        val f = file ?: return null
        if (!f.exists()) return null
        return runCatching {
            val snapshot = ApiClient.gson.fromJson(
                f.readText(),
                object : TypeToken<Snapshot>() {}.type
            ) as? Snapshot ?: return null
            val age = System.currentTimeMillis() - snapshot.savedAt
            if (age > MAX_AGE_MS) return null
            val apps = snapshot.apps ?: return null
            Entry(apps, snapshot.favorites, fresh = age <= FRESH_MS)
        }.getOrNull()
    }

    fun clear() {
        runCatching { file?.delete() }
    }
}
