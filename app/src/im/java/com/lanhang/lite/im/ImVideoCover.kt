package com.lanhang.lite.im

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 视频封面（`SightMessage.thumbUri`）的尺寸守门员。
 *
 * 🔴 **为什么必须有**：融云发送链路里 `SightMessageHandler.encodeMessage` 会把 `thumbUri`
 * 指向的文件**原样 `file2byte` + base64 塞进消息体**（aar 字节码核对过，没有任何缩放环节），
 * 而服务端对**整条消息体**的上限是 128KB —— 一帧 1080p 的 JPEG 轻松 200~600KB，
 * base64 再膨胀 4/3，必爆 `RC_MSG_SIZE_OUT_OF_LIMIT`，表现就是「视频发不出去」。
 * 官方客户端是把封面预先压过的，我们自己抽帧就必须自己压。
 *
 * 两个硬指标（参考项目同款结论）：
 * - 最长边 ≤ [MAX_EDGE_PX]（360px，聊天里封面最多显示到这个宽度）；
 * - 文件 ≤ [MAX_BYTES]（48KB，给 128KB 的消息体留足 JSON 结构与 base64 膨胀的余量）。
 *
 * 封面拿不到时**不设 `thumbUri`**（而不是退回「封面 = 视频文件」）：SDK 内部会用
 * `ThumbnailUtils.createVideoThumbnail` + 质量 30 自己补一帧，那条路是安全的；
 * 把视频本体当封面则必定把整段视频 base64 进消息体。
 */
internal object ImVideoCover {

    private const val TAG = "LanhangIM"

    /** 封面最长边上限（px） */
    const val MAX_EDGE_PX = 360

    /** 封面文件上限（字节） */
    const val MAX_BYTES = 48L * 1024

    /** 逐级尝试的最长边：先保清晰度，实在压不下再降分辨率 */
    private val EDGES = intArrayOf(360, 240)

    /** JPEG 质量阶梯 */
    private val QUALITIES = intArrayOf(70, 55, 40, 30)

    /**
     * 把 [src] 压成合规封面写进 [out]，返回落盘文件；失败返回 null。
     *
     * 先在每个分辨率档位上走质量阶梯，第一个 ≤ [MAX_BYTES] 的就用；
     * 全都超（360px 上理论上不会发生）就写最小的那一份 —— **不要因此放弃整条视频**。
     */
    fun write(src: Bitmap, out: File): File? {
        var best: ByteArray? = null
        for (edge in EDGES) {
            val bmp = scale(src, edge)
            try {
                for (quality in QUALITIES) {
                    val bytes = jpeg(bmp, quality) ?: continue
                    if (bytes.size <= MAX_BYTES) return writeBytes(out, bytes)
                    if (best == null || bytes.size < best.size) best = bytes
                }
            } finally {
                if (bmp !== src) bmp.recycle()
            }
        }
        return best?.let { writeBytes(out, it) }
    }

    /**
     * 抽帧失败时的占位封面（灰底 + 播放三角）。
     *
     * 有封面才不会让对端列表里出现一块空白；纯灰块又容易被当成「图裂了」，
     * 所以画一个不喧宾夺主的三角。
     */
    fun placeholder(out: File): File? = runCatching {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(0x3C, 0x40, 0x43))
            val a = size * 0.20f
            val cx = size / 2f
            val cy = size / 2f
            val triangle = Path().apply {
                moveTo(cx + a * 0.85f, cy)
                lineTo(cx - a * 0.60f, cy - a)
                lineTo(cx - a * 0.60f, cy + a)
                close()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(170, 0xFF, 0xFF, 0xFF)
                style = Paint.Style.FILL
            }
            canvas.drawPath(triangle, paint)
            write(bmp, out)
        } finally {
            bmp.recycle()
        }
    }.getOrNull()

    /**
     * 已有封面的兜底校验：超限就重压一遍，返回可直接交给 SDK 的 Uri。
     *
     * - 合规 → 原样返回；
     * - 超限但解得开 → 重压后返回新文件；
     * - 解不开 → 原样返回（总比没有封面强，SDK 侧还有兜底）；
     * - 文件根本不在盘上 → null（调用方不设 `thumbUri`，交给 SDK 自己生成）。
     *
     * 只处理 `file://`：别的 scheme（content:// 之类）我们不碰，交给 SDK 按自己的路子走。
     */
    fun ensureSmall(uri: Uri): Uri? {
        if (uri.scheme != "file") return uri
        val file = File(uri.path ?: return null)
        if (!file.isFile) return null
        if (file.length() <= MAX_BYTES) return uri
        val decoded = decodeScaled(file) ?: return uri
        val out = File(file.parentFile, "cover_${System.currentTimeMillis()}.jpg")
        val written = try {
            write(decoded, out)
        } finally {
            decoded.recycle()
        }
        return written?.let(Uri::fromFile)
    }

    /** 按 [MAX_EDGE_PX] 预采样解码，避免为了判断尺寸先把原图整个解进内存 */
    private fun decodeScaled(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun scale(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, nw, nh, true) }.getOrDefault(src)
    }

    private fun jpeg(bmp: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, os)
            os.toByteArray()
        }
    }.getOrNull()

    private fun writeBytes(out: File, bytes: ByteArray): File? = runCatching {
        out.outputStream().use { it.write(bytes) }
        out.takeIf { it.length() > 0 }
    }.getOrNull().also { ok ->
        if (ok == null) Log.w(TAG, "cover write failed path=${out.absolutePath}")
    }
}
