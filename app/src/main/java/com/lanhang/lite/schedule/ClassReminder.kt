package com.lanhang.lite.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lanhang.lite.R
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.session.Session
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课提醒的调度中心。
 *
 * ## 为什么是「窗口预排 + 全量对账」而不是「只挂一个链式闹钟」
 *
 * 只挂一个闹钟（触发后再排下一个）最省事，但它是**单点**：哪一次重排抛异常，整条链就断了，
 * 而用户看不到任何异常 —— 直到某天发现「提醒一直没响」。
 *
 * 这里改成（参考 sleepy 的做法，实现更简单）：一次排好未来 [ClassReminderPlan.WINDOW_DAYS] 天
 * 所有课的闹钟，每个闹钟带**自己的** requestCode；每次 [rearm] 都重新算整个窗口，
 * 把「码段内、这次不需要」的槽位全部撤销。于是：
 *
 * - 某一条排失败只影响那一节，其他课照响；
 * - 换课表 / 改提前量 / 关开关之后，旧闹钟靠同一次对账精确撤销，不会留孤儿；
 * - 每天 00:05 一次全窗口重同步（[ACTION_RESYNC]）把窗口往前滚，
 *   即使某天所有闹钟都失败了也能自愈。
 *
 * sleepy 因为 requestCode 由 courseId 派生，必须维护一份「已排码集合」的账本；
 * 我们的 requestCode 是**窗口内序号**（`RC_SLOT_BASE + 0..MAX_SLOTS-1`），
 * 直接按码段全扫即可，账本反而多一个会和系统对不上的状态，所以没做。
 *
 * ## 什么时候必须重排（漏一个就静默失效）
 *
 * 开机、应用更新（`MY_PACKAGE_REPLACED`，**最容易漏**：不加的话每次发版所有用户的提醒会
 * 静默消失到下次打开 App）、改系统时间/时区、精确闹钟权限被授予、课表刷新成功、
 * 设置变更、App 启动。见 [ClassReminderReceiver] 与 `App.onCreate`。
 */
object ClassReminder {

    private const val TAG = "ClassReminder"

    /** 槽位闹钟的 action（[ClassReminderReceiver] 收） */
    const val ACTION_ALARM = "com.lanhang.lite.schedule.CLASS_REMINDER_ALARM"

    /** 每日全窗口重同步 */
    const val ACTION_RESYNC = "com.lanhang.lite.schedule.CLASS_REMINDER_RESYNC"

    /** 上课那一刻：把课前那条换成「正在上课 · 还剩多久下课」 */
    const val ACTION_CLASS_STARTED = "com.lanhang.lite.schedule.CLASS_REMINDER_STARTED"

    /** 下课那一刻：撤掉课中那条通知 */
    const val ACTION_CLASS_ENDED = "com.lanhang.lite.schedule.CLASS_REMINDER_ENDED"

    const val EXTRA_KEY = "slot_key"

    /**
     * 槽位码段起点。必须与 [ClassReminderPlan.MAX_SLOTS] 配合：
     * 对账时会把这个区间整个扫一遍，撤销不在新计划里的闹钟。
     * 不要与小组件的 4500/4510、互动消息的 4610、提醒自身的 4899/4900 重叠。
     */
    private const val RC_SLOT_BASE = 5000

    /**
     * 上下课边界闹钟的码段：`5300 + (节次-1)*2 + 0/1`。
     * 与槽位段 5000（占 64 个 → 5000..5063）、工作圈 4610、小组件 4500/4510 都不冲突。
     */
    private const val RC_BOUNDARY_BASE = 5300

    /** 老版本「次日课程提醒」的码段与 action（功能已删，只用于清理残留闹钟） */
    private const val LEGACY_RC_NEXT_DAY = 5450
    private const val LEGACY_ACTION_NEXT_DAY = "com.lanhang.lite.schedule.CLASS_REMINDER_NEXT_DAY"

    /** 老版本「测试通知」的码段与 action（功能已于 2026-10-29 删除，只用于清理残留闹钟） */
    private const val LEGACY_RC_TEST = 4899
    private const val LEGACY_ACTION_TEST = "com.lanhang.lite.schedule.CLASS_REMINDER_TEST"

    private const val RC_RESYNC = 4900

    /** 00:05 重同步：多数人还没起床，顺带把新的一天排进来 */
    private val RESYNC_TIME: LocalTime = LocalTime.of(0, 5)

    private val dateTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

    /**
     * 全量重排。**幂等**，可以在任意入口反复调用。
     *
     * 关闭状态 / 未登录 / 没有课表缓存 / 没有通知权限 → 一律撤销全部闹钟（不空转）。
     * 注意「撤销闹钟」不等于「改用户开关」：开关始终反映用户意图，缺权限时开关仍保持打开，
     * 由页面上的红条告诉用户缺什么。
     */
    @Synchronized
    fun rearm(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        ClassReminderNotify.ensureChannel(app)
        cancelLegacyNextDay(app, manager)
        val store = ClassReminderStore

        val data = if (Session.userId.isNotBlank()) {
            ScheduleRepository.loadCached()?.data
        } else {
            null
        }
        if (!store.enabled || data == null || !ClassReminderNotify.canNotify(app)) {
            cancelArmed(app, manager)
            // 提醒关掉了，别再让「上课勿扰」的残留档位挂着（下课那次闹钟可能永远不会来）
            ClassDnd.restore(app)
            // 已弹出的提醒也要撤：Android 16 上它是常驻芯片，用户自己滑不掉
            ClassReminderNotify.cancelAll(app)
            return
        }

        val desired = ClassReminderPlan.upcoming(
            data = data,
            now = System.currentTimeMillis(),
            advanceMinutes = store.advanceMinutes
        )
        // 先把这次不需要的槽位全撤掉（含改提前量之后挪位的、换课表之后消失的）
        for (index in 0 until ClassReminderPlan.MAX_SLOTS) {
            if (index >= desired.size) cancelSlot(app, manager, index)
        }
        desired.forEachIndexed { index, slot ->
            setSlot(app, manager, index, slot)
        }
        armResync(app, manager)
        cancelFinishedToday(app, data)
        settleStaleDnd(app, data)
        Log.d(TAG, "armed ${desired.size} slots, advance=${store.advanceMinutes}")
    }

    /**
     * 兜底：**没有正在上的课**时，把「上课勿扰」恢复掉。
     *
     * 勿扰的生命周期本该由下课那一刻的边界闹钟结束，但边界一旦被系统攒批错过
     * （docs §10.5 的冻结，实测能晚 7 分钟、极端情况更久），用户就会一直停在
     * 「仅闹钟」里 —— 别人打电话也不响，而且他完全不知道为什么。
     *
     * 这里只恢复勿扰、**不碰岛和通知**：`rearm` 会在「课前提醒刚发出去」之后立刻再跑一次，
     * 那时课还没开始，若顺手 cancel 岛，等于刚发就撤。
     */
    private fun settleStaleDnd(context: Context, data: ScheduleData) {
        val now = System.currentTimeMillis()
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val ongoing = ClassReminderPlan.slotsOn(data, today, 0).any { slot ->
            val end = endAtOf(slot) ?: return@any false
            now in slot.startAt until end
        }
        if (!ongoing) ClassDnd.restore(context)
    }

    /**
     * 兜底：把今天**已经上完**的课那条通知收掉。
     *
     * Android 16 上课前提醒是常驻芯片（`setOngoing` + promoted），用户滑不掉；正常由
     * 上课 / 下课两个边界闹钟收。万一边界被系统攒批错过（见 docs §10.5 的冻结），
     * 它就会挂一整天。`rearm` 每次都会跑（应用启动 / 00:05 重同步 / 每条提醒之后），
     * 正好拿来兜这个底。
     */
    private fun cancelFinishedToday(context: Context, data: ScheduleData) {
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val now = System.currentTimeMillis()
        for (slot in ClassReminderPlan.slotsOn(data, today, 0)) {
            val endAt = endAtOf(slot) ?: continue
            if (now > endAt) ClassReminderNotify.cancelSlot(context, slot.period)
        }
    }

    /**
     * 关开关 / 退出登录：撤掉全部闹钟，并把**已经弹出的通知**一起收掉。
     *
     * 不能像以前那样「留给用户自己滑掉」：Android 16 上课前那条是 promoted **常驻**通知
     * （`setOngoing(true)` 是系统提升的前提），用户根本滑不掉，界面关了开关、状态栏还挂着
     * 一条「还有 N 分钟上课」，而且永远不会自己消失。
     */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        cancelArmed(app, manager)
        runCatching { manager.cancel(resyncPending(app)) }
        ClassReminderNotify.cancelAll(app)
        // 关提醒时如果正处在上课勿扰里，必须把档位还回去 —— 否则用户会一直静音下去
        ClassDnd.restore(app)
    }

    /**
     * 闹钟真的响了（[ACTION_ALARM]）。
     *
     * 这里必须**再校验一遍**，不能信任几天前排下去的那条：课表换了、提醒关了、课被撤销了，
     * 闹钟本身都不知道。校验口径（与需求文档一致）：
     *
     * - 课程仍存在（重新按当天数据算一遍，找不到就是作废）；
     * - 现在还没上课（已经上课了就不发，迟到补发只会干扰课堂），
     *   唯一的例外是提前量 0 —— 它的提醒时刻**就是**上课时刻，
     *   必须容许几秒钟的误差，否则永远不会响。
     */
    fun handleAlarm(context: Context, key: String?) {
        val app = context.applicationContext
        val store = ClassReminderStore
        if (!store.enabled) return
        val parsed = key?.let(ClassReminderPlan::parseKey) ?: return
        val data = ScheduleRepository.loadCached()?.data ?: return
        val (date, period) = parsed
        val slot = ClassReminderPlan.slotsOn(data, date, store.advanceMinutes)
            .firstOrNull { it.period == period } ?: return
        val now = System.currentTimeMillis()
        // 投递延迟实测（2026-10-28，本机 HyperOS）：设了提前 15 分钟，排期确实是 07:45
        // （`dumpsys alarm` 里 window=0 exactAllowReason=permission），系统却 07:51:49 才投 ——
        // 同一夜冻结里小组件 00:05 的午夜闹钟也是 08:47 才补投。排期没错，是后台被冻住、
        // 解冻后补发。留一行日志，下次「又晚了」能直接分清是排期还是投递。
        Log.i(TAG, "slot ${slot.key} delivered ${now - slot.triggerAt}ms after trigger")
        val lateBy = now - slot.startAt
        val tolerated = if (store.advanceMinutes <= 0) GRACE_WHEN_ON_TIME else 0L
        if (lateBy > tolerated) return
        val minutesLeft = ((slot.startAt - now + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
        ClassReminderNotify.show(app, slot, minutesLeft)
        // 课前那条发出去之后，把「上课」「下课」两个边界也挂上，供课中提醒收尾
        armBoundaries(app, slot)
    }

    /**
     * 上课 / 下课两个边界闹钟（2026-10-28 加，只服务「课中提醒」）。
     *
     * 为什么不在 [rearm] 里一起排：边界时刻依赖「这一节具体是几点的课」，而 [rearm] 只算触发时刻
     * （`triggerAt`），拿不到下课时间；再者边界只在「课前那条真的发出去了」之后才有意义
     * —— 课被撤销、提醒被关掉时，这两个闹钟不该存在。
     */
    private fun armBoundaries(context: Context, slot: ClassReminderPlan.Slot) {
        // 边界闹钟现在是**必排**的：课中提醒要靠它换文案，岛要靠它换卡/收起，
        // 上课自动勿扰要靠它切档并恢复；三者都关时还剩下一个用途 ——
        // 把课前那条收起来（Android 16 上它是常驻的芯片，不收会一直挂着）
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val endAt = endAtOf(slot) ?: return
        val now = System.currentTimeMillis()
        if (endAt <= now) return
        val rc = RC_BOUNDARY_BASE + (slot.period - 1) * 2
        // 闹钟响晚了（系统冻结后台）时 startAt 可能已经过去：setExact 会立刻投递，
        // 正好等价于「上课了才补上课中那条」，不需要特判
        setAlarm(context, manager, rc, slot.startAt, boundaryPending(context, rc, ACTION_CLASS_STARTED, slot.key))
        setAlarm(context, manager, rc + 1, endAt, boundaryPending(context, rc + 1, ACTION_CLASS_ENDED, slot.key))
    }

    /** 某一节的下课时刻（毫秒）；节次越界返回 null。连堂取**最后一块的结束节次** */
    private fun endAtOf(slot: ClassReminderPlan.Slot): Long? {
        val lastPeriod = slot.courses.maxOfOrNull { it.end } ?: slot.period
        val end = PeriodClock.end(lastPeriod) ?: return null
        return slot.date.atTime(end).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 上课那一刻：**收起课前那条**、按需开勿扰。
     *
     * 不在这里发「正在上课 / 还有 N 分钟下课」—— 人已经在教室了，还要被提醒还有多久下课，
     * 是纯噪音（2026-10-28 用户明确要求去掉）。这里只做两件收尾：
     * ① 开上课勿扰（可选）；② 撤掉课前那条。
     *
     * ② 是必须的：Android 16 上课前那条是 promoted **常驻胶囊**，用户滑不掉，
     * 不收就会一直挂着「还有 N 分钟上课」—— 过了上课点就成了错的信息。
     *
     * 二次校验同 [handleAlarm]：课表换了、课被撤销了，几天前排下的闹钟并不知道。
     */
    fun handleClassStarted(context: Context, key: String?) {
        val app = context.applicationContext
        val parsed = key?.let(ClassReminderPlan::parseKey) ?: return
        val data = ScheduleRepository.loadCached()?.data ?: return
        val (date, period) = parsed
        val slot = ClassReminderPlan.slotsOn(data, date, 0)
            .firstOrNull { it.period == period } ?: return
        val endAt = endAtOf(slot) ?: return
        if (System.currentTimeMillis() >= endAt) return
        // 上课自动勿扰：只在这节课的区间内生效，下课由 handleClassEnded 恢复原档位
        if (ClassReminderStore.autoDnd) ClassDnd.apply(app)
        ClassReminderNotify.cancelSlot(app, period)
    }

    /** 下课那一刻：恢复勿扰档位、再撤一次通知（边界闹钟可能被系统吞，这次是第二道保险） */
    fun handleClassEnded(context: Context, key: String?) {
        val app = context.applicationContext
        val period = key?.let(ClassReminderPlan::parseKey)?.second ?: return
        if (ClassReminderStore.autoDnd) ClassDnd.restore(app)
        ClassReminderNotify.cancelSlot(app, period)
    }

    /**
     * 下一次提醒；关闭 / 没课 / 没数据都返回 null。
     *
     * [data] 由调用方传入（设置页一次渲染里只读一次缓存，别让两个函数各解析一遍课表 JSON）。
     */
    fun nextSlot(data: ScheduleData?, now: Long = System.currentTimeMillis()): ClassReminderPlan.Slot? {
        if (!ClassReminderStore.enabled || data == null) return null
        return ClassReminderPlan.next(data, now, ClassReminderStore.advanceMinutes)
    }

    /** `10/28 07:45 · 高等数学 · 教1-201`；没有下一次则返回 null */
    fun nextSlotText(context: Context, data: ScheduleData?): String? {
        val slot = nextSlot(data) ?: return null
        val text = LocaleHelper.wrap(context)
        val whenText = dateTimeFormat.format(
            Instant.ofEpochMilli(slot.triggerAt).atZone(ZoneId.systemDefault())
        )
        val first = slot.courses.firstOrNull()
        val courseText = when {
            first == null -> text.getString(R.string.class_reminder_next_no_course)
            first.room.isBlank() -> first.name
            else -> text.getString(R.string.class_reminder_course_line, first.name, first.room)
        }
        val more = if (slot.courses.size > 1) {
            text.getString(R.string.class_reminder_next_more, slot.courses.size - 1)
        } else {
            ""
        }
        return text.getString(R.string.class_reminder_next_value, whenText, courseText + more)
    }

    /** 精确闹钟权限：false 时降级为非精确（Doze 里可能延迟几分钟） */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    // ------------------------------------------------------------ 闹钟落地

    private fun setSlot(context: Context, manager: AlarmManager, index: Int, slot: ClassReminderPlan.Slot) {
        val rc = RC_SLOT_BASE + index
        val pending = slotPending(context, rc, slot.key)
        setAlarm(context, manager, rc, slot.triggerAt, pending)
    }

    private fun cancelSlot(context: Context, manager: AlarmManager, index: Int) {
        val pending = slotPending(context, RC_SLOT_BASE + index, null)
        runCatching { manager.cancel(pending) }
    }

    private fun cancelArmed(context: Context, manager: AlarmManager) {
        for (index in 0 until ClassReminderPlan.MAX_SLOTS) cancelSlot(context, manager, index)
        cancelBoundaries(context, manager)
        runCatching { manager.cancel(resyncPending(context)) }
        cancelLegacyTest(context, manager)
    }

    /**
     * 清掉老版本残留的「次日课程提醒」闹钟（2026-10-28 该功能已删除）。
     *
     * 代码删了，但已经排进系统 AlarmManager 的那个闹钟还在 —— 今晚 21:00 会白唤醒一次
     * （广播过去没有任何分支处理，等于空转）。这里按**同一个 action** 建一次 PendingIntent
     * 把它撤掉：`PendingIntent` 的匹配只看 (requestCode, action, …) 不看 extras，能命中。
     * 用 `FLAG_NO_CREATE`：本来就不存在时返回 null，不会平白造一个出来。
     * 所有人的机器都跑过一版之后，这段可以删。
     */
    private fun cancelLegacyNextDay(context: Context, manager: AlarmManager) {
        runCatching {
            val pending = PendingIntent.getBroadcast(
                context,
                LEGACY_RC_NEXT_DAY,
                Intent(context, ClassReminderReceiver::class.java)
                    .setAction(LEGACY_ACTION_NEXT_DAY),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            manager.cancel(pending)
        }
    }

    /**
     * 扫掉整个上下课边界码段。
     *
     * `PendingIntent` 的匹配只看 `(requestCode, action, data, type, …)`、**不看 extras**，
     * 所以撤销时 key 传空串也能命中同一个 —— 但 action 必须对（上课 / 下课是两条不同的 intent）。
     */
    private fun cancelBoundaries(context: Context, manager: AlarmManager) {
        for (period in 1..PeriodClock.count) {
            val rc = RC_BOUNDARY_BASE + (period - 1) * 2
            runCatching {
                manager.cancel(boundaryPending(context, rc, ACTION_CLASS_STARTED, ""))
            }
            runCatching {
                manager.cancel(boundaryPending(context, rc + 1, ACTION_CLASS_ENDED, ""))
            }
        }
    }

    /**
     * 有精确闹钟权限用 `setExactAndAllowWhileIdle`，没有就降级 `setAndAllowWhileIdle`。
     *
     * 降级后仍要 `AllowWhileIdle`：普通的 `set()` 在 Doze 里会被推到维护窗口（可能几小时后），
     * 那等于没提醒。降级后的误差是「9 分钟限频 + 系统攒批」，页面上的黄条要如实写「可能延迟」。
     */
    private fun setAlarm(
        context: Context,
        manager: AlarmManager,
        requestCode: Int,
        at: Long,
        pending: PendingIntent
    ) {
        if (canScheduleExact(context)) {
            runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }.onFailure {
                if (it is SecurityException) {
                    Log.w(TAG, "exact denied for $requestCode, fallback", it)
                    runCatching {
                        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                    }
                } else {
                    Log.w(TAG, "set exact failed for $requestCode", it)
                }
            }
            return
        }
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure { Log.w(TAG, "set idle failed for $requestCode", it) }
    }

    /**
     * 每日全窗口重同步。
     *
     * 用 `setInexactRepeating`：它的唯一职责是「让窗口往前滚」，差几分钟完全无所谓，
     * 为此占一个精确闹钟额度不值得（而且精确闹钟没授权时还不能用）。
     * `RTC_WAKEUP` 是刻意的：一天唤醒一次，换来的是一致性 —— 窗口不会因为设备整夜深度休眠
     * 而停在旧的一天。
     */
    private fun armResync(context: Context, manager: AlarmManager) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        var next = now.toLocalDate().atTime(RESYNC_TIME).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        runCatching {
            manager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                next.toInstant().toEpochMilli(),
                AlarmManager.INTERVAL_DAY,
                resyncPending(context)
            )
        }.onFailure { Log.w(TAG, "armResync failed", it) }
    }

    // ------------------------------------------------------------ PendingIntent

    private fun slotPending(context: Context, requestCode: Int, key: String?): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_ALARM)
        if (key != null) intent.putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun resyncPending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RC_RESYNC,
            Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_RESYNC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 清掉老版本残留的「60 秒后测试」闹钟（2026-10-29 该功能已删除）。
     *
     * 和 [cancelLegacyNextDay] 同一套路：代码删了，但已经排进 AlarmManager 的那条还在，
     * 到点会白唤醒一次（广播没有任何分支处理，等于空转）。按同一个
     * `(requestCode, action)` 建 PendingIntent 撤掉；`FLAG_NO_CREATE` 保证本来不存在时
     * 不会平白造一个出来。所有人的机器都跑过一版之后，这段可以删。
     */
    private fun cancelLegacyTest(context: Context, manager: AlarmManager) {
        runCatching {
            val pending = PendingIntent.getBroadcast(
                context,
                LEGACY_RC_TEST,
                Intent(context, ClassReminderReceiver::class.java)
                    .setAction(LEGACY_ACTION_TEST),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            manager.cancel(pending)
        }
    }

    /** 上课 / 下课边界（action 不同 → 必须各建一条，不能共用一个 PendingIntent） */
    private fun boundaryPending(
        context: Context,
        requestCode: Int,
        action: String,
        key: String
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ClassReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 提前量 0 时闹钟响在「上课那一刻」，系统投递本身就带几十毫秒延迟，不给宽限就永远不响 */
    private const val GRACE_WHEN_ON_TIME = 3 * 60_000L
}
