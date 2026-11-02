package com.lanhang.lite.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lanhang.lite.R
import java.io.File

/**
 * 拉起系统安装器（**只能拉起，不能静默安装** —— Android 不允许应用自己装 apk）。
 *
 * 两个前提：
 * 1. `REQUEST_INSTALL_PACKAGES` 权限（清单里已声明，属于普通权限，装上就有）
 * 2. Android 8+ 还要用户在本机打开「允许安装未知应用」，没开时 `ACTION_VIEW` 会被系统直接拒，
 *    所以先查 `canRequestPackageInstalls()`，没开就跳设置页并提示一句话
 *
 * `ClipData` 那行不是多余的：部分 ROM（含 MIUI）只认 `clipData` 里的 uri 才给读权限，
 * 只设 `FLAG_GRANT_READ_URI_PERMISSION` 会「有界面但安装失败」。
 */
object UpdateInstaller {

    /** @return null = 已拉起安装器；否则返回要提示给用户的文案 */
    fun install(context: Context, file: File): String? {
        if (!file.isFile) {
            return context.getString(R.string.update_install_file_missing)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            return context.getString(
                if (opened) R.string.update_install_permission_needed
                else R.string.update_install_permission_failed
            )
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return context.getString(R.string.update_install_file_missing)

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // setClipData 返回 void，不能串在上面的链式调用里
        intent.clipData = ClipData.newRawUri("update", uri)
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return if (launched) null else context.getString(R.string.update_install_blocked)
    }
}
