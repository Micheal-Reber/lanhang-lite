package com.lanhang.lite.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.lanhang.lite.R
import com.lanhang.lite.campusnet.CampusNetActivity
import com.lanhang.lite.campusnet.CampusNetAuth
import com.lanhang.lite.campusnet.CampusNetStore
import com.lanhang.lite.campusnet.SrunApi
import com.lanhang.lite.core.ApiException
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.core.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 校园网小组件：桌面上一张 2×2 状态卡，点一下就能认证。
 *
 * **点击行为（用户拍板）**：
 * - App 里勾了「记住密码」→ **直接认证**，不打开 App（密码从 Keystore 里取）；
 * - 没存密码 → **跳转到 App 的校园网页**去输密码认证（小组件没有输入框）。
 *
 * **为什么后台直接认证是可接受的**：用户点小组件是**明确的前台意图**，
 * 且走 `goAsync()` 持有进程，不是「后台自动重连」——后者才是厂商 ROM 必杀的那类。
 * 认证走的是校园网内的 HTTP 门户，毫秒级返回，不会有长任务。
 *
 * 🔴 **渲染绝不做网络请求**（2026-10-26 用户反馈「App 里下线了，小组件还显示在线」时踩到）：
 * `updateAppWidget` 是在主线程调用的，OkHttp 的同步请求在主线程会抛异常；
 * 一旦把状态查询写在渲染路径里，异常被吞掉 → 小组件永远拿不到新状态，
 * 只会显示上一次缓存的「已认证」。所以：
 * - **渲染只读本地缓存**（纯本地，瞬时完成）；
 * - **状态查询一律放协程**（见 [refreshAll] / [runAuth]），查完写缓存再触发一次渲染。
 *
 * 认证流程与 App 内完全一致（[CampusNetAuth]），域探测/身份域重试这些细节不重复实现。
 */
class CampusNetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 先用缓存把界面画出来（立即可见），再异步查一次真实状态
        for (id in appWidgetIds) renderWidget(context, id)
        refreshAll(context)
        armTick(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        renderWidget(context, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        refreshAll(context)
        armTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 最后一个实例被移除 → 别再空转心跳
        cancelTick(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val manager = AppWidgetManager.getInstance(context)
        val left = manager.getAppWidgetIds(
            ComponentName(context, CampusNetWidgetProvider::class.java)
        )
        if (left.isEmpty()) cancelTick(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_AUTH -> handleAuth(context, intent)
            ACTION_LOGOUT -> handleLogout(context, intent)
            // 心跳：查一次真实状态并重绘（状态可能在别处变了）
            ACTION_TICK -> refreshAll(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                refreshAll(context)
                armTick(context)
            }
        }
    }

    /**
     * 点击「下线」。
     *
     * **不做二次确认**（用户 2026-10-26 拍板）：点一下直接下线。
     * 之前为了二次确认引入过一个透明的确认页，绕了一圈还是让人多点一次，直接执行更利落。
     *
     * 下线是破坏性操作（会断掉本机网络），所以必须**执行成功才改状态**：
     * 失败时保留原状态（仍显示「已认证」），并把失败原因写进副标题，
     * 否则用户会看到「已认证」却其实已经断网，或反过来看到「未认证」但其实还在线。
     */
    private fun handleLogout(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                renderWidget(context, appWidgetId, busy = CampusNetWidgetData.BusyKind.LOGOUT)
                val ctx = LocaleHelper.wrap(context.applicationContext)
                try {
                    CampusNetAuth.logout(context.applicationContext, CampusNetStore.username)
                    Log.d(TAG, "logout ok")
                    // 下线是确定的结果：立刻把小组件刷成「未认证」，
                    // 不用等下一次心跳（否则桌面上的小卡片还会挂着旧的「已认证」）
                    CampusNetWidgetData.save(
                        context.applicationContext,
                        CampusNetWidgetData.Cached(state = CampusNetWidgetData.State.OFFLINE)
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "logout failed", e)
                    // 失败：状态不变（可能还在线上），但把原因告诉用户
                    val detail = (e as? ApiException)?.text?.resolve(ctx) ?: e.message.orEmpty()
                    CampusNetWidgetData.save(
                        context.applicationContext,
                        CampusNetWidgetData.Cached(
                            state = CampusNetWidgetData.State.ONLINE,
                            failure = detail,
                        )
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "logout crashed", e)
            } finally {
                renderWidget(context, appWidgetId)
                pending.finish()
            }
        }
    }

    /**
     * 点击「一键认证」。
     *
     * 用 `goAsync()` 持有进程跑完网络请求（约 1~3s），期间把小组件画成「认证中」。
     */
    private fun handleAuth(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 没存密码 → 没有输入框可用，跳 App 让用户输密码
                if (!CampusNetWidgetData.canAuthenticateDirectly(context)) {
                    openCampusNetPage(context)
                    return@launch
                }
                // 先画「认证中」，让用户立刻看到反馈（认证要 1~3s）
                renderWidget(
                    context,
                    appWidgetId,
                    busy = CampusNetWidgetData.BusyKind.AUTH,
                )
                runAuth(context, appWidgetId)
            } catch (e: Throwable) {
                Log.e(TAG, "auth failed", e)
                runCatching { renderWidget(context, appWidgetId) }
            } finally {
                pending.finish()
            }
        }
    }

    /** 执行认证并把结果刷到小组件上 */
    private suspend fun runAuth(context: Context, appWidgetId: Int) {
        val app = context.applicationContext
        val password = CampusNetStore.password()
        val username = CampusNetStore.username
        if (password.isNullOrEmpty() || username.isBlank()) {
            // 存着的密码没了（Keystore 失效 / 被清了）→ 只能跳 App
            openCampusNetPage(context)
            return
        }
        val ctx = LocaleHelper.wrap(app)
        try {
            val result = CampusNetAuth.authenticate(app, username, password)
            Log.d(TAG, "auth ok: ${result.message} domain=${result.domain}")
            // 认证接口回 ok 就说明已经上线了，**不要再查一次状态接口**：
            // 认证刚完成时门户的状态可能还没刷新（实测会回 not_online），
            // 拿它当权威会把明明成功的认证写成「未认证」。
            // IP/在线时长留空，下一次心跳自然会补上。
            CampusNetWidgetData.save(
                app,
                CampusNetWidgetData.Cached(state = CampusNetWidgetData.State.ONLINE)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "auth error", e)
            // 失败：把错误文案（已按错误码翻译）写到缓存里
            val detail = (e as? ApiException)?.text?.resolve(ctx) ?: e.message.orEmpty()
            CampusNetWidgetData.save(
                app,
                CampusNetWidgetData.Cached(
                    state = CampusNetWidgetData.State.FAILED,
                    failure = detail,
                )
            )
        }
        renderWidget(context, appWidgetId)
    }

    /** 跳转到 App 的校园网页（用户在那里输密码认证） */
    private fun openCampusNetPage(context: Context) {
        val intent = Intent(context, CampusNetActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "打开校园网页失败", it) }
    }

    companion object {
        private const val TAG = "CampusNetWidget"

        /** 点击认证的广播 action */
        const val ACTION_AUTH = "com.lanhang.lite.widget.CAMPUS_NET_AUTH"

        /** 点击下线的广播 action（点一下直接下线，不做二次确认） */
        const val ACTION_LOGOUT = "com.lanhang.lite.widget.CAMPUS_NET_LOGOUT"

        /** 状态心跳的广播 action */
        const val ACTION_TICK = "com.lanhang.lite.widget.CAMPUS_NET_TICK"

        /** 广播 PendingIntent 的 requestCode */
        private const val AUTH_REQUEST = 4500

        /** 心跳闹钟的 requestCode */
        private const val TICK_REQUEST = 4510

        /**
         * 状态心跳间隔（10 分钟）。
         *
         * **为什么需要心跳**：用户可能在**别的地方**把网线下了（在别的设备、网页版门户、
         * 或校园网自动重连后重新分配了 IP），本机小组件会一直显示上一次的「已认证」。
         * 10 分钟一次足够——这个状态本身不要求实时，而小组件本来也不会自动重绘。
         *
         * 用 `ELAPSED_REALTIME`（非 WAKEUP）：熄屏时不唤醒设备，亮屏后补一次；
         * 因此不需要 `SCHEDULE_EXACT_ALARM` 权限，也不会在兜里耗电。
         */
        private const val TICK_INTERVAL_MS = 10L * 60L * 1000L

        /** 查在线状态的超时（毫秒）。校园网内是毫秒级，超时只是兜底 */
        private const val STATUS_TIMEOUT_MS = 5_000L

        /**
         * 排下一次状态心跳（链式单次闹钟）。
         *
         * 没有小组件实例时**不排**——避免为了一个不存在的组件空转。
         */
        fun armTick(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(
                ComponentName(app, CampusNetWidgetProvider::class.java)
            )
            if (ids.isEmpty()) {
                cancelTick(app)
                return
            }
            val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + TICK_INTERVAL_MS,
                    tickPendingIntent(app)
                )
            }.onFailure { Log.w(TAG, "armTick failed", it) }
        }

        fun cancelTick(context: Context) {
            val app = context.applicationContext
            val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching { alarmManager.cancel(tickPendingIntent(app)) }
        }

        private fun tickPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, CampusNetWidgetProvider::class.java).setAction(ACTION_TICK)
            return PendingIntent.getBroadcast(
                context,
                TICK_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * 刷新所有实例：查一次真实状态 → 写缓存 → 重绘。
         *
         * **App 里认证/下线成功后也要调它**——否则小组件会一直显示上一次的状态
         *（2026-10-26 用户反馈「App 里下线了，小组件还是在线的页面」）。
         *
         * 走协程：状态查询是同步网络请求，不能放主线程。
         */
        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(
                ComponentName(app, CampusNetWidgetProvider::class.java)
            ) ?: return
            if (ids.isEmpty()) return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val status = queryStatus(app)
                    saveStatus(app, status)
                } catch (e: Throwable) {
                    Log.e(TAG, "refreshAll failed", e)
                }
                for (id in ids) renderWidget(app, id)
            }
            // 链式续排下一次心跳
            armTick(app)
        }

        /**
         * 查一次在线状态并写进缓存。
         *
         * **区分「查不到」和「确实没在线」**（这是小组件状态准不准的关键）：
         * - `status == null` → **连不上门户**（不在校园网 / 网络断了 / 刚重连还没通）。
         *   这时**保留原状态**不动——查不到不等于没在线，否则在电梯里掏手机看一眼，
         *   小组件就从「已认证」跳成「未认证」，纯属误导。
         * - `status.online == false` → 门户明确回了 `not_online_error`，**确实没在线**，置 OFFLINE。
         * - `status.online == true` → 在线，写入 IP 与在线时长。
         *
         * **必须在协程里调**（`SrunApi.status` 是同步网络请求）。
         */
        suspend fun saveStatus(context: Context, status: SrunApi.NetStatus?) {
            val app = context.applicationContext
            if (status == null) {
                // 连不上门户：保留原状态（可能是还在校园网内、只是这次没查通）
                Log.d(TAG, "saveStatus: 连不上门户，保留原状态")
                return
            }
            if (!status.online) {
                CampusNetWidgetData.save(
                    app,
                    CampusNetWidgetData.Cached(state = CampusNetWidgetData.State.OFFLINE)
                )
                return
            }
            CampusNetWidgetData.save(
                app,
                CampusNetWidgetData.Cached(
                    state = CampusNetWidgetData.State.ONLINE,
                    ip = status.ip,
                    onlineSeconds = status.onlineSeconds,
                )
            )
        }

        /** 查一次在线状态；连不上门户返回 null */
        suspend fun queryStatus(context: Context): SrunApi.NetStatus? =
            withTimeoutOrNull(STATUS_TIMEOUT_MS) {
                runCatching { SrunApi.status(context) }.getOrNull()
            }

        /**
         * 用**已知的权威状态**同步小组件（不发起网络请求）。
         *
         * App 的校园网页刷新状态时顺手调它：那边刚查过门户，状态是准的，
         * 不必让小组件再查一次。
         *
         * @param status 门户状态；null = 连不上门户 → **保留原状态**（查不到 ≠ 没在线）
         */
        fun syncStatus(context: Context, status: SrunApi.NetStatus?) {
            val app = context.applicationContext
            if (status == null) return
            if (status.online) {
                CampusNetWidgetData.save(
                    app,
                    CampusNetWidgetData.Cached(
                        state = CampusNetWidgetData.State.ONLINE,
                        ip = status.ip,
                        onlineSeconds = status.onlineSeconds,
                    )
                )
            } else {
                CampusNetWidgetData.save(
                    app,
                    CampusNetWidgetData.Cached(state = CampusNetWidgetData.State.OFFLINE)
                )
            }
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(
                ComponentName(app, CampusNetWidgetProvider::class.java)
            ) ?: return
            for (id in ids) renderWidget(app, id)
        }

        /**
         * 标记为「未认证」并立即重绘所有实例。
         *
         * **App 里下线成功后调它**：下线是确定的结果，不必再查一次网络确认。
         */
        fun markOffline(context: Context) {
            val app = context.applicationContext
            CampusNetWidgetData.save(
                app,
                CampusNetWidgetData.Cached(state = CampusNetWidgetData.State.OFFLINE)
            )
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(
                ComponentName(app, CampusNetWidgetProvider::class.java)
            ) ?: return
            for (id in ids) renderWidget(app, id)
        }

        /**
         * 重绘一个实例。**只读本地缓存，不做任何网络请求。**
         *
         * @param busy 正在进行的操作（画「认证中」/「下线中」）
         */
        fun renderWidget(
            context: Context,
            appWidgetId: Int,
            busy: CampusNetWidgetData.BusyKind = CampusNetWidgetData.BusyKind.NONE,
        ) {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val app = context.applicationContext
            runCatching {
                val manager = AppWidgetManager.getInstance(app)
                val (wDp, hDp) = sizeDp(manager.getAppWidgetOptions(appWidgetId))
                val ctx = LocaleHelper.wrap(app)
                val snapshot = CampusNetWidgetData.build(
                    app,
                    CampusNetWidgetData.load(app),
                    busy = busy,
                )

                val views = RemoteViews(app.packageName, R.layout.widget_campus_net)
                views.setImageViewBitmap(
                    R.id.widget_bitmap,
                    CampusNetWidgetRenderer.render(app, snapshot, wDp, hDp)
                )

                // 按钮：浅/深 × 认证/下线 共 4 组 View，只切可见性
                // （禁止 setInt 反射调 setBackgroundResource / setTextColor，launcher 端可能静默拒绝）
                val dark = CampusNetWidgetRenderer.isDark(ctx)
                // 已认证时按钮变「下线」：用户在桌面就能直接下线，不必进 App。
                // **不做二次确认**（用户 2026-10-26 拍板）：点一下直接下线。
                val online = snapshot.state == CampusNetWidgetData.State.ONLINE
                val authLabel = ctx.getString(
                    when (busy) {
                        CampusNetWidgetData.BusyKind.AUTH ->
                            R.string.widget_campus_button_busy
                        else -> R.string.widget_campus_button_auth
                    }
                )
                val logoutLabel = ctx.getString(
                    when (busy) {
                        CampusNetWidgetData.BusyKind.LOGOUT ->
                            R.string.widget_campus_button_logout_busy
                        else -> R.string.widget_campus_button_offline
                    }
                )
                views.setTextViewText(R.id.widget_campus_button_auth_light, authLabel)
                views.setTextViewText(R.id.widget_campus_button_auth_dark, authLabel)
                views.setTextViewText(R.id.widget_campus_button_logout_light, logoutLabel)
                views.setTextViewText(R.id.widget_campus_button_logout_dark, logoutLabel)
                // 进行中：按钮不可点（避免并发点多次，服务端有失败次数限制）
                val buttonVisible = busy == CampusNetWidgetData.BusyKind.NONE
                val showAuth = buttonVisible && !online
                val showLogout = buttonVisible && online
                views.setViewVisibility(
                    R.id.widget_campus_button_auth_light,
                    if (showAuth && !dark) View.VISIBLE else View.GONE
                )
                views.setViewVisibility(
                    R.id.widget_campus_button_auth_dark,
                    if (showAuth && dark) View.VISIBLE else View.GONE
                )
                views.setViewVisibility(
                    R.id.widget_campus_button_logout_light,
                    if (showLogout && !dark) View.VISIBLE else View.GONE
                )
                views.setViewVisibility(
                    R.id.widget_campus_button_logout_dark,
                    if (showLogout && dark) View.VISIBLE else View.GONE
                )

                // 点击：已认证 → 直接下线；未认证 → 直接认证
                val authIntent = authPendingIntent(app, appWidgetId)
                val logoutIntent = logoutPendingIntent(app, appWidgetId)
                views.setOnClickPendingIntent(R.id.widget_campus_button_auth_light, authIntent)
                views.setOnClickPendingIntent(R.id.widget_campus_button_auth_dark, authIntent)
                views.setOnClickPendingIntent(R.id.widget_campus_button_logout_light, logoutIntent)
                views.setOnClickPendingIntent(R.id.widget_campus_button_logout_dark, logoutIntent)
                // 整块区域点击 = 打开 App 的校园网页（想在 App 里看详情/输密码的人）
                views.setOnClickPendingIntent(R.id.widget_bitmap, openAppIntent(app))
                views.setContentDescription(
                    R.id.widget_bitmap,
                    ctx.getString(R.string.widget_campus_description)
                )
                manager.updateAppWidget(appWidgetId, views)
            }.onFailure { Log.e(TAG, "renderWidget failed id=$appWidgetId", it) }
        }

        /** 认证广播的 PendingIntent */
        private fun authPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, CampusNetWidgetProvider::class.java).apply {
                action = ACTION_AUTH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                AUTH_REQUEST + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** 打开 App 校园网页的 PendingIntent */
        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, CampusNetActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                AUTH_REQUEST + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * 下线广播的 PendingIntent。
         *
         * 「下线」按钮点一下直接执行（用户 2026-10-26 拍板，不要二次确认），
         * 所以走广播进 Provider —— 和「一键认证」同一条路径，不需要跳任何页面。
         */
        private fun logoutPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, CampusNetWidgetProvider::class.java).apply {
                action = ACTION_LOGOUT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                AUTH_REQUEST + 2 + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** 组件尺寸（dp） */
        private fun sizeDp(options: Bundle): Pair<Int, Int> {
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            return w.coerceAtLeast(40) to h.coerceAtLeast(40)
        }
    }
}
