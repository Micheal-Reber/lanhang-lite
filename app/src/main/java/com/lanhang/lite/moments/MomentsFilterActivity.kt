package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.revealInScroll
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityMomentsFilterBinding
import com.lanhang.lite.net.MomentsApi
import com.lanhang.lite.settings.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 工作圈过滤。
 *
 * 三块：总开关、四个预设档位、自定义关键词。规则与判定逻辑全在 [MomentsFilterStore]，
 * 这里只负责把状态画出来、把改动写回去 —— 判定逻辑不能散在界面里，否则列表页和这里迟早不一致。
 *
 * 预览用当前规则扫最近 [PREVIEW_DEPTH] 条，只报命中条数、不显示正文：
 * 命中项里大量带手机号和微信号，把原文贴进对话框等于二次扩散。
 */
class MomentsFilterActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMomentsFilterBinding

    private var previewing = false

    /** render 期间为 true：此时改 isChecked 会回调监听，必须忽略，否则开关会自我翻转 */
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMomentsFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // insets 会多次回调，基准值必须先取
        val baseBottomPadding = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 键盘也算「底部遮挡」（2026-10-28 用户反馈输入框被键盘盖住）：
            // targetSdk 35 在 Android 15+ 强制 edge-to-edge，adjustResize 不再改变窗口高度，
            // 不把 ime 高度补进滚动区的 padding，页面最后一张卡（自定义关键词）会整块躲在键盘下面。
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
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
                baseBottomPadding + maxOf(bars.bottom, ime.bottom)
            )
            if (ime.bottom > 0) revealKeywordInput()
            insets
        }

        bindSwitches()
        bindCustom()
        binding.rowViewFiltered.setOnClickListener { FilteredMomentsActivity.start(this) }
        binding.btnPreview.setOnClickListener { preview() }
        // 带占位符的文案不能在 xml 里直接写，否则界面上会露出 %1$d
        binding.btnPreview.text = getString(R.string.moments_filter_preview, PREVIEW_DEPTH)
        render()
    }

    /**
     * 把自定义关键词输入框滚进可见区（键盘弹出时逐帧调用，见 [revealInScroll]）。
     *
     * 「自定义关键词」是页面最后一栏，输入框几乎贴着屏幕底部，键盘一弹出来正好盖住它。
     * 这里只往下滚到「刚好露出」为止，「自定义关键词」小标题还留在屏幕上，用户知道自己在上哪一栏；
     * 不滚到底、也不在收起键盘时往回滚，避免页面来回跳。
     */
    private fun revealKeywordInput() {
        val margin = resources.getDimensionPixelSize(R.dimen.space_lg)
        binding.scroll.post { revealInScroll(binding.scroll, binding.tilKeyword, margin) }
    }

    // ---------------------------------------------------------------- 开关

    private fun bindSwitches() {
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            MomentsFilterStore.enabled = checked
            render()
        }
        binding.swAd.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            MomentsFilterStore.setPreset(MomentsFilterStore.Preset.AD, checked)
        }
        binding.swPartTime.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            MomentsFilterStore.setPreset(MomentsFilterStore.Preset.PART_TIME, checked)
        }
        binding.swSecondHand.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            MomentsFilterStore.setPreset(MomentsFilterStore.Preset.SECOND_HAND, checked)
        }
        binding.swHousing.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            MomentsFilterStore.setPreset(MomentsFilterStore.Preset.HOUSING, checked)
        }
    }

    /**
     * 把存储里的状态刷到界面。
     *
     * `isChecked` 赋值会回调 `setOnCheckedChangeListener`，因此整个过程用 [rendering] 圈起来 ——
     * 否则「总开关」的监听会再调一次 render，白跑一轮（值相同不会再触发，但别依赖这个细节）。
     */
    private fun render() {
        val enabled = MomentsFilterStore.enabled
        rendering = true
        binding.swEnabled.isChecked = enabled
        binding.swAd.isChecked = MomentsFilterStore.isPresetOn(MomentsFilterStore.Preset.AD)
        binding.swPartTime.isChecked =
            MomentsFilterStore.isPresetOn(MomentsFilterStore.Preset.PART_TIME)
        binding.swSecondHand.isChecked =
            MomentsFilterStore.isPresetOn(MomentsFilterStore.Preset.SECOND_HAND)
        binding.swHousing.isChecked =
            MomentsFilterStore.isPresetOn(MomentsFilterStore.Preset.HOUSING)
        rendering = false
        // 总开关关掉时档位整体置灰：留着可点但没有效果，会让人以为设置没保存
        listOf(binding.swAd, binding.swPartTime, binding.swSecondHand, binding.swHousing)
            .forEach { it.isEnabled = enabled }
        binding.btnPreview.isEnabled = enabled && !previewing
        renderRules()
    }

    // ------------------------------------------------------------ 自定义词

    private fun bindCustom() {
        binding.btnAdd.setOnClickListener { addRule() }
        binding.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addRule()
                true
            } else {
                false
            }
        }
    }

    private fun addRule() {
        val raw = binding.etKeyword.text?.toString().orEmpty()
        when (MomentsFilterStore.addCustomRule(raw)) {
            MomentsFilterStore.AddResult.OK -> {
                binding.etKeyword.setText("")
                renderRules()
                toast(getString(R.string.moments_filter_add_ok))
            }

            MomentsFilterStore.AddResult.EMPTY ->
                toast(getString(R.string.moments_filter_add_empty))

            MomentsFilterStore.AddResult.TOO_LONG ->
                toast(getString(R.string.moments_filter_add_too_long, MomentTextMatcher.MAX_QUERY_LENGTH))

            MomentsFilterStore.AddResult.TOO_MANY_WORDS ->
                toast(getString(R.string.moments_filter_add_too_many, MomentTextMatcher.MAX_RULE_WORDS))

            MomentsFilterStore.AddResult.TOO_MANY_RULES ->
                toast(getString(R.string.moments_filter_add_too_many_rules, MomentsFilterStore.MAX_CUSTOM_RULES))

            MomentsFilterStore.AddResult.DUPLICATE ->
                toast(getString(R.string.moments_filter_add_dup))
        }
    }

    private fun renderRules() {
        val group = binding.chipRules
        group.removeAllViews()
        val rules = MomentsFilterStore.customRules()
        binding.tvCustomEmpty.isVisible = rules.isEmpty()
        rules.forEach { rule ->
            val chip = Chip(this).apply {
                text = rule
                isCloseIconVisible = true
                setCloseIconResource(R.drawable.ic_close)
                closeIconContentDescription =
                    getString(R.string.moments_filter_custom_delete, rule)
                setOnCloseIconClickListener {
                    MomentsFilterStore.removeCustomRule(rule)
                    renderRules()
                }
            }
            group.addView(chip)
        }
    }

    // ---------------------------------------------------------------- 预览

    /**
     * 用当前规则扫最近 [PREVIEW_DEPTH] 条，报命中条数。
     *
     * 两次 `timeline(size=50)` + 两次 `feed/batch`，四个请求 —— 不给它加缓存，
     * 因为用户想确认的就是「现在这套规则会挡住些什么」。
     */
    private fun preview() {
        if (previewing) return
        previewing = true
        binding.btnPreview.isEnabled = false
        binding.btnPreview.text = getString(R.string.moments_filter_preview_running)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    var cursor: String? = null
                    var scanned = 0
                    var hit = 0
                    while (scanned < PREVIEW_DEPTH) {
                        val ids = MomentsApi.getTimeLine(cursor, PREVIEW_PAGE)
                        if (ids.isEmpty()) break
                        // 游标取原始最后一条：过滤只看正文，不影响翻页
                        cursor = ids.last()
                        val feeds = MomentsApi.getFeeds(ids)
                        scanned += feeds.size
                        for (feed in feeds) {
                            val text = MomentItem(feed).content.text.orEmpty()
                            if (MomentsFilterStore.match(text, feed.user_id) != null) hit++
                        }
                        if (ids.size < PREVIEW_PAGE) break
                    }
                    scanned to hit
                }
                val (scanned, hit) = result
                val message = if (hit == 0) {
                    getString(R.string.moments_filter_preview_none, scanned)
                } else {
                    getString(R.string.moments_filter_preview_result, scanned, hit)
                }
                MaterialAlertDialogBuilder(this@MomentsFilterActivity)
                    .setTitle(R.string.moments_filter_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.common_confirm, null)
                    .show()
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@MomentsFilterActivity, getString(R.string.moments_filter_tag), e)
                toast(e.userMessage(this@MomentsFilterActivity, R.string.moments_load_failed))
            } finally {
                previewing = false
                binding.btnPreview.isEnabled = MomentsFilterStore.enabled
                binding.btnPreview.text = getString(R.string.moments_filter_preview, PREVIEW_DEPTH)
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 预览扫描深度：两个 timeline 页 */
        private const val PREVIEW_DEPTH = 100
        private const val PREVIEW_PAGE = 50

        fun start(context: Context) {
            context.startActivity(Intent(context, MomentsFilterActivity::class.java))
        }
    }
}
