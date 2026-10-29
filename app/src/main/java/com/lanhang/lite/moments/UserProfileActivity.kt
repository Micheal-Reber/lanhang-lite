package com.lanhang.lite.moments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityUserProfileBinding
import com.lanhang.lite.databinding.ItemProfileRowBinding
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.net.StaffBrief
import com.lanhang.lite.net.UserStatus
import com.lanhang.lite.session.Session
import com.lanhang.lite.settings.Diagnostics
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个人资料页（工作圈点头像 / 昵称进来）。
 *
 * 对应官方 `UserDetailActivity` + `StaffDetailFragment`，是「两级页面」的第一级：
 * 资料在这里，贴文点「个人动态」进 [MyMomentsActivity]（第二级）。
 *
 * 数据来源与隐私规则严格照官方（见 `docs/moments-user-profile-research.md` §4）：
 * - 资料：`GET /staffs/{id}`，失败（学生视角常见 `10001 NOT_PERMITTED`）回退 `POST /staffs/batch`
 * - 隐私位：`GET /user/getUserStatusById/{id}`；取不到时**按最严处理**（手机号/邮箱一律打码）
 * - 查看者身份：`GET /user/getUserWork`，`"0"` = 学生
 * - 学校名：`company_id` → `GET /companies/{id}`
 *
 * 掩码矩阵（照抄 `StaffDetailFragment:130-205`）：
 * | 查看者 | 目标 | 学号/工号 | 手机号 | 邮箱 |
 * | 学生 | 学生 | 显示「学号」 | telFlag!=0 → ******** | mailFlag!=0 → ******** |
 * | 学生 | 教职工 | **整行隐藏** | 同上 | 同上 |
 * | 教职工 | 任意 | 显示「工号/学号」 | 明文 | 明文 |
 *
 * 隐私红线：服务端可能直接返回明文，**必须**在客户端按 flag 遮住，否则等于绕过学校设置。
 */
class UserProfileActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityUserProfileBinding

    private val targetId: String by lazy { intent.getStringExtra(EXTRA_USER_ID).orEmpty() }

    private val isSelf: Boolean get() = targetId.isNotEmpty() && targetId == Session.userId

    /** 一次拉取的全部输入，避免绑定阶段再访问网络 */
    private data class ProfileData(
        val staff: StaffBrief?,
        val status: UserStatus?,
        val viewerIsStaff: Boolean,
        val schoolName: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setTitle(
            if (isSelf) R.string.user_profile_title_self else R.string.user_profile_title
        )

        // insets 会多次回调，基准值必须先取：直接读当前 padding 会一次次累加
        val baseBottom = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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
                baseBottom + bars.bottom
            )
            insets
        }

        binding.rowPosts.setOnClickListener { MyMomentsActivity.start(this, targetId) }
        binding.btnRetry.setOnClickListener { load() }
        // 自己看自己（设置 → 当前账号）只展示资料：不挂「个人动态」入口，
        // 「我的动态」在主导航已有独立入口，这里再放一次属于重复
        binding.cardPosts.isVisible = !isSelf

        if (targetId.isBlank()) {
            showError(getString(R.string.user_profile_not_found))
            return
        }
        load()
    }

    private fun load() {
        binding.progress.isVisible = true
        binding.errorBox.isVisible = false
        lifecycleScope.launch {
            try {
                val data = fetch()
                if (data.staff == null) {
                    showError(getString(R.string.user_profile_not_found))
                    return@launch
                }
                bind(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                Diagnostics.record(this@UserProfileActivity, TAG, e)
                showError(e.userMessage(this@UserProfileActivity, R.string.user_profile_error))
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@UserProfileActivity, TAG, e)
                showError(getString(R.string.user_profile_error))
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    /**
     * 三条请求并行（隐私位 / 查看者身份 / 资料），学校名依赖资料里的 `company_id` 所以在其后。
     * 隐私位与身份是**可选**的：失败就让 [ProfileData.status] 为 null，绑定阶段按最严处理。
     */
    private suspend fun fetch(): ProfileData = withContext(Dispatchers.IO) {
        coroutineScope {
            val statusJob = async { optional { RceApi.userStatus(targetId) } }
            val workJob = async { optional { RceApi.getUserWork() } }
            val staff = RceApi.staffInfo(targetId)
            val status = statusJob.await()
            val viewerIsStaff = workJob.await()?.trim()?.let { it.isNotEmpty() && it != "0" } ?: false

            val companyId = staff?.company_id?.takeIf { it.isNotBlank() }
                ?: Session.companyId.takeIf { isSelf }
            val school = companyId?.let { optional { RceApi.company(it) } }?.name
            if (staff != null) UserInfoCache.remember(staff)

            ProfileData(staff, status, viewerIsStaff, school?.takeIf { it.isNotBlank() })
        }
    }

    /** 可选请求：任何异常（含解析失败）都当作「没有」，但**不能**吞掉协程取消 */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun bind(data: ProfileData) {
        val staff = data.staff
        bindHeader(staff)
        bindRows(staff, data)
        binding.scroll.isVisible = true
        binding.errorBox.isVisible = false
    }

    private fun bindHeader(staff: StaffBrief?) {
        val fallback = UserInfoCache.get(targetId)
        // 大图优先（就是这一页显示的那一档）；资料接口没给地址时才用缓存里的
        val avatar = staff?.bestPortrait.orEmpty().ifBlank { fallback?.bestPortrait.orEmpty() }
        // 自己看自己时顺手把头像地址存进 Session：设置页账号卡冷启动就能直接出图
        if (isSelf && avatar.isNotBlank()) {
            Session.avatarUrl = avatar
            Session.avatarFetchedAt = System.currentTimeMillis()
        }
        val name = staff?.name?.takeIf { it.isNotBlank() }
            ?: UserInfoCache.nameOf(targetId)
            ?: getString(R.string.moments_anonymous)
        val alt = staff?.altPortrait.orEmpty().ifBlank { fallback?.altPortrait.orEmpty() }
        binding.ivAvatar.bind(name, avatar.takeIf { it.isNotBlank() }, alt.takeIf { it.isNotBlank() })

        binding.tvName.text = name

        val subtitle = listOfNotNull(departOf(staff), dutyOf(staff))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        binding.tvSubtitle.text = subtitle
        binding.tvSubtitle.isVisible = subtitle.isNotEmpty()
    }

    private fun bindRows(staff: StaffBrief?, data: ProfileData) {
        val status = data.status
        // 权限位取不到时按最严：手机号/邮箱一律打码（宁严不松）
        val telMasked = !isSelf && !data.viewerIsStaff && (status?.telFlag ?: 1) != 0
        val mailMasked = !isSelf && !data.viewerIsStaff && (status?.mailFlag ?: 1) != 0
        val targetWorkFlag = status?.userWorkFlag ?: 0

        // 学号/工号：学生视角看教职工时官方整行隐藏
        val staffNoVisible = isSelf || data.viewerIsStaff || targetWorkFlag == 0
        val staffNo = staff?.studentNo
            ?: Session.username.takeIf { isSelf && it.isNotBlank() }

        val phone = staff?.mobile?.takeIf { it.isNotBlank() }
            ?: staff?.tel?.takeIf { it.isNotBlank() }
        val email = staff?.email?.takeIf { it.isNotBlank() }

        // 手机号
        setRow(
            binding.rowPhone,
            R.string.user_profile_label_phone,
            value = if (telMasked) MASK else phone,
            show = telMasked || !phone.isNullOrBlank()
        )
        // 学号 / 工号
        setRow(
            binding.rowStaffNo,
            if (targetWorkFlag == 1) R.string.user_profile_label_work_no
            else R.string.user_profile_label_staff_no,
            value = staffNo,
            show = staffNoVisible && !staffNo.isNullOrBlank()
        )
        // 邮箱（被打码时与官方一致：不可点、不可复制）
        setRow(
            binding.rowEmail,
            R.string.user_profile_label_email,
            value = if (mailMasked) MASK else email,
            show = mailMasked || !email.isNullOrBlank(),
            enabled = !mailMasked
        )

        layoutDividers(
            listOf(binding.rowPhone.root, binding.rowStaffNo.root, binding.rowEmail.root),
            listOf(binding.dividerStaffNo, binding.dividerEmail)
        )

        // 组织
        val depart = departOf(staff)
        val major = majorOf(staff)
        val klass = classOf(staff)
        val duty = dutyOf(staff)
        setRow(
            binding.rowSchool,
            R.string.user_profile_label_school,
            value = data.schoolName,
            show = !data.schoolName.isNullOrBlank()
        )
        setRow(
            binding.rowDepart,
            R.string.user_profile_label_depart,
            value = depart,
            show = !depart.isNullOrBlank()
        )
        setRow(
            binding.rowMajor,
            R.string.user_profile_label_major,
            value = major,
            show = !major.isNullOrBlank()
        )
        setRow(
            binding.rowClass,
            R.string.user_profile_label_class,
            value = klass,
            show = !klass.isNullOrBlank()
        )
        setRow(
            binding.rowPost,
            R.string.user_profile_label_post,
            value = duty,
            show = !duty.isNullOrBlank()
        )
        layoutDividers(
            listOf(
                binding.rowSchool.root,
                binding.rowDepart.root,
                binding.rowMajor.root,
                binding.rowClass.root,
                binding.rowPost.root
            ),
            listOf(
                binding.dividerDepart,
                binding.dividerMajor,
                binding.dividerClass,
                binding.dividerPost
            )
        )
        binding.cardOrg.isVisible = listOf(
            binding.rowSchool.root, binding.rowDepart.root, binding.rowMajor.root,
            binding.rowClass.root, binding.rowPost.root
        ).any { it.isVisible }

        binding.tvMaskHint.isVisible = telMasked || mailMasked
    }

    private fun setRow(
        row: ItemProfileRowBinding,
        labelRes: Int,
        value: String?,
        show: Boolean,
        enabled: Boolean = true
    ) {
        row.root.isVisible = show
        if (!show) return
        row.tvRowLabel.setText(labelRes)
        row.tvRowValue.text = value.orEmpty()
        row.tvRowValue.isEnabled = enabled
    }

    /**
     * 行被隐藏后要重排分隔线：给第 2 个及之后**可见**的行保留它上方那条线，
     * 其余（含卡片顶部那条）一律隐藏，避免出现「两条线贴在一起」或「末尾多一条线」。
     */
    private fun layoutDividers(rows: List<View>, dividers: List<View>) {
        dividers.forEach { it.isVisible = false }
        var seen = false
        rows.forEachIndexed { i, row ->
            if (!row.isVisible) return@forEachIndexed
            if (i > 0 && seen) dividers.getOrNull(i - 1)?.isVisible = true
            seen = true
        }
    }

    private fun showError(message: String) {
        binding.errorBox.isVisible = true
        binding.tvError.text = message
        binding.scroll.isVisible = false
        binding.progress.isVisible = false
    }

    // ---------------- 字段解析 ----------------

    /**
     * 学院 / 院系。
     *
     * ⚠️ 不能用 `depart_name` 打头：真机实测学生对它是**班级代码**（`<班级代码>`），
     * 直接显示会变成「<班级代码> · 学生」，用户看不出是哪个学院。优先级：
     * 1. `org` 第一段 —— 实测 `仪器科学与光电工程学院-2024-<班级代码>`，最稳
     * 2. `orgs_info[0].path` 里第 3 个组织节点（学校 > 学生 > 学院 > 年级 > 班级）
     * 3. `depart_name`（教职工账号这里通常就是学院名）
     * 4. `extra` 里的组织名（老接口兼容）
     */
    private fun departOf(staff: StaffBrief?): String? {
        val org = staff?.org?.trim().orEmpty()
        if (org.isNotEmpty()) {
            val head = org.substringBefore('-').trim()
            if (head.isNotEmpty() && !head.isPureCode()) return head
        }
        collegeFromPath(staff)?.let { return it }
        staff?.depart_name?.trim()?.takeIf { it.isNotEmpty() && !it.isPureCode() }?.let { return it }
        return orgFromExtra(staff?.extra)
    }

    /** `orgs_info[0].path` 里的学院：跳过学校、身份层、年级与班级编码，取最靠后的中文名节点 */
    private fun collegeFromPath(staff: StaffBrief?): String? {
        val path = staff?.orgsInfo?.firstOrNull()?.path.orEmpty()
        if (path.size < 3) return null
        // [学校, 学生, 学院, 2024, <班级代码>] —— 从后往前跳过纯编码节点，第一个中文名就是学院
        return path.asReversed()
            .firstOrNull { node ->
                val name = node.name?.trim().orEmpty()
                name.isNotEmpty() && !name.isPureCode()
            }
            ?.name?.trim()
    }

    /** 专业：`/staffs/{id}` 的 `userOrgencode`（`POST /staffs/batch` 没有这个字段） */
    private fun majorOf(staff: StaffBrief?): String? =
        staff?.userOrgencode?.trim()?.takeIf { it.isNotEmpty() }

    /** 班级：学生对 `depart_name` 就是班级代码（`<班级代码>`），教职工一般没有 */
    private fun classOf(staff: StaffBrief?): String? {
        val name = staff?.depart_name?.trim().orEmpty()
        if (name.isEmpty()) return null
        // 教职工的 depart_name 是学院名时，上面已经当院系显示了，这里不再重复
        if (!name.isPureCode()) return null
        return name
    }

    /**
     * 职务。学生账号的 `duty_name` 常直接是学号，不能当职务渲染
     * （身份码页已记过这个坑），所以学号/纯数字一律丢弃。
     */
    private fun dutyOf(staff: StaffBrief?): String? {
        val duty = staff?.duty_name?.trim().orEmpty()
        if (duty.isEmpty()) return null
        val staffNo = staff?.studentNo.orEmpty()
        if (duty == staffNo) return null
        if (duty.all { it.isDigit() }) return null
        return duty
    }

    /**
     * `extra` 里的组织名。官方无条件把它当 JSON 解析，学生账号的 `extra` 是 `"30"`
     * 这种纯数字会抛异常，组织行永远空白——这里改成「解析得出才用」。
     */
    private fun orgFromExtra(extra: String?): String? {
        val raw = extra?.trim().orEmpty()
        if (!raw.startsWith("{")) return null
        return runCatching {
            val obj = JsonParser.parseString(raw).asJsonObject
            ORG_KEYS.firstNotNullOfOrNull { key ->
                obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "个人资料加载"
        private const val MASK = "********"

        private val ORG_KEYS = listOf(
            "depart_name", "department_name", "org_name", "dept_name",
            "unit_name", "company_name", "department", "org", "unit"
        )

        const val EXTRA_USER_ID = "user_id"

        fun start(context: Context, userId: String) {
            if (userId.isBlank()) return
            context.startActivity(
                Intent(context, UserProfileActivity::class.java)
                    .putExtra(EXTRA_USER_ID, userId)
            )
        }

        /** 供 [MomentAdapter] 等回调直接使用，避免各处重复拼 Intent */
        fun intent(context: Context, userId: String): Intent =
            Intent(context, UserProfileActivity::class.java)
                .putExtra(EXTRA_USER_ID, userId)
    }
}

/**
 * 班级/年级编码判定（`<班级代码>`、`2024`、`<学号>`）。
 *
 * 用来把「学院名」与「班级代码」区分开：学生账号的 `depart_name` 是班级代码，
 * `orgs_info` 的 path 里也混着年级与班级，只有中文名才是给人看的组织名。
 */
private fun String.isPureCode(): Boolean =
    isNotEmpty() && length <= 12 && any { it.isDigit() } && none { it.code in 0x4E00..0x9FFF }
