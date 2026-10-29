package com.lanhang.lite.idcard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityIdCardBinding
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.net.StaffBrief
import com.lanhang.lite.session.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 身份码：头像 + 姓名 + 学号 + 组织 + 二维码 + CODE_128 条形码 + 刷新时间。
 *
 * 对应官方 `MyQrCodeActivity`。两处数据来源：
 * - 人员信息：`POST /api/staffs/batch`（本项目早就在用，含 `staff_no` / `portrait_url` / `extra`）
 * - 二维码 token：`GET /api/apps/getUserQRCode`，返回值是**二维码原文**（不是图片），
 *   每次调用都会变，所以页面必须带「刷新时间」并支持点击重取。
 *
 * 二维码/条形码区强制白底黑码：扫码设备依赖高对比，不能跟随明暗主题反色。
 */
class IdCardActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, IdCardActivity::class.java))
        }

        /** `extra` 里可能出现的组织字段名，按优先级排列（不同角色账号字段不同） */
        private val ORG_KEYS = listOf(
            "depart_name", "department_name", "org_name", "dept_name",
            "unit_name", "company_name", "department", "org", "unit"
        )

        /** 等人员信息的上限：超时就用会话里的学号先把码画出来 */
        private const val PROFILE_WAIT_MS = 3_000L
    }

    private lateinit var binding: ActivityIdCardBinding

    private var profileJob: Job? = null
    private var codeJob: Job? = null

    /** 条形码内容（学号），profile 拉回来才有，拉不到就不画条形码 */
    private var staffNo: String = ""

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIdCardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshCode() }
        binding.cardCode.setOnClickListener { refreshCode() }

        // insets 会多次回调，基准 padding 必须先存下来，否则每次累加
        val baseScrollBottom = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0
            )
            binding.scroll.setPadding(
                binding.scroll.paddingLeft,
                binding.scroll.paddingTop,
                binding.scroll.paddingRight,
                baseScrollBottom + bars.bottom
            )
            insets
        }

        binding.tvName.text = Session.name.ifEmpty { Session.username }
        binding.tvStaffNo.text = Session.username

        loadProfile()
        // 等第一次 layout 完成再取码：二维码位图必须按控件真实像素生成，
        // 否则会被 ImageView 二次缩放，模块尺寸不再是整数倍，扫码枪容易读不出
        binding.imgQr.post { refreshCode() }
    }

    override fun onDestroy() {
        super.onDestroy()
        codeJob?.cancel()
        profileJob?.cancel()
    }

    private fun loadProfile() {
        val userId = Session.userId
        if (userId.isBlank()) return
        profileJob = lifecycleScope.launch {
            val staff = try {
                withContext(Dispatchers.IO) {
                    RceApi.staffsBatch(listOf(userId))?.data?.firstOrNull()
                }
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                null
            } ?: return@launch

            bindProfile(staff)
        }
    }

    private fun bindProfile(staff: StaffBrief) {
        staff.name?.takeIf { it.isNotBlank() }?.let { binding.tvName.text = it }

        // studentNo 已截掉邮箱后缀：批量接口对部分学生返回的是「学号@学校邮箱域」，
        // 直接用它会让条形码里编进邮箱（见 StaffBrief.studentNo）
        val staffNoText = staff.studentNo
            ?: staff.duty_name?.takeIf { it.isNotBlank() }
            ?: Session.username
        staffNo = staffNoText
        binding.tvStaffNo.text = staffNoText

        val org = orgName(staff)
        binding.tvOrg.isVisible = !org.isNullOrBlank()
        binding.tvOrg.text = org.orEmpty()

        // 头像：取不到照片就留空，不画姓名首字、也不放占位圆（2026-10-26 用户明确要求）。
        // 地址走 bestPortrait（大图优先），跟个人资料页同一张，缩小显示
        binding.imgAvatar.bind(
            staff.name,
            staff.bestPortrait.takeIf { it.isNotBlank() },
            staff.altPortrait.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 组织名。优先顶层 `depart_name`；否则解析 `extra`。
     *
     * 官方在这里有 bug：它无条件把 `extra` 当 JSON 解析，而学生账号的 `extra` 是 `"30"` 这种
     * 纯数字，直接抛 `JSONException`，组织行永远空白。这里改成「解析得出才显示」。
     */
    private fun orgName(staff: StaffBrief): String? {
        staff.depart_name?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = staff.extra?.trim().orEmpty()
        if (!raw.startsWith("{")) return null
        return runCatching {
            val obj = JsonParser.parseString(raw).asJsonObject
            ORG_KEYS.firstNotNullOfOrNull { key ->
                obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun refreshCode() {
        codeJob?.cancel()
        codeJob = lifecycleScope.launch {
            binding.qrProgress.isVisible = true
            binding.tvError.isVisible = false

            // profile 决定条码内容，等它结束再画，避免先画一次再重画；
            // 但人员接口慢时不该拖住二维码，最多等 3 秒就先用会话里的学号画
            withTimeoutOrNull(PROFILE_WAIT_MS) { profileJob?.join() }

            val token = try {
                withContext(Dispatchers.IO) { RceApi.getUserQRCode() }.orEmpty()
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                showError(e.userMessage(this@IdCardActivity, R.string.id_card_error))
                return@launch
            }

            if (token.isBlank()) {
                showError(
                    getString(
                        R.string.id_card_error,
                        getString(R.string.id_card_error_empty)
                    )
                )
                return@launch
            }

            val qrSide = qrTargetPx()
            val barWidth = binding.imgBarcode.width.takeIf { it > 0 } ?: qrSide
            val barHeight = binding.imgBarcode.height.takeIf { it > 0 } ?: dp(74)
            val barText = staffNo

            // 编码是纯 CPU 工作（350 字节要跑 Reed-Solomon），放 IO 线程
            val (qr, barcode) = withContext(Dispatchers.IO) {
                IdCardCodec.qr(token, qrSide) to
                    barText.takeIf { it.isNotEmpty() }
                        ?.let { IdCardCodec.barcode(it, barWidth, barHeight) }
            }

            if (qr == null) {
                showError(
                    getString(
                        R.string.id_card_error,
                        getString(R.string.id_card_error_encode)
                    )
                )
                return@launch
            }

            binding.imgQr.setImageBitmap(qr)
            binding.imgBarcode.isVisible = barcode != null
            binding.imgBarcode.setImageBitmap(barcode)
            binding.tvBarcodeText.isVisible = barcode != null
            binding.tvBarcodeText.text = barText
            binding.qrProgress.isVisible = false
            binding.tvRefreshedAt.text = getString(
                R.string.id_card_refreshed_at,
                timeFormat.format(Date())
            )
        }
    }

    private fun showError(message: String) {
        binding.qrProgress.isVisible = false
        binding.tvError.text = message
        binding.tvError.isVisible = true
    }

    /** 二维码边长：优先用控件实际像素（已 layout 时），没有就按布局里的 240dp 兜底 */
    private fun qrTargetPx(): Int {
        val side = minOf(binding.imgQr.width, binding.imgQr.height)
        return if (side > 0) side else dp(240)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
