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
 * 有效期给了 30 天：过期就不用了，避免显示半年前已经下架的应用。
 */
object OaCache {

    private const val FILE = "oa_apps.json"
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private var file: File? = null

    private data class Snapshot(
        val savedAt: Long = 0,
        val apps: OaAppsAll? = null,
        val favorites: List<String> = emptyList()
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

    /** 读快照；没有、损坏、或过期都返回 null */
    fun load(): Pair<OaAppsAll, List<String>>? {
        val f = file ?: return null
        if (!f.exists()) return null
        return runCatching {
            val snapshot = ApiClient.gson.fromJson(
                f.readText(),
                object : TypeToken<Snapshot>() {}.type
            ) as? Snapshot ?: return null
            if (System.currentTimeMillis() - snapshot.savedAt > MAX_AGE_MS) return null
            val apps = snapshot.apps ?: return null
            apps to snapshot.favorites
        }.getOrNull()
    }

    fun clear() {
        runCatching { file?.delete() }
    }
}
