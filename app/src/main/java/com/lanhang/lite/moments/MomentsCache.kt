package com.lanhang.lite.moments

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.lanhang.lite.net.ApiClient
import java.io.File

/**
 * 工作圈第一页的离线快照。
 *
 * 为什么要做：[MomentsFragment] 原来完全没有内容缓存 —— 断网进工作圈就是一片空白 + 报错。
 * 课表、成绩都能离线看，唯独工作圈不行，而「地铁上/信号差时刷两眼」恰恰是高频场景。
 *
 * 只存**第一页**（`PAGE_SIZE` 条），不做全量：时间线是流式内容，存得越多越容易
 * 让用户看到几周前的动态还以为是最新的。存一页的意义是「打开有个东西看」，
 * 不是「离线也能一直翻」。
 *
 * 落盘失败一律静默：缓存是锦上添花，绝不能因为它让正常加载失败。
 */
object MomentsCache {

    private const val FILE = "moments_timeline.json"

    /** 超过这个时长就不用了：一周前的动态拿来当"上次看的内容"已经没有意义 */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private var file: File? = null

    private data class Snapshot(
        val savedAt: Long = 0,
        val feeds: List<MomentFeed> = emptyList()
    )

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
    }

    /** 成功加载第一页后调用 */
    fun save(feeds: List<MomentFeed>) {
        if (feeds.isEmpty()) return
        val f = file ?: return
        runCatching {
            val json = ApiClient.gson.toJson(Snapshot(System.currentTimeMillis(), feeds))
            f.writeText(json)
        }
    }

    /** 读快照；没有、损坏、或过期都返回 null */
    fun load(): List<MomentFeed>? {
        val f = file ?: return null
        if (!f.exists()) return null
        return runCatching {
            val snapshot = ApiClient.gson.fromJson(
                f.readText(),
                object : TypeToken<Snapshot>() {}.type
            ) as? Snapshot ?: return null
            val age = System.currentTimeMillis() - snapshot.savedAt
            if (age > MAX_AGE_MS) return null
            snapshot.feeds.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun clear() {
        runCatching { file?.delete() }
    }
}
