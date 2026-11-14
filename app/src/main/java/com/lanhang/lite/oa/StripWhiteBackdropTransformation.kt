package com.lanhang.lite.oa

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation

/**
 * 把「与图片边缘连通的白色背景」抠成透明。
 *
 * 办事大厅的 logo 由各单位自行上传，两种都有：透明 PNG（深色卡片上融进背景），
 * 以及白底 JPG/PNG（深色卡片上就是一块 40dp 白方块，四角露白特别扎眼）。
 *
 * 只抠**与边缘连通**的白色：图标内部的白色被彩色包围（红盾牌上的白十字、
 * 文印图标里的白纸），不与外部连通，不会被误伤。
 *
 * 与背景相邻的浅色像素按亮度降 alpha，去掉彩色图形周围残留的那一圈白描边。
 */
class StripWhiteBackdropTransformation : Transformation {

    // 显式写死缓存键：默认取类名，R8 改名后会让已缓存的图全部失效一次
    override val cacheKey: String = "strip_white_backdrop_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width < 2 || height < 2) return input

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        // 边缘没有任何不透明的白 → 透明底或满幅彩图，原样返回
        if (!hasOpaqueWhiteEdge(pixels, width, height)) return input

        val background = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0

        // 4 连通洪水填充：只有「从边缘能走到的白」才算背景
        fun push(index: Int) {
            if (background[index] || !isBackgroundWhite(pixels[index])) return
            background[index] = true
            queue[tail++] = index
        }

        for (x in 0 until width) {
            push(x)
            push((height - 1) * width + x)
        }
        for (y in 0 until height) {
            push(y * width)
            push(y * width + width - 1)
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) push(index - 1)
            if (x < width - 1) push(index + 1)
            if (y > 0) push(index - width)
            if (y < height - 1) push(index + width)
        }

        val output = IntArray(pixels.size)
        var removed = false
        for (i in pixels.indices) {
            if (background[i]) {
                output[i] = Color.TRANSPARENT
                removed = true
            } else {
                output[i] = feather(pixels, background, i, width, height)
            }
        }
        if (!removed) return input

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * 与背景相邻的浅色像素：越接近纯白越透明。
     *
     * 不做这一步，彩色图形四周会留一圈 1px 的白边（原图从白底上抗锯齿过渡出来的），
     * 在深色卡片上看起来就是「脏」。
     */
    private fun feather(
        pixels: IntArray,
        background: BooleanArray,
        index: Int,
        width: Int,
        height: Int,
    ): Int {
        val color = pixels[index]
        val x = index % width
        val y = index / width
        val nearBackground = (x > 0 && background[index - 1]) ||
            (x < width - 1 && background[index + 1]) ||
            (y > 0 && background[index - width]) ||
            (y < height - 1 && background[index + width])
        if (!nearBackground) return color

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // 彩色像素（盾牌红、文档蓝）min 值很低，直接跳过，不动它们的 alpha
        if (minOf(r, g, b) < SOFT_MIN) return color
        val keep = ((255 - maxOf(r, g, b)).toFloat() / (255 - SOFT_MIN)).coerceIn(0f, 1f)
        return Color.argb((keep * 255).toInt(), r, g, b)
    }

    /**
     * 边缘是否存在「不透明的白」。
     *
     * **不能拿「白色占边缘的比例」当门槛**：图标主体常常铺满整幅，只有四个角露白。
     * 实测「新闻稿件报审」那类 logo 的四边白色占比只有 36%，一开始设了 55% 的门槛，
     * 结果整类白底图全被当成「不是白底」跳过，修了等于没修。
     *
     * 洪水填充本身只吃与边缘连通的白色，误伤风险很低，所以这里只挡两种情况：
     * 边缘没有任何白（透明 PNG，边缘 alpha = 0）与零星抗锯齿噪点（下限 [MIN_WHITE_EDGE_PIXELS]）。
     */
    private fun hasOpaqueWhiteEdge(pixels: IntArray, width: Int, height: Int): Boolean {
        var white = 0
        for (x in 0 until width) {
            if (isBackgroundWhite(pixels[x])) white++
            if (isBackgroundWhite(pixels[(height - 1) * width + x])) white++
        }
        for (y in 0 until height) {
            if (isBackgroundWhite(pixels[y * width])) white++
            if (isBackgroundWhite(pixels[y * width + width - 1])) white++
        }
        return white >= MIN_WHITE_EDGE_PIXELS
    }

    /** 不透明的近白像素（白底可能不是纯白，留一点余量到 #EBEBEB） */
    private fun isBackgroundWhite(color: Int): Boolean =
        Color.alpha(color) >= 250 &&
            Color.red(color) >= 235 &&
            Color.green(color) >= 235 &&
            Color.blue(color) >= 235

    private companion object {
        /** 羽化门槛：低于这个亮度的像素属于图形本体，不碰 */
        const val SOFT_MIN = 190

        /** 边缘白像素少于这个数就当作噪点，不处理（也挡掉边缘全透明的 PNG） */
        const val MIN_WHITE_EDGE_PIXELS = 4
    }
}
