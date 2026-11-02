package com.lanhang.lite.update

/**
 * 自动更新的模型。结构与 Hoshi Reader 的 `features/update` 对齐，去掉本项目用不上的部分
 * （预览版、ABI 多包、DataStore）。
 */
data class UpdateAsset(
    val name: String,
    /** 首选下载地址；API 走镜像成功时这里就是镜像地址（与 Hoshi 的 preferMirrors 同语义） */
    val downloadUrl: String,
    /** 上面的备用地址，按顺序试 */
    val fallbackUrls: List<String> = emptyList(),
    /** GitHub 下发的 `digest`，去掉 `sha256:` 前缀；老 release 可能没有 */
    val sha256: String? = null,
) {
    fun candidates(): List<String> = (listOf(downloadUrl) + fallbackUrls).distinct()

    /**
     * 上次在 [failedDownloadUrl] 失败后该用哪个地址。
     *
     * 和 Hoshi 的 `downloadUrlAfterFailed` 一致：按候选顺序**往后挪一个**，挪到底就回到首选。
     * 不用「尝试次数」计数：计数器在进程重启/清缓存后会归零，而上次失败的地址是持久化的，
     * 重试才真的会换镜像。
     */
    fun urlAfterFailed(failedDownloadUrl: String?): String {
        if (failedDownloadUrl == null) return downloadUrl
        val list = candidates()
        return list.getOrNull(list.indexOf(failedDownloadUrl) + 1) ?: downloadUrl
    }
}

data class UpdateRelease(
    /** 去掉 `v` 前缀的版本名，如 `0.9.3` */
    val versionName: String,
    val htmlUrl: String,
    val assets: List<UpdateAsset>,
)

/** 检查结果，对应 Hoshi 的 `UpdateCheckOutcome`（保留了本项目需要的几档） */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    data class Available(
        val versionName: String,
        val asset: UpdateAsset,
        val releaseUrl: String,
    ) : UpdateCheck

    /** 有新版本，但 release 里没有能用的 apk（比如只传了源码包） */
    data object NoAsset : UpdateCheck

    /** 用户点过「跳过此版本」，自动检查时不再打扰；手动检查仍会提示 */
    data class Skipped(val versionName: String) : UpdateCheck

    /** 网络/解析失败；[message] 已经可以直接展示 */
    data class Failed(val message: String) : UpdateCheck
}

/** 下载状态（由 [UpdateDownloadManager] 从系统 DownloadManager 查询） */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** @param progress 0~100；拿不到进度时为 -1 */
    data class Downloading(val progress: Int) : DownloadState

    data class Ready(val versionName: String) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/** 本地记录的阶段（对应 Hoshi 的 `UpdateDownloadRecordStatus`） */
enum class UpdateStatus {
    /** 检查到新版本，还没开始下载 */
    AVAILABLE,

    /** 用户点了「跳过此版本」 */
    SKIPPED,

    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

/**
 * 本地一条更新记录。
 *
 * 存「当时挑中的资产与地址」而不只存 version：换镜像、校验 sha256、失败后换下一个地址
 * 都要用到，重查一次 release 反而可能已经变了。
 */
data class UpdateRecord(
    val versionName: String,
    val releaseUrl: String = "",
    val assetName: String = "",
    val fileName: String = "",
    val downloadId: Long? = null,
    val sha256: String? = null,
    val downloadUrl: String? = null,
    val fallbackUrls: List<String> = emptyList(),
    val status: UpdateStatus = UpdateStatus.AVAILABLE,
    val failureDetails: String? = null,
) {
    fun toAsset(): UpdateAsset? {
        val url = downloadUrl ?: return null
        return UpdateAsset(
            name = assetName.ifBlank { fileName },
            downloadUrl = url,
            fallbackUrls = fallbackUrls,
            sha256 = sha256,
        )
    }

    fun matches(other: UpdateCheck.Available): Boolean =
        versionName == other.versionName && assetName == other.asset.name && sha256 == other.asset.sha256
}
