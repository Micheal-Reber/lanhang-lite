package com.lanhang.lite.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.lanhang.lite.schedule.AppContext
import com.lanhang.lite.schedule.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课程提醒调度器（移植自 Sleepy `CourseNotificationScheduler`）。
 *
 * 每日提醒：在用户指定时间发今日摘要 / 明日预告。
 * 课前提醒：预排未来 7 天（含今天）每节课前 N 分钟的**精确**闹钟；
 * 每天 00:05 全窗口重同步（撤销不再需要的、覆盖仍需要的，幂等）。
 *
 * 🔴 三类闹钟**全部是自续期的一次性闹钟**（2026-11-09 P0-3）：`setRepeating` 属于
 * 「标准闹钟」，Doze 下要等维护窗口、App Standby 分桶再限流一次，正是「不点开 App
 * 就不响」的天花板。改用 `setExactAndAllowWhileIdle` 后，**触发方必须先把下一次排上
 * 再干别的活**（各 receiver 的第一件事），否则一次失败就断链。
 *
 * requestCode 用「日期 + 起始节次」派生（[ReminderData.armedKey]）：
 * 同一节课重复 set 由 AlarmManager 原地替换，只需撤销「上次排过、这次不再需要」的。
 * 账本持久化在独立 SP 里，进程被杀也不丢，换课表/改周次/关开关后旧闹钟都能精确撤销。
 */
object ReminderScheduler {

    // request codes（与 Sleepy 的 1/2/3 对齐；课前槽位基数 100）
    private const val RC_DAILY = 1
    private const val RC_BEFORE_CLASS_SCHEDULER = 2
    private const val RC_TOMORROW_DAILY = 3

    /** 课前闹钟预排窗口：今天起 7 个日历天 */
    const val WINDOW_DAYS = 7

    /**
     * 闹钟触发后续期时的最小提前量。
     *
     * AlarmManager 允许提前几个毫秒投递；不留余量的话「明天 07:00」会被算成
     * 「今天 07:00」，一秒后又触发一次，续期链原地打转。
     */
    private const val FIRE_MARGIN_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val context: Context get() = AppContext.instance

    // ==================== 全量重排 ====================

    fun scheduleAll() {
        ReminderNotify.ensureChannels(context)
        scope.launch {
            lock.withLock { scheduleAllLocked() }
            // 排完再对一眼流体云投放状态（兜底：切回横幅/关总开关/改提前分钟都可能让投放过期）
            reconcileFluidCloud()
        }
    }

    suspend fun cancelAll() = lock.withLock { cancelAllLocked() }

    /** 退出登录路径：不阻塞 UI，异步撤掉全部闹钟与账本。 */
    fun cancelAllAsync() {
        scope.launch { lock.withLock { cancelAllLocked() } }
    }

    private suspend fun scheduleAllLocked(): Boolean {
        cancelRecurringLocked()
        if (!ReminderPrefs.isEnabled(context)) {
            cancelBeforeClassLocked()
            ReminderLog.event("schedule-all", "result=disabled")
            return false
        }
        var daily = 0
        if (ReminderPrefs.isDailyEnabled(context)) {
            if (ReminderPrefs.isTodayEnabled(context)) { scheduleDaily(); daily++ }
            if (ReminderPrefs.isTomorrowEnabled(context)) { scheduleTomorrow(); daily++ }
        }
        if (!ReminderPrefs.isBeforeClassEnabled(context)) {
            cancelBeforeClassLocked()
            ReminderLog.event("schedule-all", "result=ok daily=$daily before=off")
            return false
        }
        scheduleBeforeClassDaily()
        resyncBeforeClassLocked()
        ReminderLog.event("schedule-all", "result=ok daily=$daily before=on")
        return true
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun cancelRecurringLocked() {
        alarmManager().cancel(pending(RC_DAILY, DailyNotifyReceiver::class.java))
        alarmManager().cancel(pending(RC_TOMORROW_DAILY, TomorrowNotifyReceiver::class.java))
    }

    private suspend fun cancelBeforeClassLocked() {
        alarmManager().cancel(pending(RC_BEFORE_CLASS_SCHEDULER, BeforeClassScheduleReceiver::class.java))
        // 账本 + 窗口内出现过的：覆盖「账本上线前旧版本排下的」与「课程已删」两种孤儿
        val codes = ReminderPrefs.loadArmedCodes(context) + windowCodes()
        codes.forEach { cancelBeforeClassAlarm(it) }
        ReminderPrefs.clearArmedCodes(context)
    }

    // ==================== 每日提醒 ====================

    /**
     * 排「今日摘要」的下一次。
     *
     * @param afterFire true = 由接收器在响过之后调用，续期要留 [FIRE_MARGIN_MS] 余量
     */
    fun scheduleDaily(afterFire: Boolean = false) = scheduleDailyAlarm(
        timeStr = ReminderPrefs.dailyTime(context),
        fallbackHour = 7,
        fallbackMinute = 0,
        pending = pending(RC_DAILY, DailyNotifyReceiver::class.java),
        minDelayMs = if (afterFire) FIRE_MARGIN_MS else 0L
    )

    /** 排「明日预告」的下一次；[afterFire] 同 [scheduleDaily]。 */
    fun scheduleTomorrow(afterFire: Boolean = false) = scheduleDailyAlarm(
        timeStr = ReminderPrefs.tomorrowTime(context),
        fallbackHour = 22,
        fallbackMinute = 0,
        pending = pending(RC_TOMORROW_DAILY, TomorrowNotifyReceiver::class.java),
        minDelayMs = if (afterFire) FIRE_MARGIN_MS else 0L
    )

    private fun scheduleDailyAlarm(
        timeStr: String,
        fallbackHour: Int,
        fallbackMinute: Int,
        pending: PendingIntent,
        minDelayMs: Long
    ) {
        val parts = timeStr.split(":")
        // 破损 pref（"07:60"、空值）钳到合法范围，避免 DateTimeException 崩在闹钟设置路径
        val hour = (parts.getOrNull(0)?.toIntOrNull() ?: fallbackHour).coerceIn(0, 23)
        val minute = (parts.getOrNull(1)?.toIntOrNull() ?: fallbackMinute).coerceIn(0, 59)
        val epoch = nextOccurrence(hour, minute, minDelayMs)
        setOneShotAlarm(epoch, pending)
        ReminderLog.event("arm-daily", "at=$hour:${"%02d".format(minute)} epoch=$epoch")
    }

    /** 下一次 `HH:mm` 的绝对时刻；离 [minDelayMs] 不够就顺延一天。 */
    private fun nextOccurrence(hour: Int, minute: Int, minDelayMs: Long): Long {
        val target = LocalTime.of(hour, minute)
        var at = LocalDate.now().atTime(target)
        if (!LocalTime.now().isBefore(target)) at = at.plusDays(1)
        var epoch = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        while (epoch - System.currentTimeMillis() < minDelayMs) {
            at = at.plusDays(1)
            epoch = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return epoch
    }

    /**
     * 自续期的一次性闹钟：排完这一次，下一次由接收器响过之后自己再排。
     *
     * **不用 `setRepeating`**（P0-3）：它属于「标准闹钟」，在 Doze 下要等到维护窗口
     * 才投递，App Standby 分桶还会再限流一次 —— 正是「不点开 App 就不响」的天花板。
     * `setExactAndAllowWhileIdle` 是官方列明的 Doze 豁免之一，一天三次的量级远低于配额。
     * 无精确闹钟权限时降级 `setAndAllowWhileIdle`（仍穿透 Doze），不新增任何权限。
     */
    private fun setOneShotAlarm(epoch: Long, pending: PendingIntent) {
        val am = alarmManager()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
        }
    }

    // ==================== 课前提醒 ====================

    /**
     * 排「每天 00:05 全窗口重同步」的下一次（自续期，见 [setOneShotAlarm]）。
     *
     * @param afterFire true = 由接收器响过之后调用，续期要留 [FIRE_MARGIN_MS] 余量
     */
    fun scheduleBeforeClassDaily(afterFire: Boolean = false) {
        val epoch = nextOccurrence(0, 5, if (afterFire) FIRE_MARGIN_MS else 0L)
        setOneShotAlarm(
            epoch,
            pending(RC_BEFORE_CLASS_SCHEDULER, BeforeClassScheduleReceiver::class.java)
        )
        ReminderLog.event("arm-resync", "at=00:05 epoch=$epoch")
    }

    /** 每日 00:05 与「课表刷新后」都会走这里：全窗口重同步，幂等。 */
    suspend fun resyncWindow() = lock.withLock { resyncBeforeClassLocked() }

    private suspend fun resyncBeforeClassLocked() {
        val previous = ReminderPrefs.loadArmedCodes(context)
        if (!ReminderPrefs.isEnabled(context) || !ReminderPrefs.isBeforeClassEnabled(context)) {
            previous.forEach { cancelBeforeClassAlarm(it) }
            ReminderPrefs.clearArmedCodes(context)
            ReminderLog.event("resync", "skip=disabled prev=${previous.size}")
            return
        }
        // 7 天窗口通常跨两周，而主缓存只有落盘那一周的课：先把窗口内缺的周数据补上，
        // 否则「下周才开始的课」整门排不进闹钟（服务端按周返回，见 ReminderData.dataForWeek）。
        val covered = runCatching { ReminderData.ensureWeeksCovered(WINDOW_DAYS) }
            .getOrElse {
                ReminderLog.error("resync-cover", it)
                false
            }
        ReminderPrefs.recordCoverResult(context, covered)

        // 🔴 撤销要留一手（P0-4）：数据拿不到的日期，旧闹钟一个都不许撤。
        //    「没有课」可以撤，「不知道有没有课」撤了就等于把下周才开始的课整门删掉，
        //    而且删掉之后只有点开 App 才排得回来 —— 这正是用户反馈的那条路径。
        val dates = (0 until WINDOW_DAYS).map { LocalDate.now().plusDays(it.toLong()) }
        val unknown = ReminderData.unknownDates(WINDOW_DAYS)
        val keep: Set<Int> = when (unknown) {
            null -> previous // 连课表缓存都没有：整窗不可判定，全保留
            else -> previous.filter { ReminderData.dateOfCode(it, dates) in unknown }.toSet()
        }

        val minutes = ReminderPrefs.beforeClassMinutes(context)
        val nowEpoch = System.currentTimeMillis()
        val today = LocalDate.now()

        val desired = LinkedHashMap<Int, Pair<Long, ReminderCourse>>()
        val window = mutableListOf<Int>()

        // 同一节课一周内可能出现多次（每周重复课），按天顺序遍历、先到先得 = 最早一次
        for (offset in 0 until WINDOW_DAYS) {
            val date = today.plusDays(offset.toLong())
            val courses = ReminderData.coursesOn(date)
            courses.forEach { course ->
                val key = ReminderData.armedKey(date, course.startPeriod)
                window += key
                val start = course.startTime ?: return@forEach
                val notifyEpoch = date.atTime(start)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - minutes * 60_000L
                if (notifyEpoch <= nowEpoch) return@forEach
                if (key in desired) return@forEach
                desired[key] = notifyEpoch to course
            }
        }

        val stale = (previous + window).filterNot { it in desired.keys || it in keep }
        stale.forEach { cancelBeforeClassAlarm(it) }
        desired.forEach { (key, slot) -> setBeforeClassAlarm(key, slot.first, slot.second) }
        ReminderPrefs.saveArmedCodes(context, desired.keys + keep)
        ReminderLog.event(
            "resync",
            "prev=${previous.size} desired=${desired.size} keep=${keep.size} " +
                "cancel=${stale.size} covered=$covered unknown=${unknown?.size ?: -1}"
        )
    }

    private fun setBeforeClassAlarm(requestCode: Int, epochMs: Long, course: ReminderCourse) {
        val intent = Intent(context, BeforeClassNotifyReceiver::class.java).apply {
            putExtra("courseName", course.name)
            putExtra("room", course.room)
            putExtra("teacher", course.teacher)
            putExtra("startTime", course.startText)
            putExtra("endTime", course.endText)
            putExtra("startNode", course.startPeriod)
            putExtra("notifyEpoch", epochMs)
            putExtra("classEpoch", course.startEpoch)
            putExtra("reminderDate", course.date.toString())
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            setOneShotAlarm(epochMs, pending)
            ReminderLog.event("arm-before-class", "code=$requestCode at=$epochMs")
        } catch (t: Throwable) {
            ReminderLog.error("arm-before-class-failed", t)
        }
    }

    private fun cancelBeforeClassAlarm(requestCode: Int) {
        try {
            val pending = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, BeforeClassNotifyReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pending?.let { alarmManager().cancel(it) }
        } catch (_: Exception) {
        }
    }

    /** 未来 7 天窗口内所有课程槽位的 requestCode（用于取消窗口外的孤儿） */
    private fun windowCodes(): Set<Int> {
        val today = LocalDate.now()
        return (0 until WINDOW_DAYS).flatMap { offset ->
            val date = today.plusDays(offset.toLong())
            ReminderData.coursesOn(date).map { ReminderData.armedKey(date, it.startPeriod) }
        }.toSet()
    }

    /**
     * P0-6：开关是开的、窗口里确实有课、账本却是空的 —— 说明排钟压根没跑过
     * （跑过就不可能是空的，跑过但没课则 [windowCodes] 也是空的，不会误报）。
     *
     * 提醒页据此出一条告警，避免用户在毫无提示的情况下整周收不到。
     * 读磁盘，**必须在 IO 线程调用**。
     */
    fun scheduleLooksLost(): Boolean {
        if (!ReminderPrefs.isEnabled(context) || !ReminderPrefs.isBeforeClassEnabled(context)) {
            return false
        }
        if (ReminderPrefs.loadArmedCodes(context).isNotEmpty()) return false
        return windowCodes().isNotEmpty()
    }

    // ==================== 流体云状态对账 ====================

    /**
     * 状态驱动的兜底：只要「现在」落在某节课的 `[上课-N 分钟, 上课]` 窗口内，
     * 就确保 FluidCloudService 在跑。不依赖「正好提前 N 分钟那一秒」的闹钟 ——
     * 闹钟错过或用户在窗口内才打开 App 时，靠这里把卡片补出来。
     */
    suspend fun reconcileFluidCloud() {
        if (!ensureActiveFluidCloud()) FluidCloudService.requestStop(context, reconciliation = true)
    }

    private suspend fun ensureActiveFluidCloud(): Boolean = withContextIo {
        if (FluidCastState.testCasting) return@withContextIo true
        if (!ReminderPrefs.isEnabled(context)) return@withContextIo false
        if (!ReminderPrefs.isBeforeClassEnabled(context)) return@withContextIo false
        if (!ReminderPrefs.isBeforeClassFluidEnabled(context)) return@withContextIo false
        val minutes = ReminderPrefs.beforeClassMinutes(context)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val hit = ReminderData.coursesOn(today)
            .mapNotNull { course ->
                val start = course.startTime ?: return@mapNotNull null
                val classStart = today.atTime(start)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val notifyEpoch = classStart - minutes * 60_000L
                if (now in notifyEpoch..classStart) course to (classStart to notifyEpoch) else null
            }
            .minByOrNull { it.second.first } ?: return@withContextIo false

        val (course, times) = hit
        val (classStart, notifyEpoch) = times
        val svc = Intent(context, FluidCloudService::class.java).apply {
            putExtra(FluidCloudService.EXTRA_RECONCILE, true)
            putExtra("courseName", course.name)
            putExtra("room", course.room)
            putExtra("teacher", course.teacher)
            putExtra("startTime", course.startText)
            putExtra("endTime", course.endText)
            putExtra("startNode", course.startPeriod)
            putExtra("notifyEpoch", notifyEpoch)
            putExtra("classEpoch", classStart)
        }
        try {
            ContextCompat.startForegroundService(context, svc)
            ReminderLog.event("fluid-reconcile", "start classEpoch=$classStart")
            true
        } catch (t: Throwable) {
            ReminderLog.error("fluid-reconcile-failed", t)
            false
        }
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    // ==================== PendingIntent ====================

    private fun pending(rc: Int, cls: Class<out BroadcastReceiver>): PendingIntent =
        PendingIntent.getBroadcast(
            context, rc, Intent(context, cls),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 冷启动 / 换账号后的捷径：清掉旧槽位再按当前偏好重排。 */
    fun restart() {
        scope.launch {
            lock.withLock { cancelAllLocked() }
            scheduleAll()
        }
    }

    private suspend fun cancelAllLocked() {
        cancelRecurringLocked()
        cancelBeforeClassLocked()
    }
}

// ==================== Receivers ====================

/** 今日摘要：用户设定时间触发。 */
class DailyNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderPrefs.isEnabled(context)) return
        if (!ReminderPrefs.isDailyEnabled(context)) return
        if (!ReminderPrefs.isTodayEnabled(context)) return
        ReminderLog.event("recv-daily")
        // 先把明天那颗排上（同步、幂等）：这次的正文要联网取课表，中途被杀也不会断链
        ReminderScheduler.scheduleDaily(afterFire = true)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 明天可能已经是下一周：先补该周的按周缓存，摘要才不会写成「明天没有课」
                val covered = runCatching { ReminderData.ensureWeeksCovered(2) }
                    .getOrElse { ReminderLog.error("daily-cover", it); false }
                ReminderPrefs.recordCoverResult(context, covered)
                ReminderNotify.sendDailySummary(context, LocalDate.now(), tomorrowPreview = false)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 明日预告：前一天晚上触发。 */
class TomorrowNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderPrefs.isEnabled(context)) return
        if (!ReminderPrefs.isDailyEnabled(context)) return
        if (!ReminderPrefs.isTomorrowEnabled(context)) return
        ReminderLog.event("recv-tomorrow")
        ReminderScheduler.scheduleTomorrow(afterFire = true)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 明晚预告的是「明天」：跨周时同样要先补该周缓存
                val covered = runCatching { ReminderData.ensureWeeksCovered(2) }
                    .getOrElse { ReminderLog.error("tomorrow-cover", it); false }
                ReminderPrefs.recordCoverResult(context, covered)
                ReminderNotify.sendDailySummary(
                    context,
                    LocalDate.now().plusDays(1),
                    tomorrowPreview = true
                )
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 每日 00:05 全窗口重同步。用 goAsync：onReceive 返回后进程可能被冻结，协程会停在半路。
 *
 * **续期排在最前面**（P0-3）：重同步要联网补课表，是最容易失败/被杀的一步；
 * 先把明天那颗闹钟排上，链路才不会因为一次失败而整条断掉。
 */
class BeforeClassScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderPrefs.isEnabled(context) || !ReminderPrefs.isBeforeClassEnabled(context)) return
        ReminderLog.event("recv-resync")
        ReminderScheduler.scheduleBeforeClassDaily(afterFire = true)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderScheduler.resyncWindow()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 单节课的课前提醒：横幅或流体云。
 *
 * 「形态」只认一个真值（[ReminderPrefs.beforeClassMode]），横幅与流体云互斥；
 * 流体云走前台服务，让进度条每 15s 真的推进。
 *
 * 🔴 **流体云起不来必须回退横幅**（P0-2）：后台启动前台服务受 App Standby 分桶 /
 * 厂商策略限制，静默失败 = 用户什么都收不到。增强路径失败就退回标准路径，
 * 这是 Sleepy 迁移文档里「标准通知永远可用」那条硬约束。
 */
class BeforeClassNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderPrefs.isEnabled(context) || !ReminderPrefs.isBeforeClassEnabled(context)) return
        val date = intent.getStringExtra("reminderDate")
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        val courseName = intent.getStringExtra("courseName") ?: return
        val room = intent.getStringExtra("room").orEmpty()
        val teacher = intent.getStringExtra("teacher").orEmpty()
        val startTime = intent.getStringExtra("startTime").orEmpty()
        val endTime = intent.getStringExtra("endTime").orEmpty()
        val startNode = intent.getIntExtra("startNode", 0)
        val notifyEpoch = intent.getLongExtra("notifyEpoch", System.currentTimeMillis())
        val classEpoch = intent.getLongExtra("classEpoch", System.currentTimeMillis())
        ReminderLog.event(
            "recv-before-class",
            "mode=${ReminderPrefs.beforeClassMode(context)} late=${classEpoch <= System.currentTimeMillis()}"
        )

        val course = ReminderCourse(
            date = date,
            name = courseName,
            room = room,
            teacher = teacher,
            startPeriod = startNode.coerceAtLeast(1),
            endPeriod = startNode.coerceAtLeast(1)
        )

        // 闹钟迟到到「已经上课了」就别开流体云了：服务自己会当场 stop（进度 0% 的卡片一闪而过），
        // 结果是用户什么都没看见。这种时候横幅至少还是一条可见的通知。
        if (ReminderPrefs.isBeforeClassFluidEnabled(context) &&
            classEpoch > System.currentTimeMillis()
        ) {
            val svc = Intent(context, FluidCloudService::class.java).apply {
                putExtra("courseName", courseName)
                putExtra("room", room)
                putExtra("teacher", teacher)
                putExtra("startTime", startTime)
                putExtra("endTime", endTime)
                putExtra("startNode", startNode)
                putExtra("notifyEpoch", notifyEpoch)
                putExtra("classEpoch", classEpoch)
            }
            try {
                ContextCompat.startForegroundService(context, svc)
                return
            } catch (t: Throwable) {
                // 回退横幅：增强路径失败绝不能变成「什么都没有」
                ReminderLog.error("fluid-start-failed", t)
            }
        }

        ReminderNotify.sendBeforeClassBanner(context, course)
    }
}

/** 开机 / 应用更新 / 改系统时间后重排（闹钟不跨重启，绝对时刻也会跟着变）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ReminderScheduler.scheduleAll()
                ClassDndScheduler(context).syncFromPrefs()
                // 课表缓存可能已随系统时间变化过期：下一次 App 启动/刷新会再对账一次
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { ScheduleRepository.loadCached() }
                }
            }
            // 手动改时间 / 换时区：所有闹钟存的都是绝对时刻，不重排就会指到错误的钟点。
            // （广播 action 是 TIME_SET，对应的 Intent 常量叫 ACTION_TIME_CHANGED）
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                ReminderLog.event("recv-time-changed", "action=${intent.action}")
                ReminderScheduler.scheduleAll()
            }
        }
    }
}
