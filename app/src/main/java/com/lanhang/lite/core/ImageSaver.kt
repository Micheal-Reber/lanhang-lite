package com.lanhang.lite.core

import android.content.Context
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lanhang.lite.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把一张图保存到相册（工作圈 / 私信的全屏预览「保存」用）。
 *
 * 分两条路，原因只有一个：**Android 10 之前写公共目录需要 `WRITE_EXTERNAL_STORAGE` 运行时权限**。
 * - Q（29）及以上：MediaStore + `RELATIVE_PATH`，**不需要任何权限**，写完直接进系统相册
 *   （`IS_PENDING` 先占位再落盘，避免扫描到半张图）
 * - Q 以下：交给调用方走 SAF（`CreateDocument`），用户自己选位置 —— 比为了 2% 的老设备
 *   申请一个已经被 Google 废弃的存储权限更划算，也不用在两个 flavor 之间处理清单合并冲突
 *
 * 图片本身用 `ApiClient.http` 下载（工作圈图片在媒体服务器上，公网可读；带不带 Cookie 都不影响）。
 */
object ImageSaver {

    /** 下载完、还没落盘的一张图 */
    class Prepared(
        val bytes: ByteArray,
        val mime: String,
        val name: String,
    ) {
        /** 落盘用的完整文件名（含扩展名） */
        val fileName: String get() = "$name.$ext"

        val ext: String
            get() = when (mime.lowercase(Locale.US)) {
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                else -> "jpg"
            }
    }

    /** 当前系统是否支持「无需权限直接存相册」 */
    val canSaveToGallery: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * 下载并识别类型（**必须在 IO 线程调用**）。
     *
     * 类型不按 URL 后缀猜：工作圈的图片地址常常带查询串、或者后缀和真实格式不一致，
     * 用 `BitmapFactory` 只读文件头（`inJustDecodeBounds`，不解码像素）拿到的是真实 MIME。
     */
    suspend fun prepare(url: String): Prepared? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            val request = okhttp3.Request.Builder().url(url).get().build()
            ApiClient.http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.bytes()
            }
        }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null

        val mime = runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outMimeType
        }.getOrNull()?.takeIf { it.startsWith("image/") } ?: return@withContext null

        // 文件名带时间：连续保存同一张不会被系统判成「已存在」而变成 xxx (1).jpg
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        Prepared(bytes, mime, "lanhang_$stamp")
    }

    /**
     * 存进系统相册（Q+）。成功返回 true。
     *
     * 目录用 `Pictures/蓝航Lite`：直接丢 Pictures 根目录会和用户自己的照片混在一起，
     * 丢 DCIM 又会被当成相机拍的。`IS_PENDING` 必须复位成 0，否则文件在相册里永远不可见。
     */
    suspend fun saveToGallery(context: Context, prepared: Prepared): Boolean =
        withContext(Dispatchers.IO) {
            if (!canSaveToGallery) return@withContext false
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, prepared.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, prepared.mime)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/LanhangLite"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: return@withContext false

            val written = runCatching {
                resolver.openOutputStream(uri)?.use { it.write(prepared.bytes) } != null
            }.getOrDefault(false)

            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext false
            }
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            true
        }

    /** 写入 SAF 选定的位置（Q 以下）。成功返回 true */
    suspend fun writeTo(context: Context, target: Uri, prepared: Prepared): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(target)?.use { it.write(prepared.bytes) } != null
            }.getOrDefault(false)
        }
}
