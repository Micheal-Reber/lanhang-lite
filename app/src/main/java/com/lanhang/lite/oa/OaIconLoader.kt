package com.lanhang.lite.oa

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 「工作」宫格专用的 Logo 加载器。
 *
 * 与全局图片加载器分开有两个目的：
 * - 磁盘缓存放在 filesDir，并独占 16MB，不会被工作圈原图从 LRU 里挤掉；
 * - 不遵循服务端 Cache-Control 的短时效。Logo 地址本身就是缓存键，地址没变就持续使用
 *   本地图，地址变了自然另存一份；重进页面、冷启动和断网都不需要重新下载。
 *
 * 只保留首次加载的网络能力，不是离线只读缓存。缓存仍是可淘汰的兜底资源，不存业务状态。
 */
object OaIconLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .memoryCache {
                    MemoryCache.Builder(context.applicationContext)
                        .maxSizeBytes(MEMORY_CACHE_BYTES)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.applicationContext.filesDir.resolve(DISK_CACHE_DIR))
                        .maxSizeBytes(DISK_CACHE_BYTES)
                        .build()
                }
                .respectCacheHeaders(false)
                .build()
                .also { instance = it }
        }
    }

    /** 40dp 图标解码后很小，4MB 已足够整屏宫格复用 */
    private const val MEMORY_CACHE_BYTES = 4 * 1024 * 1024

    private const val DISK_CACHE_DIR = "oa_icon_cache"
    private const val DISK_CACHE_BYTES = 16L * 1024 * 1024
}
