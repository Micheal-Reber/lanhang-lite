package com.lanhang.lite.im

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 把一条位置消息交给手机上装的地图应用打开。
 *
 * 为什么不是简单 `geo:lat,lng`：国产 ROM 上注册 `geo:` 的应用很少（用户实测过文件消息
 * 那种「只有一个莫名其妙的应用」的情况），多数地图只注册自己的私有 scheme。
 * 所以这里直接按包名探测，谁装了就用谁的私有 scheme。
 *
 * 🔴 **坐标必须先做转换**：我们用 `LocationManager` 拿的是 **WGS84**（GPS 原始坐标），
 * 而高德 / 腾讯用 **GCJ02**（国测局加密坐标）、百度用 **BD09**。
 * 直接拿 WGS84 去高德打点会偏 300~500 米 —— 在学校里等于指到另一栋楼。
 * 官方那条路不偏，是因为它用的是高德 SDK 定位，拿到的本来就是 GCJ02。
 */
object GeoShare {

    private const val TAG = "LanhangIM"

    private const val PKG_AMAP = "com.autonavi.minimap"
    private const val PKG_BAIDU = "com.baidu.BaiduMap"
    private const val PKG_TENCENT = "com.tencent.map"

    /** 应用标识：地图 App 的风控/统计用，随便给个能认出来的即可 */
    private const val SOURCE = "com.lanhang.lite"

    /**
     * 打开地图。已装的地图一个都没有时，退回高德网页版（`callnative=1` 会再试一次唤起 App）。
     *
     * @return false 表示连网页都没打开（调用方可以提示「坐标已复制」之类）
     */
    fun open(context: Context, lat: Double, lng: Double, poi: String): Boolean {
        if (lat == 0.0 && lng == 0.0) return false
        val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
        val title = poi.ifBlank { String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng) }

        // 顺序即优先级：高德在国内覆盖率最高，其次是腾讯、百度
        if (tryStart(context, PKG_AMAP, amapUri(gcjLat, gcjLng, title))) return true
        if (tryStart(context, PKG_TENCENT, tencentUri(gcjLat, gcjLng, title))) return true
        val (bdLat, bdLng) = gcj02ToBd09(gcjLat, gcjLng)
        if (tryStart(context, PKG_BAIDU, baiduUri(bdLat, bdLng, title))) return true

        // 都没装：高德网页版能用（国内可直接打开，也能引导下载 App）
        return tryStart(context, null, webUri(gcjLng, gcjLat, title))
    }

    /**
     * 起一个 ACTION_VIEW，用 [Intent.setPackage] 把它钉死在指定包上。
     *
     * 不先查 `getPackageInfo` 判断「装没装」：那需要 `<queries>` 声明，一旦漏了就会
     * 把装了的地图当成没装（2026-11-12 就是这么退到网页版的）。直接拉，拉不起来
     * 才算没有 —— 这也正是我们真正关心的。
     */
    private fun tryStart(context: Context, pkg: String?, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "map intent failed (${pkg ?: "web"}): ${it.javaClass.simpleName}") }
            .isSuccess
    }

    // ------------------------------------------------------------------ URI

    /** 高德「标注点」：sourceApplication 可以随便填，dev=0 表示不走导航 */
    private fun amapUri(lat: Double, lng: Double, title: String): String =
        "androidamap://viewMap?sourceApplication=$SOURCE&lat=$lat&lon=$lng&dev=0&poiname=${enc(title)}"

    /** 腾讯：marker 参数是分号分隔的 key:value */
    private fun tencentUri(lat: Double, lng: Double, title: String): String =
        "qqmap://map/marker?marker=coord:$lat,$lng;title:${enc(title)};addr:${enc(title)}&referer=$SOURCE"

    /** 百度：coord_type 要显式写 bd09ll，否则它会按 gcj02 解析 */
    private fun baiduUri(lat: Double, lng: Double, title: String): String =
        "baidumap://map/marker?location=$lat,$lng&title=${enc(title)}&content=${enc(title)}" +
            "&coord_type=bd09ll&src=$SOURCE"

    /** 高德网页版：position 是「经度,纬度」，别写反 */
    private fun webUri(lng: Double, lat: Double, title: String): String =
        "https://uri.amap.com/marker?position=$lng,$lat&name=${enc(title)}&src=$SOURCE&coordinate=gaode&callnative=1"

    private fun enc(text: String): String = runCatching {
        URLEncoder.encode(text, "UTF-8")
    }.getOrDefault(text)

    // ------------------------------------------------------------------ 坐标转换

    private const val PI = Math.PI
    private const val AXIS = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * WGS84 → GCJ02（国测局坐标）。
     *
     * 算法是公开的（各大地图 SDK 都这么转），只在**中国境内**生效 —— 境外不加密，
     * 所以先判范围，境外直接原样返回，否则会被平白挪几百米。
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (AXIS * (1 - EE) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (AXIS / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lng + dLng)
    }

    /** GCJ02 → BD09（百度在国测局坐标上又加了一层偏移） */
    fun gcj02ToBd09(lat: Double, lng: Double): Pair<Double, Double> {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * PI * 3000.0 / 180.0)
        val theta = atan2(y, x) - 0.000003 * cos(x * PI * 3000.0 / 180.0)
        return (z * sin(theta) + 0.006) to (z * cos(theta) + 0.0065)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
