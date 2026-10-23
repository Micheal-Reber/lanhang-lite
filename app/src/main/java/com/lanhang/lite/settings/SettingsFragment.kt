package com.lanhang.lite.settings

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.Intent
import android.text.TextUtils
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.databinding.FragmentSettingsBinding
import com.lanhang.lite.schedule.ScheduleBackdrop
import com.lanhang.lite.schedule.ScheduleBackgroundActivity
import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleExporter
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.AppLanguage
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.MainActivity
import com.lanhang.lite.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（PocketDiary 同款：目录页 + 二级页）。
 * 目录只有三行导航（外观 / 课表工具 / 关于），点进二级页后 Toolbar 切换为
 * 返回箭头 + 子页标题；系统返回键回到目录。视图切换在本 Fragment 内完成，
 * 避免与四 Tab hide/show 的 back stack 冲突。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val themeOptions = listOf(
        "light" to R.string.settings_theme_light,
        "dark" to R.string.settings_theme_dark,
        "system" to R.string.settings_theme_system
    )

    /** null = 目录页；PAGE_* = 当前二级页 */
    private var detailPage: Int? = null

    private var pendingExport: ScheduleExporter.ExportPayload? = null
    private var exportJob: Job? = null
    private val createIcsDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return@registerForActivityResult
        saveDocument(uri, payload.ics.toByteArray(Charsets.UTF_8), "${payload.baseFileName}.ics")
    }
    private val openBackgroundSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshBackgroundSummary()
    }
    private val createCsvDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return@registerForActivityResult
        saveDocument(uri, payload.csv.toByteArray(Charsets.UTF_8), "${payload.baseFileName}.csv")
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeDetail()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        binding.rowAppearance.setOnClickListener { openDetail(PAGE_APPEARANCE) }
        binding.rowTools.setOnClickListener { openDetail(PAGE_TOOLS) }
        binding.rowAbout.setOnClickListener { openDetail(PAGE_ABOUT) }
        binding.tvAppearanceSummary.text = currentThemeLabel()
        binding.tvAboutSummary.text = getString(
            R.string.settings_about_version,
            runCatching {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            }.getOrNull() ?: "--"
        )

        val restored = savedInstanceState?.getInt(KEY_DETAIL, -1) ?: -1
        if (restored == PAGE_APPEARANCE || restored == PAGE_TOOLS || restored == PAGE_ABOUT) {
            openDetail(restored, syncToolbar = false)
        }
        syncToolbar()
    }

    override fun onResume() {
        super.onResume()
        syncToolbar()
        refreshBackgroundSummary()
    }

    /** 背景页返回后刷新摘要（纯色/渐变/自定义图片） */
    private fun refreshBackgroundSummary() {
        val b = _binding ?: return
        b.detailContainer.findViewById<TextView>(R.id.tvScheduleBackgroundSummary)?.text =
            ScheduleBackdrop.summary(requireContext())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DETAIL, detailPage ?: -1)
    }

    /** 与 MainActivity 对齐 Toolbar 状态（目录态 = 标题+无返回；二级页 = 返回箭头+子页标题）。 */
    fun syncToolbar() {
        val act = activity as? MainActivity ?: return
        if (isHidden) return
        val page = detailPage
        if (page == null) {
            act.showToolbarMain(getString(R.string.tab_settings))
        } else {
            act.showToolbarSub(detailTitle(page)) { closeDetail() }
        }
    }

    private fun detailTitle(page: Int): String = when (page) {
        PAGE_APPEARANCE -> getString(R.string.settings_appearance)
        PAGE_TOOLS -> getString(R.string.settings_schedule_tools)
        else -> getString(R.string.settings_about_app)
    }

    private fun openDetail(page: Int, syncToolbar: Boolean = true) {
        val b = _binding ?: return
        val layout = when (page) {
            PAGE_APPEARANCE -> R.layout.view_settings_appearance
            PAGE_TOOLS -> R.layout.view_settings_tools
            else -> R.layout.view_settings_about
        }
        b.detailContainer.removeAllViews()
        val child = LayoutInflater.from(requireContext()).inflate(layout, b.detailContainer, false)
        b.detailContainer.addView(child)
        setupDetail(page, child)
        detailPage = page
        b.scrollDirectory.visibility = View.GONE
        b.scrollDetail.visibility = View.VISIBLE
        b.scrollDetail.scrollTo(0, 0)
        backCallback.isEnabled = true
        if (syncToolbar) syncToolbar()
    }

    private fun closeDetail() {
        val b = _binding ?: return
        detailPage = null
        b.detailContainer.removeAllViews()
        b.scrollDetail.visibility = View.GONE
        b.scrollDirectory.visibility = View.VISIBLE
        backCallback.isEnabled = false
        syncToolbar()
    }

    private fun setupDetail(page: Int, root: View) {
        when (page) {
            PAGE_APPEARANCE -> {
                root.findViewById<TextView>(R.id.tvThemeSummary).text = currentThemeLabel()
                root.findViewById<View>(R.id.rowTheme).setOnClickListener { showThemeDialog() }
                root.findViewById<TextView>(R.id.tvScheduleBackgroundSummary).text =
                    ScheduleBackdrop.summary(requireContext())
                root.findViewById<View>(R.id.rowScheduleBackground).setOnClickListener {
                    openBackgroundSettings.launch(
                        Intent(requireContext(), ScheduleBackgroundActivity::class.java)
                    )
                }
                root.findViewById<TextView>(R.id.tvLanguageSummary).text = currentLanguageLabel()
                root.findViewById<View>(R.id.rowLanguage)
                    .setOnClickListener { showLanguageDialog() }
            }
            PAGE_TOOLS -> {
                root.findViewById<View>(R.id.exportScheduleButton)
                    .setOnClickListener { showExportSchedule() }
            }
            else -> {
                root.findViewById<TextView>(R.id.tvAccount).text =
                    Session.name.ifEmpty { Session.username }.ifEmpty { "--" }
                root.findViewById<TextView>(R.id.tvVersion).text = runCatching {
                    requireContext().packageManager
                        .getPackageInfo(requireContext().packageName, 0).versionName
                }.getOrNull() ?: "--"
                root.findViewById<TextView>(R.id.tvProto).text = "NchuApp/${Session.appVersion}"
                root.findViewById<View>(R.id.diagnosticsButton)
                    .setOnClickListener { showDiagnostics() }
            }
        }
    }

    private fun currentThemeLabel(): String =
        themeOptions.firstOrNull { it.first == Session.themeMode }
            ?.let { getString(it.second) }
            ?: getString(R.string.settings_theme_system)

    private fun showThemeDialog() {
        val labels = themeOptions.map { getString(it.second) }.toTypedArray()
        val checked = themeOptions.indexOfFirst { it.first == Session.themeMode }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = themeOptions[which].first
                dialog.dismiss()
                if (mode != Session.themeMode) {
                    Session.themeMode = mode
                    applyTheme(mode)
                }
            }
            .setNegativeButton(R.string.schedule_export_cancel, null)
            .show()
    }

    private fun applyTheme(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        context?.applicationContext?.let { ScheduleWidgetUpdater.updateAll(it) }
    }

    private fun currentLanguageLabel(): String =
        getString(AppLanguage.labelRes(Session.language))

    private fun showLanguageDialog() {
        val labels = AppLanguage.options.map { getString(AppLanguage.labelRes(it)) }.toTypedArray()
        val checked = AppLanguage.options.indexOf(Session.language).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = AppLanguage.options[which]
                dialog.dismiss()
                if (mode != Session.language) {
                    Session.language = mode
                    // AppCompat 会自动重建 Activity 让新语言生效；小部件文案是渲染时写死的，需重刷
                    AppLanguage.apply(mode)
                    context?.applicationContext?.let { ScheduleWidgetUpdater.updateAll(it) }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showExportSchedule() {
        val data = ScheduleRepository.loadCached()?.data
        if (data == null) {
            Toast.makeText(requireContext(), R.string.schedule_export_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val plan = runCatching { ScheduleExporter.plan(data) }.getOrNull()
        if (plan == null) {
            Toast.makeText(requireContext(), R.string.schedule_export_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val missing = missingWeeks(data, plan)
        if (missing > 0) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.schedule_export_title)
                .setMessage(getString(R.string.schedule_export_sync_needed, missing))
                .setNegativeButton(R.string.schedule_export_cancel, null)
                .setPositiveButton(R.string.schedule_export_start_sync) { _, _ ->
                    startExport(data, plan)
                }
                .show()
        } else {
            startExport(data, plan)
        }
    }

    private fun startExport(data: ScheduleData, plan: ScheduleExporter.Plan) {
        if (exportJob?.isActive == true) return
        val missing = missingWeeks(data, plan)
        val progress = if (missing > 0) createExportProgress() else null
        val baseIsCurrent = weekInBase(data, plan.currentWeek)
        exportJob = lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    val weeks = LinkedHashMap<Int, ScheduleData>()
                    val total = plan.weeks.count()
                    var done = 0
                    for (week in plan.weeks) {
                        ensureActive()
                        val weekData = if (week == plan.currentWeek && baseIsCurrent) {
                            data
                        } else {
                            cachedWeek(week, data.termId)
                                ?: ScheduleRepository.refresh(week, updateWidget = false).data
                        } ?: throw IllegalStateException(getString(R.string.settings_week_empty, week))
                        weeks[week] = weekData
                        done++
                        if (progress != null) {
                            withContext(Dispatchers.Main) {
                                progress.text.text = getString(
                                    R.string.schedule_export_syncing,
                                    week,
                                    done,
                                    total
                                )
                            }
                        }
                    }
                    ensureActive()
                    ScheduleExporter.build(requireContext(), data, plan, weeks)
                }
                progress?.dialog?.dismiss()
                if (payload == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.schedule_export_no_data,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showExportOptions(payload)
                }
            } catch (e: CancellationException) {
                progress?.dialog?.dismiss()
            } catch (e: Exception) {
                progress?.dialog?.dismiss()
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.schedule_export_failed)
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } finally {
                exportJob = null
            }
        }
    }

    private fun showExportOptions(payload: ScheduleExporter.ExportPayload) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        fun themeColor(attr: Int): Int {
            val a = ctx.obtainStyledAttributes(intArrayOf(attr))
            val c = a.getColor(0, 0)
            a.recycle()
            return c
        }

        // AppCompat AlertController 中 setMessage 与 setItems 互斥（设了 message
        // 列表就永不挂载），因此说明文字与选项行都放进自定义 view。
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val message = TextView(ctx).apply {
            text = getString(
                R.string.schedule_export_message,
                payload.courseCount,
                payload.firstWeek,
                payload.lastWeek
            )
            val ta = ctx.obtainStyledAttributes(
                intArrayOf(com.google.android.material.R.attr.materialAlertDialogBodyTextStyle)
            )
            val styleId = ta.getResourceId(0, 0)
            ta.recycle()
            if (styleId != 0) setTextAppearance(ctx, styleId)
            setTextColor(onSurface)
            setPadding(dp(20), 0, dp(20), dp(12))
        }
        container.addView(
            message,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val labels = arrayOf(
            getString(R.string.schedule_export_ics),
            getString(R.string.schedule_export_csv),
            getString(R.string.schedule_export_share)
        )
        val rows = labels.map { label ->
            TextView(ctx).apply {
                text = label
                textSize = 16f
                setTextColor(onSurface)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(20), dp(12), dp(20), dp(12))
                val tvAttrs =
                    ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                val ripple = tvAttrs.getDrawable(0)
                tvAttrs.recycle()
                if (ripple != null) background = ripple
            }
        }
        rows.forEach {
            container.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.schedule_export_title)
            .setView(container)
            .setNegativeButton(R.string.schedule_export_cancel, null)
            .create()
        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                dialog.dismiss()
                when (index) {
                    0 -> {
                        pendingExport = payload
                        createIcsDocument.launch("${payload.baseFileName}.ics")
                    }
                    1 -> {
                        pendingExport = payload
                        createCsvDocument.launch("${payload.baseFileName}.csv")
                    }
                    2 -> shareSchedule(payload.text)
                }
            }
        }
        dialog.show()
    }

    private fun createExportProgress(): ExportProgress {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val bar = ProgressBar(ctx).apply { isIndeterminate = true }
        layout.addView(bar, LinearLayout.LayoutParams(dp(32), dp(32)))
        val text = TextView(ctx).apply {
            setText(R.string.schedule_export_preparing)
            textSize = 14f
            setPadding(dp(16), 0, 0, 0)
        }
        layout.addView(
            text,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.schedule_export_title)
            .setView(layout)
            .setNegativeButton(R.string.schedule_export_cancel, null)
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                exportJob?.cancel()
                dialog.dismiss()
            }
        }
        dialog.show()
        return ExportProgress(dialog, text)
    }

    private fun missingWeeks(data: ScheduleData, plan: ScheduleExporter.Plan): Int {
        val baseIsCurrent = weekInBase(data, plan.currentWeek)
        return plan.weeks.count { week ->
            if (week == plan.currentWeek && baseIsCurrent) {
                false
            } else {
                cachedWeek(week, data.termId) == null
            }
        }
    }

    private fun weekInBase(data: ScheduleData, week: Int): Boolean {
        val baseWeek = data.weekInfo?.currentWeek?.trim()?.toIntOrNull()
        return baseWeek == null || baseWeek == week
    }

    private fun cachedWeek(week: Int, termId: String?): ScheduleData? {
        val data = ScheduleRepository.loadCachedWeek(week)?.data ?: return null
        if (!termId.isNullOrBlank() && data.termId != termId) return null
        return data
    }

    private data class ExportProgress(
        val dialog: AlertDialog,
        val text: TextView
    )

    private fun saveDocument(uri: Uri, bytes: ByteArray, displayName: String) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val output = ctx.contentResolver.openOutputStream(uri)
                        ?: error("open output stream failed")
                    output.use { stream ->
                        stream.write(bytes)
                    }
                }.isSuccess
            }
            val message = if (success) {
                ctx.getString(R.string.schedule_export_saved, displayName)
            } else {
                ctx.getString(R.string.schedule_export_failed)
            }
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareSchedule(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.schedule_export_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.schedule_export_chooser)))
    }

    private fun showDiagnostics() {
        val report = Diagnostics.report(requireContext())
        val preview = TextView(requireContext()).apply {
            text = report
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(20, 12, 20, 12)
            ellipsize = TextUtils.TruncateAt.END
        }
        val scroll = ScrollView(requireContext()).apply {
            addView(preview)
            layoutParams = ViewGroup.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_diagnostics)
            .setView(scroll)
            .setNegativeButton(R.string.common_close, null)
            .setPositiveButton(R.string.common_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_diagnostics_subject))
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(send, getString(R.string.settings_diagnostics_chooser)))
            }
            .show()
    }

    companion object {
        private const val PAGE_APPEARANCE = 0
        private const val PAGE_TOOLS = 1
        private const val PAGE_ABOUT = 2
        private const val KEY_DETAIL = "settings_detail_page"
    }
}
