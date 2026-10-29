package com.lanhang.lite.moments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityPublishMomentBinding
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.settings.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发表动态（独立页面）。
 *
 * 从原来的 Dialog 改造成页面，只换承载方式，**发布能力一项没少**：
 * 2000 字上限与计数、最多 9 张图（追加选图、去重）、缩略图点开全屏预览、
 * 单张移除、发布前校验、图片上传 + 发 feed、失败原因落诊断日志。
 *
 * 相对弹窗额外补的两点（页面化的必要交互）：
 * - 返回时若还有内容，先确认再退出，避免误触丢失草稿
 * - 发布中禁用输入并屏蔽返回，避免上传到一半被取消留下半截状态
 */
class PublishMomentActivity : AppCompatActivity() {

    /**
     * 语言包装。本项目不用系统的 per-app locale，而是给每个 Activity 的 base context
     * 包一层 `createConfigurationContext`（见 [com.lanhang.lite.core.LocaleHelper]）。
     * **漏掉这一层，页面就会一直用系统语言**：系统英文 + App 内选中文时，
     * 其它页面是中文、这一页却是英文。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.lanhang.lite.core.LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityPublishMomentBinding
    private val selectedImages = mutableListOf<Uri>()
    private var publishing = false

    private var colorVariant = 0
    private var colorError = 0
    private var accent = 0

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        // 追加而不是替换：再次点「添加」不能把已经选好的图冲掉；
        // 同一个 Uri 重复选中只留一份，总数仍受 9 张上限约束。
        uris.forEach { uri ->
            if (selectedImages.size < MAX_IMAGES && selectedImages.none { it == uri }) {
                selectedImages.add(uri)
            }
        }
        renderSelectedImages()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPublishMomentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        colorVariant = MaterialColors.getColor(
            binding.tvCounter,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        colorError = MaterialColors.getColor(
            binding.tvCounter,
            com.google.android.material.R.attr.colorError
        )
        // 随明暗主题翻转的品牌强调色：夜间取亮蓝（values-night 的 primary），
        // 压在深色面板上仍然清楚。不要再硬编码某一套明暗值。
        accent = MaterialColors.getColor(
            binding.tvCounter,
            com.google.android.material.R.attr.colorPrimary
        )

        binding.toolbar.setNavigationOnClickListener { confirmExit() }
        binding.btnPublish.setOnClickListener { publish() }
        binding.etContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                Unit

            override fun afterTextChanged(s: Editable?) = refreshState()
        })

        // 页面化后返回手势/返回键都要走同一套「内容未保存」确认
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        applyInsets()
        renderSelectedImages()
        refreshState()
    }

    private fun applyInsets() {
        // insets 会多次回调，基准值先存下来，否则每次累加
        val baseBottom = resources.getDimensionPixelSize(R.dimen.space_sm)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 键盘也算「底部遮挡」（2026-10-28）：edge-to-edge 下 adjustResize 不改窗口高度，
            // 不补 ime 高度的话底栏（字数统计 + 发表按钮）会整条躲在键盘后面
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.toolbar.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = baseBottom + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    /** 字数与「发表」可用性一起刷新：空内容时按钮置灰，不靠点击后弹提示 */
    private fun refreshState() {
        val length = binding.etContent.text.length
        binding.tvCounter.text = getString(R.string.moments_publish_counter, length, MAX_LENGTH)
        binding.tvCounter.setTextColor(if (length >= MAX_LENGTH) colorError else colorVariant)
        binding.btnPublish.isEnabled = !publishing && (length > 0 || selectedImages.isNotEmpty())
    }

    private fun confirmExit() {
        if (publishing) return
        val hasDraft = binding.etContent.text.isNotBlank() || selectedImages.isNotEmpty()
        if (!hasDraft) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.moments_publish_discard_title)
            .setMessage(R.string.moments_publish_discard_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.moments_publish_discard_confirm) { _, _ -> finish() }
            .show()
    }

    private fun publish() {
        if (publishing) return
        val text = binding.etContent.text.toString().trim()
        val images = selectedImages.toList()
        when {
            text.isEmpty() && images.isEmpty() -> {
                toast(getString(R.string.moments_publish_empty))
                return
            }

            text.length > MAX_LENGTH -> {
                toast(getString(R.string.moments_publish_too_long))
                return
            }
        }

        publishing = true
        setBusy(true)
        lifecycleScope.launch {
            try {
                val appContext = applicationContext
                withContext(Dispatchers.IO) {
                    val media = images.map { MomentImageUploader.upload(appContext, it) }
                    check(MomentsApi.publishFeed(text, media) != null) {
                        getString(R.string.moments_server_no_id)
                    }
                }
                selectedImages.clear()
                toast(getString(R.string.moments_publish_done))
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: Exception) {
                // 取消（退出页面）不算失败，不能弹提示，也不能继续碰界面
                if (e.isCancellation) return@launch
                Diagnostics.record(this@PublishMomentActivity, getString(R.string.moments_tag_publish), e)
                toast(e.userMessage(this@PublishMomentActivity, R.string.moments_publish_failed))
            } finally {
                publishing = false
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        binding.etContent.isEnabled = !busy
        refreshState()
    }

    /** 缩略图 + 末尾的「添加」方块；选满 9 张后添加方块自动消失 */
    private fun renderSelectedImages() {
        val row = binding.imageRow
        row.removeAllViews()
        val ctx = this
        val tileSize = resources.getDimensionPixelSize(R.dimen.publish_image_tile)
        val radius = resources.getDimension(R.dimen.publish_image_radius)
        val gap = resources.getDimensionPixelSize(R.dimen.publish_image_gap)
        val removeSize = resources.getDimensionPixelSize(R.dimen.publish_image_remove)

        selectedImages.forEachIndexed { index, uri ->
            val tile = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                    marginEnd = gap
                }
                isClickable = true
                isFocusable = true
                // 点缩略图看大图：复用工作圈的全屏预览（多图可左右滑、双击缩放、单击关闭）
                setOnClickListener {
                    if (index in selectedImages.indices) {
                        ImagePreviewActivity.start(
                            ctx,
                            selectedImages.map { it.toString() },
                            index
                        )
                    }
                }
            }
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
                load(uri)
            }
            tile.addView(image, FrameLayout.LayoutParams(-1, -1))

            val remove = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(android.graphics.Color.WHITE)
                val inset = resources.getDimensionPixelSize(R.dimen.space_xs)
                setPadding(inset, inset, inset, inset)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xC2000000.toInt())
                }
                contentDescription = getString(R.string.moments_remove_image)
                setOnClickListener {
                    if (index in selectedImages.indices) {
                        selectedImages.removeAt(index)
                        renderSelectedImages()
                    }
                }
            }
            tile.addView(
                remove,
                FrameLayout.LayoutParams(removeSize, removeSize, Gravity.TOP or Gravity.END).apply {
                    topMargin = resources.getDimensionPixelSize(R.dimen.space_xs)
                    marginEnd = resources.getDimensionPixelSize(R.dimen.space_xs)
                }
            )
            row.addView(tile)
        }

        if (selectedImages.size < MAX_IMAGES) {
            val add = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_publish_add_tile)
                layoutParams = LinearLayout.LayoutParams(tileSize, tileSize)
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.moments_add_image_short)
                setOnClickListener {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            val iconSize = resources.getDimensionPixelSize(R.dimen.icon_lg)
            add.addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_add)
                    setColorFilter(accent)
                },
                LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
            add.addView(
                TextView(ctx).apply {
                    text = getString(R.string.moments_add_image_short)
                    // 字号走 token（11sp = text_label_small），不直写 sp
                    setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.text_label_small)
                    )
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    setPadding(0, resources.getDimensionPixelSize(R.dimen.space_xs), 0, 0)
                },
                // 显式居中：文字与上面的加号保持同一条竖直中线
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
            row.addView(add)
        }

        // 图片增减会影响「发表」按钮的可用性
        refreshState()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val MAX_IMAGES = 9
        const val MAX_LENGTH = 2000

        fun intent(context: Context): Intent =
            Intent(context, PublishMomentActivity::class.java)
    }
}
