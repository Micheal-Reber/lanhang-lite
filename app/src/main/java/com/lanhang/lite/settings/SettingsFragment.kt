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
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.campusnet.CampusNetActivity
import com.lanhang.lite.databinding.FragmentSettingsBinding
import com.lanhang.lite.idcard.IdCardActivity
import com.lanhang.lite.library.LibrarySeatActivity
import com.lanhang.lite.moments.MomentsFilterActivity
import com.lanhang.lite.moments.MomentsFilterStore
import com.lanhang.lite.moments.UserInfoCache
import com.lanhang.lite.moments.UserProfileActivity
import com.lanhang.lite.net.MiniappApi
import com.lanhang.lite.net.StaffBrief
import com.lanhang.lite.oa.OaHallActivity
import com.lanhang.lite.schedule.ClassReminderActivity
import com.lanhang.lite.schedule.ClassReminderStore
import com.lanhang.lite.schedule.ScheduleBackdrop
import com.lanhang.lite.schedule.ScheduleBackgroundActivity
import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleExporter
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.AppLanguage
import com.lanhang.lite.session.Logout
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.LoginActivity
import com.lanhang.lite.ui.MainActivity
import com.lanhang.lite.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（目录页 + 二级页）。
 * 目录页是两张大圆角分组卡（版式对齐 Hoshi Reader 的设置页）：
 *   ① 我的资料 / 外观 / 工具 / 网上办事大厅
 *   ② 诊断与日志 / 关于
 * 点进二级页后 Toolbar 切换为返回箭头 + 子页标题；系统返回键回到目录。
 * 视图切换在本 Fragment 内完成，避免与四 Tab hide/show 的 back stack 冲突。
 * 「工具」二级页收纳导出课表、身份码、图书馆座位、校园网、接口调试。
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
        binding.rowOaHall.setOnClickListener {
            startActivity(Intent(requireContext(), OaHallActivity::class.java))
        }
        binding.rowDirectory.setOnClickListener {
            com.lanhang.lite.directory.DirectoryActivity.start(requireContext())
        }
        binding.rowAbout.setOnClickListener { openDetail(PAGE_ABOUT) }
        binding.rowAccount.setOnClickListener { openMyProfile() }
        binding.rowLogout.setOnClickListener { confirmLogout() }
        binding.rowDiagnostics.setOnClickListener { showDiagnostics() }
        bindAccountCard()

        val restored = savedInstanceState?.getInt(KEY_DETAIL, -1) ?: -1
        if (restored == PAGE_APPEARANCE || restored == PAGE_TOOLS || restored == PAGE_ABOUT) {
            openDetail(restored, syncToolbar = false)
        }
        syncToolbar()
    }

    /** 目录页最顶端的账号卡：姓名 + 学号 */
    private fun bindAccountCard() {
        val b = _binding ?: return
        val name = Session.name.ifBlank { Session.username }.ifBlank { "--" }
        val studentNo = Session.username
        b.tvAccountName.text = name
        // 没有学号（或姓名本身就是学号）时给一句提示，别让第二行空着
        b.tvAccountMeta.text = if (studentNo.isNotBlank() && studentNo != name) {
            studentNo
        } else {
            getString(R.string.settings_account_meta_hint)
        }
        bindAvatar()
    }

    /**
     * 账号卡头像：**沿用个人资料页显示的那一档地址**（`portrait_big_url`）。
     *
     * 两档地址（`…/thumbnail/…` 与 `…/HDPhoto/…`）两个接口都会给，但 `GET /staffs/{id}`
     * 对部分账号给的是空串，所以地址一律取 `StaffBrief.bestPortrait`，缺了由
     * [UserInfoCache] / [com.lanhang.lite.net.RceApi.staffInfo] 用批量接口补回来。
     * 这里不自己拼 `portrait_url`，按控件尺寸缩小显示。
     *
     * 取不到就留空：不弹提示、也不画占位（2026-10-26 用户明确要求，头像失败不该打断设置页）。
     */
    private fun bindAvatar() {
        val id = Session.userId
        if (id.isBlank()) return
        val known = UserInfoCache.get(id)
        val big = known?.portrait_big_url?.takeIf { it.isNotBlank() }
        // 大图地址优先：Session 里可能还留着旧版本存下的缩略图地址（那一档手机连不上）
        val cached = big ?: Session.avatarUrl.ifBlank { known?.bestPortrait.orEmpty() }
        // 没有地址也先画一次：有旧缓存时立刻出图，没有就留空（不画占位）
        showAvatar(cached, known)
        val stale = System.currentTimeMillis() - Session.avatarFetchedAt > AVATAR_TTL_MS
        // 还不知道大图地址时也要补一次（老版本只存过缩略图地址）
        if (cached.isBlank() || big == null || Session.avatarUrl != cached || stale) {
            fetchAvatar(id)
        }
    }

    private fun fetchAvatar(id: String) {
        // 先记「试过了」的时间：失败也记，否则每次进设置都会重发请求
        Session.avatarFetchedAt = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            // 批量接口就带 portrait_big_url（实测），不用再单独查 /staffs/{id}
            UserInfoCache.resolve(listOf(id))
            val brief = UserInfoCache.get(id)
            val url = brief?.bestPortrait.orEmpty()
            if (url.isBlank()) return@launch
            // 万一只有缩略图地址，别把 Session 里已有的大图地址覆盖掉
            if (brief?.portrait_big_url.isNullOrBlank() && Session.avatarUrl.isNotBlank()) return@launch
            Session.avatarUrl = url
            Session.avatarFetchedAt = System.currentTimeMillis()
            showAvatar(url, brief)
        }
    }

    /** 头像加载：照片取不到就留空，不弹提示——头像失败不该打断设置页 */
    private fun showAvatar(url: String, brief: StaffBrief?) {
        val b = _binding ?: return
        // 主地址挂了还能试另一档（同一张图的另一个尺寸）
        val alt = brief?.altPortrait?.takeIf { it.isNotBlank() && it != url }
        b.ivAccountAvatar.bind(Session.name.ifBlank { Session.username }, url, alt)
    }

    private fun openMyProfile() {
        val id = Session.userId
        if (id.isBlank()) {
            Toast.makeText(requireContext(), R.string.error_relogin, Toast.LENGTH_SHORT).show()
        } else {
            UserProfileActivity.start(requireContext(), id)
        }
    }

    /**
     * 退出登录二次确认：退出是不可逆的会话操作，误触成本高（要重输账号密码），
     * 所以先弹 M3 确认框。取消（含返回键/点外部）只关框，停留在当前页面。
     */
    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.action_logout) { _, _ -> performLogout() }
            .show()
    }

    private fun performLogout() {
        val ctx = requireContext()
        Logout.clear(ctx)
        // 带 CLEAR_TASK：设置页可能正停在「外观 / 工具 / 关于」这几个二级态上，
        // 只 finish 会留下一层已经没会话的空壳返回栈（返回键能退回到旧界面，点哪都报重新登录）
        startActivity(
            Intent(ctx, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        activity?.finish()
    }

    /** 「1.2.3 (45)」：版本名 + 构建号，参考 Hoshi 关于页的版本写法 */
    private fun versionLabel(): String = runCatching {
        val info = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
        getString(
            R.string.settings_about_version_value,
            info.versionName ?: "--",
            PackageInfoCompat.getLongVersionCode(info)
        )
    }.getOrNull() ?: "--"

    /** 外部链接（源码仓库 / 许可证）：交给系统浏览器，没有可用应用时提示而不是崩 */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(
                    requireContext(),
                    R.string.settings_about_link_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    override fun onResume() {
        super.onResume()
        syncToolbar()
        refreshBackgroundSummary()
        refreshClassReminderSummary()
        // 从过滤设置页返回时档位/词数可能变了，摘要跟着刷
        refreshMomentsFilterSummary()
        // 资料页可能刚回填过头像地址，回到设置页顺手对齐一次（命中缓存时不发请求）
        bindAvatar()
    }

    /** 工具页里的上课提醒摘要（停在工具页时从提醒页返回要跟着变） */
    private fun refreshClassReminderSummary() {
        val b = _binding ?: return
        b.detailContainer.findViewById<TextView>(R.id.tvClassReminderSummary)?.text =
            ClassReminderStore.summary(requireContext())
    }

    /** 工作圈过滤摘要：`已开启 · 广告推广 等 28 个关键词` / `已关闭`（行在工具页里） */
    private fun refreshMomentsFilterSummary() {
        val b = _binding ?: return
        b.detailContainer.findViewById<TextView>(R.id.tvMomentsFilterSummary)?.text =
            MomentsFilterStore.summary(requireContext())
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
        // 工具页现在也装「身份码」，不只课表相关，标题与入口统一叫「工具」
        PAGE_TOOLS -> getString(R.string.settings_tools)
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
                root.findViewById<View>(R.id.rowClassReminder).setOnClickListener {
                    ClassReminderActivity.start(requireContext())
                }
                // 摘要要读当前开关与提前量，所以每次打开工具页都现算
                root.findViewById<TextView>(R.id.tvClassReminderSummary)?.text =
                    ClassReminderStore.summary(requireContext())
                root.findViewById<View>(R.id.exportScheduleButton)
                    .setOnClickListener { showExportSchedule() }
                root.findViewById<View>(R.id.rowIdCard).setOnClickListener {
                    startActivity(Intent(requireContext(), IdCardActivity::class.java))
                }
                // 图书馆座位、校园网 2026-10-26 从设置目录挪进工具页
                root.findViewById<View>(R.id.rowLibrarySeat)
                    .setOnClickListener { LibrarySeatActivity.start(requireContext()) }
                root.findViewById<View>(R.id.rowCampusNet).setOnClickListener {
                    startActivity(Intent(requireContext(), CampusNetActivity::class.java))
                }
                // 工作圈过滤 2026-10-28 从设置目录挪进工具页
                root.findViewById<TextView>(R.id.tvMomentsFilterSummary)?.text =
                    MomentsFilterStore.summary(requireContext())
                root.findViewById<View>(R.id.rowMomentsFilter).setOnClickListener {
                    MomentsFilterActivity.start(requireContext())
                }
                root.findViewById<View>(R.id.rowDebugSchedule)
                    .setOnClickListener { debugSchedule() }
            }
            else -> {
                // 关于页：版本信息 / 反馈问题 / 项目链接；诊断已提到目录页（2026-10-26）
                root.findViewById<TextView>(R.id.tvVersion).text = versionLabel()
                root.findViewById<TextView>(R.id.tvProto).text = "NchuApp/${Session.appVersion}"
                root.findViewById<View>(R.id.rowFeedback)
                    .setOnClickListener { openUrl(ISSUES_URL) }
                root.findViewById<View>(R.id.rowSourceCode)
                    .setOnClickListener { openUrl(REPO_URL) }
                root.findViewById<View>(R.id.rowLicense)
                    .setOnClickListener { openUrl("$REPO_URL/blob/main/LICENSE") }
            }
        }
    }

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
                    // 见 LocaleHelper：不再用系统的 per-app locale（它会系统级 relaunch，
                    // 过程中有 0.4s 纯黑帧）。改完自己 recreate()，界面立刻换语言且不黑屏
                    AppLanguage.apply(mode)
                    context?.applicationContext?.let { ScheduleWidgetUpdater.updateAll(it) }
                    requireActivity().recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /**
     * 课表接口调试（原顶栏 «更多» 菜单项，2026-10-25 挪进「设置 → 工具」）：
     * 先握手拿 ldapToken，再抓一次 GetSchedule 原文，只回报长度，不渲染数据。
     * 用于排查「课表拉不下来 / 一直是旧数据」时到底是会话问题还是接口问题。
     */
    private fun debugSchedule() {
        val b = _binding ?: return
        val ctx = b.root.context
        // 用 viewLifecycleOwner 而不是 fragment 的 lifecycleScope：随视图销毁一起取消
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (!MiniappApi.handshake(Session.deviceId(ctx))) {
                        return@withContext getString(
                            R.string.debug_handshake_failed,
                            Session.ldapToken.take(20)
                        )
                    }
                    val raw = MiniappApi.getScheduleRaw(null)
                    getString(R.string.debug_handshake_ok, raw.length)
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.debug_schedule_title)
                    .setMessage(result)
                    .setPositiveButton(R.string.common_close, null)
                    .show()
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Toast.makeText(
                    ctx,
                    e.userMessage(ctx, R.string.debug_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
                val message = e.userMessage(
                    requireContext(),
                    R.string.schedule_export_failed_detail
                )
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

        /** 公开仓库：关于页的「源码仓库」「开源许可」都指向这里 */
        private const val REPO_URL = "https://github.com/Micheal-Reber/lanhang-lite"

        /**
         * 唯一的反馈渠道：仓库 Issues 页面（2026-10-26 用户指定）。
         * 不内置站内反馈表单、不引第三方 SDK——反馈内容公开可检索，维护者在同一处跟进。
         */
        private val ISSUES_URL get() = "$REPO_URL/issues"

        /** 头像地址缓存多久后静默刷新一次（换头像能跟上，又不至于每次进设置都发请求） */
        private const val AVATAR_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
