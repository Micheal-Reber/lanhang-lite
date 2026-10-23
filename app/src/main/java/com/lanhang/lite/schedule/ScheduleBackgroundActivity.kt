package com.lanhang.lite.schedule

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.lanhang.lite.R
import com.lanhang.lite.databinding.ActivityScheduleBackgroundBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 课表背景设置：预览 → 应用 / 移除。
 * 预览与真实课表共用 [ScheduleStyle] 与 [SchedulePreview]，所见即所得。
 */
class ScheduleBackgroundActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleBackgroundBinding

    private var spec = ScheduleBackdrop.Spec()

    /** 相册里刚选中、尚未写入私有目录的图片 */
    private var pendingImage: Uri? = null
    private var previewJob: Job? = null

    private val accentColor: Int by lazy { ScheduleStyle.fromTheme(this).accent }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            pendingImage = uri
            spec = ScheduleBackdrop.Spec(ScheduleBackdrop.TYPE_IMAGE, "")
            updateImageState()
            rebuildSwatches()
            refreshPreview()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleBackgroundBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0
            )
            binding.actionBar.setPadding(dp(16), dp(8), dp(16), dp(8) + bars.bottom)
            insets
        }

        layoutInflater.inflate(R.layout.view_schedule_preview, binding.previewHost, true)

        spec = ScheduleBackdrop.load(this)
        binding.rowPickImage.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnApply.setOnClickListener { applyBackground() }
        binding.btnRemove.setOnClickListener { removeBackground() }

        rebuildSwatches()
        updateImageState()
        refreshPreview()
    }

    override fun onDestroy() {
        previewJob?.cancel()
        super.onDestroy()
    }

    // ==================== 选择项 ====================

    private fun rebuildSwatches() {
        buildSwatchRow(
            binding.solidRow,
            ScheduleBackdrop.presets.filter { it.type == ScheduleBackdrop.TYPE_COLOR }
        )
        buildSwatchRow(
            binding.gradientRow,
            ScheduleBackdrop.presets.filter { it.type == ScheduleBackdrop.TYPE_GRADIENT }
        )
    }

    private fun buildSwatchRow(container: LinearLayout, presets: List<ScheduleBackdrop.Preset>) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        presets.forEach { preset ->
            val selected = spec.type == preset.type && spec.value == preset.id
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f).apply {
                    marginEnd = (6 * density).toInt()
                }
                isClickable = true
                isFocusable = true
                contentDescription = getString(preset.labelRes)
                setOnClickListener { select(preset) }
            }

            val fill = android.view.View(this)
            fill.background = if (preset.type == ScheduleBackdrop.TYPE_GRADIENT) {
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(preset.start, preset.end)
                ).apply { cornerRadius = 12f * density }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(preset.start)
                }
            }
            val inset = if (selected) (5 * density).toInt() else 0
            cell.addView(fill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(inset, inset, inset, inset) })

            if (selected) {
                cell.background = GradientDrawable().apply {
                    shape = if (preset.type == ScheduleBackdrop.TYPE_GRADIENT) {
                        GradientDrawable.RECTANGLE
                    } else {
                        GradientDrawable.OVAL
                    }
                    cornerRadius = 15f * density
                    setStroke((2 * density).toInt().coerceAtLeast(2), accentColor)
                }
            }
            container.addView(cell)
        }
    }

    private fun select(preset: ScheduleBackdrop.Preset) {
        pendingImage = null
        spec = ScheduleBackdrop.Spec(preset.type, preset.id)
        updateImageState()
        rebuildSwatches()
        refreshPreview()
    }

    private fun updateImageState() {
        val density = resources.displayMetrics.density
        val source: Any? = when {
            pendingImage != null -> pendingImage
            spec.type == ScheduleBackdrop.TYPE_IMAGE -> ScheduleBackdrop.imageFile(this)
            else -> null
        }
        val thumb = binding.imgPhotoThumb
        if (source != null) {
            thumb.setPadding(0, 0, 0, 0)
            thumb.imageTintList = null
            thumb.load(source) { crossfade(false) }
        } else {
            val padding = (10 * density).toInt()
            thumb.setPadding(padding, padding, padding, padding)
            thumb.imageTintList = ColorStateList.valueOf(accentColor)
            thumb.setImageResource(R.drawable.ic_photo)
        }
        binding.tvImageState.text = when {
            pendingImage != null -> getString(R.string.schedule_bg_image_pending)
            spec.type == ScheduleBackdrop.TYPE_IMAGE -> getString(R.string.schedule_bg_image_in_use)
            else -> getString(R.string.schedule_bg_image_supported)
        }
    }

    // ==================== 预览 ====================

    private fun refreshPreview() {
        previewJob?.cancel()
        val target = spec
        previewJob = lifecycleScope.launch {
            val analysis = withContext(Dispatchers.IO) {
                runCatching { ScheduleBackdrop.analyze(this@ScheduleBackgroundActivity, target, pendingImage) }
                    .getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            val style = analysis?.let { ScheduleStyle.fromBackdrop(it) }
                ?: ScheduleStyle.fromTheme(this@ScheduleBackgroundActivity)
            val source: Any? = when {
                pendingImage != null -> pendingImage
                target.type == ScheduleBackdrop.TYPE_IMAGE -> ScheduleBackdrop.imageFile(
                    this@ScheduleBackgroundActivity
                )

                else -> null
            }
            SchedulePreview.render(binding.previewHost, target, analysis, source, style)
            binding.tvToneHint.text = when {
                analysis == null -> getString(R.string.schedule_bg_tone_none)
                analysis.darkText -> getString(R.string.schedule_bg_tone_dark_text)
                else -> getString(R.string.schedule_bg_tone_light_text)
            }
        }
    }

    // ==================== 应用 / 移除 ====================

    private fun applyBackground() {
        val target = spec
        if (!target.isCustom) {
            removeBackground()
            return
        }
        binding.btnApply.isEnabled = false
        lifecycleScope.launch {
            val image = pendingImage
            if (image != null) {
                val saved = withContext(Dispatchers.IO) {
                    ScheduleBackdrop.saveImage(this@ScheduleBackgroundActivity, image)
                }
                if (!saved) {
                    binding.btnApply.isEnabled = true
                    Toast.makeText(
                        this@ScheduleBackgroundActivity,
                        R.string.schedule_bg_image_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
            }
            ScheduleBackdrop.save(this@ScheduleBackgroundActivity, target)
            Toast.makeText(
                this@ScheduleBackgroundActivity,
                R.string.schedule_bg_applied,
                Toast.LENGTH_SHORT
            ).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun removeBackground() {
        ScheduleBackdrop.clear(this)
        Toast.makeText(this, R.string.schedule_bg_removed, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
