package com.lanhang.lite.widget

import android.content.Context
import com.lanhang.lite.campusnet.CampusNetStore
import com.lanhang.lite.core.LocaleHelper
import java.util.concurrent.TimeUnit

/**
 * 校园网小组件的状态快照。
 *
 * **为什么单独一层**：小组件的 RemoteViews 拿不到资源引用（`TextRef`），
 * 只能在 Data 层把文案 resolve 成 String 再交出去（与 [ScheduleWidgetData] 同一约定）。
 *
 * **状态与文案分开存**（2026-10-26 用户反馈「App 里下线了，小组件还显示在线」时定的）：
 * - 缓存里存的是**结构化状态**（枚举 + IP + 在线秒数 + 失败原因）；
 * - 文案在渲染时按**当前语言**现算。
 *
 * 为什么不缓存成品文案：缓存里的文案是写入那一刻的语言，用户中途切语言后，
 * 小组件会一直显示旧语言的文字；而「已认证 / 未认证」这种状态判定必须可靠，
 * 不能靠文案字符串去猜（否则 App 里下线后小组件还显示「已认证」）。
 *
 * 状态从哪来：
 * - 每次认证/下线后写入本地缓存（SP），小组件重绘时直接读——**不联网也能显示**；
 * - 缓存里没有就当「未认证」。
 */
object CampusNetWidgetData {

    /** 小组件上的状态 */
    enum class State {
        /** 未认证（也没在校园网上） */
        OFFLINE,

        /** 已认证 */
        ONLINE,

        /** 正在认证 */
        BUSY,

        /** 认证失败（带原因） */
        FAILED,
    }

    /**
     * 正在执行的耗时操作（渲染时**覆盖**缓存状态）。
     *
     * 认证和下线都要联网（1~3s），期间必须让用户看到「在干什么」，
     * 两种操作的文案不一样，所以不能只用布尔值。
     */
    enum class BusyKind {
        /** 没有进行中的操作 */
        NONE,

        /** 正在认证 */
        AUTH,

        /** 正在下线 */
        LOGOUT,
    }

    /**
     * 缓存里的结构化状态。
     *
     * @param state 状态枚举（判定「是否在线」只看这个）
     * @param ip 在线时的 IP
     * @param onlineSeconds 在线时长（秒）
     * @param failure 上次失败的原因（已翻译的文案，无法再本地化）
     */
    data class Cached(
        val state: State,
        val ip: String? = null,
        val onlineSeconds: Long = 0,
        val failure: String? = null,
    )

    /**
     * 一次状态快照（**文案已 resolve 成 String**，可以直接交给 RemoteViews）。
     *
     * @param title 主标题（如「已认证」）
     * @param detail 副标题（在线时长 / 错误原因 / 操作提示）
     * @param state 状态枚举，决定按钮文案与可点性
     */
    data class Snapshot(
        val title: String,
        val detail: String,
        val state: State,
    )

    private const val FILE = "lanhang_campus_widget"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 写状态缓存。
     *
     * 用 SP 而不是内存：小组件进程随时可能被回收，内存态一丢，用户看到的就回到「未认证」。
     */
    fun save(context: Context, cached: Cached) {
        sp(context).edit()
            .putString("state", cached.state.name)
            .putString("ip", cached.ip)
            .putLong("online_seconds", cached.onlineSeconds)
            .putString("failure", cached.failure)
            // 清掉旧版遗留的成品文案件（title/detail）：文案改成渲染时现算了，
            // 留着它们只会让人误以为状态还从这两个键里读。
            .remove("title")
            .remove("detail")
            .apply()
    }

    /** 读状态缓存；没有缓存返回 null（= 从没认证过）。 */
    fun load(context: Context): Cached? {
        val sp = sp(context)
        val name = sp.getString("state", null) ?: return null
        val state = runCatching { State.valueOf(name) }.getOrDefault(State.OFFLINE)
        return Cached(
            state = state,
            ip = sp.getString("ip", null),
            onlineSeconds = sp.getLong("online_seconds", 0L),
            failure = sp.getString("failure", null),
        )
    }

    fun clear(context: Context) {
        sp(context).edit().clear().apply()
    }

    /**
     * 把结构化状态渲染成带文案的快照（**文案在这里现算，跟随应用语言**）。
     *
     * @param cached 缓存里的结构化状态（null = 还没认证过）
     * @param busy 正在进行的操作（覆盖缓存状态）
     */
    fun build(
        context: Context,
        cached: Cached?,
        busy: BusyKind = BusyKind.NONE,
    ): Snapshot {
        val ctx = LocaleHelper.wrap(context)
        when (busy) {
            BusyKind.AUTH -> return Snapshot(
                title = ctx.getString(com.lanhang.lite.R.string.widget_campus_busy),
                detail = ctx.getString(com.lanhang.lite.R.string.widget_campus_busy_detail),
                state = State.BUSY,
            )
            BusyKind.LOGOUT -> return Snapshot(
                title = ctx.getString(com.lanhang.lite.R.string.widget_campus_logout_busy),
                detail = ctx.getString(com.lanhang.lite.R.string.widget_campus_logout_busy_detail),
                state = State.BUSY,
            )
            BusyKind.NONE -> Unit
        }
        if (cached == null) {
            return Snapshot(
                title = ctx.getString(com.lanhang.lite.R.string.widget_campus_offline),
                detail = ctx.getString(com.lanhang.lite.R.string.widget_campus_offline_detail),
                state = State.OFFLINE,
            )
        }
        return when (cached.state) {
            State.ONLINE -> Snapshot(
                title = ctx.getString(com.lanhang.lite.R.string.widget_campus_online),
                detail = onlineDetail(ctx, cached),
                state = State.ONLINE,
            )
            // 失败原因没法本地化（是服务端错误码翻译出来的文案），原样用
            State.FAILED -> Snapshot(
                title = ctx.getString(com.lanhang.lite.R.string.widget_campus_failed),
                detail = cached.failure
                    ?: ctx.getString(com.lanhang.lite.R.string.widget_campus_offline_detail),
                state = State.FAILED,
            )
            State.BUSY -> Snapshot(
                title = ctx.getString(com.lanhang.lite.R.string.widget_campus_busy),
                detail = ctx.getString(com.lanhang.lite.R.string.widget_campus_busy_detail),
                state = State.BUSY,
            )
            State.OFFLINE -> Snapshot(
                title = ctx.getString(com.lanhang.lite.R.string.widget_campus_offline),
                detail = ctx.getString(com.lanhang.lite.R.string.widget_campus_offline_detail),
                state = State.OFFLINE,
            )
        }
    }

    /** 在线状态的副标题：IP · 在线时长 */
    private fun onlineDetail(context: Context, cached: Cached): String {
        val minutes = TimeUnit.SECONDS.toMinutes(cached.onlineSeconds)
        val hours = minutes / 60
        val mins = minutes % 60
        val duration = if (hours > 0) {
            context.getString(com.lanhang.lite.R.string.widget_campus_duration_hm, hours, mins)
        } else {
            context.getString(com.lanhang.lite.R.string.widget_campus_duration_m, mins)
        }
        return context.getString(
            com.lanhang.lite.R.string.widget_campus_online_detail,
            cached.ip ?: "--",
            duration,
        )
    }

    /**
     * 小组件能不能直接认证（不用跳 App）。
     *
     * **必须先在 App 里勾了「记住密码」**：小组件没有输入框，密码只能从
     * Keystore 加密存储里取（[CampusNetStore.password]）。没存 → 只能跳 App。
     */
    fun canAuthenticateDirectly(context: Context): Boolean {
        // CampusNetStore 的 SP 在 App.onCreate 里 init 过；小组件与 App 同进程，正常都 init 过了。
        // 万一没 init（极端情况），runCatching 兜住，退回「不能直接认证」→ 跳 App。
        return runCatching { CampusNetStore.password() != null }.getOrDefault(false)
    }
}
