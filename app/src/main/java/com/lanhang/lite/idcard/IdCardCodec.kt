package com.lanhang.lite.idcard

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 身份码的两种图形编码：二维码（承载动态 token）与 CODE_128 条形码（承载学号）。
 *
 * 编码本身用 ZXing core（用户批准的依赖破例，见 AGENTS.md §5）：
 * 手写 Reed-Solomon 与掩码选择一旦有偏差就是「扫不出来」这种没法自查的故障，
 * 这类规范数值不值得自己重写。
 *
 * 两个 writer 都会把模块尺寸取整后居中留白，所以传入任意像素尺寸都不会出现半像素模块
 * （半像素模块正是「屏幕上看得到、扫码枪读不出」的典型原因）。
 */
object IdCardCodec {

    /** 二维码静区模块数，规范要求 4；给 2 是为了在 240dp 的小图上把模块做大一点 */
    private const val QR_MARGIN_MODULES = 2

    /**
     * 生成二维码位图。
     *
     * @param sizePx 目标边长（像素）
     * @param highContrast 纠错级别 L/M；L 能承载的数据量最大，
     *   身份码 token 约 350 字节，用 L 可以让版本和模块数尽量小
     */
    fun qr(text: String, sizePx: Int, highContrast: Boolean = false): Bitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to
                    if (highContrast) ErrorCorrectionLevel.M else ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to QR_MARGIN_MODULES
            )
            toBitmap(QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints))
        }.getOrNull()
    }

    /** 生成 CODE_128 条形码位图；宽度不够时 writer 会自动降级为更紧凑的字符集 */
    fun barcode(text: String, widthPx: Int, heightPx: Int): Bitmap? {
        if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0
            )
            toBitmap(
                Code128Writer().encode(text, BarcodeFormat.CODE_128, widthPx, heightPx, hints)
            )
        }.getOrNull()
    }

    /** BitMatrix → 纯黑白位图（不透明，避免某些扫码端把透明像素当灰） */
    private fun toBitmap(bits: BitMatrix): Bitmap {
        val width = bits.width
        val height = bits.height
        val pixels = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[index++] = if (bits[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
