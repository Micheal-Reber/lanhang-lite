package com.lanhang.lite.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.radiobutton.MaterialRadioButton
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.toast
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityPermissionControlBinding
import com.lanhang.lite.net.RceApi
import com.lanhang.lite.net.UserStatus
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.showFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 权限控制页（2026-11-13，对齐原版 `PermissionControlActivity`）。
 *
 * 决定别人在你的个人主页能看到什么、能怎么联系你。读 `GET /user/getUserStatus`
 * （按登录会话取本人），写 `POST /user/setUserStatus`。
 *
 * 与原版的两点差异：
 * 1. 原版用 CheckBox 手工做互斥（点一个要把另外两个 setChecked(false)），
 *    这里用 `RadioGroup + MaterialRadioButton` —— 单选本来就该交给控件自己保证；
 * 2. 原版拿不到数据时仍可点保存，会把 7 个可编辑字段按界面默认值写回去。
 *    这里**加载失败就不给保存**：没读到原始值就保存，等于拿默认值覆盖用户已有的隐私位。
 *
 * `hideTelFlag` / `hideOfficeFlag` 原版页面上也不开放修改，只是读出来原样回传，
 * 否则服务端可能把它们当成 0 覆盖掉。
 */
class PermissionControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionControlBinding

    /** 加载到的原始值；保存时用它补齐不开放修改的两个 hide 位 */
    private var loaded: UserStatus? = null

    /** 保存期间置灰，防连点（每点一次就是一个写请求） */
    private var saving = false

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：切语言时由设置页显式 recreate()，不走系统的 per-app locale
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PermissionControlActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionControlBinding.inflate(layoutInflater)
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

        binding.btnRetry.setOnClickListener { load() }
        binding.btnSave.setOnClickListener { save() }
        load()
    }

    private fun load() {
        showContent(false)
        binding.stateCenter.isVisible = false
        binding.progress.isVisible = true
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            val status = try {
                withContext(Dispatchers.IO) {
                    RceApi.myUserStatus() ?: RceApi.userStatus(Session.userId)
                }
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                binding.progress.isVisible = false
                // 拿不到原始值时保存会把隐私位覆盖成默认值，所以失败态不给保存按钮
                binding.stateCenter.isVisible = true
                binding.tvError.showFailure(e, R.string.permission_load_failed)
                return@launch
            }
            binding.progress.isVisible = false
            if (status == null) {
                binding.stateCenter.isVisible = true
                binding.tvError.text = getString(R.string.error_request_failed)
                return@launch
            }
            loaded = status
            bind(status)
            showContent(true)
            // 数据没变化时保存没有意义；改了任意一档才亮
            binding.btnSave.isEnabled = true
        }
    }

    private fun bind(status: UserStatus) {
        pick(binding.rgPhone, status.telFlag ?: 0)
        pick(binding.rgEmail, status.mailFlag ?: 0)
        pick(binding.rgAvatar, status.headFlag ?: 0)
        pick(binding.rgMessage, status.messageFlag ?: 0)
        pick(binding.rgGroup, status.groupFlag ?: 0)
        pick(binding.rgPin, status.pinFlag ?: 0)
        pick(binding.rgFriend, status.friendFlag ?: 0)
    }

    /** 把 [value] 对应的那颗单按钮勾上；越界（两档字段收到 2）时退回第一档 */
    private fun pick(group: android.widget.RadioGroup, value: Int) {
        val button = group.getChildAt(value) as? MaterialRadioButton
            ?: group.getChildAt(0) as? MaterialRadioButton
            ?: return
        group.check(button.id)
    }

    private fun save() {
        if (saving) return
        val status = loaded ?: return
        saving = true
        binding.btnSave.isEnabled = false

        // 9 个值必须是 JSON 字符串（RceApi.setUserStatus 里转），这里只给数值
        val flags = linkedMapOf(
            "telFlag" to current(binding.rgPhone),
            "mailFlag" to current(binding.rgEmail),
            "headFlag" to current(binding.rgAvatar),
            "messageFlag" to current(binding.rgMessage),
            "groupFlag" to current(binding.rgGroup),
            "pinFlag" to current(binding.rgPin),
            "friendFlag" to current(binding.rgFriend),
            // 不开放修改的两位原样回传
            "hideTelFlag" to (status.hideTelFlag ?: 0),
            "hideOfficeFlag" to (status.hideOfficeFlag ?: 0),
        )

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { RceApi.setUserStatus(flags) }
                toast(getString(R.string.permission_saved))
                finish()
            } catch (e: Exception) {
                if (e.isCancellation) return@launch
                saving = false
                binding.btnSave.isEnabled = true
                toast(e.userMessage(this@PermissionControlActivity, R.string.permission_save_failed))
            }
        }
    }

    /** 当前勾选的档位；一组里必须有一颗被勾（加载成功时已保证），兜底给第一档 */
    private fun current(group: android.widget.RadioGroup): Int {
        val id = group.checkedRadioButtonId
        for (i in 0 until group.childCount) {
            if (group.getChildAt(i).id == id) return i
        }
        return 0
    }

    private fun showContent(show: Boolean) {
        binding.scroll.isVisible = show
    }
}
