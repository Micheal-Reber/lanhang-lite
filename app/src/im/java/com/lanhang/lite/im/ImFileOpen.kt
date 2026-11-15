package com.lanhang.lite.im

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.lanhang.lite.R
import com.lanhang.lite.update.UpdateInstaller
import java.io.File
import java.util.Locale

/**
 * 私信文件：**落到可见位置 + 交给系统打开**。
 *
 * 为什么单独抽出来（2026-11-15 加「聊天详情 → 文件」时）：聊天页和文件列表页都要
 * 「下载 → 复制到接收目录 → 按 MIME 打开」，两份实现很容易走偏（命名规则一变，
 * 下载落点和复制落点就对不上，每次点开都要重下一遍）。这里做唯一入口。
 *
 * 两条硬约定：
 * - **不直接打开下载目录里的文件**：SDK 的落点可能在 cache（随时被清），也不在
 *   FileProvider 白名单里，`FileProvider.getUriForFile` 会抛 IllegalArgumentException。
 *   所以一律先复制到 `filesDir/im/recv/`（白名单见 `res/xml/file_paths.xml`）。
 * - `FileMessage.getType()` **不保证是 MIME**（自己发的没设过，对端可能给扩展名），
 *   只有带斜杠的声明值才当 MIME 用，其余按扩展名查系统表。
 */
internal object ImFileOpen {

    /** apk 的 MIME：走系统安装器而不是普通 ACTION_VIEW */
    private const val MIME_APK = "application/vnd.android.package-archive"

    /**
     * 接收目录里的目标文件：`filesDir/im/recv/<key前32>_<文件名>`。
     *
     * 与 [ImClient.fetchToRecvDir] 的命名**必须一致** —— 一样才能让「刚下完的那份」
     * 被这里直接命中，不再重复拷贝/下载。
     */
    fun recvFile(ctx: Context, key: String, name: String?): File {
        val dir = File(ctx.filesDir, "im/recv").apply { mkdirs() }
        return File(dir, "${key.take(32)}_${ImClient.safeFileName(name.orEmpty())}")
    }

    /** 把别处的文件（SDK 下载落点）复制进接收目录；同长度已存在就直接给已有的 */
    fun ensureInRecvDir(ctx: Context, src: File, key: String, name: String?): File? = runCatching {
        val dst = recvFile(ctx, key, name ?: src.name)
        if (dst.isFile && dst.length() == src.length()) return@runCatching dst
        src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
        dst
    }.getOrNull()

    /** 交给系统打开；返回 false = 没有任何应用能处理（调用方提示可保存到手机） */
    fun open(ctx: Context, file: File, mime: String, chooser: Boolean = false): Boolean {
        // apk 交给系统安装器：和「检查更新」复用同一套（含「未知来源」授权引导）。
        // 用 ACTION_VIEW 自己拼一份的话，没开未知来源时只会静默失败。
        if (mime == MIME_APK) {
            UpdateInstaller.install(ctx, file)?.let { message ->
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
            }
            return true
        }
        return viewWith(ctx, file, mime, chooser)
    }

    /**
     * 顺序：精确 MIME → 通配。
     *
     * 精确 MIME 没有任何应用认领时（压缩包、冷门格式）退到通配类型，否则能处理通配的
     * 浏览器/文件管理器永远不会出现在列表里。
     */
    fun openBestEffort(ctx: Context, file: File, name: String?, declared: String?): Boolean {
        val mime = resolveMime(name ?: file.name, declared)
        if (open(ctx, file, mime, chooser = false)) return true
        if (mime != "*/*" && viewWith(ctx, file, "*/*", chooser = true)) return true
        return false
    }

    /**
     * 推断 MIME：只认带斜杠的声明值，否则按扩展名查系统表，再不行用通配。
     *
     * 🔴 直接拿 `FileMessage.getType()` 去 `setDataAndType` 会匹配不到任何应用 ——
     * 用户实测 apk 只弹出「网易云音乐」一个选项，就是 MIME 不对。
     */
    fun resolveMime(name: String?, declared: String?): String {
        val d = declared?.trim().orEmpty()
        if (d.contains('/')) return d
        val fileName = name.orEmpty()
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** 起一个 ACTION_VIEW；返回是否真的拉起了（ActivityNotFound 也算 false） */
    fun viewWith(ctx: Context, file: File, mime: String, chooser: Boolean): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 部分 ROM 只认 ClipData 里的授权，不看 flag
        intent.clipData = ClipData.newRawUri(file.name, uri)
        val target = if (chooser) {
            Intent.createChooser(intent, ctx.getString(R.string.im_file_open_with))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent
        }
        // 从非 Activity 的 Context 拉起要 NEW_TASK，否则直接抛 AndroidRuntimeException
        if (ctx !is android.app.Activity) target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(target) }.isSuccess
    }
}
