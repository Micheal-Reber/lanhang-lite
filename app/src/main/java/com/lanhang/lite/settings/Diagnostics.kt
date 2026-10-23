package com.lanhang.lite.settings

import android.content.Context
import android.os.Build

object Diagnostics {
    @Synchronized
    fun record(context: Context, area: String, error: Throwable) {
        runCatching {
            val file = java.io.File(context.filesDir, "diagnostics.log")
            if (file.length() > 64 * 1024) file.writeText("")
            val message = redact("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            file.appendText("${System.currentTimeMillis()} [$area] $message\n")
        }
    }

    fun report(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val pid = android.os.Process.myPid()
        val raw = runCatching {
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=$pid", "-t", "500", "-v", "time", "*:W")
            ).inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "读取系统日志失败：${it.javaClass.simpleName}" }

        val safeLines = raw.lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { line ->
                val lower = line.lowercase()
                lower.contains("okhttp") || lower.contains("authorization") ||
                    lower.contains("moments_last") || lower.contains("cookies_debug")
            }
            .map(::redact)
            .toList()
            .takeLast(250)
        val appErrors = runCatching {
            java.io.File(context.filesDir, "diagnostics.log")
                .takeIf { it.exists() }
                ?.readLines()
                .orEmpty()
                .takeLast(100)
        }.getOrDefault(emptyList())

        return buildString {
            appendLine("蓝航 Lite 诊断信息")
            appendLine("版本：$version")
            appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("日志级别：Warning / Error；仅本机最近日志")
            appendLine("应用错误记录：")
            appErrors.forEach(::appendLine)
            appendLine("— 日志开始 —")
            safeLines.forEach(::appendLine)
            appendLine("— 日志结束 —")
        }.take(48 * 1024)
    }

    private fun redact(line: String): String = line
        .replace(
            Regex("(?i)(cookie|authorization|access[_-]?token|ldap[_-]?token|password|user_id)\\s*[=:]\\s*[^\\s,}]+"),
            "$1=[已隐藏]"
        )
        .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer [已隐藏]")
}
