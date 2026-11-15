package com.lanhang.lite.library

import com.lanhang.lite.core.toast
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivitySeatSignCodeBinding
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * **出码之后必须轮询**（2026-11-15 同步分叉第 76 轮）：官方 `ht.Seat.CodeSignPage`
 * 每 2 秒 `POST /Seat/Index/checkSignQRCode`，直到 `CODE == "ok"` 才算签上；
 * 「还没被扫」回的就是 `CODE != ok`，是正常中间态。原来只出码不问结果，
 * 签到器吃掉码之后本机毫无感知，用户自然以为没签上（用户原话：
 * 「扫描二维码后无法成功签到，只有打开微信端扫码才正常」）。
 */
class SeatSignCodeActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivitySeatSignCodeBinding

    private var loading = false

    /** 首次加载由 onCreate 发起，避免 onCreate + onResume 连着重取两次码 */
    private var firstResume = true

    /** 当前这张码。轮询必须拿它去问服务端，换码时要跟着换 */
    private var code: String = ""

    /** 轮询任务；换码 / 离开页面必须取消，否则会拿着旧码一直问 */
    private var pollJob: Job? = null

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
        // 回到前台大概率是在签到器上扫过一轮了，换一张新码（换码会重启轮询）
        if (!firstResume) loadCode()
        firstResume = false
    }

    override fun onStop() {
        super.onStop()
        // 离开页面就停：后台还要每 2 秒打一次接口没有意义，也省得白耗电
        pollJob?.cancel()
        pollJob = null
    }

    // ==================== 加载 ====================

    private fun loadCode() {
        if (loading) return
        loading = true
        binding.progress.show()
        binding.btnRefresh.isEnabled = false
        // 换码先把上一张码的轮询停掉，否则会拿旧码一直问服务端
        pollJob?.cancel()
        pollJob = null
        code = ""
        binding.tvHint.setText(R.string.seat_sign_code_hint)
        lifecycleScope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    LibrarySeatApi.ensureLogin(Session.deviceId(AppContext.instance))
                    // 码和图片一起取：轮询要用明文码，图片只是拿去画
                    val c = LibrarySeatApi.signCode()
                    c to LibrarySeatApi.signCodeImage(c)
                }
                val bitmap = BitmapFactory.decodeByteArray(pair.second, 0, pair.second.size)
                if (isFinishing || isDestroyed) return@launch
                if (bitmap == null) {
                    binding.ivCode.setImageDrawable(null)
                    toast(getString(R.string.seat_sign_code_failed))
                } else {
                    binding.ivCode.setImageBitmap(bitmap)
                    code = pair.first
                }
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                if (isFinishing || isDestroyed) return@launch
                binding.ivCode.setImageDrawable(null)
                toast(e.userMessage(this@SeatSignCodeActivity, R.string.library_seat_load_failed))
            } finally {
                loading = false
                if (!isFinishing && !isDestroyed) {
                    binding.progress.hide()
                    binding.btnRefresh.isEnabled = true
                }
            }
            // 码拿到之后再交给轮询；顺序反了会拿空码去问
            if (code.isNotBlank() && !isFinishing && !isDestroyed) startPolling(code)
        }
    }

    /**
     * 每 2 秒问一次「签到器有没有把我的码吃掉」—— 官方前端就是这个节奏。
     *
     * 成功：提示「已签到成功」并停下。失败：连续 3 次才提一句「仍在重试」，
     * 偶尔一次网络抖动不该吓用户（服务端「还没被扫」本身也是 `CODE != ok`，不算失败）。
     */
    private fun startPolling(signCode: String) {
        pollJob?.cancel()
        binding.tvHint.setText(R.string.seat_sign_code_polling)
        pollJob = lifecycleScope.launch {
            var misses = 0
            while (true) {
                delay(POLL_INTERVAL_MS)
                val ok = try {
                    withContext(Dispatchers.IO) { LibrarySeatApi.pollCheckSign(signCode) }
                } catch (e: Exception) {
                    // 取消就是取消：launch 块里 return@launch，别让收尾代码继续跑
                    if (e.isCancellation) return@launch
                    misses++
                    false
                }
                if (isFinishing || isDestroyed) return@launch
                if (ok) {
                    binding.tvHint.setText(R.string.seat_sign_code_ok)
                    // 已经签上了，这张码没用了，别再让用户点「刷新」换一张
                    binding.btnRefresh.isEnabled = false
                    return@launch
                }
                if (misses >= 3) binding.tvHint.setText(R.string.seat_sign_code_retrying)
            }
        }
    }

    companion object {
        /** 官方 `ht.Seat.CodeSignPage` 的轮询节奏 */
        private const val POLL_INTERVAL_MS = 2_000L

        fun start(context: Context) {
            context.startActivity(Intent(context, SeatSignCodeActivity::class.java))
        }
    }
}
