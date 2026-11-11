package com.lanhang.lite.settings

import android.content.Context
import android.os.Build
import com.lanhang.lite.R

/**
 * 诊断快照的采集与拼装。
 *
 * 2026-10-30 拆成两半：
 * - [Report] 是**结构化**的（字段 / 错误列表 / 日志行），页面按 Hoshi Reader 的版式分组渲染
 *   （分组卡片里「标签左、值右」+ 分区小标题 + 等宽日志块）；
 * - [Report.toText] 把同一份数据拼成纯文本，给「保存到文件」「分享」用。
 *
 * 两者共用一次采集结果：采集里要 `exec logcat`（几十到几百毫秒），不能渲染一次、分享时再跑一次。
 */
object Diagnostics {
    /** 一帧诊断快照 */
    data class Report(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val androidVersion: String,
        val sdkInt: Int,
        val device: String,
        val time: String,
        /** 应用自己记录的异常，一行一条，最新的在最后 */
        val appErrors: List<String>,
        /** 过滤后的 logcat 行（Warning 及以上，已脱敏） */
        val logLines: List<String>,
    ) {
        /** 分享 / 保存用的纯文本：字段一段、错误一段、日志一段，段间空行 */
        fun toText(context: Context): String = buildString {
            appendLine(context.getString(R.string.diagnostics_title))
            appendLine("${context.getString(R.string.diagnostics_field_package)}: $packageName")
            appendLine("${context.getString(R.string.diagnostics_field_version)}: $versionLabel")
            appendLine("${context.getString(R.string.diagnostics_field_android)}: $androidLabel")
            appendLine("${context.getString(R.string.diagnostics_field_device)}: $device")
            appendLine("${context.getString(R.string.diagnostics_field_time)}: $time")
            appendLine()
            appendLine("${context.getString(R.string.diagnostics_app_errors)}:")
            if (appErrors.isEmpty()) {
                appendLine(context.getString(R.string.diagnostics_none))
            } else {
                appErrors.forEach(::appendLine)
            }
            appendLine()
            appendLine(context.getString(R.string.diagnostics_log_begin))
            if (logLines.isEmpty()) {
                appendLine(context.getString(R.string.diagnostics_none))
            } else {
                logLines.forEach(::appendLine)
            }
            appendLine(context.getString(R.string.diagnostics_log_end))
        }

        /** 界面上「版本」那一行的值：`0.9.1 (12)`，两个数字之间不需要翻译 */
        val versionLabel: String get() = "$versionName ($versionCode)"

        /** 界面上「Android」那一行的值：`16 (API 36)` */
        val androidLabel: String get() = "$androidVersion (API $sdkInt)"
    }

    @Synchronized
    fun record(context: Context, area: String, error: Throwable) =
        record(context, area, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")

    /**
     * 追加一条自述事件（不是异常也要能记）：`[area] message`，一行一条。
     *
     * 为什么不是 `Log.w`：诊断页采集的是 `logcat -d … *:W`，D/I 级根本采不到，
     * 而提醒链路的排钟埋点需要长期留痕 —— 只能落在这个文件里。
     */
    @Synchronized
    fun record(context: Context, area: String, message: String) {
        runCatching {
            val file = java.io.File(context.filesDir, "diagnostics.log")
            if (file.length() > 64 * 1024) file.writeText("")
            file.appendText("${System.currentTimeMillis()} [$area] ${redact(context, message)}\n")
        }
    }

    /** 采集一次快照。会 `exec logcat`，**必须在 IO 线程调用** */
    fun collect(context: Context): Report {
        // 版本号带上构建号：同一个 versionName 可能对应几次不同的构建，
        // 出问题时只看 0.9.1 认不出用户装的是哪一个
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val pid = android.os.Process.myPid()
        val raw = runCatching {
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=$pid", "-t", "500", "-v", "time", "*:W")
            ).inputStream.bufferedReader().use { it.readText() }
        }.getOrElse {
            context.getString(R.string.diagnostics_log_failed, it.javaClass.simpleName)
        }

        val logLines = raw.lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { line ->
                val lower = line.lowercase()
                lower.contains("okhttp") || lower.contains("authorization") ||
                    lower.contains("moments_last") || lower.contains("cookies_debug")
            }
            .map { redact(context, it) }
            .toList()
            .takeLast(250)

        val appErrors = runCatching {
            java.io.File(context.filesDir, "diagnostics.log")
                .takeIf { it.exists() }
                ?.readLines()
                .orEmpty()
                .takeLast(100)
        }.getOrDefault(emptyList())

        return Report(
            packageName = context.packageName,
            versionName = info?.versionName ?: "unknown",
            versionCode = info?.let {
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)
            } ?: 0L,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()),
            appErrors = appErrors,
            logLines = logLines,
        )
    }

    private fun redact(context: Context, line: String): String {
        val hidden = context.getString(R.string.diagnostics_redacted)
        val credential = Regex(
            "(?i)(cookie|authorization|access[_-]?token|ldap[_-]?token|password|user_id)\\s*[=:]\\s*[^\\s,}]+"
        )
        return line
            .replace(credential) { match -> "${match.groupValues[1]}=$hidden" }
            .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer $hidden")
    }
}
