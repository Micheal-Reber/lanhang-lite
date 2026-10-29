package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.lanhang.lite.R
import com.lanhang.lite.databinding.ActivityImagePreviewBinding
import com.lanhang.lite.core.LocaleHelper

/** 工作圈图片全屏预览：左右滑动多图，双指/双击缩放，单击关闭 */
class ImagePreviewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    private lateinit var binding: ActivityImagePreviewBinding
    private val urls by lazy { intent.getStringArrayListExtra(EXTRA_URLS).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.let { v ->
            ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val lp = binding.tvIndex.layoutParams as ViewGroup.MarginLayoutParams
                lp.topMargin = 16 * resources.displayMetrics.density.toInt() + bars.top
                binding.tvIndex.layoutParams = lp
                insets
            }
        }

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
