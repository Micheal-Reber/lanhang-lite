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
 * 每日提醒：在用户指定时间发今日摘要 / 明日预告，用 `setRepeating(INTERVAL_DAY)`。
 * 课前提醒：预排未来 7 天（含今天）每节课前 N 分钟的**精确**闹钟；
 * 每天 00:05 全窗口重同步（撤销不再需要的、覆盖仍需要的，幂等）。
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
            return false
        }
        if (ReminderPrefs.isDailyEnabled(context)) {
            if (ReminderPrefs.isTodayEnabled(context)) scheduleDaily()
            if (ReminderPrefs.isTomorrowEnabled(context)) scheduleTomorrow()
        }
        if (!ReminderPrefs.isBeforeClassEnabled(context)) {
            cancelBeforeClassLocked()
            return false
        }
        scheduleBeforeClassDaily()
        resyncBeforeClassLocked()
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

    private fun scheduleDaily() = scheduleDailyAlarm(
        timeStr = ReminderPrefs.dailyTime(context),
        fallbackHour = 7,
        fallbackMinute = 0,
        pending = pending(RC_DAILY, DailyNotifyReceiver::class.java)
    )

    private fun scheduleTomorrow() = scheduleDailyAlarm(
        timeStr = ReminderPrefs.tomorrowTime(context),
        fallbackHour = 22,
        fallbackMinute = 0,
        pending = pending(RC_TOMORROW_DAILY, TomorrowNotifyReceiver::class.java)
    )

    private fun scheduleDailyAlarm(
        timeStr: String,
        fallbackHour: Int,
        fallbackMinute: Int,
        pending: PendingIntent
    ) {
        val parts = timeStr.split(":")
        // 破损 pref（"07:60"、空值）钳到合法范围，避免 DateTimeException 崩在闹钟设置路径
        val hour = (parts.getOrNull(0)?.toIntOrNull() ?: fallbackHour).coerceIn(0, 23)
        val minute = (parts.getOrNull(1)?.toIntOrNull() ?: fallbackMinute).coerceIn(0, 59)
        val target = LocalTime.of(hour, minute)
        var next = LocalDate.now().atTime(target)
        if (!LocalTime.now().isBefore(target)) next = next.plusDays(1)
        val epoch = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setRepeatingAlarm(epoch, pending)
    }

    private fun setRepeatingAlarm(epoch: Long, pending: PendingIntent) {
        val am = alarmManager()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, epoch, AlarmManager.INTERVAL_DAY, pending)
        } else {
            am.setRepeating(AlarmManager.RTC_WAKEUP, epoch, AlarmManager.INTERVAL_DAY, pending)
        }
    }

    // ==================== 课前提醒 ====================

    private fun scheduleBeforeClassDaily() {
        val target = LocalTime.of(0, 5)
        var next = LocalDate.now().atTime(target)
        if (LocalTime.now().isAfter(target)) next = next.plusDays(1)
        val epoch = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setRepeatingAlarm(
            epoch,
            pending(RC_BEFORE_CLASS_SCHEDULER, BeforeClassScheduleReceiver::class.java)
        )
    }

    /** 每日 00:05 与「课表刷新后」都会走这里：全窗口重同步，幂等。 */
    suspend fun resyncWindow() = lock.withLock { resyncBeforeClassLocked() }

    private suspend fun resyncBeforeClassLocked() {
        val previous = ReminderPrefs.loadArmedCodes(context)
        if (!ReminderPrefs.isEnabled(context) || !ReminderPrefs.isBeforeClassEnabled(context)) {
            previous.forEach { cancelBeforeClassAlarm(it) }
            ReminderPrefs.clearArmedCodes(context)
            return
        }
        // 7 天窗口通常跨两周，而主缓存只有落盘那一周的课：先把窗口内缺的周数据补上，
        // 否则「下周才开始的课」整门排不进闹钟（服务端按周返回，见 ReminderData.dataForWeek）。
        runCatching { ReminderData.ensureWeeksCovered(WINDOW_DAYS) }
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

        (previous + window).filterNot { it in desired }.forEach { cancelBeforeClassAlarm(it) }
        desired.forEach { (key, slot) -> setBeforeClassAlarm(key, slot.first, slot.second) }
        ReminderPrefs.saveArmedCodes(context, desired.keys.toSet())
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
        val am = alarmManager()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 精确闹钟权限被收回：降级非精确但仍穿透 Doze，否则提前几天排的闹钟会被拖到亮屏才响
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pending)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pending)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pending)
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
            true
        } catch (_: Throwable) {
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
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 明天可能已经是下一周：先补该周的按周缓存，摘要才不会写成「明天没有课」
                runCatching { ReminderData.ensureWeeksCovered(2) }
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
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 明晚预告的是「明天」：跨周时同样要先补该周缓存
                runCatching { ReminderData.ensureWeeksCovered(2) }
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

/** 每日 00:05 全窗口重同步。用 goAsync：onReceive 返回后进程可能被冻结，协程会停在半路。 */
class BeforeClassScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderPrefs.isEnabled(context) || !ReminderPrefs.isBeforeClassEnabled(context)) return
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

        if (ReminderPrefs.isBeforeClassFluidEnabled(context)) {
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
            } catch (_: Throwable) {
            }
            return
        }

        if (!ReminderPrefs.isBeforeClassBannerEnabled(context)) return
        ReminderNotify.sendBeforeClassBanner(
            context,
            ReminderCourse(
                date = date,
                name = courseName,
                room = room,
                teacher = teacher,
                startPeriod = startNode.coerceAtLeast(1),
                endPeriod = startNode.coerceAtLeast(1)
            )
        )
    }
}

/** 开机 / 应用更新后重排（闹钟不跨重启，必须重建）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        ReminderScheduler.scheduleAll()
        ClassDndScheduler(context).syncFromPrefs()
        // 课表缓存可能已随系统时间变化过期：下一次 App 启动/刷新会再对账一次
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ScheduleRepository.loadCached() }
        }
    }
}
