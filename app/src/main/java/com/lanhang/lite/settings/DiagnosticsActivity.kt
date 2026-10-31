package com.lanhang.lite.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断页（2026-10-30 由设置页的 AlertDialog 改成独立页面）。
 *
 * 版式对齐 Hoshi Reader 的 `features/diagnostics/DiagnosticsView.kt`：
 * 标题居中的顶栏 + 右侧两个动作（保存 / 分享），正文是**分组卡片里「标签左、值右」**
 * 的字段清单，两个正文块（应用错误记录 / 运行日志）才用等宽字体。
 *
 * 等宽只给日志：时间戳、异常类名、PID 在比例字体下对不齐列，看不出「同一秒刷了十几条同样的错」。
 *
 * 数据由 [Diagnostics.collect] 采集（会 `exec logcat`，必须放 IO 线程）；
 * 采集期间内容隐藏、中间转圈，动作按钮 disabled —— 与 Hoshi 的 `shareText.isNotBlank()` 同一个意思：
 * 没准备好时按钮点了不该没反应。
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    /** 最近一次采集结果；保存/分享都用它，避免每点一次就重跑一次 logcat */
    private var report: Diagnostics.Report? = null

    /** 分享/保存用的纯文本（由 [Diagnostics.Report.toText] 生成） */
    private var reportText: String = ""

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val text = reportText
        if (uri == null || text.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("open output stream failed")
                }.isSuccess
            }
            val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            toast(
                if (ok) getString(R.string.diagnostics_saved, name)
                else getString(R.string.diagnostics_save_failed)
            )
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // insets 会多次回调，基准值必须先取（否则每次回调把上一次的结果再加一遍）
        val baseBottom = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tb = binding.toolbar
            tb.setPadding(tb.paddingLeft, bars.top, tb.paddingRight, 0)
            binding.scroll.setPadding(
                binding.scroll.paddingLeft,
                binding.scroll.paddingTop,
                binding.scroll.paddingRight,
                baseBottom + bars.bottom
            )
            insets
        }

        // 菜单在布局里用 app:menu 挂上（没有 setSupportActionBar 的 AppCompatActivity 里
        // onCreateOptionsMenu 不会被调用，这是上一版两个图标不出来的原因）
        tintMenuIcons()
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_diagnostics_save -> {
                    if (ensureReady()) createDocument.launch(defaultFileName())
                    true
                }
                R.id.action_diagnostics_share -> {
                    if (ensureReady()) share()
                    true
                }
                else -> false
            }
        }

        setActionsEnabled(false)
        load()
    }

    /** 顶栏图标统一跟 onSurface；与 MainActivity / 互动消息页的处理一致 */
    private fun tintMenuIcons() {
        val tint = MaterialColors.getColor(
            binding.toolbar,
            com.google.android.material.R.attr.colorOnSurface
        )
        val menu = binding.toolbar.menu
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.mutate()?.setTint(tint)
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        val menu = binding.toolbar.menu
        for (i in 0 until menu.size()) menu.getItem(i).isEnabled = enabled
    }

    private fun load() {
        lifecycleScope.launch {
            val collected = withContext(Dispatchers.IO) { Diagnostics.collect(this@DiagnosticsActivity) }
            if (isFinishing || isDestroyed) return@launch
            report = collected
            reportText = collected.toText(this@DiagnosticsActivity)
            render(collected)
            setActionsEnabled(true)
            binding.progress.isVisible = false
            binding.scroll.isVisible = true
        }
    }

    private fun render(r: Diagnostics.Report) {
        binding.tvPackage.text = r.packageName
        binding.tvVersion.text = r.versionLabel
        binding.tvAndroid.text = r.androidLabel
        binding.tvDevice.text = r.device
        binding.tvTime.text = r.time
        binding.tvErrors.text = if (r.appErrors.isEmpty()) {
            getString(R.string.diagnostics_none)
        } else {
            r.appErrors.joinToString("\n")
        }
        binding.tvLog.text = if (r.logLines.isEmpty()) {
            getString(R.string.diagnostics_none)
        } else {
            r.logLines.joinToString("\n")
        }
    }

    /** 采集还没跑完就点按钮：明确提示，不默默什么都不做 */
    private fun ensureReady(): Boolean {
        if (report != null) return true
        toast(getString(R.string.diagnostics_not_ready))
        return false
    }

    private fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "lanhang-diagnostics-$stamp.txt"
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_diagnostics_subject))
            putExtra(Intent.EXTRA_TEXT, reportText)
        }
        startActivity(Intent.createChooser(send, getString(R.string.settings_diagnostics_chooser)))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }
}
