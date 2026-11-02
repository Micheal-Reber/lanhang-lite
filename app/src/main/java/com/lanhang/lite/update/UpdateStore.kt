package com.lanhang.lite.update

import android.content.Context
import com.lanhang.lite.net.ApiClient

/**
 * 更新记录的本地存储（对应 Hoshi 的 `UpdateDownloadStore`）。
 *
 * 与 Hoshi 的差别只在实现手段：它用 DataStore，本项目**不加依赖**，用 SharedPreferences
 * 存一段 JSON（Gson 已是项目依赖）。字段与状态机保持一致，所以上层逻辑可以逐行对照。
 */
object UpdateStore {

    private const val FILE = "lanhang_update"
    private const val KEY_RECORD = "record"
    private const val KEY_LAST_CHECK = "last_check_at"

    /** 自动检查的节流窗口：一天（对应 Hoshi 的 PeriodicWorkRequest 1 天） */
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): UpdateRecord? {
        val raw = sp(context).getString(KEY_RECORD, null) ?: return null
        return runCatching { ApiClient.gson.fromJson(raw, UpdateRecord::class.java) }.getOrNull()
    }

    private fun save(context: Context, record: UpdateRecord) {
        sp(context).edit()
            .putString(KEY_RECORD, ApiClient.gson.toJson(record))
            .apply()
    }

    /** 检查到新版本：只记「有这个版本」，下载 id 与失败原因清掉 */
    fun saveAvailable(context: Context, update: UpdateCheck.Available) {
        save(
            context,
            UpdateRecord(
                versionName = update.versionName,
                releaseUrl = update.releaseUrl,
                assetName = update.asset.name,
                fileName = UpdateDownloadManager.FILE_NAME,
                downloadId = null,
                sha256 = update.asset.sha256,
                downloadUrl = update.asset.downloadUrl,
                fallbackUrls = update.asset.fallbackUrls,
                status = UpdateStatus.AVAILABLE,
            )
        )
    }

    fun saveDownloading(
        context: Context,
        update: UpdateCheck.Available,
        downloadId: Long,
        url: String,
    ) {
        save(
            context,
            UpdateRecord(
                versionName = update.versionName,
                releaseUrl = update.releaseUrl,
                assetName = update.asset.name,
                fileName = UpdateDownloadManager.FILE_NAME,
                downloadId = downloadId,
                sha256 = update.asset.sha256,
                downloadUrl = url,
                fallbackUrls = update.asset.fallbackUrls,
                status = UpdateStatus.DOWNLOADING,
            )
        )
    }

    /** 「跳过此版本」：自动检查时不再提示，直到发布更高的版本 */
    fun skip(context: Context, update: UpdateCheck.Available) {
        save(
            context,
            UpdateRecord(
                versionName = update.versionName,
                releaseUrl = update.releaseUrl,
                assetName = update.asset.name,
                fileName = UpdateDownloadManager.FILE_NAME,
                sha256 = update.asset.sha256,
                downloadUrl = update.asset.downloadUrl,
                fallbackUrls = update.asset.fallbackUrls,
                status = UpdateStatus.SKIPPED,
            )
        )
    }

    fun markDownloaded(context: Context, downloadId: Long) {
        val record = load(context) ?: return
        if (record.downloadId != downloadId) return
        save(context, record.copy(status = UpdateStatus.DOWNLOADED, failureDetails = null))
    }

    fun markFailed(context: Context, downloadId: Long, details: String?) {
        val record = load(context) ?: return
        if (record.downloadId != downloadId) return
        save(context, record.copy(status = UpdateStatus.FAILED, failureDetails = details))
    }

    fun clear(context: Context) {
        sp(context).edit().remove(KEY_RECORD).apply()
    }

    // ---------------------------------------------------------------- 检查节流

    fun lastCheckAt(context: Context): Long = sp(context).getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(context: Context, at: Long = System.currentTimeMillis()) {
        sp(context).edit().putLong(KEY_LAST_CHECK, at).apply()
    }

    /** 距上次检查是否超过一天（对应 Hoshi 的每日周期任务） */
    fun shouldAutoCheck(context: Context): Boolean =
        System.currentTimeMillis() - lastCheckAt(context) > CHECK_INTERVAL_MS
}
