package com.lanhang.lite.campusnet

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.TextRef
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.revealInScroll
import com.lanhang.lite.core.userDetail
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityCampusNetBinding
import com.lanhang.lite.settings.Diagnostics
import com.lanhang.lite.ui.OaWebViewActivity
import com.lanhang.lite.widget.CampusNetWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 校园网：账号设置 + 一键认证（深澜 Srun）。
 *
 * 交互原则（用户拍板）：
 * - **只做前台手动认证**，打开 App 不自动尝试 —— 自动认证在 Doze / 厂商省电下不可靠，
 *   失败还会莫名其妙弹一次错误；
 * - **密码可以存在本机**（[CampusNetStore] 用 Keystore AES-GCM 加密），
 *   但默认不勾「记住密码」，用户自己不选就不落盘；
 * - 下线也要二次确认：下线会踢掉当前设备，误触等于自己断网。
 *
 * 网络层见 [SrunApi]，全是阻塞调用，这里一律 `Dispatchers.IO`。
 */
class CampusNetActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：每个 Activity 自己包，漏了这页会一直跟系统语言
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityCampusNetBinding

    /** 当前是否已认证：决定主按钮是「立即认证」还是「下线」 */
    private var online = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCampusNetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // targetSdk 35 在 Android 15+ 强制 edge-to-edge，不补 inset 的话标题会和状态栏叠在一起
        // （和 UserProfileActivity / LibrarySeatActivity 保持同一写法；insets 会多次回调，基准值先取）
        val baseBottom = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 键盘也算「底部遮挡」：edge-to-edge 下 adjustResize 不改窗口高度，
            // 不补 ime 高度的话账号/密码框和「登录」按钮会整块躲在键盘后面
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
                baseBottom + maxOf(bars.bottom, ime.bottom)
            )
            // 只在「键盘刚弹出」这一下滚一次，收起键盘不往回滚，避免页面来回跳
            // 键盘每长高一帧都滚一次（只往下、滚够为止）：只在「刚弹出」那一刻滚不管用，
            // 那一帧 ime 高度还是 0，会被判定成「已经可见」
            if (ime.bottom > 0) revealFocusedInput()
            insets
        }

        // 账号默认 = 统一身份认证学号；有已加密保存的密码就回填
        binding.etUsername.setText(CampusNetStore.username)
        CampusNetStore.password()?.let { binding.etPassword.setText(it) }
        // 勾选状态**独立持久化**，不能拿「有没有密码」反推：
        // 认证失败时密码不落盘，反推的话勾选就跟着消失，看起来像「记住密码没生效」。
        // 先设值再挂监听，否则初始化那一下会把已存的密码当「取消勾选」清掉。
        binding.swRemember.isChecked = CampusNetStore.remember
        binding.swRemember.setOnCheckedChangeListener { _, checked ->
            CampusNetStore.remember = checked
            if (!checked) CampusNetStore.clearPassword()
        }

        binding.btnPrimary.setOnClickListener {
            if (online) confirmLogout() else authenticate()
        }
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        binding.rowSelfService.setOnClickListener {
            OaWebViewActivity.open(
                this,
                SrunApi.SELF_SERVICE_URL,
                null,
                getString(R.string.campus_net_self_service)
            )
        }

        refreshStatus()
    }

    /**
     * 把当前获得焦点的输入框滚进可见区（键盘弹出时调用）。
     *
     * 登录表单在页面中下部，键盘一弹出来，「登录」按钮就正好被盖住；这里只滚「刚刚够」露出
     * 当前输入框的距离，用户继续往下滚就能看到按钮（逐帧调用，见 [revealInScroll]）。
     */
    private fun revealFocusedInput() {
        val target = currentFocus ?: return
        val margin = resources.getDimensionPixelSize(R.dimen.space_xl)
        binding.scroll.post { revealInScroll(binding.scroll, target, margin) }
    }

    // ==================== 状态 ====================

    /**
     * 刷新在线状态。
     *
     * 「连不上认证服务器」是**正常情况**（不在校园网），所以这里不写诊断日志、不弹 toast，
     * 只把状态卡改成「无法连接」并给出操作建议。
     */
    private fun refreshStatus() {
        if (busy) return
        startBusy()
        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { SrunApi.status(this@CampusNetActivity) }
                render(status, null)
                // 桌面小组件跟着同步：这里已经拿到了权威的在线状态，顺手更新一次，
                // 免得用户在这边看到「未认证」而小组件还停在「已认证」。
                CampusNetWidgetProvider.syncStatus(this@CampusNetActivity, status)
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                render(null, e)
            } finally {
                endBusy()
            }
        }
    }

    private fun render(status: SrunApi.NetStatus?, error: Throwable?) {
        if (status == null) {
            binding.tvStatus.setText(R.string.campus_net_status_unknown)
            binding.tvStatusDetail.text = error?.userDetail(this)
                ?: getString(R.string.campus_net_unreachable)
            setOnline(false)
            return
        }
        if (status.online) {
            binding.tvStatus.setText(R.string.campus_net_status_online)
            binding.tvStatusDetail.text = getString(
                R.string.campus_net_online_detail,
                status.ip ?: "--",
                formatDuration(status.onlineSeconds),
                formatTraffic(status.bytes)
            )
            setOnline(true)
        } else {
            binding.tvStatus.setText(R.string.campus_net_status_offline)
            binding.tvStatusDetail.setText(R.string.campus_net_offline_detail)
            setOnline(false)
        }
    }

    private fun setOnline(value: Boolean) {
        online = value
        binding.btnPrimary.setText(
            if (value) R.string.campus_net_logout else R.string.campus_net_login
        )
    }

    // ==================== 认证 / 下线 ====================

    private fun authenticate() {
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        if (username.isEmpty()) {
            toast(getString(R.string.campus_net_empty_account))
            binding.etUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            toast(getString(R.string.campus_net_empty_password))
            binding.etPassword.requestFocus()
            return
        }
        if (busy) return
        startBusy()
        lifecycleScope.launch {
            var ok = false
            val remember = binding.swRemember.isChecked
            try {
                // 先把密码存下来，再做认证。
                // 旧实现是「认证成功才保存」——认证一失败（不在校园网、门户拒绝）就等于勾了没勾，
                // 用户退出重进看到勾选被关掉、密码也空了（2026-10-26 反馈）。用户点认证时填的密码
                // 就是他想被记住的那一个，与这次能不能登录上去无关。
                // 保存失败（Keystore 异常）不阻断认证本身。
                val saved = withContext(Dispatchers.IO) {
                    if (remember) {
                        CampusNetStore.savePassword(password)
                    } else {
                        CampusNetStore.clearPassword()
                        true
                    }
                }
                if (!saved) toast(getString(R.string.campus_net_save_failed))
                val result = withContext(Dispatchers.IO) {
                    // 认证流程与桌面小组件共用（见 CampusNetAuth），两处行为必须一致
                    CampusNetAuth.authenticate(this@CampusNetActivity, username, password)
                }
                // IP 已在线上时不能报「认证成功」——用户会以为刚建立的会话
                toast(
                    getString(
                        if (result.alreadyOnline) {
                            R.string.campus_net_already_online
                        } else {
                            R.string.campus_net_login_ok
                        }
                    )
                )
                ok = true
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                // 门户明确回「密码错误」时，把刚才存的那份撤掉：留着只会下次自动填入同一个错密码，
                // 用户还以为是「记住密码记住错了」。勾选状态保留，用户改完密码再点一次即可。
                if (remember && (e as? ApiException)?.text == TextRef.Res(R.string.campus_net_err_password)) {
                    withContext(Dispatchers.IO) { CampusNetStore.clearPassword() }
                }
                Diagnostics.record(this@CampusNetActivity, getString(R.string.campus_net_tag), e)
                toast(e.userMessage(this@CampusNetActivity, R.string.campus_net_failed))
            } finally {
                endBusy()
            }
            if (ok) {
                refreshStatus()
                // 桌面小组件要跟着变：认证后它显示的还是上一次的状态
                // （2026-10-26 用户反馈「App 里下线了，小组件还显示在线的页面」）
                CampusNetWidgetProvider.refreshAll(this@CampusNetActivity)
            }
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.campus_net_logout_confirm_title)
            .setMessage(R.string.campus_net_logout_confirm_message)
            .setPositiveButton(R.string.campus_net_logout) { _, _ -> performLogout() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun performLogout() {
        if (busy) return
        startBusy()
        lifecycleScope.launch {
            var ok = false
            try {
                withContext(Dispatchers.IO) {
                    // 下线走共享流程（App 内页面与桌面小组件同一条路径）
                    CampusNetAuth.logout(this@CampusNetActivity, currentUsername())
                }
                toast(getString(R.string.campus_net_logout_ok))
                ok = true
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                Diagnostics.record(this@CampusNetActivity, getString(R.string.campus_net_tag), e)
                toast(e.userMessage(this@CampusNetActivity, R.string.campus_net_failed))
            } finally {
                endBusy()
            }
            if (ok) {
                refreshStatus()
                // 桌面小组件要跟着变：认证/下线后它显示的还是上一次的状态
                // （2026-10-26 用户反馈「App 里下线了，小组件还显示在线的页面」）
                // 下线是确定的结果，直接写「未认证」——不用等小组件再去查一次网络。
                CampusNetWidgetProvider.markOffline(this@CampusNetActivity)
            }
        }
    }

    // ==================== 小工具 ====================

    private fun currentUsername(): String =
        binding.etUsername.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: CampusNetStore.username

    private fun startBusy() {
        busy = true
        binding.progress.show()
        binding.btnPrimary.isEnabled = false
        binding.btnRefresh.isEnabled = false
    }

    private fun endBusy() {
        busy = false
        binding.progress.hide()
        binding.btnPrimary.isEnabled = true
        binding.btnRefresh.isEnabled = true
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return getString(R.string.campus_net_duration, hours, minutes)
    }

    private fun formatTraffic(bytes: Long): String {
        val mb = bytes / (1024L * 1024L)
        return if (mb >= 1024) {
            getString(R.string.campus_net_traffic_gb, mb / 1024)
        } else {
            getString(R.string.campus_net_traffic_mb, mb)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
