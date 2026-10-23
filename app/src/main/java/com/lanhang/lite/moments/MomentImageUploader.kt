package com.lanhang.lite.moments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.gson.JsonObject
import com.lanhang.lite.net.ApiClient
import com.lanhang.lite.net.RceApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Uploads a selected photo to the same media service used by the official app. */
object MomentImageUploader {
    private const val FALLBACK_MAX_BYTES = 10L * 1024 * 1024
    private const val MAX_DIMENSION = 2048
    private val uploadHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun upload(context: Context, uri: Uri): MediaItem {
        val config = RceApi.configurationAll().features?.mediaServer
        val token = RceApi.getImageUploadToken()
        val fileName = "moment_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg"
        val maxBytes = config?.maxFileSize?.takeIf { it > 0 } ?: FALLBACK_MAX_BYTES
        val imageFile = File(context.cacheDir, fileName)
        val sourceFile = File(context.cacheDir, "source_$fileName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                sourceFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            compress(sourceFile, imageFile, maxBytes)

            val uploadUrl = config?.uploadUrls?.firstOrNull { it.isNotBlank() }
                ?: token.server?.let { "http://$it" }
                ?: error("服务器没有提供图片上传地址")
            val downloadBase = config?.downloadUrls?.firstOrNull { it.isNotBlank() }
                ?: token.domain?.let { "http://$it" }
                ?: error("服务器没有提供图片访问地址")

            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("token", token.token ?: error("图片上传令牌为空"))
                .addFormDataPart("key", fileName)
                .addFormDataPart("file", fileName, imageFile.asRequestBody("image/jpeg".toMediaType()))
                .build()
            val request = Request.Builder().url(uploadUrl).post(form).build()
            uploadHttp.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("图片上传失败 (${response.code}): ${body.take(160)}")
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

    private fun compress(source: File, destination: File, maxBytes: Long) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是有效图片" }
        val sample = generateSequence(1) { it * 2 }
            .takeWhile { maxOf(bounds.outWidth, bounds.outHeight) / it > MAX_DIMENSION }
            .lastOrNull() ?: 1
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: error("图片解码失败")

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
                if (minOf(nextWidth, nextHeight) < 320) error("图片超过服务器大小限制")
                val smaller = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
                current.recycle()
                current = smaller
            }
        } finally {
            current.recycle()
        }
    }
}
