package com.lanhang.lite.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.lanhang.lite.R

/**
 * 检查更新：拉 release → 比版本 → 挑**与本变体匹配**的安装包 → 记一条本地记录。
 *
 * 与 Hoshi 的 `UpdateCheckService.check` 对应关系：
 * - 它按 ABI 多包挑资产，我们按**包名**挑（两个 flavor 的 apk 在同一个 release 里）
 * - 它支持「用户跳过某版本」（`Skipped` + `ignoreSkipped`），我们照做
 * - 它把结果写进 DataStore，我们写 SharedPreferences（[UpdateStore]）
 */
object UpdateChecker {

    /** 真机排查用：`adb logcat -s LanhangUpdate` */
    private const val TAG = "LanhangUpdate"

    fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "--" }

    fun currentVersionCode(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }.getOrDefault(0L)

    /**
     * 是否 debug 构建。
     *
     * debug 包**能检查、不能下载安装**：release 与 debug 签名不同，覆盖安装必然
     * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（2026-11-02 已被这个绕了半天）。
     * 本项目没开 `buildFeatures.buildConfig`，所以用 `FLAG_DEBUGGABLE`。
     */
    fun isDebugBuild(context: Context): Boolean = runCatching {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }.getOrDefault(false)

    /**
     * @param ignoreSkipped 手动点「检查更新」时传 true —— 用户主动问了就要告诉他，
     *        哪怕之前点过「跳过此版本」；自动检查传 false。
     */
    suspend fun check(context: Context, ignoreSkipped: Boolean = false): UpdateCheck {
        val current = currentVersionName(context)
        val release = try {
            UpdateApi.latestRelease()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "check failed: ${e.javaClass.simpleName} ${e.message}")
            return UpdateCheck.Failed(
                context.getString(R.string.update_error_network, e.message.orEmpty().take(80))
            )
        }
        Log.i(
            TAG,
            "release=${release.versionName} current=$current assets=" +
                release.assets.joinToString { it.name }
        )
        if (UpdateApi.compareVersion(release.versionName, current) <= 0) return UpdateCheck.UpToDate

        val asset = pickAsset(context, release.assets) ?: run {
            Log.w(TAG, "no matching asset for ${context.packageName}")
            return UpdateCheck.NoAsset
        }
        val available = UpdateCheck.Available(
            versionName = release.versionName,
            asset = asset,
            releaseUrl = release.htmlUrl,
        )

        // 用户跳过过同一版本就不再打扰（手动检查除外）
        val record = UpdateStore.load(context)
        if (!ignoreSkipped &&
            record?.status == UpdateStatus.SKIPPED &&
            record.versionName == available.versionName
        ) {
            Log.i(TAG, "skipped by user: ${available.versionName}")
            return UpdateCheck.Skipped(available.versionName)
        }

        // 下载中/已下载的记录不能被「可用」覆盖，否则会丢掉 downloadId 与校验信息
        val keepExisting = record != null && record.matches(available) &&
            (record.status == UpdateStatus.DOWNLOADING || record.status == UpdateStatus.DOWNLOADED)
        if (!keepExisting) UpdateStore.saveAvailable(context, available)

        Log.i(TAG, "update available ${available.versionName} asset=${asset.name}")
        return available
    }

    /**
     * 挑 apk：私信版要 `-im` 那个，轻量版要 `-lite` 且不带 `-im` 的。
     *
     * 兜底只允许「release 里恰好只有一个 apk」——多个 apk 却认不出哪个属于自己时，
     * 宁可报「没有可用安装包」，也不要赌一个装错的包（包名不同 = 装成另一个应用）。
     */
    private fun pickAsset(context: Context, assets: List<UpdateAsset>): UpdateAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val isIm = context.packageName.endsWith(".im")
        val picked = if (isIm) {
            apks.firstOrNull { it.name.contains("-im", ignoreCase = true) }
        } else {
            apks.firstOrNull {
                it.name.contains("-lite", ignoreCase = true) &&
                    !it.name.contains("-im", ignoreCase = true)
            }
        }
        return picked ?: apks.singleOrNull()
    }
}
