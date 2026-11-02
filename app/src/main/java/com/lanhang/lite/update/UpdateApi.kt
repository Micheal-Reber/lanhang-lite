package com.lanhang.lite.update

import com.google.gson.annotations.SerializedName
import com.lanhang.lite.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 拉 GitHub Releases 的 latest（**不加任何第三方更新库**，用项目里已有的 OkHttp + Gson）。
 *
 * 与 Hoshi 的 `GitHubReleaseUpdateRepository` 同构：`直连 → 镜像` 依次重试，**一旦某个镜像
 * 成功，下载地址也优先用镜像**（它用 `preferMirrors` 标记做同一件事）——API 能通的镜像，
 * 下包大概率也能通，而 github.com 的下载域名在国内经常是另一回事。
 */
object UpdateApi {

    private const val REPO = "Micheal-Reber/lanhang-lite"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    ///
    /// ⚠️ 下面两组前缀是 2026-11-02 实测过的（curl 带 `-r 0-1024` 取首包）：
    /// - `ghproxy.vip` / `gh.llkk.cc` 当时是 **403 / 超时**，别加回来
    /// - `gh-proxy.com` 对 API 与 apk 都通（apk 返回 206，1.3s）
    /// - `ghproxy.net` 只对 apk 通，而且慢（~9s），放最后当兜底
    /// 失效了就在这里换，其他地方不用动。
    private val API_MIRRORS = listOf(
        "https://gh-proxy.com/",
    )

    private val DOWNLOAD_MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /**
     * 取最新 release。**必须在 IO 线程调用**（OkHttp 同步请求）。
     *
     * @throws IllegalStateException 全部候选地址都失败时抛出，message 里带最后一次的 HTTP 码与响应体摘要
     */
    suspend fun latestRelease(): UpdateRelease = withContext(Dispatchers.IO) {
        val candidates = listOf(LATEST) + API_MIRRORS.map { it + LATEST }
        var lastError: Throwable? = null
        candidates.forEachIndexed { index, url ->
            try {
                // index > 0 说明走的是镜像 → 下载地址也优先镜像
                return@withContext parse(fetch(url), preferMirrors = index > 0)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw IllegalStateException(lastError?.message ?: "network error", lastError)
    }

    private fun fetch(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            // GitHub API 强制要求 UA，不带会直接 403
            .header("User-Agent", "LanhangLite-Android")
            .get()
            .build()
        ApiClient.http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 带上响应体前 120 字：限流/404 时 body 里写得很清楚，只有状态码根本查不出来
                throw IllegalStateException("HTTP ${resp.code} ${body.take(120)}".trim())
            }
            if (body.isBlank()) throw IllegalStateException("empty body")
            return body
        }
    }

    private fun parse(json: String, preferMirrors: Boolean): UpdateRelease {
        val parsed = ApiClient.gson.fromJson(json, GitHubReleaseJson::class.java)
            ?: throw IllegalStateException("bad json")
        val version = parsed.tagName?.let { normalizeVersion(it) }
            ?: throw IllegalStateException("tag=${parsed.tagName}")
        return UpdateRelease(
            versionName = version,
            htmlUrl = parsed.htmlUrl.orEmpty(),
            assets = parsed.assets.orEmpty().mapNotNull { asset ->
                val name = asset.name ?: return@mapNotNull null
                val url = asset.browserDownloadUrl ?: return@mapNotNull null
                buildAsset(name, url, asset.normalizedSha256(), preferMirrors)
            },
        )
    }

    /**
     * 把镜像拼进候选地址。
     *
     * 前缀拼接（`https://gh-proxy.com/` + 原始 url）是这类代理的统一用法；
     * 首选镜像时原地址降为兜底，反之镜像全做兜底。
     */
    private fun buildAsset(
        name: String,
        url: String,
        sha256: String?,
        preferMirrors: Boolean,
    ): UpdateAsset {
        val mirrored = DOWNLOAD_MIRRORS.map { it + url }
        return if (preferMirrors && mirrored.isNotEmpty()) {
            UpdateAsset(
                name = name,
                downloadUrl = mirrored.first(),
                fallbackUrls = (mirrored.drop(1) + url).distinct(),
                sha256 = sha256,
            )
        } else {
            UpdateAsset(name = name, downloadUrl = url, fallbackUrls = mirrored, sha256 = sha256)
        }
    }

    /**
     * `v0.9.2` / `0.9.2` → `0.9.2`；带后缀（`0.9.3-beta1`）一并不认，返回 null。
     *
     * 只认三段数字是刻意的：发布时 tag 一定按 `vX.Y.Z` 打，模糊匹配反而会把
     * `v0.9.2-preview` 这种当成正式版推给所有人。
     */
    fun normalizeVersion(raw: String): String? {
        val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").matchEntire(raw.trim()) ?: return null
        return "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
    }

    /** 版本三段比较；解析不了的一律当 0，避免因为脏 tag 反复提示更新 */
    fun compareVersion(a: String, b: String): Int {
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private data class GitHubReleaseJson(
        @SerializedName("tag_name") val tagName: String? = null,
        @SerializedName("html_url") val htmlUrl: String? = null,
        @SerializedName("assets") val assets: List<GitHubAssetJson>? = null,
    )

    private data class GitHubAssetJson(
        @SerializedName("name") val name: String? = null,
        @SerializedName("browser_download_url") val browserDownloadUrl: String? = null,
        @SerializedName("digest") val digest: String? = null,
    ) {
        /** GitHub 给的是 `sha256:abc...`，统一成裸的十六进制串 */
        fun normalizedSha256(): String? = digest
            ?.removePrefix("sha256:")
            ?.trim()
            ?.takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) }
            ?.lowercase(Locale.US)
    }
}
