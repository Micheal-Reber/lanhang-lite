package com.lanhang.lite.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lanhang.lite.R
import com.lanhang.lite.core.toast
import com.lanhang.lite.databinding.ViewSettingsDiagnosticsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断与日志（设置二级页，2026-11-14 由独立 Activity 迁入）。
 *
 * 版式对齐 Hoshi Reader 的 `features/diagnostics/DiagnosticsView.kt`：分组卡片里「标签左、值右」
 * 的字段清单，两个正文块（应用错误记录 / 运行日志）才用等宽字体。
 * 等宽只给日志：时间戳、异常类名、PID 在比例字体下对不齐列，看不出「同一秒刷了十几条同样的错」。
 *
 * 顶栏的「保存 / 分享」仍留在宿主 Toolbar 上（原来是自己那根 toolbar 的 `app:menu`）：
 * 菜单资源 [R.menu.menu_diagnostics] 由 `SettingsFragment.syncToolbar` 挂到 MainActivity，
 * 点击经 `onMenuAction` 回到这里 —— 二级页共用宿主顶栏，图标必须跟着二级页一起进退。
 *
 * 数据由 [Diagnostics.collect] 采集（会 `exec logcat`，必须放 IO 线程）；
 * 采集期间内容隐藏、中间转圈。原来还有「没准备好时两个图标 disabled」，现在图标在宿主顶栏上，
 * 二级页不方便去改宿主的菜单状态，所以改成点下去明确提示（[ensureReady]）——
 * 与 Hoshi 的 `shareText.isNotBlank()` 同一个意思：没准备好时点了不该没反应。
 */
class DiagnosticsFragment : Fragment() {

    private var _binding: ViewSettingsDiagnosticsBinding? = null
    private val binding get() = _binding!!

    /** 最近一次采集结果；保存/分享都用它，避免每点一次就重跑一次 logcat */
    private var report: Diagnostics.Report? = null

    /** 分享/保存用的纯文本（由 [Diagnostics.Report.toText] 生成） */
    private var reportText: String = ""

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val text = reportText
        if (uri == null || text.isEmpty()) return@registerForActivityResult
        val c = _binding?.root?.context ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    c.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("open output stream failed")
                }.isSuccess
            }
            if (_binding == null) return@launch
            val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            toast(
                if (ok) getString(R.string.diagnostics_saved, name)
                else getString(R.string.diagnostics_save_failed)
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ViewSettingsDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 顶栏动作（由宿主的 onOptionsItemSelected 转发）。
     * 返回 true = 已处理，宿主不再往下走。
     */
    fun onMenuAction(itemId: Int): Boolean = when (itemId) {
        R.id.action_diagnostics_save -> {
            if (ensureReady()) _binding?.root?.context?.let { createDocument.launch(defaultFileName()) }
            true
        }
        R.id.action_diagnostics_share -> {
            if (ensureReady()) share()
            true
        }
        else -> false
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val c = _binding?.root?.context ?: return@launch
            val collected = withContext(Dispatchers.IO) { Diagnostics.collect(c) }
            if (_binding == null) return@launch
            report = collected
            reportText = collected.toText(c)
            render(collected)
            binding.progress.hide()
            binding.content.isVisible = true
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
        val c = _binding?.root?.context ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.settings_diagnostics_subject))
            putExtra(android.content.Intent.EXTRA_TEXT, reportText)
        }
        c.startActivity(
            android.content.Intent.createChooser(send, getString(R.string.settings_diagnostics_chooser))
        )
    }
}
