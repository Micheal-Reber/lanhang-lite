package com.lanhang.lite.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper

/**
 * 网页扫码页（相机预览 + zxing 解码），只服务于「赣青二课」的扫码签到/签退。
 *
 * 2026-11-15 从分叉 `Nuclear-Potatoes/lanhang-lite-miuix` 第 77 轮移植。为什么需要它：
 * 二课首页右上角的扫码按钮走 `window.CP2.showScanQr({needResult:1})` —— `window.CP2` 是
 * 青新闻 App 注入的桥对象，站点自己铺了一份同名 stub，但 `showScanQr` 返回的 Promise
 * **永远不 resolve**，所以在我们的 WebView 里点下去就是「毫无反应」。
 * 现在由 [ScanBridge] 把这个方法接管，落到这里做真正的相机扫码，
 * 按站点约定的结构 `{code:1, data:{qrCode}}` 回调给网页（见 OaWebViewActivity#deliverScanResult）。
 *
 * 移植时按本仓规范改了三处（其余相机/解码逻辑与分叉一致）：
 * - 文案走 `strings.xml`（中英），Activity 包 [LocaleHelper]（守卫要求）；
 * - 主题用 [R.style.Theme_LanhangLite_Fullscreen]（黑底 + 黑状态栏，与图片预览同款），
 *   targetSdk 35 强制 edge-to-edge，所以「关闭」按钮要按状态栏 inset 下移，否则被压在状态栏下面；
 * - 返回键走 `OnBackPressedDispatcher`（不 override 已废弃的 onBackPressed）。
 *
 * 解码用项目已有的 `com.google.zxing:core`（身份码生成也用它），只做解码、不做生成；
 * 预览用最朴素的 `android.hardware.Camera`（Camera1 虽已废弃，但只有它的
 * `setPreviewCallback` 能拿到 NV21 原始帧，且不用引 CameraX）。
 */
class ScanQrActivity : AppCompatActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    companion object {
        const val EXTRA_RESULT = "qr_result"

        /** 扫码成功（网页侧据此走签到流程） */
        const val RESULT_SCANNED = 1001

        /** 用户放弃（返回键 / 关闭按钮 / 没有相机权限） */
        const val RESULT_CANCELLED = 1002

        fun intent(context: Context): Intent = Intent(context, ScanQrActivity::class.java)
    }

    private var camera: Camera? = null
    private var previewing = false
    private val reader = MultiFormatReader()
    private var hint: TextView? = null

    /** 预览帧宽高：Camera1 的 onPreviewFrame 每秒十几帧，每帧问一次 parameters 会掉帧 */
    private var previewW = 0
    private var previewH = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 正常路径不会走到这里（OaWebViewActivity 那边先申请过权限），
        // 万一没授权就直接回「取消」，别卡一个黑屏让用户以为卡死
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setResult(RESULT_CANCELLED)
            finish()
            return
        }
        reader.setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.EAN_13,
                ),
                DecodeHintType.TRY_HARDER to true,
            )
        )

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(
            SurfaceView(this).apply { holder.addCallback(this@ScanQrActivity) },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val tip = TextView(this).apply {
            setText(R.string.scan_qr_hint)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        hint = tip
        val tipParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM; bottomMargin = dp(160) }
        root.addView(tip, tipParams)

        val close = TextView(this).apply {
            setText(R.string.scan_qr_close)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener {
                setResult(RESULT_CANCELLED)
                finish()
            }
        }
        val closeParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(16); marginEnd = dp(8) }
        root.addView(close, closeParams)

        // edge-to-edge：顶部按钮让开状态栏，底部提示让开导航栏（inset 会多次回调，直接 set 而不是累加）
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            closeParams.topMargin = dp(16) + bars.top
            close.layoutParams = closeParams
            tipParams.bottomMargin = dp(160) + bars.bottom
            tip.layoutParams = tipParams
            insets
        }

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_CANCELLED)
                finish()
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun surfaceCreated(holder: SurfaceHolder) {
        startPreview(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (previewing) {
            runCatching { camera?.stopPreview() }
            previewing = false
        }
        startPreview(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopPreview()
    }

    private fun startPreview(holder: SurfaceHolder) {
        if (previewing) return
        // 摄像头被别的应用占着时 Camera.open() 会返回 null，再用 0 号显式试一次
        val cam = runCatching { Camera.open() }.getOrNull()
            ?: runCatching { Camera.open(0) }.getOrNull()
        if (cam == null) {
            android.util.Log.e("ScanQr", "Camera.open 两次都失败，没有可用摄像头")
            hint?.setText(R.string.scan_qr_no_camera)
            return
        }
        camera = cam

        // 每一项设置单独兜住：模拟器/部分机型的虚拟摄像头不支持连续对焦、也未必接受 NV21，
        // 任何一项抛异常都不该把整个预览拖垮（分叉那边踩过「一锅端只看得到初始化失败」）
        runCatching {
            val params = cam.parameters
            val sizes = params.supportedPreviewSizes.orEmpty()
            val target = sizes
                .filter { it.width <= 1280 && it.height <= 1280 }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
            if (target != null) params.setPreviewSize(target.width, target.height)
            // 只有设备明确支持连续对焦时才设，否则 setParameters 抛 IllegalArgumentException
            val modes = runCatching { params.supportedFocusModes }.getOrNull().orEmpty()
            if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = params
            previewW = params.previewSize?.width ?: 0
            previewH = params.previewSize?.height ?: 0
        }.onFailure { android.util.Log.w("ScanQr", "摄像头参数设置失败，用默认值继续", it) }

        // 不强求 NV21：默认 YV12 的 Y 平面与 NV21 完全相同，zxing 只读 Y 平面
        runCatching {
            val params = cam.parameters
            params.previewFormat = ImageFormat.NV21
            cam.parameters = params
        }.onFailure { android.util.Log.w("ScanQr", "设置 NV21 失败，沿用默认格式", it) }

        // 开流失败就是失败，必须报出来
        runCatching {
            cam.setDisplayOrientation(90)
            cam.setPreviewDisplay(holder)
            cam.setPreviewCallback(this)
            cam.startPreview()
            previewing = true
            hint?.setText(R.string.scan_qr_hint)
        }.onFailure {
            android.util.Log.e("ScanQr", "启动预览失败", it)
            hint?.setText(R.string.scan_qr_init_failed)
            stopPreview()
        }
    }

    private fun stopPreview() {
        val cam = camera ?: return
        runCatching {
            cam.setPreviewCallback(null)
            cam.stopPreview()
        }
        runCatching { cam.release() }
        camera = null
        previewing = false
    }

    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        if (data == null || cam == null) return
        // 用开预览时缓存的尺寸，不再每帧 setParameters 级地问一次
        val w = if (previewW > 0) previewW else (cam.parameters.previewSize?.width ?: return)
        val h = if (previewH > 0) previewH else (cam.parameters.previewSize?.height ?: return)
        // NV21 的 Y 平面在最前面，正好是 zxing 要的灰度图；预览被转了 90°，这里反过来把宽高对调
        val source = runCatching {
            PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        }.getOrNull() ?: return
        val rotated = runCatching { source.rotateCounterClockwise() }.getOrNull() ?: source
        val bitmap = BinaryBitmap(HybridBinarizer(rotated))
        val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
        reader.reset()
        if (result != null) {
            // 先停预览再回调，避免同一条码连着触发好几次
            stopPreview()
            setResult(RESULT_SCANNED, Intent().putExtra(EXTRA_RESULT, result.text.orEmpty()))
            finish()
        }
    }

    override fun onPause() {
        stopPreview()
        super.onPause()
    }
}
