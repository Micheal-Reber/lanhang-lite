package com.lanhang.lite.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * 更新包下载（对应 Hoshi 的 `AndroidUpdateDownloadManager`）。
 *
 * 用**系统 DownloadManager**：自带通知栏进度、断点、进程被杀也能继续；目标落在
 * `Android/data/<pkg>/files/Download/`，**不需要任何存储权限**。
 *
 * 与 Hoshi 一致的几个细节：
 * - 状态以系统为准，本地记录只存 `downloadId`/地址/校验值
 * - 失败后重试**按上次失败的 url 往后挪一个候选地址**（不是「尝试次数」）
 * - 校验 sha256 通过才算 Downloaded，失败删文件并标 Failed
 * - 下载完不在后台直接拉起安装器（Android 10+ 拦后台启动 Activity），由 UI 触发
 */
object UpdateDownloadManager {

    private const val TAG = "LanhangUpdate"
    private const val MIME_APK = "application/vnd.android.package-archive"

    const val FILE_NAME = "lanhang-update.apk"

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    /**
     * 开始下载，返回 downloadId（失败返回 null）。
     *
     * 目标文件先删：DownloadManager 在目标已存在时直接 `STATUS_FAILED`
     * （`ERROR_FILE_ALREADY_EXISTS`），用户重试一次就卡死在这里。
     */
    fun enqueue(context: Context, update: UpdateCheck.Available): Long? {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return null
        val record = UpdateStore.load(context)
        val previousFailedUrl = record
            ?.takeIf { it.status == UpdateStatus.FAILED && it.matches(update) }
            ?.downloadUrl
        val url = update.asset.urlAfterFailed(previousFailedUrl)

        val target = file(context)
        runCatching { if (target.exists()) target.delete() }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(context.getString(com.lanhang.lite.R.string.update_download_title_apk))
            .setDescription(
                context.getString(com.lanhang.lite.R.string.update_download_title, update.versionName)
            )
            .setMimeType(MIME_APK)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))

        val id = runCatching { manager.enqueue(request) }.getOrNull() ?: return null
        UpdateStore.saveDownloading(context, update, id, url)
        Log.i(TAG, "download enqueued id=$id url=$url")
        return id
    }

    /** 查询当前状态（UI 轮询用）；顺手把校验结果写回记录 */
    suspend fun state(context: Context): DownloadState = withContext(Dispatchers.IO) {
        val record = UpdateStore.load(context) ?: return@withContext DownloadState.Idle
        if (isInstalledAlready(context, record)) {
            UpdateStore.clear(context)
            return@withContext DownloadState.Idle
        }
        when (record.status) {
            UpdateStatus.DOWNLOADED -> {
                val target = file(context)
                if (target.isFile) DownloadState.Ready(record.versionName) else {
                    // 文件被清掉了（清缓存、手动删除）→ 记录作废
                    UpdateStore.clear(context)
                    DownloadState.Idle
                }
            }
            UpdateStatus.FAILED -> DownloadState.Failed(record.failureDetails.orEmpty())
            UpdateStatus.DOWNLOADING -> {
                val id = record.downloadId ?: return@withContext DownloadState.Idle
                val manager = context.getSystemService(DownloadManager::class.java)
                    ?: return@withContext DownloadState.Idle
                val cursor = runCatching {
                    manager.query(DownloadManager.Query().setFilterById(id))
                }.getOrNull() ?: return@withContext DownloadState.Idle
                cursor.use { c ->
                    if (!c.moveToFirst()) {
                        UpdateStore.clear(context)
                        return@withContext DownloadState.Idle
                    }
                    when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            finish(context, record, id)
                        }
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_PAUSED,
                        -> DownloadState.Downloading(progressOf(c))
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            fail(context, record, id, "reason=$reason")
                        }
                        else -> DownloadState.Idle
                    }
                }
            }
            // 只检查到、还没下载
            UpdateStatus.AVAILABLE, UpdateStatus.SKIPPED -> DownloadState.Idle
        }
    }

    /**
     * 下载完成的统一收尾（[UpdateDownloadReceiver] 与轮询都会走到）。
     *
     * 必须校验 sha256：镜像站偶尔返回一个 HTML 错误页（200 状态），只看状态码会把
     * 一个「网页」当 apk 交给安装器。
     */
    suspend fun handleCompletion(context: Context, downloadId: Long) = withContext(Dispatchers.IO) {
        val record = UpdateStore.load(context) ?: return@withContext
        if (record.downloadId != downloadId) return@withContext
        // 迟到的广播：包已经装上去了，这条记录没意义了。不拦的话会走 finish() →
        // validate() 找不到文件（安装时被系统用掉了）→ 标成「下载失败」
        if (isInstalledAlready(context, record)) {
            runCatching { file(context).delete() }
            UpdateStore.clear(context)
            return@withContext
        }
        if (record.status == UpdateStatus.DOWNLOADED) return@withContext
        finish(context, record, downloadId)
    }

    /**
     * 这条记录对应的版本是不是已经装上了（或比当前还旧）。
     *
     * 为什么不能只看文件在不在：用户装完新版本后，下载目录里的 apk 会被系统安装器用掉、
     * 或者被清理逻辑删掉，而本地记录还停在 DOWNLOADED —— 下次进设置页查到文件没了，
     * 就标成「下载失败，请重试」。用户明明已经是最新版，却看到一行红字（2026-11-03 真机实测）。
     * **版本号才是事实**：记录的版本不比当前新，就说明这件事已经完成。
     */
    private fun isInstalledAlready(context: Context, record: UpdateRecord): Boolean {
        val current = UpdateChecker.currentVersionName(context)
        return UpdateApi.compareVersion(record.versionName, current) <= 0
    }

    /** 放弃本次下载（用户改主意 / 换版本） */
    fun cancel(context: Context) {
        val id = UpdateStore.load(context)?.downloadId
        if (id != null) {
            runCatching { context.getSystemService(DownloadManager::class.java)?.remove(id) }
        }
        runCatching { file(context).delete() }
        UpdateStore.clear(context)
    }

    private fun finish(context: Context, record: UpdateRecord, downloadId: Long): DownloadState {
        val target = file(context)
        return try {
            validate(target, record.sha256)
            UpdateStore.markDownloaded(context, downloadId)
            Log.i(TAG, "download finished version=${record.versionName}")
            DownloadState.Ready(record.versionName)
        } catch (e: Exception) {
            runCatching { target.delete() }
            fail(context, record, downloadId, e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun fail(
        context: Context,
        record: UpdateRecord,
        downloadId: Long,
        details: String,
    ): DownloadState {
        runCatching { file(context).delete() }
        UpdateStore.markFailed(context, downloadId, details)
        Log.w(TAG, "download failed version=${record.versionName} $details")
        return DownloadState.Failed(details)
    }

    private fun progressOf(c: android.database.Cursor): Int {
        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        if (total <= 0) return -1
        return ((done * 100) / total).toInt().coerceIn(0, 100)
    }

    private fun validate(file: File, expectedSha256: String?) {
        if (!file.isFile) throw FileNotFoundException(file.absolutePath)
        if (expectedSha256 == null) return
        val actual = sha256(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("sha256 mismatch: expected=$expectedSha256 actual=$actual")
        }
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
