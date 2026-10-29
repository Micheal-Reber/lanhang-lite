package com.lanhang.lite.library

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivitySeatSignCodeBinding
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 座位签到码（对应官方 `ht.Seat.CodeSignPage`）。
 *
 * 本校没有蓝牙信标（`myBookingList` 里 `ibeacons` 返回空数组），现场签到走的是
 * 「手机出示二维码 → 馆内签到器扫」。所以这张码不是可选装饰：
 * 「我的预约」里的「签到」按钮走的是服务端 `checkIn` 接口，而签到器只认这张码。
 *
 * 两条实测约定：
 * 1. 明文码 `/Seat/Index/mySignQRCode` **每次请求都换一个新的**，缓存旧码没用，
 *    所以进页面、回到前台、点二维码都要重新取；
 * 2. 二维码图片 `/Content/Index/createQRCodeToShow?str=<code>` 要会话 Cookie，
 *    不带就 302 到登录页 —— Coil 默认 ImageLoader 没有 CookieJar，
 *    只能用 [com.lanhang.lite.net.ApiClient] 的 OkHttp 自己拉字节再解码。
 */
class SeatSignCodeActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivitySeatSignCodeBinding

    private var loading = false

    /** 首次加载由 onCreate 发起，避免 onCreate + onResume 连着重取两次码 */
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeatSignCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0,
            )
            insets
        }

        binding.btnRefresh.setOnClickListener { loadCode() }
        // 点码本身也能换一张，扫码失败时不用到处找按钮
        binding.ivCode.setOnClickListener { loadCode() }

        loadCode()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台大概率是在签到器上扫过一轮了，换一张新码
        if (!firstResume) loadCode()
        firstResume = false
    }

    // ==================== 加载 ====================

    private fun loadCode() {
        if (loading) return
        loading = true
        binding.progress.isVisible = true
        binding.btnRefresh.isEnabled = false
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    LibrarySeatApi.ensureLogin(Session.deviceId(AppContext.instance))
                    val code = LibrarySeatApi.signCode()
                    val png = LibrarySeatApi.signCodeImage(code)
                    BitmapFactory.decodeByteArray(png, 0, png.size)
                }
                if (isFinishing || isDestroyed) return@launch
                if (bitmap == null) {
                    binding.ivCode.setImageDrawable(null)
                    toast(getString(R.string.seat_sign_code_failed))
                } else {
                    binding.ivCode.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                if (isFinishing || isDestroyed) return@launch
                binding.ivCode.setImageDrawable(null)
                toast(e.userMessage(this@SeatSignCodeActivity, R.string.library_seat_load_failed))
            } finally {
                loading = false
                if (!isFinishing && !isDestroyed) {
                    binding.progress.isVisible = false
                    binding.btnRefresh.isEnabled = true
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SeatSignCodeActivity::class.java))
        }
    }
}
