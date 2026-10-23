package com.lanhang.lite.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.lanhang.lite.R
import com.lanhang.lite.schedule.PeriodClock
import com.lanhang.lite.schedule.ScheduleRepository
import com.lanhang.lite.session.Session
import com.lanhang.lite.ui.LoginActivity
import com.lanhang.lite.ui.MainActivity
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

object ScheduleWidgetUpdater {

    const val ACTION_MIDNIGHT = "com.lanhang.lite.widget.ACTION_MIDNIGHT"

    /** 「剩 N 分」倒计时的分钟心跳（由 updateAll/updateWidget 排下一次） */
    const val ACTION_TICK = "com.lanhang.lite.widget.ACTION_TICK"

    // ── 2×2「今日课程」翻天导航（PendingIntent 广播 action） ──
    const val ACTION_PREV_DAY = "com.lanhang.lite.widget.TODAY_NAV_PREV"
    const val ACTION_NEXT_DAY = "com.lanhang.lite.widget.TODAY_NAV_NEXT"
    const val ACTION_RESET_DAY = "com.lanhang.lite.widget.TODAY_NAV_RESET"

    private const val TAG = "ScheduleWidget"
    private const val ALARM_REQUEST = 4101
    private const val CLICK_REQUEST = 4200

    /** 倒计时心跳的 PendingIntent requestCode（同一个复用，天然去重） */
    private const val TICK_REQUEST = 4410

    /** 上课期间的重绘间隔：一分钟一次就够（「剩 N 分」按分钟跳） */
    private const val TICK_INTERVAL_MS = 60_000L

    /** 导航按钮的 requestCode 基数：同一实例的 prev/next/reset 必须互不相同 */
    private const val NAV_REQUEST_BASE = 4300

    /** 生成式预览的本地限频（系统侧还有 ~2 次/小时 的硬限） */
    private const val PREVIEW_PUSH_INTERVAL_MS = 12L * 60L * 60L * 1000L
    private const val PREVIEW_SMALL_DP = 154
    private const val PREVIEW_WEEK_DP = 320

    /** 全部已注册变体（组件 → isSmall）— 刷新广播/更新统一遍历 */
    private val allVariants = listOf(
        ScheduleWidgetProvider::class.java to false,
        ScheduleWidgetSmallProvider::class.java to true
    )

    /** 已放置的小部件实例总数（两组件合计）— 午夜闹钟存活判定用 */
    fun activeCount(context: Context): Int {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        return allVariants.sumOf { (cls, _) ->
            manager.getAppWidgetIds(ComponentName(app, cls))?.size ?: 0
        }
    }

    /**
     * @param pushPreview 是否顺带推「生成式预览」（每分钟的心跳刷新没必要推，系统侧限频）
     */
    fun updateAll(context: Context, pushPreview: Boolean = true) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val snapshot = runCatching { ScheduleWidgetData.load() }.getOrNull()
        for ((cls, isSmall) in allVariants) {
            val ids = manager.getAppWidgetIds(ComponentName(app, cls)) ?: continue
            for (id in ids) {
                runCatching { updateWidget(app, manager, id, snapshot, isSmall) }
                    .onFailure { Log.e(TAG, "updateAll failed id=$id", it) }
            }
        }
        if (pushPreview) pushGeneratedPreview(app, manager, snapshot)
        refreshTick(app)
    }

    /**
     * 排下一次「分钟心跳」。
     *
     * 小部件不会自己重绘，而「剩 N 分」要跟着时钟走，所以用**链式单次闹钟**：
     * - 没放今日小部件 / 没登录 / 实例都翻到了别的日期 → 取消，不空转
     * - 正在上课 → 60s 后刷（到下课那一刻会精确触发一次：这一刷让上完的课消失、后面的课顶上）
     * - 还没上课 → 排到下一节上课时刻，顺便开始倒计时
     * - 今天全上完 → 取消
     *
     * 用 [AlarmManager.ELAPSED_REALTIME]（非 WAKEUP）：熄屏时不唤醒设备，亮屏后补一次；
     * 因此不需要 SCHEDULE_EXACT_ALARM 权限，也不会在兜里耗电。
     */
    fun refreshTick(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(
            ComponentName(app, ScheduleWidgetSmallProvider::class.java)
        )
        if (ids == null || ids.isEmpty() || !Session.isLoggedIn) {
            cancelTick(app)
            return
        }
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val showsToday = ids.any {
            runCatching { TodayNavStore.target(app, it, today) }.getOrNull() == today
        }
        if (!showsToday) {
            cancelTick(app)
            return
        }
        val courses = runCatching { ScheduleWidgetData.load(today) }.getOrNull()
            ?.days?.firstOrNull { it.date == today }?.courses.orEmpty()
        val nowSecond = now.toLocalTime().toSecondOfDay()
        val pending = courses.filter { PeriodClock.endMinute(it.end) * 60 > nowSecond }
        if (pending.isEmpty()) {
            cancelTick(app)
            return
        }
        val ongoing = pending.firstOrNull {
            PeriodClock.startMinute(it.start) * 60 <= nowSecond &&
                nowSecond < PeriodClock.endMinute(it.end) * 60
        }
        val delayMs = if (ongoing != null) {
            // 平时的 60s 一跳；离下课不足 60s 就精确排在下课那一刻
            val endSecond = PeriodClock.endMinute(ongoing.end) * 60
            ((endSecond - nowSecond) * 1_000L + 1_000L)
                .coerceIn(5_000L, TICK_INTERVAL_MS)
        } else {
            val nextStart = pending.map { PeriodClock.startMinute(it.start) * 60 }
                .filter { it > nowSecond }
                .minOrNull()
            if (nextStart == null) {
                cancelTick(app)
                return
            }
            ((nextStart - nowSecond) * 1_000L).coerceAtLeast(TICK_INTERVAL_MS)
        }
        armTick(app, delayMs)
    }

    private fun armTick(context: Context, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + delayMs,
                tickPendingIntent(context)
            )
        }.onFailure { Log.w(TAG, "armTick failed delay=$delayMs", it) }
    }

    fun cancelTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarmManager.cancel(tickPendingIntent(context)) }
    }

    private fun tickPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleWidgetProvider::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            TICK_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Android 15+「生成的预览」（官方规范 developer.android.com/develop/ui/views/appwidgets/previews）：
     * 选择器优先展示 App 通过 setWidgetPreview 推上来的 RemoteViews，而不是静态
     * previewImage / previewLayout，因此这里把真实渲染结果推一份上去。
     *
     * 系统对该 API 限频（约 2 次/小时），本地再限到 12 小时一次，避免无效调用；
     * 更新失败不影响桌面小部件本身。
     */
    private fun pushGeneratedPreview(
        context: Context,
        manager: AppWidgetManager,
        snapshot: ScheduleWidgetData.WeekSnapshot?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (snapshot == null) return
        val now = System.currentTimeMillis()
        if (now - Session.widgetPreviewPushedAt < PREVIEW_PUSH_INTERVAL_MS) return
        var pushed = false
        for ((cls, isSmall) in allVariants) {
            val ok = runCatching {
                val bitmap = if (isSmall) {
                    ScheduleWidgetRenderer.renderToday(context, snapshot, PREVIEW_SMALL_DP, PREVIEW_SMALL_DP)
                } else {
                    ScheduleWidgetRenderer.render(context, snapshot, PREVIEW_WEEK_DP, PREVIEW_WEEK_DP)
                }
                val views = RemoteViews(context.packageName, R.layout.widget_schedule_week)
                views.setImageViewBitmap(R.id.widget_bitmap, bitmap)
                if (isSmall) {
                    // 预览也要带 ◀ ⟳ ▶，否则选择器里看到的和真实放置后不一致
                    // 预览即「今天」，所以复位按钮按禁用态呈现（与真实放置初始状态一致）
                    bindNavButtons(context, views, 0, true, navigated = false)
                }
                manager.setWidgetPreview(
                    ComponentName(context, cls),
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                    views
                )
            }.onFailure { Log.w(TAG, "setWidgetPreview failed: ${cls.simpleName}", it) }.isSuccess
            pushed = pushed || ok
        }
        if (pushed) Session.widgetPreviewPushedAt = now
    }

    fun updateWidget(context: Context, appWidgetId: Int) {
        val app = context.applicationContext
        runCatching {
            val manager = AppWidgetManager.getInstance(app)
            val snapshot = runCatching { ScheduleWidgetData.load() }.getOrNull()
            updateWidget(app, manager, appWidgetId, snapshot, isSmallInstance(manager, appWidgetId))
        }.onFailure { Log.e(TAG, "updateWidget failed id=$appWidgetId", it) }
        refreshTick(app)
    }

    private fun isSmallInstance(manager: AppWidgetManager, appWidgetId: Int): Boolean =
        runCatching {
            manager.getAppWidgetInfo(appWidgetId)?.provider?.className ==
                ScheduleWidgetSmallProvider::class.java.name
        }.getOrDefault(false)

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: ScheduleWidgetData.WeekSnapshot?,
        isSmall: Boolean
    ) {
        val (wDp, hDp) = sizeDp(manager.getAppWidgetOptions(appWidgetId))
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_week)
        if (isSmall) {
            // 每个实例自己记着「翻到哪一天」，所以快照要按目标日期单读（可能跨周）
            val targetDate = TodayNavStore.target(context, appWidgetId)
            val daySnapshot = runCatching { ScheduleWidgetData.load(targetDate) }
                .getOrNull() ?: snapshot
            views.setImageViewBitmap(
                R.id.widget_bitmap,
                ScheduleWidgetRenderer.renderToday(context, daySnapshot, wDp, hDp, targetDate)
            )
            val showNav = daySnapshot != null &&
                hDp >= ScheduleWidgetRenderer.TODAY_NAV_MIN_HEIGHT_DP
            // 已经翻到别的日期 → 复位按钮高亮可点；就在今天 → 置灰禁用
            val navigated = targetDate != LocalDate.now()
            bindNavButtons(context, views, appWidgetId, showNav, navigated)
        } else {
            views.setImageViewBitmap(
                R.id.widget_bitmap,
                ScheduleWidgetRenderer.render(context, snapshot, wDp, hDp)
            )
            views.setViewVisibility(R.id.widget_nav_prev, View.GONE)
            views.setViewVisibility(R.id.widget_nav_reset, View.GONE)
            views.setViewVisibility(R.id.widget_nav_next, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.widget_bitmap, clickPendingIntent(context, appWidgetId))
        views.setContentDescription(
            R.id.widget_bitmap,
            context.getString(R.string.widget_open_description)
        )
        manager.updateAppWidget(appWidgetId, views)
    }

    /**
     * 绑定右下角 ◀ ⟳ ▶ 三个点击区：
     * 圆角底色画在位图里（见 [ScheduleWidgetRenderer.drawNavButtons]），这里只切图标
     * （浅/深 × 复位可用/禁用）并挂 PendingIntent。每个按钮用 (实例 id, 序号) 生成唯一
     * requestCode，否则会互相覆盖。
     *
     * @param navigated 该实例是否已翻到别的日期。复位按钮在「就在今天」时是禁用态：
     *   图标置灰 + 点击不做任何事（见 [handleNavAction]），但**仍然挂热区**，
     *   否则点它会穿透到底图去打开 App，与「禁用」的观感矛盾。
     */
    private fun bindNavButtons(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        visible: Boolean,
        navigated: Boolean
    ) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widget_nav_prev, visibility)
        views.setViewVisibility(R.id.widget_nav_reset, visibility)
        views.setViewVisibility(R.id.widget_nav_next, visibility)
        if (!visible) return

        val dark = ScheduleWidgetRenderer.isDark(context)
        views.setImageViewResource(
            R.id.widget_nav_prev,
            if (dark) R.drawable.ic_widget_nav_left_on_dark else R.drawable.ic_widget_nav_left
        )
        views.setImageViewResource(
            R.id.widget_nav_next,
            if (dark) R.drawable.ic_widget_nav_right_on_dark else R.drawable.ic_widget_nav_right
        )
        views.setImageViewResource(
            R.id.widget_nav_reset,
            when {
                navigated && dark -> R.drawable.ic_widget_nav_reset_on_dark
                navigated -> R.drawable.ic_widget_nav_reset
                dark -> R.drawable.ic_widget_nav_reset_dim_on_dark
                else -> R.drawable.ic_widget_nav_reset_dim
            }
        )
        views.setContentDescription(
            R.id.widget_nav_reset,
            context.getString(
                if (navigated) R.string.widget_nav_reset else R.string.widget_nav_reset_disabled
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_nav_prev,
            navPendingIntent(context, appWidgetId, ACTION_PREV_DAY, 0)
        )
        views.setOnClickPendingIntent(
            R.id.widget_nav_reset,
            navPendingIntent(context, appWidgetId, ACTION_RESET_DAY, 2)
        )
        views.setOnClickPendingIntent(
            R.id.widget_nav_next,
            navPendingIntent(context, appWidgetId, ACTION_NEXT_DAY, 1)
        )
    }

    private fun navPendingIntent(
        context: Context,
        appWidgetId: Int,
        action: String,
        ordinal: Int
    ): PendingIntent {
        val intent = Intent(context, ScheduleWidgetSmallProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            NAV_REQUEST_BASE + appWidgetId * 3 + ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 翻天点击的落地逻辑（由 Provider 在 goAsync 里调用）：
     * 1. 按 action 推进/复位该实例的日期状态
     * 2. 立即重推该实例（本地缓存能满足大部分情况）
     * 3. 跨周且目标周没有缓存时补拉一次，再刷一次
     */
    suspend fun handleNavAction(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val today = LocalDate.now()
        Log.d(TAG, "nav action=$action id=$appWidgetId today=$today")
        when (action) {
            ACTION_PREV_DAY -> TodayNavStore.shift(context, appWidgetId, -1L, today)
            ACTION_NEXT_DAY -> TodayNavStore.shift(context, appWidgetId, 1L, today)
            ACTION_RESET_DAY -> {
                // 已经在今天：按钮处于禁用态，点击不产生任何变化（不重绘、不补拉）
                if (TodayNavStore.target(context, appWidgetId, today) == today) return
                TodayNavStore.reset(context, appWidgetId)
            }
            else -> return
        }
        updateWidget(context, appWidgetId)
        val target = TodayNavStore.target(context, appWidgetId, today)
        val week = ScheduleWidgetData.targetWeekOf(target) ?: return
        val currentWeek = runCatching { ScheduleWidgetData.load()?.week }.getOrNull()
        val missing = ScheduleRepository.loadCachedWeek(week) == null && week != currentWeek
        if (!missing) return
        // goAsync 只有 ~10s；网络补拉加超时兜底，失败就保持当前渲染
        withTimeoutOrNull(6_000L) {
            runCatching { ScheduleRepository.refresh(week, updateWidget = false) }
                .onFailure { Log.w(TAG, "nav fetch week=$week failed", it) }
        }
        updateWidget(context, appWidgetId)
    }

    private fun sizeDp(options: Bundle): Pair<Int, Int> {
        val hintW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val hintH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.getParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    SizeF::class.java
                )?.map { it.width to it.height }.orEmpty()
            } else {
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                (options.getParcelableArrayList<SizeF>(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES
                )?.map { it.width to it.height }).orEmpty()
            }
        } else {
            emptyList()
        }
        pickSize(sizes, hintW, hintH)?.let { picked ->
            return picked.first.roundToInt().coerceAtLeast(1) to
                picked.second.roundToInt().coerceAtLeast(1)
        }
        val width = hintW.takeIf { it > 0 }
            ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 }
            ?: 320
        val height = hintH.takeIf { it > 0 }
            ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }
            ?: 320
        return width to height
    }

    private fun pickSize(
        sizes: List<Pair<Float, Float>>,
        hintW: Int,
        hintH: Int
    ): Pair<Float, Float>? {
        val valid = sizes.filter { it.first > 0f && it.second > 0f }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first()
        val byWidth = valid.sortedByDescending { it.first }
        if (valid.size == 2 && abs(byWidth[0].first - byWidth[1].first) <= 2f) {
            return if (byWidth[0].second <= byWidth[1].second) byWidth[0] else byWidth[1]
        }
        if (hintW > 0 && hintH > 0) {
            return valid.minWithOrNull(
                compareBy<Pair<Float, Float>> {
                    abs(it.first - hintW) + abs(it.second - hintH)
                }.thenBy { it.first * it.second }
            )
        }
        return valid.minWithOrNull(
            compareBy<Pair<Float, Float>> { if (it.second >= it.first) 0 else 1 }
                .thenByDescending { it.first }
                .thenBy { it.second }
        )
    }

    fun scheduleMidnight(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            next.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            midnightPendingIntent(app)
        )
    }

    fun cancelMidnight(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(midnightPendingIntent(app))
    }

    private fun midnightPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleWidgetProvider::class.java)
            .setAction(ACTION_MIDNIGHT)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun clickPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = if (Session.isLoggedIn) {
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        } else {
            Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            CLICK_REQUEST + appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
