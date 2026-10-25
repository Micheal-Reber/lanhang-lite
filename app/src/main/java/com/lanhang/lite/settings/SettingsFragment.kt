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
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import com.lanhang.lite.R
import com.lanhang.lite.ui.miuix.MiuixDialogSpec
import com.lanhang.lite.ui.miuix.showMiuixConfirm
import com.lanhang.lite.ui.miuix.showMiuixDialog
import com.lanhang.lite.ui.miuix.showMiuixPicker
import com.lanhang.lite.ui.miuix.MiuixProgressDialog
import com.lanhang.lite.ui.miuix.showMiuixProgress
import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleExporter
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.AppLanguage
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.MainActivity
import com.lanhang.lite.ui.miuix.LanhangMiuixTheme
import com.lanhang.lite.ui.miuix.isNightMode
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

    /**
     * Compose 侧读取的 UI 状态。视觉层已换成 Miuix（见 [SettingsScreen]），
     * 这里只保留数据与动作；改完状态要调 [render] 触发重组。
     */
    private var uiState by mutableStateOf(SettingsUiState())

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
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LanhangMiuixTheme(darkTheme = LocalContext.current.isNightMode()) {
                SettingsScreen(
                    state = uiState,
                    onOpenAppearance = { openDetail(PAGE_APPEARANCE) },
                    onOpenTools = { openDetail(PAGE_TOOLS) },
                    onOpenAbout = { openDetail(PAGE_ABOUT) },
                    onTheme = { showThemeDialog() },
                    onLanguage = { showLanguageDialog() },
                    onExport = { showExportSchedule() },
                    onDiagnostics = { showDiagnostics() },
                    onDebugSchedule = { (activity as? com.lanhang.lite.ui.MainActivity)?.debugSchedule() },
                    onLogout = { confirmLogout() },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val restored = savedInstanceState?.getInt(KEY_DETAIL, -1) ?: -1
        if (restored == PAGE_APPEARANCE || restored == PAGE_TOOLS || restored == PAGE_ABOUT) {
            detailPage = restored
            backCallback.isEnabled = true
        }
        render()
        syncToolbar()
    }

    /** 把 Fragment 侧的数据整理成 [SettingsUiState] 交给 Compose 渲染。 */
    private fun render() {
        val version = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "--"
        uiState = SettingsUiState(
            page = detailPage ?: SettingsUiState.PAGE_NONE,
            themeLabel = currentThemeLabel(),
            languageLabel = currentLanguageLabel(),
            account = Session.name.ifEmpty { Session.username }.ifEmpty { "--" },
            version = version,
            proto = "NchuApp/${Session.appVersion}",
        )
    }

    override fun onResume() {
        super.onResume()
        syncToolbar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DETAIL, detailPage ?: -1)
    }

    /** 与 MainActivity 对齐 Toolbar 状态（目录态 = 标题+无返回；二级页 = 返回箭头+子页标题）。 */
    /**
     * 顶栏横移方向：+1 = 从右侧推入（进入二级页），-1 = 从左侧回位（返回上级）。
     *
     * 2026-10-25 用户反馈「设置-外观的返回动画，顶栏向左、内容向右」：
     * 之前返回时这里调的是 `showToolbarMain(标题)`（slideDir = 0，只做淡切），
     * 而内容侧是「子页向右退出、目录页从左侧回位」，两者方向对不上。
     * 现在返回统一给 -1，和内容一起向右回位。
     */
    private var toolbarDir = 0

    fun syncToolbar() {
        val act = activity as? MainActivity ?: return
        if (isHidden) return
        val page = detailPage
        val dir = toolbarDir
        toolbarDir = 0
        if (page == null) {
            act.showToolbarMain(getString(R.string.tab_settings), slideDir = dir)
        } else {
            act.showToolbarSub(detailTitle(page), onBack = { closeDetail() }, slideDir = dir)
        }
    }

    private fun detailTitle(page: Int): String = when (page) {
        PAGE_APPEARANCE -> getString(R.string.settings_appearance)
        PAGE_TOOLS -> getString(R.string.settings_schedule_tools)
        else -> getString(R.string.settings_about_app)
    }

    private fun openDetail(page: Int, syncToolbar: Boolean = true) {
        detailPage = page
        toolbarDir = 1
        backCallback.isEnabled = true
        render()
        if (syncToolbar) syncToolbar()
    }

    private fun closeDetail() {
        detailPage = null
        toolbarDir = -1
        backCallback.isEnabled = false
        render()
        syncToolbar()
    }

    private fun currentThemeLabel(): String =
        themeOptions.firstOrNull { it.first == Session.themeMode }
            ?.let { getString(it.second) }
            ?: getString(R.string.settings_theme_system)

    private fun showThemeDialog() {
        val labels = themeOptions.map { getString(it.second) }
        val checked = themeOptions.indexOfFirst { it.first == Session.themeMode }.coerceAtLeast(0)
        showMiuixPicker(
            context = requireContext(),
            title = getString(R.string.settings_theme_title),
            items = labels,
            selectedIndex = checked,
            cancelText = getString(R.string.schedule_export_cancel),
            onPick = { which ->
                val mode = themeOptions.getOrNull(which)?.first ?: return@showMiuixPicker
                if (mode != Session.themeMode) {
                    Session.themeMode = mode
                    applyTheme(mode)
                }
            },
        )
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
        val labels = AppLanguage.options.map { getString(AppLanguage.labelRes(it)) }
        val checked = AppLanguage.options.indexOf(Session.language).coerceAtLeast(0)
        showMiuixPicker(
            context = requireContext(),
            title = getString(R.string.settings_language),
            items = labels,
            selectedIndex = checked,
            cancelText = getString(R.string.common_cancel),
            onPick = { which ->
                val mode = AppLanguage.options.getOrNull(which) ?: return@showMiuixPicker
                if (mode != Session.language) {
                    Session.language = mode
                    // 见 LocaleHelper：不再用系统的 per-app locale（它会系统级 relaunch，
                    // 过程中有 0.4s 纯黑帧）。改完自己 recreate()，界面立刻换语言且不黑屏
                    AppLanguage.apply(mode)
                    context?.applicationContext?.let { ScheduleWidgetUpdater.updateAll(it) }
                    requireActivity().recreate()
                }
            },
        )
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
            showMiuixConfirm(
                context = requireContext(),
                title = getString(R.string.schedule_export_title),
                message = getString(R.string.schedule_export_sync_needed, missing),
                confirmText = getString(R.string.schedule_export_start_sync),
                dismissText = getString(R.string.schedule_export_cancel),
                onConfirm = { startExport(data, plan) },
            )
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
                                progress.setText(
                                    getString(
                                        R.string.schedule_export_syncing,
                                        week,
                                        done,
                                        total
                                    )
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
        val labels = listOf(
            getString(R.string.schedule_export_ics),
            getString(R.string.schedule_export_csv),
            getString(R.string.schedule_export_share)
        )
        showMiuixDialog(
            requireContext(),
            MiuixDialogSpec(
                title = getString(R.string.schedule_export_title),
                summary = getString(
                    R.string.schedule_export_message,
                    payload.courseCount,
                    payload.firstWeek,
                    payload.lastWeek
                ),
                items = labels,
                onItemClick = { index ->
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
                },
                actions = listOf(getString(R.string.schedule_export_cancel) to {}),
            ),
        )
    }

    private fun createExportProgress(): MiuixProgressDialog = showMiuixProgress(
        context = requireContext(),
        title = getString(R.string.schedule_export_title),
        initialText = getString(R.string.schedule_export_preparing),
        cancelText = getString(R.string.schedule_export_cancel),
        onCancel = { exportJob?.cancel() },
    )

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

    /** 退出登录（原顶栏「更多」菜单项，2026-10-24 用户要求删掉三点菜单后移到这里） */
    private fun confirmLogout() {
        showMiuixConfirm(
            context = requireContext(),
            title = getString(R.string.action_logout),
            message = getString(R.string.settings_logout_confirm),
            confirmText = getString(R.string.action_logout),
            dismissText = getString(R.string.common_cancel),
            onConfirm = { (activity as? com.lanhang.lite.ui.MainActivity)?.logout() },
        )
    }

    private fun showDiagnostics() {
        val report = Diagnostics.report(requireContext())
        showMiuixDialog(
            requireContext(),
            MiuixDialogSpec(
                title = getString(R.string.settings_diagnostics),
                summary = report,
                scrollableSummary = true,
                actions = listOf(
                    getString(R.string.common_close) to {},
                    getString(R.string.common_share) to {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_diagnostics_subject))
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        startActivity(
                            Intent.createChooser(send, getString(R.string.settings_diagnostics_chooser))
                        )
                    },
                ),
            ),
        )
    }

    companion object {
        private const val PAGE_APPEARANCE = 0
        private const val PAGE_TOOLS = 1
        private const val PAGE_ABOUT = 2
        private const val KEY_DETAIL = "settings_detail_page"
    }
}
