package com.lanhang.lite.schedule

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.lanhang.lite.R
import java.time.ZoneId
import java.util.TimeZone

/**
 * 把自建考试同步到系统日历（2026-11-26，设置 → 通用里可开关）。
 *
 * 两条原则：
 * ① **只写一个日历**（优先设备的「本地」/ 主日历），并且**自己记下插入了哪些事件 id** ——
 *    靠标题去匹配是不可靠的：用户可以改标题、也可能手动删。
 * ② 同步是**全量**的：先删上次插入的事件，再按当前考试列表重插。考试总量小（一学期十几场），
 *    全量换来的是「改日期一定不会留下旧事件」。
 *
 * 失败都返回 false 而不是抛异常：日历相关权限在国产 ROM 上经常被限制，
 * 一个同步失败不该让设置页崩溃。
 */
object ExamCalendarSync {

    private const val TAG = "ExamCalendarSync"

    private const val PREF = "exam_calendar"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_EVENT_IDS = "event_ids"

    /** 系统日历里的提醒提前量（分钟），与 App 内「考前 1 小时」保持一致 */
    private const val REMIND_MINUTES = 60

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** 开关落盘。关掉时顺手把已插入的事件删干净（不留一堆没人管的红点） */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clear(context)
        return true
    }

    /** 同步结果：0 成功，其余为具体原因（界面按它选文案，别再说「没有可写日历」冤枉用户） */
    const val RESULT_OK = 0
    const val RESULT_NO_PERMISSION = 1
    const val RESULT_NO_CALENDAR = 2
    const val RESULT_WRITE_FAILED = 3

    /**
     * 全量同步一次。返回 [RESULT_OK] 表示已写完（或本来就没有考试可写）。
     */
    fun sync(context: Context): Int {
        if (!isEnabled(context)) return RESULT_OK
        if (!hasPermission(context)) return RESULT_NO_PERMISSION
        val calendarId = pickCalendar(context) ?: return RESULT_NO_CALENDAR
        clear(context)
        val resolver = context.contentResolver
        val zone = TimeZone.getDefault().id
        val ids = mutableListOf<Long>()
        for (exam in LocalExamStore.all()) {
            val date = exam.localDate() ?: continue
            val start = exam.startTime() ?: continue
            val end = exam.endTime() ?: continue
            val startMs = date.atTime(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMs = date.atTime(end).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val desc = buildList {
                add(context.getString(R.string.local_exam_calendar_tag))
                exam.seat.takeIf { it.isNotBlank() }?.let {
                    add(context.getString(R.string.local_exam_seat_value, it))
                }
                exam.note.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString("\n")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(
                    CalendarContract.Events.TITLE,
                    context.getString(R.string.local_exam_calendar_title, exam.subject)
                )
                put(CalendarContract.Events.DESCRIPTION, desc)
                put(CalendarContract.Events.EVENT_LOCATION, exam.location)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = runCatching {
                resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            }.getOrNull() ?: continue
            val eventId = ContentUris.parseId(uri)
            ids += eventId
            // 提前 60 分钟提醒（系统日历自己会弹，不依赖我们的闹钟）
            runCatching {
                resolver.insert(
                    CalendarContract.Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, eventId)
                        put(CalendarContract.Reminders.MINUTES, REMIND_MINUTES)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                )
            }
        }
        prefs(context).edit().putString(KEY_EVENT_IDS, ids.joinToString(",")).apply()
        Log.i(TAG, "synced ${ids.size} exam event(s) into calendar $calendarId")
        return when {
            ids.isNotEmpty() -> RESULT_OK
            // 没有考试可写：也算成功（否则空列表时会误报失败）
            LocalExamStore.all().isEmpty() -> RESULT_OK
            else -> RESULT_WRITE_FAILED
        }
    }

    /** 删除本 App 之前插入的全部事件（按记录的 id 删；记录丢了就删不到，可接受） */
    fun clear(context: Context) {
        val raw = prefs(context).getString(KEY_EVENT_IDS, "").orEmpty()
        if (raw.isBlank()) return
        val resolver = context.contentResolver
        raw.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { id ->
            runCatching {
                resolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    null,
                    null
                )
            }
        }
        prefs(context).edit().remove(KEY_EVENT_IDS).apply()
    }

    /**
     * 选一个可写的日历。
     *
     * 🔴 这台机器（HyperOS）的日历 Provider **没有 `is_primary` 列**：带它的查询会抛
     * `SQLiteException: no such column: is_primary`，异常一吞就变成「没有可写日历」的假报错
     * （2026-11-26 用户实测，`content query` 验证过：不带该列能正常返回 3 个日历）。
     *
     * 所以按可靠性从高到低三级降级，**每一级都记日志**（下次再有类似问题，logcat 里一眼看出
     * 是哪一级返回的、还是三级都空）：
     * ① 可写 + 可见 → ② 只判可写 → ③ 不限条件（最后一级可能拿到只读日历，
     *    插入会失败，但那时报的是「写入失败」而不是「没有日历」，语义不同）。
     *
     * selection 一律用字面量拼接，不用 `?` 占位符：部分定制 provider 对参数绑定支持有差异。
     */
    private fun pickCalendar(context: Context): Long? {
        val resolver = context.contentResolver
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val writable = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        val candidates = listOf(
            "$writable AND ${CalendarContract.Calendars.VISIBLE} = 1",
            writable,
            null
        )
        for (selection in candidates) {
            val id = queryFirstId(resolver, projection, selection)
            Log.i(TAG, "pick calendar selection=[$selection] -> $id")
            if (id != null) return id
        }
        Log.w(TAG, "no calendar at all")
        return null
    }

    private fun queryFirstId(
        resolver: android.content.ContentResolver,
        projection: Array<String>,
        selection: String?
    ): Long? = runCatching {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.onFailure { Log.w(TAG, "calendar query failed: $selection", it) }.getOrNull()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
