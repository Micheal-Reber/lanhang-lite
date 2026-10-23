package com.lanhang.lite.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.databinding.FragmentSettingsBinding
import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleExporter
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.Session
import com.lanhang.lite.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val themeOptions = listOf(
        "light" to "亮色",
        "dark" to "暗色",
        "system" to "跟随系统"
    )

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
        renderThemeRow()
        binding.tvAccount.text = Session.name.ifEmpty { Session.username }.ifEmpty { "--" }
        binding.tvVersion.text = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "--"
        binding.tvProto.text = "NchuApp/${Session.appVersion}"
        binding.exportScheduleButton.setOnClickListener { showExportSchedule() }
        binding.diagnosticsButton.setOnClickListener { showDiagnostics() }
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
                        } ?: throw IllegalStateException("第${week}周课表为空")
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
                    ScheduleExporter.build(data, plan, weeks)
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.schedule_export_title)
            .setMessage(
                getString(
                    R.string.schedule_export_message,
                    payload.courseCount,
                    payload.firstWeek,
                    payload.lastWeek
                )
            )
            .setItems(
                arrayOf(
                    getString(R.string.schedule_export_ics),
                    getString(R.string.schedule_export_csv),
                    getString(R.string.schedule_export_share)
                )
            ) { _, which ->
                when (which) {
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
            .setNegativeButton(R.string.schedule_export_cancel, null)
            .show()
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
            .setTitle("诊断信息")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .setPositiveButton("分享") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "蓝航 Lite 诊断日志")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(send, "分享诊断信息"))
            }
            .show()
    }

    private fun renderThemeRow() {
        val row = binding.themeRow
        row.removeAllViews()
        val ctx = requireContext()
        val padH = (4 * resources.displayMetrics.density).toInt()
        val padV = (10 * resources.displayMetrics.density).toInt()
        for ((mode, label) in themeOptions) {
            val tv = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setPadding(padH, padV, padH, padV)
                textSize = 13f
                minHeight = (40 * resources.displayMetrics.density).toInt()
            }
            val bg = ContextCompat.getDrawable(ctx, R.drawable.bg_week_pill)!!.mutate() as GradientDrawable
            val selected = mode == Session.themeMode
            if (selected) {
                bg.setColor(ContextCompat.getColor(ctx, R.color.week_pill_bg))
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.week_pill_text))
                tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                bg.setColor(Color.TRANSPARENT)
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.icon_muted))
                tv.typeface = android.graphics.Typeface.DEFAULT
            }
            tv.background = bg
            tv.setOnClickListener {
                if (Session.themeMode != mode) {
                    Session.themeMode = mode
                    applyTheme(mode)
                    renderThemeRow()
                }
            }
            val lp = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            row.addView(tv, lp)
        }
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
}
