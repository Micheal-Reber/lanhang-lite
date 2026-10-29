package com.lanhang.lite.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lanhang.lite.R
import com.lanhang.lite.databinding.ActivityOaWebviewBinding
import com.lanhang.lite.session.Session
import com.lanhang.lite.net.isJwtFresh
import android.content.Context
import com.lanhang.lite.core.LocaleHelper

class OaWebViewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_APP_TOKEN = "appToken"
        private const val EXTRA_TITLE = "title"

        fun open(context: android.content.Context, url: String, appToken: String?, title: String?) {
            context.startActivity(
                android.content.Intent(context, OaWebViewActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_APP_TOKEN, appToken)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }

    private lateinit var binding: ActivityOaWebviewBinding
    private lateinit var headers: HashMap<String, String>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOaWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 避开状态栏/挖孔
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.web_title_fallback)

        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 照抄原版指纹：去掉 "; wv"，追加 NchuApp UA
        val baseUa = settings.userAgentString.replace("; wv", "")
        val netType = "WIFI"
        settings.userAgentString =
            "$baseUa NchuApp/${Session.appVersion} NetType/$netType MicroMessenger/8.0.47.2560"

        // 同步 RCE 会话 Cookie 进 WebView（原版 syncCookie 行为）
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        com.lanhang.lite.net.ApiClient.allCookies().forEach { (domain, cookie) ->
            cm.setCookie("http://$domain", cookie)
            cm.setCookie("https://$domain", cookie)
        }
        cm.flush()

        // ⚠️ 千万别写成 `appToken?.let { put("Token", it) } ?: run { ...ldapToken... }`：
        // `HashMap.put` 返回的是**旧值**，首次插入返回 null，于是 elvis 把「put 成功」误判成
        // 「appToken 为空」，接着用 Session.ldapToken 把刚取到的 appToken 覆盖掉。
        // ldapToken 约 2 小时过期，拿过期的去请求，网关只回 `Error.aspx?code=4000`
        // （客户端身份鉴权失败），界面上就是那张「身份信息已过期」的页面。
        // 2026-10-26 实测：设备里 ldapToken 已过期 179 分钟，导致网上办事大厅每个应用都打不开；
        // 用新取到的 appToken 请求同一地址则 302 正常跳转。
        val appToken = intent.getStringExtra(EXTRA_APP_TOKEN)?.takeIf { it.isNotBlank() }
        val token = appToken ?: Session.ldapToken.takeIf { it.isNotBlank() && isJwtFresh(it) }
        headers = HashMap<String, String>().apply {
            token?.let { put("Token", it) }
            put("Version", Session.appVersion)
            put("DeviceID", Session.deviceId(this@OaWebViewActivity))
            put("NetType", netType)
            // 原版带 ssid（要定位权限才拿得到）；我们不要那个权限，留空串与官方口径保持一致
            put("ssid", "")
            com.lanhang.lite.net.ApiClient.cookieHeader()?.let { put("Cookie", it) }
        }
        // 认证失败只会表现为一张错误页，先把「用了哪种令牌、多长」记下来，下次一眼可查
        android.util.Log.d(
            "OaWebView",
            "Token 来源=${if (appToken != null) "appToken" else "ldapToken"} " +
                "len=${token?.length ?: 0} url=$url"
        )

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 每次跳转都带伪装头重新加载（原版行为）
                view.loadUrl(request.url.toString(), headers)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progress.visibility = android.view.View.GONE
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                // 记录所有网络请求，用于发现课表数据接口
                android.util.Log.i(
                    "LanhangReq",
                    "${request.method} ${request.url} ua=${request.requestHeaders["User-Agent"] ?: "-"} cookie=${(request.requestHeaders["Cookie"] ?: "-").take(120)}"
                )
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.webView.loadUrl(url, headers)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
