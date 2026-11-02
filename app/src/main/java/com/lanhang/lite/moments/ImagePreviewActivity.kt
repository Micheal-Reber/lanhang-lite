package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.lanhang.lite.R
import com.lanhang.lite.core.ImageSaver
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.toast
import com.lanhang.lite.databinding.ActivityImagePreviewBinding
import kotlinx.coroutines.launch

/** 工作圈图片全屏预览：左右滑动多图，双指/双击缩放，单击关闭 */
class ImagePreviewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    private lateinit var binding: ActivityImagePreviewBinding
    private val urls by lazy { intent.getStringArrayListExtra(EXTRA_URLS).orEmpty() }

    /** 正在保存（防连点） */
    private var saving = false

    /** Q 以下走 SAF 时，下载好等用户选位置的那张图 */
    private var pending: ImageSaver.Prepared? = null

    /**
     * Q 以下的保存落点。
     *
     * Android 10 起写相册不需要权限，10 之前要 `WRITE_EXTERNAL_STORAGE` 运行时权限 ——
     * 为了少数老设备去申请一个已被 Google 废弃的权限不划算，这里改成让用户自己选位置。
     * Q+ 根本不会走这个分支（见 [ImageSaver.canSaveToGallery]）。
     */
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        val prepared = pending
        pending = null
        if (uri == null || prepared == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = ImageSaver.writeTo(this@ImagePreviewActivity, uri, prepared)
            toast(getString(if (ok) R.string.image_saved else R.string.image_save_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.let { v ->
            ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val density = resources.displayMetrics.density
                val indexLp = binding.tvIndex.layoutParams as ViewGroup.MarginLayoutParams
                indexLp.topMargin = (16 * density).toInt() + bars.top
                binding.tvIndex.layoutParams = indexLp
                // 保存按钮和「2 / 5」用同一个基准线，状态栏高度变了也不会错位
                val saveLp = binding.btnSave.layoutParams as ViewGroup.MarginLayoutParams
                saveLp.topMargin = (12 * density).toInt() + bars.top
                binding.btnSave.layoutParams = saveLp
                insets
            }
        }

        binding.btnSave.setOnClickListener { saveCurrent() }
        binding.pager.adapter = PreviewAdapter(urls) { finishWithFade() }
        binding.pager.setCurrentItem(
            intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.size - 1), false
        )
        updateIndex(binding.pager.currentItem)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndex(position)
                resetOtherPages(position)
            }
        })
    }

    /**
     * 翻页后把**别的**页面恢复成 fitCenter。
     *
     * ViewPager2 默认保留左右各一页，而这些页里的图片是「矩阵变换」状态：
     * 不主动复位的话，放大看第 1 张 → 划到第 2 张 → 再划回来，第 1 张还是放大状态、
     * 平移位置还是上次的（用户会以为「图片错位了」）。
     */
    private fun resetOtherPages(current: Int) {
        val rv = binding.pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? PreviewAdapter.VH ?: continue
            if (holder.bindingAdapterPosition != current) holder.iv.resetIfZoomed()
        }
    }

    private fun updateIndex(position: Int) {
        binding.tvIndex.text = if (urls.size > 1) "${position + 1} / ${urls.size}" else ""
    }

    /**
     * 保存当前这张到相册。
     *
     * 下载 + 识别格式都在 IO（[ImageSaver.prepare]），按钮在过程中置灰：一张原图几 MB，
     * 弱网下要等几秒，不给反馈用户会以为没点上而反复点。
     */
    private fun saveCurrent() {
        if (saving) return
        val url = urls.getOrNull(binding.pager.currentItem)?.takeIf { it.isNotBlank() } ?: return
        saving = true
        binding.btnSave.isEnabled = false
        binding.btnSave.alpha = 0.4f
        lifecycleScope.launch {
            try {
                val prepared = ImageSaver.prepare(url)
                if (prepared == null) {
                    toast(getString(R.string.image_save_failed))
                    return@launch
                }
                if (ImageSaver.canSaveToGallery) {
                    val ok = ImageSaver.saveToGallery(this@ImagePreviewActivity, prepared)
                    toast(getString(if (ok) R.string.image_saved else R.string.image_save_failed))
                } else {
                    // 老系统：先把图下好，再让用户挑位置（记住 prepared，回调里用）
                    pending = prepared
                    createDocument.launch(prepared.fileName)
                }
            } finally {
                saving = false
                binding.btnSave.isEnabled = true
                binding.btnSave.alpha = 1f
            }
        }
    }

    private fun finishWithFade() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private class PreviewAdapter(
        private val urls: List<String>,
        private val onClose: () -> Unit
    ) : RecyclerView.Adapter<PreviewAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ZoomImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                onTap = onClose
            }
            return VH(iv)
        }

        override fun getItemCount(): Int = urls.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.iv.load(urls[position]) {
                crossfade(150)
                // 🔴 解码尺寸必须**大于控件尺寸**：coil 默认按 View 测量值取样，而这一页是 MATCH_PARENT
                // → 正好等于屏宽。那样双指放到 2.5x 只是把 1080 的位图拉伸，看着就是一片糊
                // （2026-10-31 用户对比「放大很模糊」）。这里按最长边的 1.5 倍解码并封顶 2000px：
                // 放到 1.5x 以内是 1:1 像素，再大也只是轻度插值；上限是为了不 OOM（ViewPager2 会留 3 页）。
                val longSide = maxOf(
                    holder.iv.resources.displayMetrics.widthPixels,
                    holder.iv.resources.displayMetrics.heightPixels,
                )
                val target = (longSide * 1.5f).toInt().coerceAtMost(2000)
                size(target, target)
            }
        }

        class VH(val iv: ZoomImageView) : RecyclerView.ViewHolder(iv)
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_INDEX = "index"

        fun start(context: Context, urls: List<String>, index: Int) {
            if (urls.isEmpty()) return
            context.startActivity(
                Intent(context, ImagePreviewActivity::class.java).apply {
                    putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    putExtra(EXTRA_INDEX, index)
                }
            )
        }
    }
}
