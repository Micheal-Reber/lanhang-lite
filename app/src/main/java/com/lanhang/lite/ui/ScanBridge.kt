package com.lanhang.lite.ui

import android.webkit.JavascriptInterface

/**
 * 网页里的 `LhScan` 对象：把「赣青二课」的扫码入口接到原生相机上。
 *
 * 2026-11-15 从分叉 `Nuclear-Potatoes/lanhang-lite-miuix` 第 77 轮移植。
 *
 * 站点那边（`pages-index` 的 `handleSenCode`）在非微信环境是这么调的：
 * ```js
 * window.CP2.showScanQr({ needResult: 1 }).then(function (t) {
 *     if (1 == t.code) this.handleSignAdd(t.data.qrCode)
 * })
 * ```
 * `window.CP2` 在青新闻 App 里是原生注入的真桥；在我们的 WebView 里这个对象虽然存在
 * （站点自己铺了一份 stub），但 `showScanQr` 返回的 Promise **永不 resolve** ——
 * 用户点下去毫无反应。所以 `OaWebViewActivity` 在页面加载完成后注入 JS，把
 * `CP2.showScanQr` 换成「调 [scan] → 起 [ScanQrActivity] → resolve」的版本。
 *
 * 🔴 只暴露一个动作，且**只在二课域名下 addJavascriptInterface**（见 OaWebViewActivity）：
 * JS 桥是网页能直接调 App 的口子，不给校外其它站点留任何入口。
 */
internal class ScanBridge(private val host: OaWebViewActivity) {

    /** 网页侧入口：起原生扫码页，结果由 Activity 回调后经 evaluateJavascript 交还网页 */
    @JavascriptInterface
    fun scan() {
        host.startScanForWeb()
    }

    /** 网页侧的日志出口：注入是否生效、回调是否到达，都能在 logcat 里看到 */
    @JavascriptInterface
    fun log(message: String?) {
        android.util.Log.i("OaWebView", "ScanBridge: " + (message ?: ""))
    }
}
