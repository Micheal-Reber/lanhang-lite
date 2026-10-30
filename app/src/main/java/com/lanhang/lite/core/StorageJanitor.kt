package com.lanhang.lite.core

import android.content.Context
import java.io.File

/**
 * 本地存储的清理与统计。
 *
 * 分两类，处理方式不同：
 * - **缓存**（`cacheDir`，主要是 Coil 的图片）：随时可删，丢了下次访问会重新下。
 * - **数据**（`filesDir`，课表/成绩/自建课程/离线快照）：这是用户资产，**一律不动**。
 *
 * 唯一会被清的 `filesDir` 内容是几个**调试产物**（`cookies_*.txt`、`moments_last.json`、
 * `diag_prev.log`）和老版本遗留的 `score_*.json` —— 后者与 `grades_*.json` 内容重复，
 * 是文件改名时留下的垃圾。
 */
object StorageJanitor {

    data class Usage(val images: Long, val others: Long) {
        val total: Long get() = images + others
    }

    /** 老版本「成绩」用的文件名前缀，与现在的 `grades_` 内容重复 */
    private const val LEGACY_SCORE_PREFIX = "score_"

    /** 调试产物：可以随便删 */
    private val DUMP_FILES = listOf(
        "cookies_debug.txt",
        "cookies_candidates.txt",
        "moments_last.json",
        "diag_prev.log"
    )

    /** 启动时静默清理，跑在 IO 线程 */
    fun run(context: Context) {
        val files = context.applicationContext.filesDir
        // 旧命名遗留：改名那天起就没人读过它们，白占空间
        files.listFiles()?.forEach { f ->
            if (f.name.startsWith(LEGACY_SCORE_PREFIX)) f.delete()
        }
        // diag_prev 只在崩溃恢复时有意义，留一天足够
        val prev = File(files, "diag_prev.log")
        if (prev.exists() && System.currentTimeMillis() - prev.lastModified() > DAY_MS) {
            prev.delete()
        }
    }

    /** 统计当前占用（IO 线程调用） */
    fun usage(context: Context): Usage {
        val app = context.applicationContext
        val images = sizeOf(File(app.cacheDir, "image_cache"))
        var others = sizeOf(app.cacheDir) - images
        if (others < 0) others = 0
        others += dumpSize(app.filesDir)
        return Usage(images, others)
    }

    /**
     * 清掉缓存，返回释放的字节数（IO 线程调用）。
     *
     * 只删 `cacheDir` 与该删的 dump；`filesDir` 里的课表、成绩、自建课程、
     * 离线快照一个都不碰 —— 那是用户的数据，不是缓存。
     */
    fun clear(context: Context): Long {
        val app = context.applicationContext
        val before = sizeOf(app.cacheDir) + dumpSize(app.filesDir)
        // cacheDir 下的内容（含 Coil 的 Journal）：删空后 Coil 会自建，
        // 不删 cacheDir 本身，避免个别 ROM 上目录被占用时抛异常
        app.cacheDir.listFiles()?.forEach { deleteQuietly(it) }
        DUMP_FILES.forEach { File(app.filesDir, it).delete() }
        val after = sizeOf(app.cacheDir) + dumpSize(app.filesDir)
        return (before - after).coerceAtLeast(0)
    }

    private fun dumpSize(files: File): Long =
        DUMP_FILES.sumOf { File(files, it).let { f -> if (f.exists()) f.length() else 0 } }

    private fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { sizeOf(it) } ?: 0
    }

    private fun deleteQuietly(file: File) {
        runCatching {
            if (file.isDirectory) file.listFiles()?.forEach { deleteQuietly(it) }
            file.delete()
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** `1.2 MB` / `840 KB` */
    fun format(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
