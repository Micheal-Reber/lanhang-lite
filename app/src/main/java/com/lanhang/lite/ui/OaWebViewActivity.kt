package com.lanhang.lite.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.toast
import com.lanhang.lite.databinding.ActivityOaWebviewBinding
import com.lanhang.lite.net.isJwtFresh
import com.lanhang.lite.session.Session

/**
 * 内置浏览器：办事大厅的应用落地页 + 校外站点（「赣青二课」）。
 *
 * ## 两套请求头，按站点分（2026-11-14 从分叉第 18 / 71 / 74 轮搬入）
 *
 * 原来这里是「无差别」的：所有站点都带一整套 RCE 伪装头（Token / DeviceID / Cookie 头）、
 * 都在 UA 里追加 `MicroMessenger`、都开 `useWideViewPort`。校外站点因此有三种不同的白板：
 * - 带 RCE 头去敲第三方站点，被当成非法客户端；
 * - 显式 `Cookie` 头会**盖掉** WebView 自己的 CookieJar，学生邮箱（Roundcube）的
 *   `roundcube_sessid` 每次都丢；
 * - `MicroMessenger` 会让「微信内嵌」版登录页死等 `WeixinJSBridge`（WebView 里永远等不到），
 *   而 `useWideViewPort` 会把标准自适应页面量成 0 高。
 *
 * 现在：**校内域（`*.nchu.edu.cn`）** 照旧带全套装扮；**校外域** 只带一个干净 UA，
 * Cookie 只走 CookieManager。
 */
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

        /** 网页 `<input type="file">` 弹系统选择器的请求码 */
        private const val REQUEST_FILE_CHOOSER = 4001

        /** 官方 `goBack()` 特判的表单设计器域（在它上面按返回直接关页面） */
        private const val FORM_DESIGN_URL = "https://form-design-m.paas.nchu.edu.cn/"

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

    /** 当前站点 host（校外站点的登录态按它存取，见 [WebLoginStore]） */
    private lateinit var host: String

    /** 校内域名（`*.nchu.edu.cn`）：决定要不要带 RCE 伪装头 */
    private var campusHost = true

    /** 网页发起的文件选择请求；用户取消时必须回 null，否则那个 input 永远卡在等待中 */
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOaWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // 左上角返回箭头：布局里挂了 navigationIcon，但**这里必须接监听**，否则点了没反应
        // （全仓 20 个 toolbar 页面都接了，只有这页漏过 —— 2026-11-13 用户反馈「校园卡支付退不出来」）
        binding.toolbar.setNavigationOnClickListener {
            if (!goBackOrExit()) finish()
        }

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

        host = Uri.parse(url).host.orEmpty()
        campusHost = host.endsWith("nchu.edu.cn")
        // OA 那几个 H5 没有 viewport meta、靠 980px 视口排版，反过来还要微信指纹；
        // 其余站点（学生邮箱、赣青二课、各业务系统）都是标准自适应页面，两个开关都要关
        val oaHost = host.endsWith("app.nchu.edu.cn") || host == "go.nchu.edu.cn"

        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        if (oaHost) {
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        val netType = "WIFI"
        // 照抄原版指纹：去掉 "; wv"，追加 NchuApp UA；MicroMessenger 只给 OA 站
        val baseUa = settings.userAgentString.replace("; wv", "")
        settings.userAgentString = "$baseUa NchuApp/${Session.appVersion} NetType/$netType" +
            if (oaHost) " MicroMessenger/8.0.47.2560" else ""

        // 同步 RCE 会话 Cookie 进 WebView（原版 syncCookie 行为）
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        com.lanhang.lite.net.ApiClient.allCookies().forEach { (domain, cookie) ->
            setCookies(cm, "http://$domain", cookie)
            setCookies(cm, "https://$domain", cookie)
            // 学校的统一身份认证摊在多个子域上（`go.nchu.edu.cn` 才是改密码 / 改手机号那两页），
            // 而 CookieJar 收上来的票大多是 host-only，跳过去一个都带不上 → 「身份鉴权失败」。
            // 校内站点本来就在同一套 SSO 后面，这里补写一份到根域与 go 子域；只在校内域生效。
            if (domain.endsWith("nchu.edu.cn")) {
                setCookies(cm, "http://nchu.edu.cn", cookie)
                setCookies(cm, "https://nchu.edu.cn", cookie)
                setCookies(cm, "http://go.nchu.edu.cn", cookie)
                setCookies(cm, "https://go.nchu.edu.cn", cookie)
            }
        }
        cm.flush()

        // 校外站点的登录票据常常是会话 Cookie，只活在内存里：本页一关就没了。
        // 先把上次存下的写回去，加载完 / 本页暂停时再存一次（见 WebLoginStore）。
        if (!campusHost) {
            WebLoginStore.restore(this, host)
            runCatching { cm.flush() }
        }

        // ⚠️ 千万别写成 `appToken?.let { put("Token", it) } ?: run { ...ldapToken... }`：
        // `HashMap.put` 返回的是**旧值**，首次插入返回 null，于是 elvis 把「put 成功」误判成
        // 「appToken 为空」，接着用 Session.ldapToken 把刚取到的 appToken 覆盖掉。
        // ldapToken 约 2 小时过期，拿过期的去请求，网关只回 `Error.aspx?code=4000`
        // （客户端身份鉴权失败），界面上就是那张「身份信息已过期」的页面。
        // 2026-10-26 实测：设备里 ldapToken 已过期 179 分钟，导致网上办事大厅每个应用都打不开；
        // 用新取到的 appToken 请求同一地址则 302 正常跳转。
        //
        // 🔴 这套头**只发给校内站点**：带 RCE 头去敲校外站点会被当成非法客户端（白板原因之一）。
        // 另外**不要再手工塞 `Cookie` 头** —— 显式 Cookie 头会盖掉 WebView 自己的 CookieJar，
        // 学生邮箱（Roundcube）的 roundcube_sessid 每次都因此丢掉，静态资源拿不到同一个会话。
        // 会话 Cookie 已经在上面逐条写进 CookieManager 了。
        val appToken = intent.getStringExtra(EXTRA_APP_TOKEN)?.takeIf { it.isNotBlank() }
        val token = appToken ?: Session.ldapToken.takeIf { it.isNotBlank() && isJwtFresh(it) }
        headers = HashMap<String, String>().apply {
            if (campusHost) {
                token?.let { put("Token", it) }
                put("Version", Session.appVersion)
                put("DeviceID", Session.deviceId(this@OaWebViewActivity))
                put("NetType", netType)
                // 原版带 ssid（要定位权限才拿得到）；我们不要那个权限，留空串与官方口径保持一致
                put("ssid", "")
            }
        }
        // 认证失败只会表现为一张错误页，先把「用了哪种令牌、多长」记下来，下次一眼可查
        android.util.Log.d(
            "OaWebView",
            "Token 来源=${if (appToken != null) "appToken" else "ldapToken"} " +
                "len=${token?.length ?: 0} campus=$campusHost host=$host"
        )

        // 网页里的 `<input type="file">` 要 App 代弹系统选择器（「赣青二课」上传头像 / 证明材料）
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val pick = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(pick, REQUEST_FILE_CHOOSER)
                    true
                } catch (_: Throwable) {
                    fileCallback = null
                    false
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 正在按返回往回退时，页面又自己往前跳 —— 这一段历史是「重定向链」
                //（校园卡支付：go 网关 → ykt 支付页，每一跳都被下面那句 loadUrl 写进历史，
                // 于是回退一步立刻又被弹回原页，按多少次都出不来，2026-11-13 实测）。
                // 这种跳转**不入历史**，直接继续往回退；退到底就关页面。
                if (unwinding) {
                    view.post {
                        if (view.canGoBack()) view.goBack()
                        else {
                            unwinding = false
                            finish()
                        }
                    }
                    return true
                }
                // 非 http(s)：tel: / mailto: / weixin:// / alipays:// / intent:// 一律交系统。
                // 丢给 WebView 的话它只会加载失败、什么都不发生 —— 用户看到的就是「点充值没反应」
                // （2026-11-14 从分叉第 74 轮搬入：缴电费拉起微信/支付宝）。
                val scheme = request.url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    openExternal(request.url.toString(), request.url)
                    return true
                }
                // 每次跳转都带伪装头重新加载（原版行为；校外站点 headers 为空，等价于普通加载）
                view.loadUrl(request.url.toString(), headers)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progress.hide()
                // 登录往往发生在页面内部（uni-app 不整页重载），这里顺手存一次登录态
                if (!campusHost) WebLoginStore.save(this@OaWebViewActivity, host)
            }
        }

        binding.webView.loadUrl(url, headers)
    }

    /**
     * 把非 http(s) 的链接交给系统（拉起微信 / 支付宝 / 拨号 / 邮件，或 `intent://` 打包跳转）。
     *
     * 两个真实原因（分叉第 74 轮记录）：
     * 1. Android 11+ 的包可见性 —— 清单里没有 `<queries>` 时，系统根本不告诉我们设备上装了
     *    微信 / 支付宝，`startActivity` 必然抛 `ActivityNotFoundException`，被 `runCatching`
     *    吞掉之后用户看到的就是「点了没反应」（清单里已补齐）；
     * 2. 支付跳转常用 `intent://…#Intent;scheme=alipays;package=…;end` 这种打包形式，
     *    直接 ACTION_VIEW 打不开，得先用 `Intent.parseUri` 解包。
     *
     * 按「解包 → 直接 VIEW → 兜底地址 → 明确提示」四级依次尝试，**绝不再静默失败**。
     */
    private fun openExternal(raw: String, uri: Uri) {
        if (raw.startsWith("intent:", ignoreCase = true)) {
            val parsed = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
            if (parsed != null) {
                if (runCatching { startActivity(parsed) }.isSuccess) return
                val fallback = parsed.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) {
                    if (runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                        }.isSuccess
                    ) return
                }
            }
            // `intent://` 里可能是 weixin:// 之类，再按普通 scheme 试一次
            val scheme = raw.substringAfter("scheme=", "").substringBefore(";")
            if (scheme.isNotBlank()) {
                if (runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://")))
                    }.isSuccess
                ) return
            }
            toast(R.string.oa_web_open_app_failed)
            return
        }
        val view = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.oa_web_open_app_failed)
        } catch (_: SecurityException) {
            toast(R.string.oa_web_open_app_failed)
        }
    }

    /** 把系统文件选择器的结果回填给网页（多选取 clipData，单选取 data，取消回 null） */
    @Deprecated("WebView 的文件选择仍走 onActivityResult（FileChooserParams 没给 ActivityResult API）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val cb = fileCallback ?: return
        fileCallback = null
        val uris: Array<Uri>? = if (resultCode != RESULT_OK || data == null) {
            null
        } else {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) {
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        }
        cb.onReceiveValue(uris)
    }

    override fun onPause() {
        super.onPause()
        // 有些站点是登录成功后才写 Cookie、页面没重载：离开前再存一次
        if (::host.isInitialized && !campusHost) WebLoginStore.save(this, host)
    }

    /**
     * 正在按返回往回退的途中（见 [shouldOverrideUrlLoading] 的重定向链处理）。
     *
     * 用户一碰屏幕就清掉：这一段退完了，之后的跳转恢复「进历史」的正常语义。
     * 靠 `dispatchTouchEvent` 而不是定时器判定 —— 自动重定向不会带触摸，用户操作一定会带。
     */
    private var unwinding = false

    /** 上一次按下返回时的地址与时间：同一地址连按两次 = 这页自己退不动，直接退出 */
    private var lastBackUrl: String? = null
    private var lastBackAt = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) unwinding = false
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 返回一页；没有可退的历史就返回 false（调用方据此关页面）。
     *
     * 与官方 `RceWebViewActivity.goBack()` 同语义（它的关闭按钮 = `if (!goBack()) finish()`），
     * 另带官方对表单设计器的特判：那一页往回退会掉回网关页，官方直接关页面。
     *
     * 2026-11-14 补一条兜底（分叉第 74 轮）：**同一地址连按两次返回 = 直接退出**。
     * 单页应用（uni-app 的 hash 路由）和「返回后页面自己又重定向回原地址」这两种情况下
     * `canGoBack()` 恒为真但画面一动不动，没有这条规则用户会被困在网页里出不来。
     */
    private fun goBackOrExit(): Boolean {
        val wv = binding.webView
        val now = System.currentTimeMillis()
        val current = wv.url.orEmpty()
        if (lastBackUrl == current && now - lastBackAt < 3000L) return false
        lastBackUrl = current
        lastBackAt = now
        if (!wv.canGoBack()) return false
        val cur = wv.copyBackForwardList()?.let { list ->
            list.getItemAtIndex(list.currentIndex)?.url
        }.orEmpty()
        if (cur.contains(FORM_DESIGN_URL)) return false
        unwinding = true
        wv.goBack()
        return true
    }

    override fun onBackPressed() {
        if (!goBackOrExit()) {
            unwinding = false
            super.onBackPressed()
        }
    }

    /**
     * 逐条写 Cookie。
     *
     * `CookieManager.setCookie` 一次**只吃一条**；把 `"a=1; b=2"` 整串丢给它，会存成一条
     * 名字里带分号的畸形 Cookie —— 第三方站点（学生邮箱这类）拿到畸形 Cookie 直接渲染成白板。
     * `ApiClient.allCookies()` 返回的正是拼好的整串，所以必须在这里拆开。
     */
    private fun setCookies(cm: CookieManager, target: String, raw: String) {
        raw.split(";").forEach { piece ->
            val item = piece.trim()
            if (item.contains('=') && !item.startsWith("=")) {
                runCatching { cm.setCookie(target, item) }
            }
        }
    }
}
