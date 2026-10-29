package com.lanhang.lite.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage
import com.lanhang.lite.databinding.ActivityLibrarySeatBinding
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.session.Session
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图书馆座位预约（选座页）。
 *
 * 交互按厂商 H5 的规则收敛：
 * 1. 先选空间（`/Space/Category/list`）；切空间会重新拉一次选座表单拿规则；
 * 2. 只能约**当天**；开始时间取 `max(当前小时, default.beginTime) .. 闭馆-最短时长`
 *    —— **包含当前小时**（09:58 可约 09:00–10:00），结束时间不晚于闭馆、时长有上下限；
 * 3. 时段确定后查座（`searchSeats` 会返回整张座位图），点空座位选中；
 * 4. 下单前必须二次确认——预约会占用真实座位，未签到会记违约，不能一键误触。
 *
 * 时段边界的推导见 [SeatRules] 的注释（官方 H5 `_setData` + 服务端实测）。
 *
 * 网络层见 [LibrarySeatApi]，全是阻塞调用，这里一律 `Dispatchers.IO`。
 */
class LibrarySeatActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper；新增页面必须自己包，否则本页会跟着系统语言走
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityLibrarySeatBinding

    private var rooms: List<SeatRoom> = emptyList()
    private var currentRoom: SeatRoom? = null
    private var form: SeatForm? = null

    /** 开始时间（整点），[NO_HOUR] = 尚未确定；0 点是合法小时，不能用 0 当哨兵 */
    private var startHour = NO_HOUR
    private var durationHours = 1

    private var busy = false

    /**
     * 进度圈用计数而不是布尔：加载空间 → 拉表单 → 查座位是嵌套的，
     * 各自 show/hide 会互相提前关掉（表单 finally 先跑就把查座的圈关了）。
     */
    private var pendingLoads = 0

    /** 下单成功后跳去「我的预约」，回来时要重新查一次座位（刚约的座位已变占用） */
    private var refreshOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibrarySeatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_my_bookings) {
                MySeatBookingsActivity.start(this)
                true
            } else {
                false
            }
        }

        val bottomBasePadding = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                bars.top,
                binding.toolbar.paddingRight,
                0,
            )
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight,
                bottomBasePadding + bars.bottom,
            )
            insets
        }

        binding.seatMap.onSeatTap = { seat -> onSeatTapped(seat) }
        binding.rowStart.setOnClickListener { pickStartHour() }
        binding.rowDuration.setOnClickListener { pickDuration() }
        binding.btnBook.setOnClickListener { confirmBooking() }

        beginLoading()
        loadRooms()
    }

    override fun onResume() {
        super.onResume()
        if (form == null || startHour == NO_HOUR) return
        // 页面挂久了会跨过整点甚至跨过整个时段，回来先确认选中的时段还能约
        if (!endsInFuture(startHour, durationHours)) {
            refreshTimeSelection()
            querySeats()
            return
        }
        if (refreshOnResume) {
            refreshOnResume = false
            querySeats()
        }
    }

    // ==================== 加载流程 ====================

    private fun loadRooms() {
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    LibrarySeatApi.ensureLogin(Session.deviceId(AppContext.instance))
                    LibrarySeatApi.rooms()
                }
                if (loaded.isEmpty()) {
                    showFatal(getString(R.string.library_seat_no_room))
                    return@launch
                }
                rooms = loaded
                currentRoom = rooms.first()
                renderRooms()
                bindRoom(rooms.first())
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                showFatal(e.userMessage(this@LibrarySeatActivity, R.string.library_seat_load_failed))
            } finally {
                endLoading()
            }
        }
    }

    private fun renderRooms() {
        val group = binding.roomChips
        group.removeAllViews()
        for (room in rooms) {
            val chip = layoutInflater.inflate(R.layout.item_term_chip, group, false) as Chip
            chip.text = room.name
            chip.isChecked = room == currentRoom
            chip.setOnClickListener { selectRoom(room) }
            group.addView(chip)
        }
    }

    private fun selectRoom(room: SeatRoom) {
        if (room == currentRoom && form != null) return
        currentRoom = room
        bindRoom(room)
    }

    /** 切空间：重拉表单（规则可能不同）→ 重算时段 → 查座 */
    private fun bindRoom(room: SeatRoom) {
        form = null
        startHour = NO_HOUR
        binding.seatMap.setMap(null)
        binding.seatMap.setPlanBitmap(null)
        renderSelection(null)
        binding.tvMapTitle.text = room.name
        binding.tvFreeCount.text = ""
        beginLoading()
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { LibrarySeatApi.form(room) }
                if (currentRoom != room) return@launch
                form = loaded
                val options = startOptions()
                if (options.isEmpty()) {
                    showMapHint(getString(R.string.library_seat_no_slot))
                } else {
                    // 官方默认值也是「最早可约的那个整点」，即当前小时（或服务端 default 更晚时取 default）
                    startHour = options.first()
                    durationHours = clampDuration(loaded.defaultDurationHours)
                }
                renderTime()
                Log.d(
                    TAG,
                    "room=${room.name} ${loaded.rules} default=${loaded.defaultBeginHour}h " +
                        "options=${options.map { hourLabel(it) }} -> " +
                        "${if (startHour == NO_HOUR) "无可用时段" else hourLabel(startHour)}" +
                        "+${durationHours}h",
                )
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                showFatal(e.userMessage(this@LibrarySeatActivity, R.string.library_seat_load_failed))
            } finally {
                endLoading()
                // 时段确定后才查座；form 为空时 querySeats 自己会早退
                querySeats()
            }
        }
    }

    private fun querySeats() {
        val room = currentRoom ?: return
        if (form == null || startHour == NO_HOUR || busy) return
        busy = true
        beginLoading()
        binding.tvMapHint.isVisible = false
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    LibrarySeatApi.search(room, beginEpoch(startHour), durationHours * 3600)
                }
                Log.d(
                    TAG,
                    "seats ${room.name} ${hourLabel(startHour)}+${durationHours}h -> " +
                        if (data.slotEmpty) "空时段" else "${data.seats.size} 座 / ${data.freeCount} 空闲",
                )
                binding.tvMapTitle.text = if (data.roomName == room.name) {
                    room.name
                } else {
                    getString(R.string.library_seat_map_title, room.name, data.roomName)
                }
                if (data.slotEmpty || data.seats.isEmpty()) {
                    // 该时段整层没有可约座位（闭馆 / 已约满 / 时段已过）：
                    // 不给空图（0×0 画不出东西），也不报错，直接告诉用户换时间。
                    binding.seatMap.setMap(null)
                    binding.seatMap.setPlanBitmap(null)
                    binding.tvFreeCount.text = ""
                    renderSelection(null)
                    // 只剩这一个时段时「换个时间试试」是废话（闭馆楼层 22:00 之后就是这种），给中性的说法
                    val canSwitch = startOptions().size > 1
                    showMapHint(
                        getString(
                            if (data.slotEmpty && canSwitch) R.string.library_seat_empty_slot
                            else R.string.library_seat_no_seat
                        )
                    )
                    return@launch
                }
                binding.seatMap.setMap(data)
                binding.tvFreeCount.text = getString(R.string.library_seat_free_count, data.freeCount)
                if (data.seats.none { it.isFree }) {
                    showMapHint(getString(R.string.library_seat_no_seat))
                }
                renderSelection(null)
                loadPlan(data.planUrl)
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                binding.seatMap.setMap(null)
                showMapHint(e.userMessage(this@LibrarySeatActivity, R.string.library_seat_load_failed))
            } finally {
                busy = false
                endLoading()
            }
        }
    }

    /** 平面图只是背景，失败就退化成纯色底，不影响选座 */
    private fun loadPlan(url: String?) {
        if (url.isNullOrBlank()) {
            binding.seatMap.setPlanBitmap(null)
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(this@LibrarySeatActivity)
                        .data(url)
                        .allowHardware(false)
                        .build()
                    val result = imageLoader.execute(request)
                    (result as? SuccessResult)?.drawable?.toBitmap()
                }.getOrNull()
            }
            if (!isFinishing && !isDestroyed) binding.seatMap.setPlanBitmap(bitmap)
        }
    }

    // ==================== 时段 ====================

    /**
     * 可选开始整点。**包含当前小时**（官方 H5 的 `max(当前小时, default.beginTime)`）：
     * 09:58 依然能约 09:00–10:00，因为服务端只拒绝「结束时间已过」的时段。
     * 早期版本写成 `nowHour + 1`，把当前小时整段砍掉，导致比官方少一整格。
     */
    private fun startOptions(): List<Int> {
        val rules = form?.rules ?: return emptyList()
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val range = rules.startHours(nowHour, form?.defaultBeginHour ?: 0) ?: return emptyList()
        return range.filter { endsInFuture(it, rules.minDurationHours) }
    }

    private fun durationOptions(): List<Int> {
        val rules = form?.rules ?: return emptyList()
        if (startHour == NO_HOUR) return emptyList()
        val range = rules.durationHours(startHour) ?: return emptyList()
        return range.filter { endsInFuture(startHour, it) }
    }

    private fun clampDuration(value: Int): Int {
        val options = durationOptions()
        if (options.isEmpty()) return value
        return value.coerceIn(options.first(), options.last())
    }

    /** 服务端唯一的时段校验就是「开始 + 时长 > 现在」；前置算一遍，别把服务端报错弹给用户 */
    private fun endsInFuture(hour: Int, duration: Int): Boolean =
        beginEpoch(hour) + duration * 3600L > System.currentTimeMillis() / 1000

    /**
     * 重算时段并修正选中值（跨整点后当前小时变了、或原时段已过期时调用）。
     * 原选择还有效就留着，失效则回落到最早可约的整点，和官方「重进页面」的表现一致。
     */
    private fun refreshTimeSelection() {
        val options = startOptions()
        if (options.isEmpty()) {
            startHour = NO_HOUR
            binding.seatMap.setMap(null)
            binding.seatMap.setPlanBitmap(null)
            binding.tvFreeCount.text = ""
            renderTime()
            binding.seatMap.clearSelection()
            renderSelection(null)
            showMapHint(getString(R.string.library_seat_no_slot))
            return
        }
        if (startHour !in options) {
            startHour = options.first()
            toast(getString(R.string.library_seat_slot_expired))
        }
        durationHours = clampDuration(durationHours)
        renderTime()
    }

    private fun pickStartHour() {
        val options = startOptions()
        if (options.isEmpty()) {
            toast(getString(R.string.library_seat_no_slot))
            return
        }
        val labels = options.map { hourLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_seat_start)
            .setItems(labels) { _, which ->
                val hour = options[which]
                if (hour == startHour) return@setItems
                startHour = hour
                durationHours = clampDuration(durationHours)
                renderTime()
                querySeats()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun pickDuration() {
        val options = durationOptions()
        if (options.isEmpty()) {
            toast(getString(R.string.library_seat_no_slot))
            return
        }
        val labels = options.map { getString(R.string.library_seat_duration_hours, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_seat_duration)
            .setItems(labels) { _, which ->
                val hours = options[which]
                if (hours == durationHours) return@setItems
                durationHours = hours
                renderTime()
                querySeats()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun renderTime() {
        val rules = form?.rules
        val hasSlot = rules != null && startHour != NO_HOUR
        binding.rowStart.isEnabled = hasSlot
        binding.rowDuration.isEnabled = hasSlot
        binding.tvStartValue.text =
            if (hasSlot) hourLabel(startHour) else getString(R.string.library_seat_none)
        binding.tvDurationValue.text =
            if (hasSlot) getString(R.string.library_seat_duration_hours, durationHours)
            else getString(R.string.library_seat_none)
        binding.tvRule.text = if (rules == null) {
            ""
        } else {
            getString(
                R.string.library_seat_rule,
                hourLabel(rules.minBeginHour),
                hourLabel(rules.maxEndHour),
                rules.minDurationHours,
                rules.maxDurationHours,
            )
        }
        renderSelection(binding.seatMap.selectedSeat())
    }

    // ==================== 选座与下单 ====================

    /** 座位图内部已经处理了「再点一次取消选中」，这里只把结果同步到底部栏 */
    private fun onSeatTapped(seat: SeatPoi) {
        if (!seat.isFree) {
            toast(getString(R.string.library_seat_seat_taken, seat.title))
            return
        }
        renderSelection(binding.seatMap.selectedSeat())
    }

    private fun renderSelection(seat: SeatPoi?) {
        binding.btnBook.isEnabled = seat != null && !busy
        if (seat == null) {
            binding.tvSelection.text = getString(R.string.library_seat_select_seat)
            binding.tvSelectionTime.text = ""
        } else {
            binding.tvSelection.text = getString(R.string.library_seat_selected, seat.title)
            binding.tvSelectionTime.text = getString(
                R.string.library_seat_selected_time,
                rangeLabel(),
            )
        }
    }

    private fun confirmBooking() {
        val seat = binding.seatMap.selectedSeat() ?: return
        val room = currentRoom ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_seat_book_confirm_title)
            .setMessage(
                getString(
                    R.string.library_seat_book_confirm_msg,
                    room.name,
                    seat.title,
                    rangeLabel(),
                )
            )
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.library_seat_book) { _, _ -> doBook(seat) }
            .show()
    }

    private fun doBook(seat: SeatPoi) {
        // 停留在页面上跨过整点/时段过期后，先别发请求：服务端会用「预约时段为过去时间」拒绝
        if (!endsInFuture(startHour, durationHours)) {
            refreshTimeSelection()
            querySeats()
            return
        }
        val begin = beginEpoch(startHour)
        val duration = durationHours * 3600
        busy = true
        beginLoading()
        binding.btnBook.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { LibrarySeatApi.book(seat.id, begin, duration) }
                toast(getString(R.string.library_seat_book_ok))
                binding.seatMap.clearSelection()
                renderSelection(null)
                refreshOnResume = true
                MySeatBookingsActivity.start(this@LibrarySeatActivity)
            } catch (e: Exception) {
                if (e.isCancellation) throw e
                toast(e.userMessage(this@LibrarySeatActivity, R.string.library_seat_book_failed))
            } finally {
                busy = false
                endLoading()
                renderSelection(binding.seatMap.selectedSeat())
            }
        }
    }

    // ==================== 小工具 ====================

    private fun beginLoading() {
        pendingLoads++
        if (pendingLoads == 1) binding.progress.show()
    }

    private fun endLoading() {
        pendingLoads--
        if (pendingLoads <= 0) {
            pendingLoads = 0
            binding.progress.hide()
        }
    }

    private fun beginEpoch(hour: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000

    private fun rangeLabel(): String {
        val endHour = startHour + durationHours
        return "${hourLabel(startHour)}–${hourLabel(endHour)}"
    }

    private fun hourLabel(hour: Int): String = String.format(Locale.CHINA, "%02d:00", hour)

    private fun showMapHint(message: String) {
        binding.tvMapHint.text = message
        binding.tvMapHint.isVisible = true
    }

    private fun showFatal(message: String) {
        binding.seatMap.setMap(null)
        binding.tvMapTitle.text = getString(R.string.library_seat_title)
        showMapHint(message)
        binding.rowStart.isEnabled = false
        binding.rowDuration.isEnabled = false
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "LibrarySeat"

        /** [startHour] 的「未确定」哨兵（0 点是合法整点，不能用 0 表示未选） */
        private const val NO_HOUR = -1

        fun start(context: Context) {
            context.startActivity(Intent(context, LibrarySeatActivity::class.java))
        }
    }
}
