package com.lanhang.lite.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lanhang.lite.databinding.ActivityOaWebviewBinding
import com.lanhang.lite.session.Session

class OaWebViewActivity : AppCompatActivity() {

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
        title = intent.getStringExtra(EXTRA_TITLE) ?: "应用"

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

        headers = HashMap<String, String>().apply {
            intent.getStringExtra(EXTRA_APP_TOKEN)?.takeIf { it.isNotEmpty() }
                ?.let { put("Token", it) }
                ?: run { Session.ldapToken.takeIf { it.isNotEmpty() }?.let { put("Token", it) } }
            put("Version", Session.appVersion)
            put("DeviceID", Session.deviceId(this@OaWebViewActivity))
            put("NetType", netType)
            com.lanhang.lite.net.ApiClient.cookieHeader()?.let { put("Cookie", it) }
        }

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
