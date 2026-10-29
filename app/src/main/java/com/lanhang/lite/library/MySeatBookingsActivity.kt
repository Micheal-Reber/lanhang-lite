package com.lanhang.lite.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivitySeatBookingsBinding
import com.lanhang.lite.databinding.ItemSeatBookingBinding
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.session.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的座位预约。
 *
 * 按钮不是写死的，由订单状态机驱动（见调研报告 §9）：
 * - 0 待签到 → 签到（只在服务端允许的窗口内出现）/ 取消
 * - 1 使用中 → 暂离 / 签退
 * - 2 暂离中 → 续座 / 签退
 * - 3 及以上终态 → 无操作
 *
 * 签到、暂离、签退、取消都会影响真实座位与违约记录，一律二次确认；
 * 每次操作成功后重新拉列表，用服务端状态为准，不在本地猜。
 */
class MySeatBookingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivitySeatBookingsBinding
    private val adapter = BookingAdapter(
        onAction = { booking, action -> onAction(booking, action) },
        onSignCode = { SeatSignCodeActivity.start(this) },
    )

    private var loading = false
    private var pendingOps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeatBookingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val baseBottom = binding.recycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0,
            )
            binding.recycler.setPadding(
                binding.recycler.paddingLeft,
                binding.recycler.paddingTop,
                binding.recycler.paddingRight,
                baseBottom + bars.bottom,
            )
            insets
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener { load(refresh = true) }

        beginLoading()
        load(refresh = false)
    }

    /** 首次加载由 onCreate 发起；之后每次回到前台都重新拉，签到器签完回来不该还显示「待签到」 */
    private var firstResume = true

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            load(refresh = true)
        }
    }

    private fun load(@Suppress("UNUSED_PARAMETER") refresh: Boolean) {
        if (loading) return
        loading = true
        beginLoading()
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    LibrarySeatApi.ensureLogin(Session.deviceId(AppContext.instance))
                    LibrarySeatApi.myBookings()
                }
                adapter.submit(list)
                binding.tvEmpty.isVisible = list.isEmpty()
                binding.tvEmpty.text = getString(R.string.library_seat_bookings_empty)
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                binding.tvEmpty.text =
                    e.userMessage(this@MySeatBookingsActivity, R.string.library_seat_load_failed)
                binding.tvEmpty.isVisible = adapter.itemCount == 0
                if (adapter.itemCount > 0) {
                    toast(e.userMessage(this@MySeatBookingsActivity, R.string.library_seat_load_failed))
                }
            } finally {
                loading = false
                endLoading()
                binding.swipe.isRefreshing = false
            }
        }
    }

    // ==================== 操作 ====================

    private fun onAction(booking: SeatBooking, action: SeatAction) {
        when (action) {
            // 签到是正向操作且有时限，不拦一道确认
            SeatAction.CHECK_IN -> perform(booking, action)
            SeatAction.COME_BACK -> perform(booking, action)
            SeatAction.CANCEL -> confirm(
                R.string.seat_cancel_confirm_title,
                getString(R.string.seat_cancel_confirm),
                booking,
                action,
            )

            SeatAction.STEP_OUT -> confirm(
                R.string.seat_step_out_confirm_title,
                getString(R.string.seat_step_out_confirm, booking.limitLeftBack / 60),
                booking,
                action,
            )

            SeatAction.CHECK_OUT -> confirm(
                R.string.seat_check_out_confirm_title,
                getString(R.string.seat_check_out_confirm),
                booking,
                action,
            )
        }
    }

    private fun confirm(titleRes: Int, message: String, booking: SeatBooking, action: SeatAction) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_confirm) { _, _ -> perform(booking, action) }
            .show()
    }

    private fun perform(booking: SeatBooking, action: SeatAction) {
        beginLoading()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LibrarySeatApi.perform(action, booking)
                }
                toast(getString(successTextOf(action)))
                load(refresh = true)
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                toast(e.userMessage(this@MySeatBookingsActivity, R.string.seat_action_failed))
            } finally {
                endLoading()
            }
        }
    }

    // ==================== 小工具 ====================

    private fun beginLoading() {
        pendingOps++
        if (pendingOps == 1) binding.progress.isVisible = true
    }

    private fun endLoading() {
        pendingOps--
        if (pendingOps <= 0) {
            pendingOps = 0
            binding.progress.isVisible = false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun successTextOf(action: SeatAction): Int = when (action) {
        SeatAction.CHECK_IN -> R.string.seat_action_check_in_ok
        SeatAction.CHECK_OUT -> R.string.seat_action_check_out_ok
        SeatAction.STEP_OUT -> R.string.seat_action_step_out_ok
        SeatAction.COME_BACK -> R.string.seat_action_come_back_ok
        SeatAction.CANCEL -> R.string.seat_action_cancel_ok
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, MySeatBookingsActivity::class.java))
        }
    }

    // ==================== 列表 ====================

    private class BookingAdapter(
        private val onAction: (SeatBooking, SeatAction) -> Unit,
        private val onSignCode: () -> Unit,
    ) : RecyclerView.Adapter<BookingAdapter.VH>() {

        private val items = mutableListOf<SeatBooking>()

        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        private val clockFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

        fun submit(list: List<SeatBooking>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemSeatBookingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(items[position], timeFormat, clockFormat, onAction, onSignCode)

        class VH(private val binding: ItemSeatBookingBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(
                booking: SeatBooking,
                timeFormat: SimpleDateFormat,
                clockFormat: SimpleDateFormat,
                onAction: (SeatBooking, SeatAction) -> Unit,
                onSignCode: () -> Unit,
            ) {
                val context = binding.root.context
                binding.tvRoom.text = booking.roomName
                binding.tvStatus.text = context.getString(BookingAdapter.statusTextOf(booking.status))
                binding.tvSeat.text = context.getString(R.string.library_seat_seat_row, booking.seatNum)
                binding.tvTime.text = context.getString(
                    R.string.library_seat_time_row,
                    timeFormat.format(Date(booking.beginAt * 1000)),
                    clockFormat.format(Date(booking.endAt * 1000)),
                )

                binding.llActions.removeAllViews()
                val actions = BookingAdapter.actionsFor(booking)
                for (action in actions) {
                    binding.llActions.addView(
                        buildButton(
                            context,
                            context.getString(BookingAdapter.labelOf(action)),
                            destructive = action == SeatAction.CANCEL,
                        ) {
                            onAction(booking, action)
                        },
                    )
                }
                // 馆内签到器扫的是「签到码」，和这里的「签到」按钮是两条入口，能签到时两个都给
                if (SeatAction.CHECK_IN in actions) {
                    binding.llActions.addView(
                        buildButton(context, context.getString(R.string.seat_sign_code_action)) {
                            onSignCode()
                        },
                    )
                }
                binding.llActions.isVisible = binding.llActions.childCount > 0
            }

            private fun buildButton(
                context: Context,
                text: String,
                destructive: Boolean = false,
                onClick: () -> Unit,
            ): MaterialButton {
                val button = MaterialButton(
                    context,
                    null,
                    com.google.android.material.R.attr.borderlessButtonStyle,
                )
                button.text = text
                button.minWidth = 0
                button.insetTop = 0
                button.insetBottom = 0
                button.setPaddingRelative(0, 0, 0, 0)
                button.setTextColor(
                    MaterialColors.getColor(
                        button,
                        if (destructive) {
                            com.google.android.material.R.attr.colorError
                        } else {
                            com.google.android.material.R.attr.colorPrimary
                        },
                    )
                )
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                params.marginStart = context.resources.getDimensionPixelSize(R.dimen.space_md)
                button.layoutParams = params
                button.setOnClickListener { onClick() }
                return button
            }
        }

        companion object {
            /** 状态机驱动按钮；窗口判定用服务端下发的 `nowTime` + limitSignAgo/limitSignBack */
            fun actionsFor(booking: SeatBooking): List<SeatAction> = buildList {
                when {
                    booking.isPendingSign -> {
                        if (booking.canCheckIn()) add(SeatAction.CHECK_IN)
                        add(SeatAction.CANCEL)
                    }

                    booking.isInUse -> {
                        add(SeatAction.STEP_OUT)
                        add(SeatAction.CHECK_OUT)
                    }

                    booking.isAway -> {
                        add(SeatAction.COME_BACK)
                        add(SeatAction.CHECK_OUT)
                    }
                }
            }

            fun labelOf(action: SeatAction): Int = when (action) {
                SeatAction.CHECK_IN -> R.string.seat_action_check_in
                SeatAction.CHECK_OUT -> R.string.seat_action_check_out
                SeatAction.STEP_OUT -> R.string.seat_action_step_out
                SeatAction.COME_BACK -> R.string.seat_action_come_back
                SeatAction.CANCEL -> R.string.seat_action_cancel
            }

            /** 状态机文案：8 待确认 / 0 待签到 / 1 使用中 / 2 暂离中 / 3-7 已结束 / 9 被拒 */
            fun statusTextOf(status: Int): Int = when (status) {
                8 -> R.string.seat_status_waiting
                0 -> R.string.seat_status_pending
                1 -> R.string.seat_status_in_use
                2 -> R.string.seat_status_away
                3 -> R.string.seat_status_checked_out
                4 -> R.string.seat_status_canceled
                5 -> R.string.seat_status_violated
                6 -> R.string.seat_status_no_return
                7 -> R.string.seat_status_system
                9 -> R.string.seat_status_refused
                else -> R.string.seat_status_unknown
            }
        }
    }
}
