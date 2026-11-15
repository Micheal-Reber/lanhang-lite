package com.lanhang.lite.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
 * 2026-11-15 从分叉 `Nuclear-Potatoes/lanhang-lite-miuix` 第 77 轮移植，
 * 同日晚些补上第 78/79 轮的扫码页改造。为什么需要它：
 * 二课首页右上角的扫码按钮走 `window.CP2.showScanQr({needResult:1})` —— `window.CP2` 是
 * 青新闻 App 注入的桥对象，站点自己铺了一份同名 stub，但 `showScanQr` 返回的 Promise
 * **永远不 resolve**，所以在我们的 WebView 里点下去就是「毫无反应」。
 * 现在由 [ScanBridge] 把这个方法接管，落到这里做真正的相机扫码，
 * 按站点约定的结构 `{code:1, data:{qrCode}}` 回调给网页（见 OaWebViewActivity#deliverScanResult）。
 *
 * 三处比移植版多做的事（第 78/79 轮，都是「真机一眼能看出来」的问题）：
 * - **预览变形**：`SurfaceView` 用 MATCH_PARENT 时，摄像头给的 4:3 画面会被屏幕比例硬拉。
 *   [CoverSurfaceView] 按真实分辨率做 centerCrop（等价 ImageView 的 centerCrop），溢出部分裁掉。
 * - **没有取景框**：只有一行「把二维码放进框内」，用户不知道对准哪里。[ScanOverlayView]
 *   四周压暗 + 中间方形取景区 + 四角亮线 + 一条扫描线，纯 Canvas 画，不引图片。
 * - **没有补光**：馆内签到机常在光线不好的角落。右下角加了手电筒按钮（[ic_torch]，图标见该文件注释）。
 *
 * 移植时按本仓规范改的地方（其余相机/解码逻辑与分叉一致）：
 * - 文案走 `strings.xml`（中英），Activity 包 [LocaleHelper]（守卫要求）；
 * - 主题用 [R.style.Theme_LanhangLite_Fullscreen]（黑底 + 黑状态栏，与图片预览同款），
 *   targetSdk 35 强制 edge-to-edge，所以「关闭」按钮、底部提示、手电筒都要按系统栏 inset 让位；
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

    /** 预览层：自己按摄像头比例量尺寸，是 [CoverSurfaceView] 才能设 ratio */
    private var surfaceView: CoverSurfaceView? = null

    private var torchButton: ImageView? = null
    private var torchOn = false

    /** 设备支持手电筒与否；null = 还没问过（每次重新开预览都重置） */
    private var torchSupported: Boolean? = null

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

        // 预览层：尺寸由 CoverSurfaceView 自己算，父容器居中 + 裁剪，溢出部分看不到
        val surface = CoverSurfaceView(this).apply { holder.addCallback(this@ScanQrActivity) }
        surfaceView = surface
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        )

        // 取景蒙层：四周压暗、中间透出取景方框、四角亮线
        root.addView(
            ScanOverlayView(this),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )

        val tip = TextView(this).apply {
            setText(R.string.scan_qr_hint)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            // 蒙层是半透明黑，白字在亮场景（雪白墙、白纸）上会糊掉，加一层阴影兜住
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
        }
        hint = tip
        val tipParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM; bottomMargin = dp(128f) }
        root.addView(tip, tipParams)

        // 手电筒：64dp 圆形，图标见 ic_torch.xml。点亮时白底深色图标，一眼看出状态
        val torch = ImageView(this).apply {
            setImageResource(R.drawable.ic_torch)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(17f), dp(17f), dp(17f), dp(17f))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.scan_qr_torch_on)
            setOnClickListener { toggleTorch() }
            applyTorchLook(false)
        }
        torchButton = torch
        val torchSize = dp(64f)
        val torchParams = FrameLayout.LayoutParams(torchSize, torchSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(28f)
            bottomMargin = dp(20f)
        }
        root.addView(torch, torchParams)

        val close = TextView(this).apply {
            setText(R.string.scan_qr_close)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
            setOnClickListener {
                setResult(RESULT_CANCELLED)
                finish()
            }
        }
        val closeParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(8f); marginEnd = dp(8f) }
        root.addView(close, closeParams)

        // edge-to-edge：顶部按钮让开状态栏，底部提示与手电筒让开导航栏。
        // inset 会多次回调，所以基准值必须在监听外先取好，这里直接按基准 + inset 重算。
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            closeParams.topMargin = dp(8f) + bars.top
            close.layoutParams = closeParams
            tipParams.bottomMargin = dp(128f) + bars.bottom
            tip.layoutParams = tipParams
            torchParams.bottomMargin = dp(20f) + bars.bottom
            torch.layoutParams = torchParams
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

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    // ==================== 手电筒 ====================

    /** 按钮外观：开=白底深色图标（正在补光），关=半透明黑底白色图标 */
    private fun ImageView.applyTorchLook(on: Boolean) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xF2FFFFFF.toInt() else 0x66000000)
            setStroke(dp(1f), if (on) 0x00000000 else 0x40FFFFFF)
        }
        imageTintList = ColorStateList.valueOf(if (on) 0xFF1A1A1A.toInt() else Color.WHITE)
    }

    /**
     * 切换常亮补光。
     *
     * 先问一遍 `supportedFlashModes`：模拟器与不带补光灯的前摄会返回空列表，
     * 这时直接设 `flashMode` 会抛 `IllegalArgumentException`，所以要老实告诉用户「不支持」。
     * 设置失败（比如被别的应用占用）时回滚状态，保证按钮显示和硬件一致 ——
     * 按钮亮着但没补光比按钮不亮更误导人。
     */
    private fun toggleTorch() {
        val cam = camera
        if (cam == null) {
            hint?.setText(R.string.scan_qr_not_ready)
            return
        }
        val supported = torchSupported ?: runCatching {
            cam.parameters.supportedFlashModes.orEmpty()
                .contains(Camera.Parameters.FLASH_MODE_TORCH)
        }.getOrDefault(false).also { torchSupported = it }
        if (!supported) {
            hint?.setText(R.string.scan_qr_torch_unsupported)
            return
        }
        val want = !torchOn
        val ok = runCatching {
            val params = cam.parameters
            params.flashMode = if (want) {
                Camera.Parameters.FLASH_MODE_TORCH
            } else {
                Camera.Parameters.FLASH_MODE_OFF
            }
            cam.parameters = params
            true
        }.getOrDefault(false)
        torchOn = ok && want
        torchButton?.applyTorchLook(torchOn)
        hint?.setText(if (torchOn) R.string.scan_qr_torch_on else R.string.scan_qr_hint)
    }

    // ==================== 相机 ====================

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
        // 每次开流都重新探测一次手电筒能力：前后摄能力不同，换机/换镜头后旧结论不成立
        torchSupported = null
        torchOn = false
        torchButton?.applyTorchLook(false)

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
            // 预览跑起来才知道真实分辨率：把比例交给 SurfaceView 重量一次，
            // 它按「居中裁剪铺满」算出正确尺寸，画面才不会像之前那样被拉扁。
            // 🔴 注意是 previewH / previewW —— setDisplayOrientation(90) 之后画面转成竖版，
            // 显示宽/高 = 原高/原宽，写成 previewW / previewH 会歪得更厉害。
            if (previewW > 0 && previewH > 0) {
                surfaceView?.let {
                    it.ratio = previewH.toFloat() / previewW.toFloat()
                    it.requestLayout()
                }
            } else {
                android.util.Log.w("ScanQr", "拿不到预览尺寸，按整屏铺开，画面可能变形")
            }
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
        // 硬件已经释放，按钮不能还停在「已打开」状态
        torchOn = false
        torchSupported = null
        torchButton?.applyTorchLook(false)
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

    /**
     * 按摄像头真实比例铺满屏幕的预览层。
     *
     * 摄像头给的是 4:3（如 640×480），屏幕是 1220×2656。直接把 SurfaceView 设成 MATCH_PARENT
     * 会让画面横向被拉长一倍多，人眼立刻能看出「变形」——Camera1 没有 centerCrop 开关，只能自己算。
     * 这里按 [ratio]（显示后的 宽/高）把尺寸放大到刚好盖住父容器，溢出部分由父容器居中裁掉，
     * 效果与 ImageView 的 `centerCrop` 一致。
     */
    private class CoverSurfaceView(context: Context) : SurfaceView(context) {

        /** 显示内容的 宽/高 比；0 = 还没拿到摄像头参数（此时按整屏铺，宁可临时变形也别一块空白） */
        var ratio = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            if (ratio <= 0f || w <= 0 || h <= 0) {
                setMeasuredDimension(w, h)
                return
            }
            val widthByHeight = (h * ratio).toInt()
            if (widthByHeight >= w) {
                setMeasuredDimension(widthByHeight, h)
            } else {
                setMeasuredDimension(w, (w / ratio).toInt())
            }
        }
    }

    /**
     * 取景蒙层：四周压暗、中间挖一个方形扫描区、四角画亮线，并在正中画一条扫描线。
     *
     * 纯 Canvas 绘制，不引任何图片资源。遮罩分四块 `drawRect` 画（不用 `CLEAR` 图层挖洞，
     * 那需要额外图层合成，在相机预览上会掉帧）。
     */
    private class ScanOverlayView(context: Context) : View(context) {

        private val hole = RectF()
        private val maskPaint = Paint().apply { color = 0xA6000000.toInt() }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 3.5f
            strokeCap = Paint.Cap.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2f
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val side = minOf(w, h) * 0.66f
            val cx = w / 2f
            // 稍上移，给底部提示与手电筒让出空间，视觉重心更稳
            val cy = h / 2f - h * 0.06f
            hole.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (hole.isEmpty) return
            val w = width.toFloat()
            val h = height.toFloat()

            // 四周压暗
            canvas.drawRect(0f, 0f, w, hole.top, maskPaint)
            canvas.drawRect(0f, hole.bottom, w, h, maskPaint)
            canvas.drawRect(0f, hole.top, hole.left, hole.bottom, maskPaint)
            canvas.drawRect(hole.right, hole.top, w, hole.bottom, maskPaint)

            // 四角亮线：每角两条，长度取边长 17%
            val len = hole.width() * 0.17f
            canvas.drawLine(hole.left, hole.top, hole.left + len, hole.top, framePaint)
            canvas.drawLine(hole.left, hole.top, hole.left, hole.top + len, framePaint)
            canvas.drawLine(hole.right - len, hole.top, hole.right, hole.top, framePaint)
            canvas.drawLine(hole.right, hole.top, hole.right, hole.top + len, framePaint)
            canvas.drawLine(hole.left, hole.bottom - len, hole.left, hole.bottom, framePaint)
            canvas.drawLine(hole.left, hole.bottom, hole.left + len, hole.bottom, framePaint)
            canvas.drawLine(hole.right, hole.bottom - len, hole.right, hole.bottom, framePaint)
            canvas.drawLine(hole.right - len, hole.bottom, hole.right, hole.bottom, framePaint)

            // 中间横向扫描线，帮用户判断「对准这里」
            val midY = hole.centerY()
            canvas.drawLine(hole.left + len * 0.5f, midY, hole.right - len * 0.5f, midY, linePaint)
        }
    }
}
