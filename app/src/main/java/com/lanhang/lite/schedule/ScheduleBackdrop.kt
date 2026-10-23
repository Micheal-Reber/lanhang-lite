package com.lanhang.lite.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.widget.ImageView
import coil.load
import com.lanhang.lite.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 课表自定义背景：持久化、内置纯色/渐变预设、相册图片入库，以及**对比度自适应分析**。
 *
 * 分析的目标不是「猜一个颜色」，而是保证任意背景（照片 / 纯色 / 渐变）下
 * 课表所有文字都满足 WCAG AA（正文 4.5:1 级别）可读：
 *
 * 1. 把背景按垂直方向切成 [BANDS] 条带，逐条带取代表色（照片取条带均值，
 *    纯色/渐变按色标插值）。这样竖屏、横屏、平板等任意尺寸下，条带都是
 *    归一化比例，结论一致，也不受裁剪影响（centerCrop 只裁左右）。
 * 2. 同时评估「深色文字 + 浅色遮罩」与「浅色文字 + 深色遮罩」两套方案，
 *    各自求出**最坏条带**所需的最小遮罩浓度，选代价更小的那一套。
 *    最坏条带达标 ⇒ 所有条带达标。
 * 3. 由主色调（饱和度加权的圆均值色相）推出与背景和谐的强调色，
 *    并保证强调色在遮罩上仍有足够对比度。
 *
 * 结果只依赖图片像素，与系统亮/暗主题无关，因此同一背景在亮色、暗色主题下
 * 显示完全一致。
 */
object ScheduleBackdrop {

    const val TYPE_NONE = "none"
    const val TYPE_COLOR = "color"
    const val TYPE_GRADIENT = "gradient"
    const val TYPE_IMAGE = "image"

    private const val PREFS = "lanhang_schedule_bg"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"
    private const val DIR = "schedule_bg"
    private const val FILE_NAME = "background.jpg"
    private const val MAX_IMAGE_SIDE = 1600
    private const val SAMPLE_SIDE = 96

    /** 条带数量：8 段足以覆盖渐变与照片的明暗起伏，又不会过度保守 */
    private const val BANDS = 8

    /** 正文/次要文字的目标对比度 */
    private const val TARGET_PRIMARY = 4.8
    private const val TARGET_VARIANT = 3.9

    /** 深色文字方案 / 浅色文字方案使用的文字色 */
    private val DARK_TONE = intArrayOf(0xFF11171C.toInt(), 0xFF3A434B.toInt())
    private val LIGHT_TONE = intArrayOf(0xFFFFFFFF.toInt(), 0xFFD8E1E9.toInt())

    // ==================== 数据模型 ====================

    data class Preset(
        val id: String,
        val type: String,
        val start: Int,
        val end: Int,
        val labelRes: Int
    )

    val presets: List<Preset> = listOf(
        // 纯色
        Preset("solid_mist", TYPE_COLOR, 0xFFF2F5F8.toInt(), 0xFFF2F5F8.toInt(), R.string.schedule_bg_solid_mist),
        Preset("solid_blue", TYPE_COLOR, 0xFFD8E6F2.toInt(), 0xFFD8E6F2.toInt(), R.string.schedule_bg_solid_blue),
        Preset("solid_sand", TYPE_COLOR, 0xFFF1E5D6.toInt(), 0xFFF1E5D6.toInt(), R.string.schedule_bg_solid_sand),
        Preset("solid_navy", TYPE_COLOR, 0xFF14304A.toInt(), 0xFF14304A.toInt(), R.string.schedule_bg_solid_navy),
        Preset("solid_graphite", TYPE_COLOR, 0xFF232830.toInt(), 0xFF232830.toInt(), R.string.schedule_bg_solid_graphite),
        Preset("solid_forest", TYPE_COLOR, 0xFF1E3B33.toInt(), 0xFF1E3B33.toInt(), R.string.schedule_bg_solid_forest),
        // 渐变
        Preset("grad_dawn", TYPE_GRADIENT, 0xFFF8FBFF.toInt(), 0xFFCFE2F3.toInt(), R.string.schedule_bg_grad_dawn),
        Preset("grad_sunset", TYPE_GRADIENT, 0xFFFFF1DF.toInt(), 0xFFF6C9A0.toInt(), R.string.schedule_bg_grad_sunset),
        Preset("grad_haze", TYPE_GRADIENT, 0xFFEAEDF5.toInt(), 0xFFC0C8D8.toInt(), R.string.schedule_bg_grad_haze),
        Preset("grad_deep", TYPE_GRADIENT, 0xFF091826.toInt(), 0xFF17395A.toInt(), R.string.schedule_bg_grad_deep),
        Preset("grad_dusk", TYPE_GRADIENT, 0xFF241C38.toInt(), 0xFF5B3A5E.toInt(), R.string.schedule_bg_grad_dusk),
        Preset("grad_forest", TYPE_GRADIENT, 0xFF0F2822.toInt(), 0xFF2E5A46.toInt(), R.string.schedule_bg_grad_forest)
    )

    data class Spec(val type: String = TYPE_NONE, val value: String = "") {
        val isCustom: Boolean get() = type != TYPE_NONE
    }

    /** 背景分析结论：文字明暗、遮罩浓度、强调色 */
    data class Analysis(
        val darkText: Boolean,
        val veilColor: Int,
        val veilAlpha: Float,
        val accent: Int,
        val dominantHue: Float,
        val meanColor: Int
    )

    // ==================== 持久化 ====================

    fun load(context: Context): Spec {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val type = sp.getString(KEY_TYPE, TYPE_NONE) ?: TYPE_NONE
        val value = sp.getString(KEY_VALUE, "") ?: ""
        if (type == TYPE_IMAGE && imageFile(context)?.exists() != true) return Spec()
        // 纯色的 value 有两种写法：内置预设 id（"solid_navy"）或直接的颜色串（"#14304A"）。
        // 只用 parseColor 校验会把预设 id 判成非法、整份配置被丢掉（纯色「应用不上」的老 bug）。
        if (type == TYPE_COLOR && colorOf(value) == null) return Spec()
        if (type == TYPE_GRADIENT && preset(value)?.type != TYPE_GRADIENT) return Spec()
        return Spec(type, value)
    }

    /**
     * 纯色取值：既支持内置预设 id，也支持 `#RRGGBB` / 十进制整数。
     * 背景的读取（load / analyze / bind / 预览）必须都走这里，不要各写一套解析。
     */
    fun colorOf(value: String): Int? =
        preset(value)?.takeIf { it.type == TYPE_COLOR }?.start ?: parseColor(value)

    fun save(context: Context, spec: Spec) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TYPE, spec.type)
            .putString(KEY_VALUE, spec.value)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun preset(id: String): Preset? = presets.firstOrNull { it.id == id }

    fun parseColor(value: String): Int? = runCatching {
        if (value.startsWith("#")) Color.parseColor(value) else value.toLong().toInt()
    }.getOrNull()

    fun imageFile(context: Context): File? {
        val dir = File(context.filesDir, DIR)
        val file = File(dir, FILE_NAME)
        return if (file.exists() && file.length() > 0) file else null
    }

    /** 设置页摘要文案 */
    fun summary(context: Context): String {
        val spec = load(context)
        return when (spec.type) {
            TYPE_IMAGE -> context.getString(R.string.schedule_bg_custom_image)
            TYPE_COLOR, TYPE_GRADIENT -> preset(spec.value)?.let { context.getString(it.labelRes) }
                ?: context.getString(R.string.schedule_bg_follow_theme)

            else -> context.getString(R.string.schedule_bg_follow_theme)
        }
    }

    // ==================== 图层绑定 ====================

    /** 把背景画到 solid/image 两层上，并按分析结果铺遮罩层 */
    fun bind(
        context: Context,
        solidLayer: View,
        imageLayer: ImageView,
        veil: View,
        spec: Spec,
        analysis: Analysis?
    ) {
        when (spec.type) {
            TYPE_IMAGE -> {
                solidLayer.visibility = View.GONE
                imageLayer.visibility = View.VISIBLE
                imageFile(context)?.let { file ->
                    imageLayer.load(file) { crossfade(false) }
                }
            }

            TYPE_COLOR -> {
                imageLayer.visibility = View.GONE
                solidLayer.visibility = View.VISIBLE
                colorOf(spec.value)?.let { color ->
                    solidLayer.background = GradientDrawable().apply { setColor(color) }
                }
            }

            TYPE_GRADIENT -> {
                imageLayer.visibility = View.GONE
                solidLayer.visibility = View.VISIBLE
                preset(spec.value)?.let { p ->
                    solidLayer.background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(p.start, p.end)
                    )
                }
            }

            else -> {
                solidLayer.visibility = View.GONE
                imageLayer.visibility = View.GONE
            }
        }
        if (analysis != null) {
            veil.visibility = View.VISIBLE
            veil.setBackgroundColor(
                ScheduleStyle.withAlpha(analysis.veilColor, analysis.veilAlpha)
            )
        } else {
            veil.visibility = View.GONE
        }
    }

    // ==================== 相册图片入库 ====================

    /** 把相册选中的图片缩放（最长边 <= 1600px、修正 EXIF 旋转）后存进私有目录 */
    fun saveImage(context: Context, uri: Uri): Boolean {
        return runCatching {
            var bitmap = decodeSampled(context, uri, MAX_IMAGE_SIDE) ?: return false
            bitmap = applyExifRotation(context, uri, bitmap)
            if (max(bitmap.width, bitmap.height) > MAX_IMAGE_SIDE) {
                bitmap = scaleToMax(bitmap, MAX_IMAGE_SIDE)
            }
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val target = File(dir, FILE_NAME)
            val temp = File(dir, "$FILE_NAME.tmp")
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.length() > 0
        }.getOrDefault(false)
    }

    // ==================== 分析 ====================

    fun analyze(context: Context, spec: Spec, pending: Uri? = null): Analysis? = when (spec.type) {
        TYPE_COLOR -> colorOf(spec.value)?.let { color -> fromColors(listOf(color, color)) }

        TYPE_GRADIENT -> preset(spec.value)?.let { p -> fromColors(listOf(p.start, p.end)) }

        TYPE_IMAGE -> {
            val bitmap = if (pending != null) {
                decodeSampled(context, pending, SAMPLE_SIDE)
            } else {
                imageFile(context)?.let { decodeSampledFile(it, SAMPLE_SIDE) }
            }
            bitmap?.let { bmp ->
                val result = fromBitmap(bmp)
                bmp.recycle()
                result
            }
        }

        else -> null
    }

    /** 纯色/渐变：按色标插值出条带色，再统一走对比度决策 */
    private fun fromColors(colors: List<Int>): Analysis {
        val bands = IntArray(BANDS) { index ->
            val t = if (BANDS == 1) 0f else index / (BANDS - 1f)
            lerpColors(colors, t)
        }
        val hsv = FloatArray(3)
        var sinSum = 0.0
        var cosSum = 0.0
        var weightSum = 0.0
        var satSum = 0.0
        bands.forEach { color ->
            Color.colorToHSV(color, hsv)
            val weight = 0.15 + hsv[1]
            val rad = Math.toRadians(hsv[0].toDouble())
            sinSum += sin(rad) * weight
            cosSum += cos(rad) * weight
            weightSum += weight
            satSum += hsv[1] * weight
        }
        val hue = if (weightSum <= 0.0) 214f
        else (Math.toDegrees(atan2(sinSum, cosSum)).toFloat() + 360f) % 360f
        val sat = if (weightSum <= 0.0) 0f else (satSum / weightSum).toFloat()
        return build(bands, hue, sat)
    }

    /** 照片：逐行合成条带代表色，同时统计饱和度加权主色相 */
    private fun fromBitmap(bitmap: Bitmap): Analysis {
        val width = bitmap.width
        val height = max(bitmap.height, 1)
        val bands = IntArray(BANDS)
        val counts = IntArray(BANDS)
        val sums = LongArray(BANDS * 3)

        val hsv = FloatArray(3)
        var sinSum = 0.0
        var cosSum = 0.0
        var weightSum = 0.0
        var satSum = 0.0

        val stepX = max(1, width / SAMPLE_SIDE)
        val stepY = max(1, height / SAMPLE_SIDE)
        var y = 0
        while (y < height) {
            val band = (y * BANDS / height).coerceIn(0, BANDS - 1)
            var x = 0
            while (x < width) {
                val color = bitmap.getPixel(x, y)
                sums[band * 3] += Color.red(color)
                sums[band * 3 + 1] += Color.green(color)
                sums[band * 3 + 2] += Color.blue(color)
                counts[band]++

                Color.colorToHSV(color, hsv)
                val weight = hsv[1].toDouble() * hsv[1]
                if (weight > 0.002) {
                    val rad = Math.toRadians(hsv[0].toDouble())
                    sinSum += sin(rad) * weight
                    cosSum += cos(rad) * weight
                    weightSum += weight
                    satSum += hsv[1] * weight
                }
                x += stepX
            }
            y += stepY
        }

        for (i in 0 until BANDS) {
            bands[i] = if (counts[i] == 0) {
                bands.getOrElse(i - 1) { Color.DKGRAY }
            } else {
                Color.rgb(
                    (sums[i * 3] / counts[i]).toInt(),
                    (sums[i * 3 + 1] / counts[i]).toInt(),
                    (sums[i * 3 + 2] / counts[i]).toInt()
                )
            }
        }
        val hue = if (weightSum <= 1e-6) 214f
        else (Math.toDegrees(atan2(sinSum, cosSum)).toFloat() + 360f) % 360f
        val sat = if (weightSum <= 1e-6) 0f else (satSum / weightSum).toFloat()
        return build(bands, hue, sat)
    }

    /**
     * 核心决策：两套文字方案各自求「最坏条带」所需最小遮罩浓度，取更省的方案。
     * 纯色/渐变/照片都走这一条路径，所以适配行为完全一致。
     */
    private fun build(bands: IntArray, hue: Float, sat: Float): Analysis {
        val meanLum = bands.sumOf { ScheduleStyle.relLuminance(it) } / bands.size
        val needDark = veilNeeded(bands, DARK_TONE, Color.WHITE)
        val needLight = veilNeeded(bands, LIGHT_TONE, Color.BLACK)

        val darkText = when {
            needDark + 0.06f < needLight -> true
            needLight + 0.06f < needDark -> false
            else -> meanLum >= 0.5
        }
        val veilColor = if (darkText) Color.WHITE else Color.BLACK
        val veilAlpha = (if (darkText) needDark else needLight).coerceIn(0.04f, 0.9f)

        val meanColor = Color.rgb(
            bands.sumOf { Color.red(it) } / bands.size,
            bands.sumOf { Color.green(it) } / bands.size,
            bands.sumOf { Color.blue(it) } / bands.size
        )
        val composite = ScheduleStyle.mix(meanColor, veilColor, veilAlpha)

        // 主色调：有色相时跟随背景主色，灰阶背景回落到品牌蓝，保证强调色始终成立
        val hueUse = if (sat < 0.12f) 214f else hue
        val satUse = if (sat < 0.12f) 0.48f else sat.coerceIn(0.36f, 0.72f)
        val accentSeed = if (darkText) {
            Color.HSVToColor(floatArrayOf(hueUse, satUse, 0.52f))
        } else {
            Color.HSVToColor(floatArrayOf(hueUse, satUse * 0.78f, 0.88f))
        }
        val accent = ScheduleStyle.ensureContrast(accentSeed, composite, darkText, 4.3)

        return Analysis(darkText, veilColor, veilAlpha, accent, hueUse, meanColor)
    }

    /** 扫描遮罩浓度，返回所有条带同时达标所需的最小值 */
    private fun veilNeeded(bands: IntArray, tone: IntArray, veilColor: Int): Float {
        var alpha = 0f
        while (alpha <= 0.92f) {
            var ok = true
            for (band in bands) {
                val composite = ScheduleStyle.mix(band, veilColor, alpha)
                if (ScheduleStyle.contrast(composite, tone[0]) < TARGET_PRIMARY ||
                    ScheduleStyle.contrast(composite, tone[1]) < TARGET_VARIANT
                ) {
                    ok = false
                    break
                }
            }
            if (ok) return alpha
            alpha += 0.02f
        }
        return 0.92f
    }

    // ==================== 位图工具 ====================

    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(max(bounds.outWidth, bounds.outHeight), maxSide)
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }?.let { scaleToMax(it, maxSide) }
    }

    private fun decodeSampledFile(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(max(bounds.outWidth, bounds.outHeight), maxSide)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)?.let { scaleToMax(it, maxSide) }
    }

    private fun sampleFor(longSide: Int, maxSide: Int): Int {
        var sample = 1
        while (longSide / sample > maxSide * 2) sample *= 2
        return sample
    }

    private fun scaleToMax(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * ratio).roundToInt()),
            max(1, (bitmap.height * ratio).roundToInt()),
            true
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun lerpColors(colors: List<Int>, t: Float): Int {
        if (colors.isEmpty()) return Color.DKGRAY
        if (colors.size == 1) return colors[0]
        val scaled = (t.coerceIn(0f, 1f)) * (colors.size - 1)
        val index = min(scaled.toInt(), colors.size - 2)
        val local = scaled - index
        return ScheduleStyle.mix(colors[index], colors[index + 1], local)
    }
}
