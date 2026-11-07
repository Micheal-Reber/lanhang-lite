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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.StorageJanitor
import com.lanhang.lite.core.toast
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
import com.lanhang.lite.reminder.ReminderActivity
import com.lanhang.lite.schedule.ScheduleBackdrop
import com.lanhang.lite.schedule.ScheduleBackgroundActivity
import com.lanhang.lite.schedule.ScheduleData
import com.lanhang.lite.schedule.ScheduleExporter
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.AppLanguage
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.MainActivity
import com.lanhang.lite.ui.navigation.BottomBarDims
import com.lanhang.lite.ui.navigation.BottomBarPrefs
import com.lanhang.lite.ui.navigation.BottomBarStyle
import com.lanhang.lite.update.DownloadState
import com.lanhang.lite.update.UpdateCheck
import com.lanhang.lite.update.UpdateChecker
import com.lanhang.lite.update.UpdateDownloadManager
import com.lanhang.lite.update.UpdateInstaller
import com.lanhang.lite.update.UpdateStatus
import com.lanhang.lite.update.UpdateStore
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
        binding.rowReminder.setOnClickListener {
            startActivity(Intent(requireContext(), ReminderActivity::class.java))
        }
        binding.rowTools.setOnClickListener { openDetail(PAGE_TOOLS) }
        binding.rowOaHall.setOnClickListener {
            startActivity(Intent(requireContext(), OaHallActivity::class.java))
        }
        binding.rowDirectory.setOnClickListener {
            com.lanhang.lite.directory.DirectoryActivity.start(requireContext())
        }
        binding.rowAbout.setOnClickListener { openDetail(PAGE_ABOUT) }
        // 私信入口：只有 im 版存在（lite 版 ImBridge 是空壳，canOpen() 恒 false）。
        // 直接开独立页面而不是二级页 —— 聊天记录是独立 Activity 的活，塞进设置页的
        // detailContainer 里会和「返回层级」纠缠（设置页的返回键是给自己二级页用的）。
        val imAvailable = com.lanhang.lite.core.ImBridge.canOpen()
        binding.rowIm.isVisible = imAvailable
        binding.dividerIm.isVisible = imAvailable
        if (imAvailable) {
            binding.rowIm.setOnClickListener {
                com.lanhang.lite.core.ImBridge.open(requireContext())
            }
        }
        bindImSummary()
        binding.rowAccount.setOnClickListener { openMyProfile() }
        binding.rowDiagnostics.setOnClickListener {
            // 2026-10-30 改成独立页面（原来是 AlertDialog，报错一多就挤成一坨灰墙，
            // 也没法只挑一行复制），见 DiagnosticsActivity
            DiagnosticsActivity.start(requireContext())
        }
        binding.rowClearCache.setOnClickListener { openDetail(PAGE_CACHE) }
        bindAccountCard()

        val restored = savedInstanceState?.getInt(KEY_DETAIL, -1) ?: -1
        if (restored in intArrayOf(PAGE_APPEARANCE, PAGE_TOOLS, PAGE_ABOUT, PAGE_CACHE)) {
            openDetail(restored, syncToolbar = false)
        }
        // 目录页的底部留白按底栏形态给（玻璃 112dp / 原生 96dp）。
        // 写在 xml 里的话切换形态后不会跟着变 —— 见 BottomBarDims 的说明。
        binding.contentDirectory.updatePadding(
            bottom = BottomBarDims.contentBottomPadding(requireContext())
        )
        syncToolbar()
    }

    /**
     * 「私信」那一行的第二行：**只在有未读时出现**，显示「N 条未读」。
     *
     * 没有未读就整行 GONE（用户 2026-10-31 明确要求不要「和老师同学单聊、群聊」这类说明文字），
     * 行高这时退回单行。不做实时刷新：与其他摘要一样只在 onResume 重算。
     */
    private fun bindImSummary() {
        val b = _binding ?: return
        if (!com.lanhang.lite.core.ImBridge.canOpen()) return
        val unread = com.lanhang.lite.core.ImBridge.unread()
        b.tvImSummary.isVisible = unread > 0
        if (unread > 0) {
            b.tvImSummary.text = getString(
                R.string.im_unread_summary,
                if (unread > 99) "99+" else unread.toString()
            )
        }
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
        refreshThemeSummary()
        refreshBackgroundSummary()
        // 从过滤设置页返回时档位/词数可能变了，摘要跟着刷
        refreshMomentsFilterSummary()
        // 资料页可能刚回填过头像地址，回到设置页顺手对齐一次（命中缓存时不发请求）
        bindAvatar()
        // 从私信列表回来时未读可能清零了
        bindImSummary()
    }


    /**
     * 清除缓存。
     *
     * 只删图片缓存与调试产物；课表、成绩、自建课程、离线快照都在 `filesDir` 里，**一个不动**。
     * 删之前先说清楚会释放多少、以及图片下次会重新下载 —— 用户点下去之前得知道代价。
     */
    private fun confirmClearCache(usageSummary: TextView) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) { StorageJanitor.usage(ctx) }
            if (_binding == null) return@launch
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.settings_clear_cache_confirm_title)
                .setMessage(
                    getString(
                        R.string.settings_clear_cache_confirm_message,
                        StorageJanitor.format(usage.total)
                    )
                )
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.settings_clear_cache_action) { _, _ ->
                    lifecycleScope.launch {
                        val freed = withContext(Dispatchers.IO) { StorageJanitor.clear(ctx) }
                        val after = withContext(Dispatchers.IO) { StorageJanitor.usage(ctx) }
                        usageSummary.text = getString(
                            R.string.settings_cache_usage_summary,
                            StorageJanitor.format(after.images),
                            StorageJanitor.format(after.others)
                        )
                        Toast.makeText(
                            ctx,
                            getString(
                                R.string.settings_clear_cache_done,
                                StorageJanitor.format(freed)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .show()
        }
    }

    /**
     * 二级页里的占用与周期摘要。
     *
     * 统计要遍历文件目录，必须放 IO 线程 —— 主线程扫一次 image_cache（实测 146 个文件、
     * 74MB）会明显卡一下。
     */
    private fun refreshCacheUsage(ttlSummary: TextView, usageSummary: TextView) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val days = StorageJanitor.ttlDays(ctx)
            val usage = withContext(Dispatchers.IO) { StorageJanitor.usage(ctx) }
            ttlSummary.text = ttlLabel(days)
            usageSummary.text = getString(
                R.string.settings_cache_usage_summary,
                StorageJanitor.format(usage.images),
                StorageJanitor.format(usage.others)
            )
        }
    }

    /** 选自动清理周期 */
    private fun showCacheTtlDialog(summary: TextView) {
        val ctx = context ?: return
        val options = StorageJanitor.TTL_OPTIONS
        val labels = options.map { ttlLabel(it) }.toTypedArray()
        val current = StorageJanitor.ttlDays(ctx)
        val checked = options.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_cache_ttl)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                StorageJanitor.setTtlDays(ctx, options[which])
                summary.text = labels[which]
                // 单选列表默认不自动关闭，得自己 dismiss
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun ttlLabel(days: Int): String = if (days <= 0) {
        getString(R.string.settings_cache_ttl_never)
    } else {
        getString(R.string.settings_cache_ttl_days, days)
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
        PAGE_CACHE -> getString(R.string.settings_clear_cache)
        else -> getString(R.string.settings_about_app)
    }

    private fun openDetail(page: Int, syncToolbar: Boolean = true) {
        val b = _binding ?: return
        val layout = when (page) {
            PAGE_APPEARANCE -> R.layout.view_settings_appearance
            PAGE_TOOLS -> R.layout.view_settings_tools
            PAGE_CACHE -> R.layout.view_settings_cache
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
                // 底栏形态（2026-11-02）：液态玻璃 / 原生 Material3 底栏
                root.findViewById<TextView>(R.id.tvBottomBarSummary).text = currentBottomBarLabel()
                root.findViewById<View>(R.id.rowBottomBar)
                    .setOnClickListener { showBottomBarDialog() }
            }
            PAGE_TOOLS -> {
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
            PAGE_CACHE -> {
                val ttlSummary = root.findViewById<TextView>(R.id.tvCacheTtlSummary)
                val usageSummary = root.findViewById<TextView>(R.id.tvCacheUsageSummary)
                root.findViewById<View>(R.id.rowCacheTtl).setOnClickListener {
                    showCacheTtlDialog(ttlSummary)
                }
                root.findViewById<View>(R.id.btnClearCacheNow).setOnClickListener {
                    confirmClearCache(usageSummary)
                }
                refreshCacheUsage(ttlSummary, usageSummary)
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
                bindUpdateRow(root)
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
                    // 换模式一般会 recreate（重建后 setupDetail 会重填），但「浅色 ↔ 跟随系统」
                    // 在系统本身就是浅色时 AppCompatDelegate 不一定重建，所以这里显式刷一次
                    refreshThemeSummary()
                }
            }
            .setNegativeButton(R.string.schedule_export_cancel, null)
            .show()
    }

    /** 当前主题档位的显示名：与「课表背景」「语言」两行的副标题同一口径 */
    private fun currentThemeLabel(): String {
        val label = themeOptions.firstOrNull { it.first == Session.themeMode }?.second
            ?: R.string.settings_theme_system
        return getString(label)
    }

    /** 「底栏」行副标题：当前选中的形态 */
    private fun currentBottomBarLabel(): String =
        getString(BottomBarPrefs.current(requireContext()).labelRes)

    /**
     * 底栏形态二选一。
     *
     * 定下来就 `recreate()`：底栏是在 `MainActivity.onCreate` 里一次性搭起来的
     * （Compose 玻璃树 / BottomNavigationView 二选一，还带着 ViewBackdrop 采样器），
     * 原地换 View 要先拆采样器再重建，比直接重建 Activity 风险大得多。
     * 重建后设置页靠 `KEY_DETAIL` 自动回到外观页，用户不会掉到目录页。
     */
    private fun showBottomBarDialog() {
        val options = BottomBarStyle.entries
        val current = BottomBarPrefs.current(requireContext())
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_bottom_bar)
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                val style = options[which]
                dialog.dismiss()
                if (style == current) return@setSingleChoiceItems
                BottomBarPrefs.set(requireContext(), style)
                requireActivity().recreate()
            }
            .setNegativeButton(R.string.schedule_export_cancel, null)
            .show()
    }

    /** 二级页开着时刷新主题副标题（不在外观页时找不到这个 TextView，直接跳过） */
    private fun refreshThemeSummary() {
        val b = _binding ?: return
        b.detailContainer.findViewById<TextView>(R.id.tvThemeSummary)?.text = currentThemeLabel()
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

    // ---------------------------------------------------------------- 应用内更新

    /** 下载进度轮询任务；离开关于页或装完就取消 */
    private var updatePollJob: Job? = null

    /**
     * 关于页的「检查更新」行。
     *
     * 状态机与 Hoshi 一致：可检查 → 发现新版本（稍后 / 跳过此版本 / 下载）→ 下载中（显示百分比）
     * → 已下载（点行拉起安装器）。点击行为随状态变化，副标题就是状态本身。
     */
    private fun bindUpdateRow(root: View) {
        val summary = root.findViewById<TextView>(R.id.tvUpdateSummary)
        val row = root.findViewById<View>(R.id.rowUpdate)
        val ctx = requireContext()

        row.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                when (UpdateDownloadManager.state(ctx)) {
                    is DownloadState.Ready -> installDownloaded()
                    is DownloadState.Downloading -> Unit // 副标题已经在显示进度
                    else -> runUpdateCheck(summary, silent = false)
                }
            }
        }
        // 进页面先把本地记录 / 下载状态画出来；24 小时内查过就不再自动查
        viewLifecycleOwner.lifecycleScope.launch {
            refreshDownloadState(ctx, summary)
            if (UpdateStore.shouldAutoCheck(ctx)) runUpdateCheck(summary, silent = true)
        }
    }

    /** 把本地记录 + DownloadManager 的真实状态映射到副标题；下载中就挂一个轮询 */
    private suspend fun refreshDownloadState(ctx: android.content.Context, summary: TextView) {
        when (val state = UpdateDownloadManager.state(ctx)) {
            is DownloadState.Ready -> {
                summary.setText(R.string.update_ready)
                stopUpdatePolling()
            }
            is DownloadState.Downloading -> {
                summary.text = if (state.progress >= 0) {
                    getString(R.string.update_downloading, state.progress)
                } else {
                    getString(R.string.update_checking)
                }
                startUpdatePolling(ctx, summary)
            }
            is DownloadState.Failed -> {
                summary.setText(R.string.update_download_failed)
                stopUpdatePolling()
            }
            DownloadState.Idle -> {
                stopUpdatePolling()
                // 只检查到、还没开始下载的记录：副标题直接写版本号
                val record = UpdateStore.load(ctx)
                if (record != null && record.status == UpdateStatus.AVAILABLE) {
                    summary.text = getString(R.string.update_available, record.versionName)
                } else if (record == null && !UpdateStore.shouldAutoCheck(ctx)) {
                    // 24 小时内查过、且没有待办记录 → 已经是最新版。
                    // 典型场景：刚在这个版本上更新完，UpdateDownloadManager 把记录清掉了，
                    // 不补这一句的话副标题会掉回「点击检查是否有新版本」，看着像没查过。
                    summary.setText(R.string.update_latest)
                }
            }
        }
    }

    /**
     * 每秒问一次 DownloadManager。
     *
     * 不用 `ACTION_DOWNLOAD_COMPLETE` 广播来刷新 UI？广播是用来**写状态**的（进程被杀也能收），
     * 页面开着时轮询更简单，切走就随 lifecycle 停掉。
     */
    private fun startUpdatePolling(ctx: android.content.Context, summary: TextView) {
        if (updatePollJob?.isActive == true) return
        updatePollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (!isAdded) return@launch
                val state = UpdateDownloadManager.state(ctx)
                refreshDownloadState(ctx, summary)
                if (state !is DownloadState.Downloading) return@launch
            }
        }
    }

    private fun stopUpdatePolling() {
        updatePollJob?.cancel()
        updatePollJob = null
    }

    /** @param silent 自动检查时失败不弹 toast（副标题仍会写出来） */
    private fun runUpdateCheck(summary: TextView, silent: Boolean) {
        val ctx = requireContext()
        summary.setText(R.string.update_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            // 手动点：ignoreSkipped = true，用户主动问了就要告诉他
            when (val result = UpdateChecker.check(ctx, ignoreSkipped = !silent)) {
                is UpdateCheck.UpToDate -> {
                    UpdateStore.markChecked(ctx)
                    summary.setText(R.string.update_latest)
                }
                is UpdateCheck.Available -> {
                    UpdateStore.markChecked(ctx)
                    summary.text = getString(R.string.update_available, result.versionName)
                    if (!silent) showUpdateDialog(result)
                }
                is UpdateCheck.Skipped -> {
                    UpdateStore.markChecked(ctx)
                    summary.text = getString(R.string.update_available, result.versionName)
                }
                UpdateCheck.NoAsset -> summary.setText(R.string.update_no_asset)
                is UpdateCheck.Failed -> {
                    summary.text = result.message
                    if (!silent) toast(result.message)
                }
            }
        }
    }

    /**
     * 发现新版本：稍后 / 跳过此版本 / 下载并安装。
     *
     * **debug 版不给下载按钮**：release 与 debug 签名不同，覆盖安装必然失败
     * （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），与其让人点了报错，不如直接开 Release 页面。
     * 检查本身在 debug 上照常跑 —— 否则这个功能自己永远测不到。
     */
    private fun showUpdateDialog(update: UpdateCheck.Available) {
        val ctx = requireContext()
        val debug = UpdateChecker.isDebugBuild(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.update_dialog_title, update.versionName))
            .setMessage(
                if (debug) R.string.update_dialog_message_dev else R.string.update_dialog_message
            )
            .setNeutralButton(R.string.update_action_skip) { _, _ ->
                UpdateStore.skip(ctx, update)
                toast(getString(R.string.update_skipped, update.versionName))
            }
            .setNegativeButton(R.string.update_action_later, null)
            .setPositiveButton(
                if (debug) R.string.update_action_open_release else R.string.update_action_download
            ) { _, _ ->
                if (debug) {
                    openUrl(update.releaseUrl)
                } else if (UpdateDownloadManager.enqueue(ctx, update) != null) {
                    toast(getString(R.string.update_downloading, 0))
                } else {
                    toast(getString(R.string.update_download_failed))
                }
            }
            .show()
    }

    /** 拉起系统安装器；失败原因（权限/被拦）直接 toast */
    private fun installDownloaded() {
        val ctx = requireContext()
        val message = UpdateInstaller.install(ctx, UpdateDownloadManager.file(ctx))
        if (message != null) toast(message)
    }

    companion object {
        private const val PAGE_APPEARANCE = 0
        private const val PAGE_TOOLS = 1
        private const val PAGE_ABOUT = 2
        private const val PAGE_CACHE = 3
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
