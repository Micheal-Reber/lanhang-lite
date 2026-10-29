package com.lanhang.lite.moments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.gson.JsonObject
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.TextRef
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.net.RceApi
import java.io.ByteArrayOutputStream
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Uploads a selected photo to the same media service used by the official app. */
object MomentImageUploader {
    private const val FALLBACK_MAX_BYTES = 10L * 1024 * 1024

    /**
     * 服务端 `max_file_size` 的单位是 **MB**，不是字节。
     *
     * 依据融云 SDK 自己的换算：`io/rong/imkit/widget/adapter/FileListAdapter` 里
     * `fileSize > getFileMaxSize(ctx) * 1024 * 1024`（且 ≥1024 时按 GB 展示）。
     * 本校 `GET /api/configuration/all` 实测返回 `500`，若当成字节，上限就是 500 字节 ——
     * 压缩到 320px 仍有 10~20KB，于是**任何照片都发不出去**，与图片大小无关。
     */
    private const val BYTES_PER_MB = 1024L * 1024L

    /** 上传前把长边压到这个尺寸，兼顾清晰度与体积 */
    private const val MAX_DIMENSION = 2048

    /** 压缩循环的尺寸下限：再小就没意义了，宁可报错让用户换图 */
    private const val MIN_DIMENSION = 320

    private val uploadHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun upload(context: Context, uri: Uri): MediaItem {
        val config = RceApi.configurationAll().features?.mediaServer
        val token = RceApi.getImageUploadToken()
        val fileName = "moment_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg"
        val maxBytes = serverLimitBytes(config?.maxFileSize)
        val imageFile = File(context.cacheDir, fileName)
        val sourceFile = File(context.cacheDir, "source_$fileName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                sourceFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ApiException(-3, TextRef.Res(R.string.error_image_read))
            compress(context, sourceFile, imageFile, maxBytes)

            val uploadUrl = config?.uploadUrls?.firstOrNull { it.isNotBlank() }
                ?: token.server?.let { "http://$it" }
                ?: throw ApiException(-3, TextRef.Res(R.string.error_image_upload_url))
            val downloadBase = config?.downloadUrls?.firstOrNull { it.isNotBlank() }
                ?: token.domain?.let { "http://$it" }
                ?: throw ApiException(-3, TextRef.Res(R.string.error_image_access_url))

            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "token",
                    token.token ?: throw ApiException(-3, TextRef.Res(R.string.error_image_token_empty))
                )
                .addFormDataPart("key", fileName)
                .addFormDataPart("file", fileName, imageFile.asRequestBody("image/jpeg".toMediaType()))
                .build()
            val request = Request.Builder().url(uploadUrl).post(form).build()
            uploadHttp.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(
                        -3,
                        TextRef.Res(
                            R.string.error_image_upload_failed,
                            response.code,
                            body.take(160)
                        )
                    )
                }
                val uploadResult = runCatching {
                    ApiClient.gson.fromJson(body, JsonObject::class.java)?.getAsJsonObject("rc_url")
                }.getOrNull()
                val uploadedUrl = uploadResult?.get("path")?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path ->
                        if (uploadResult.get("type")?.asInt == 1) path
                        else "${downloadBase.trimEnd('/')}/${path.trimStart('/')}"
                    }
                    ?: "${downloadBase.trimEnd('/')}/$fileName"
                return MediaItem(type = 0, url = uploadedUrl, thumbnail = uploadedUrl, digest = "")
            }
        } finally {
            sourceFile.delete()
            imageFile.delete()
        }
    }

    /**
     * 把服务端的 MB 上限换成字节，并且**不超过本地兜底值**：
     * 本校配的是 500MB，直接照用会让压缩白白放弃画质；10MB 已经远超一条动态的需要。
     */
    private fun serverLimitBytes(maxFileSizeMb: Long?): Long {
        val serverBytes = maxFileSizeMb?.takeIf { it > 0 }?.let { it * BYTES_PER_MB }
        return serverBytes?.coerceAtMost(FALLBACK_MAX_BYTES) ?: FALLBACK_MAX_BYTES
    }

    private fun compress(context: Context, source: File, destination: File, maxBytes: Long) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ApiException(-3, TextRef.Res(R.string.error_image_invalid))
        }
        // 一步采样到长边 ≤ MAX_DIMENSION：最终产物就要这个尺寸，先完整解码 4000px 以上
        // 只会白白吃掉几十 MB 堆内存（1200 万像素 ≈ 48MB ARGB_8888），低端机上直接 OOM
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: throw ApiException(-3, TextRef.Res(R.string.error_image_decode))

        val orientation = ExifInterface(source.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val oriented = if (rotation == 0f) bitmap else {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycle() }
        }

        var current = oriented
        try {
            while (true) {
                var quality = 88
                var bytes: ByteArray
                do {
                    val output = ByteArrayOutputStream()
                    current.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    bytes = output.toByteArray()
                    quality -= 8
                } while (bytes.size > maxBytes && quality >= 56)
                if (bytes.size <= maxBytes) {
                    destination.writeBytes(bytes)
                    return
                }
                val nextWidth = (current.width * 0.8f).toInt()
                val nextHeight = (current.height * 0.8f).toInt()
                if (minOf(nextWidth, nextHeight) < MIN_DIMENSION) {
                    throw ApiException(-3, TextRef.Res(R.string.error_image_too_large))
                }
                val smaller = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                current.recycle()
                current = smaller
            }
        } finally {
            current.recycle()
        }
    }
}
