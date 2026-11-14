package com.lanhang.lite.moments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonParser
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.toast
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityUserProfileBinding
import com.lanhang.lite.databinding.ItemProfileRowBinding
import com.lanhang.lite.net.FriendRequestInfo
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.net.StaffBrief
import com.lanhang.lite.net.UserStatus
import com.lanhang.lite.session.Logout
import com.lanhang.lite.session.Session
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.LoginActivity
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
        val schoolName: String?,
        /** 与目标的好友关系（im 版才拉；取不到时是 [FriendRelation.NONE]） */
        val friendRelation: FriendRelation
    )

    /**
     * 与目标的好友关系，由 `GET /friends` + `GET /friends/request_list` 推出来。
     * 官方存在本地表 `t_friends` / `t_friend_request`，我们没有那套同步机制，每次现拉。
     */
    private enum class FriendRelation { NONE, FRIEND, INCOMING, OUTGOING }

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

        // 退出登录（2026-11-01 从设置目录页搬到本页最下面）：只有自己看自己时出现，
        // 别人的资料页不该有「退出登录」
        binding.btnLogout.isVisible = isSelf
        binding.btnLogout.setOnClickListener { confirmLogout() }

        // 发私信：im 版才注入实现（lite 版 ImBridge 是空壳），且不给自己发
        val canMessage = !isSelf && com.lanhang.lite.core.ImBridge.canOpen()
        binding.cardMessage.isVisible = canMessage
        binding.rowMessage.setOnClickListener {
            com.lanhang.lite.core.ImBridge.openChat(this, targetId)
        }

        if (targetId.isBlank()) {
            showError(getString(R.string.user_profile_not_found))
            return
        }
        load()
    }

    private fun load() {
        binding.progress.show()
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
                binding.progress.hide()
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
            // 好友关系只在「这一行会显示」时才拉（自己看自己、lite 版都不显示，别白打两个请求）
            val friendJob = if (needFriendRow) async { optional { friendRelation() } } else null
            val staff = RceApi.staffInfo(targetId)
            val status = statusJob.await()
            val viewerIsStaff = workJob.await()?.trim()?.let { it.isNotEmpty() && it != "0" } ?: false

            val companyId = staff?.company_id?.takeIf { it.isNotBlank() }
                ?: Session.companyId.takeIf { isSelf }
            val school = companyId?.let { optional { RceApi.company(it) } }?.name
            if (staff != null) UserInfoCache.remember(staff)

            ProfileData(
                staff,
                status,
                viewerIsStaff,
                school?.takeIf { it.isNotBlank() },
                friendJob?.await() ?: FriendRelation.NONE
            )
        }
    }

    /**
     * 好友关系这一块要不要出现 / 要不要发那两个请求（自己看自己、lite 版都不会有）。
     *
     * 注意它**不等于** [binding.cardFriend] 的可见性：已是好友时卡片行会隐藏、
     * 改显示页面最下面的删除按钮，但关系照样要拉 —— 判定好友态正是靠它。
     */
    private val needFriendRow: Boolean
        get() = !isSelf && com.lanhang.lite.core.ImBridge.canOpen()

    /**
     * 与目标的好友关系。
     *
     * **取不到就当不是好友**：把网络故障反向当成「是好友」，用户会以为自己把人删了，
     * 比多显示一个「加好友」糟得多。两个请求各自容错，一个失败不影响另一个。
     */
    private suspend fun friendRelation(): FriendRelation = withContext(Dispatchers.IO) {
        val friends = optional { RceApi.friends() }
        if (friends != null && friends.any { it.id == targetId }) {
            return@withContext FriendRelation.FRIEND
        }
        val requests = optional { RceApi.friendRequests() } ?: return@withContext FriendRelation.NONE
        val request = requests.filter { it.uid == targetId }
            .maxByOrNull { it.update_dt ?: it.create_dt ?: 0L }
            ?: return@withContext FriendRelation.NONE
        when (request.state) {
            FriendRequestInfo.STATE_INVITED -> FriendRelation.INCOMING
            FriendRequestInfo.STATE_INVITE -> FriendRelation.OUTGOING
            // 我已同意 / 对方已接受：friends() 那次可能只是没取到，按已经是好友处理
            FriendRequestInfo.STATE_ACCEPT,
            FriendRequestInfo.STATE_ACCEPTED -> FriendRelation.FRIEND
            else -> FriendRelation.NONE
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
        bindFriendRow(data)
        bindBlockButton(staff)
        binding.scroll.isVisible = true
        binding.errorBox.isVisible = false
    }

    // ---------------- 好友（加 / 同意 / 删除） ----------------

    /**
     * 好友关系的四态，**入口与判定都在这里一处**，别再往页面其它地方塞加好友按钮 ——
     * 官方是「资料页按钮 + 通讯录新朋友行 + 会话列表菜单」三处，我们只留资料页这一处，
     * 另外两处分别由私信顶栏的「新朋友」和它的列表页承担。
     *
     * 2026-11-13 按用户要求拆成两处显示：
     * - 加好友 / 同意申请 / 等待对方同意 → 上面那张卡的行（[binding.cardFriend]）；
     * - **已是好友 → 删除好友** → 页面最下面的 [binding.btnDeleteFriend]，与「退出登录」同版式
     *   （红描边整行按钮 + 图标）。删好友是破坏性动作，挤在卡片行里太不起眼。
     *   两者不会同屏：退出登录只在自己看自己时出现，这颗只在看别人时出现。
     *
     * 「等待对方同意」是终态展示：没有可做的事，所以藏掉 chevron 并关掉 clickable
     * （挂了监听又点不动最莫名其妙；直接不显示箭头，用户一眼就知道不用等它）。
     */
    private fun bindFriendRow(data: ProfileData) {
        if (!needFriendRow) {
            binding.cardFriend.isVisible = false
            binding.btnDeleteFriend.isVisible = false
            return
        }

        val relation = data.friendRelation
        val name = data.staff?.name?.takeIf { it.isNotBlank() }
            ?: UserInfoCache.nameOf(targetId)
            ?: getString(R.string.moments_anonymous)

        // 已是好友：卡片行整卡不显示，交给页面最下面那颗红按钮
        val isFriend = relation == FriendRelation.FRIEND
        binding.cardFriend.isVisible = !isFriend
        binding.btnDeleteFriend.isVisible = isFriend
        binding.btnDeleteFriend.setOnClickListener { confirmDeleteFriend(name) }
        if (isFriend) return

        binding.tvFriendLabel.setText(
            when (relation) {
                FriendRelation.FRIEND -> R.string.friend_delete // 走不到：好友态已在上面 return
                FriendRelation.INCOMING -> R.string.friend_accept
                FriendRelation.OUTGOING -> R.string.friend_waiting
                FriendRelation.NONE -> R.string.friend_add
            }
        )

        binding.rowFriend.setOnClickListener {
            when (relation) {
                FriendRelation.FRIEND -> Unit // 走不到：删除在底部按钮
                FriendRelation.INCOMING -> acceptFriendRequest()
                FriendRelation.OUTGOING -> Unit
                FriendRelation.NONE -> showInviteDialog(name)
            }
        }
        val actionable = relation != FriendRelation.OUTGOING
        binding.rowFriend.isClickable = actionable
        binding.rowFriend.isFocusable = actionable
        binding.ivFriendChevron.isVisible = actionable
    }

    /**
     * 加好友：验证语输入框。默认「我是〈我的名字〉」与官方 `InviteFriendActivity` 同款，
     * 可改可清空（服务端允许空串，官方也没做非空校验）。
     */
    private fun showInviteDialog(myName: String) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val input = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.friend_invite_hint)
        }
        val edit = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setText(
                getString(
                    R.string.friend_invite_default,
                    myName.ifBlank { Session.name }.ifBlank { Session.username }
                )
            )
        }
        input.addView(edit)
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friend_add)
            .setView(container)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.friend_add) { _, _ ->
                sendFriendInvite(edit.text?.toString().orEmpty())
            }
            .show()
    }

    private fun sendFriendInvite(content: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RceApi.inviteFriend(targetId, content) }
                toast(getString(R.string.friend_invite_sent))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@UserProfileActivity, TAG, e)
                toast(e.userMessage(this@UserProfileActivity, R.string.friend_add_failed))
            }
        }
    }

    /**
     * 同意申请。requestId **现取**：这一行的状态是进页面时拉的，用户可能停了很久才点，
     * 拿着过期的 id 去同意只会回 `10401 request not exist`。取不到说明申请已经没了
     * （别处处理过 / 超时），重拉一次让按钮回到正确状态即可。
     */
    private fun acceptFriendRequest() {
        lifecycleScope.launch {
            try {
                val requestId = withContext(Dispatchers.IO) {
                    RceApi.friendRequests()
                        .firstOrNull { it.uid == targetId && it.isPending }
                        ?.requestId
                        ?.takeIf { it.isNotBlank() }
                }
                if (requestId == null) {
                    load()
                    return@launch
                }
                withContext(Dispatchers.IO) { RceApi.acceptFriend(requestId) }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@UserProfileActivity, TAG, e)
                toast(e.userMessage(this@UserProfileActivity, R.string.im_friend_accept_failed))
            }
        }
    }

    /** 删除好友是破坏性操作（对方好友列表也会少一个人）→ 必须二次确认 */
    private fun confirmDeleteFriend(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friend_delete)
            .setMessage(getString(R.string.friend_delete_message, name))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ -> deleteFriend() }
            .show()
    }

    private fun deleteFriend() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RceApi.deleteFriend(targetId) }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@UserProfileActivity, TAG, e)
                toast(e.userMessage(this@UserProfileActivity, R.string.friend_delete_failed))
            }
        }
    }

    /**
     * 「不看 TA 的动态」/「已屏蔽 · 点此恢复」。
     *
     * 自己的主页不显示。屏蔽后不回退/不跳走 —— 用户可能只是想确认一下这个人的资料，
     * 把人赶走反而莫名其妙；只把按钮文案换掉，回工作圈时列表会按新名单重排。
     */
    private fun bindBlockButton(staff: StaffBrief?) {
        val btn = binding.btnBlockUser
        if (isSelf) {
            btn.isVisible = false
            return
        }
        btn.isVisible = true
        val blocked = BlockedUserStore.isBlocked(targetId)
        btn.setText(
            if (blocked) R.string.moments_unblock_user else R.string.moments_block_user
        )
        btn.setOnClickListener { confirmToggleBlock(staff, blocked) }
    }

    private fun confirmToggleBlock(staff: StaffBrief?, blocked: Boolean) {
        val name = staff?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.moments_anonymous)
        if (blocked) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.moments_unblock_dialog_title)
                .setMessage(getString(R.string.moments_unblock_dialog_message, name))
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.moments_unblock_action) { _, _ ->
                    BlockedUserStore.unblock(targetId)
                    bindBlockButton(staff)
                    Toast.makeText(
                        this,
                        getString(R.string.moments_unblock_done, name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.moments_block_dialog_title)
            .setMessage(getString(R.string.moments_block_dialog_message, name))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.moments_block_action) { _, _ ->
                val result = BlockedUserStore.block(
                    BlockedUserStore.BlockedUser(targetId, name, staff?.bestPortrait)
                )
                val message = when (result) {
                    BlockedUserStore.AddResult.OK ->
                        getString(R.string.moments_block_done, name)
                    BlockedUserStore.AddResult.FULL ->
                        getString(R.string.moments_block_full)
                    BlockedUserStore.AddResult.SELF ->
                        getString(R.string.moments_block_self)
                    BlockedUserStore.AddResult.ALREADY ->
                        getString(R.string.moments_block_already)
                }
                if (result == BlockedUserStore.AddResult.OK) bindBlockButton(staff)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .show()
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
            show = staffNoVisible && !staffNo.isNullOrBlank(),
            copyable = true
        )
        // 邮箱（被打码时与官方一致：不可点、不可复制）
        setRow(
            binding.rowEmail,
            R.string.user_profile_label_email,
            value = if (mailMasked) MASK else email,
            show = mailMasked || !email.isNullOrBlank(),
            enabled = !mailMasked,
            copyable = true
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
        enabled: Boolean = true,
        copyable: Boolean = false
    ) {
        row.root.isVisible = show
        if (!show) return
        row.tvRowLabel.setText(labelRes)
        row.tvRowValue.text = value.orEmpty()
        row.tvRowValue.isEnabled = enabled

        // 长按整行复制值（学号/邮箱）：只对真值挂监听 —— 打码的 `********` 没什么可复制的，
        // 且官方对打码字段的口径就是「不可点、不可复制」（见 setRow 调用处的 enabled）。
        // 监听挂整行而不是 TextView：右对齐的值只占半行，用户会长按文字那侧
        val realValue = if (enabled) value?.takeIf { it.isNotBlank() } else null
        row.root.setOnLongClickListener(
            if (copyable && realValue != null) {
                View.OnLongClickListener {
                    getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(getString(labelRes), realValue))
                    toast(R.string.user_profile_copied)
                    true
                }
            } else {
                null
            }
        )
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

    /**
     * 退出登录二次确认。
     *
     * 从设置目录页搬过来时整套照搬：退出不可逆（要重输学号密码），先弹 M3 确认框；
     * 取消（含返回键、点外部）只关框，停在当前页。
     */
    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.action_logout) { _, _ -> performLogout() }
            .show()
    }

    private fun performLogout() {
        Logout.clear(this)
        // CLEAR_TASK：这一页是从设置页跳进来的，只 finish 会留下一层已经没有会话的空壳返回栈
        // （返回键能退回去，点哪都提示重新登录）
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun showError(message: String) {
        binding.errorBox.isVisible = true
        binding.tvError.text = message
        binding.scroll.isVisible = false
        binding.progress.hide()
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
