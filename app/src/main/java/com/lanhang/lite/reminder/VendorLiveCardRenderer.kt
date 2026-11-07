package com.lanhang.lite.reminder

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.lanhang.lite.R

/**
 * 运行时 `Build.MANUFACTURER` 对应的厂商实时卡片通道。
 *
 * 每个厂商只能改「呈现」，不能改生命周期语义 —— 卡片何时出现、何时消失、
 * 进度怎么算，全部由 [CourseLiveCardState] + `FluidCloudService` 决定。
 */
enum class LiveCardVendor {
    OPPO,
    ONEPLUS,
    REALME,
    XIAOMI,
    VIVO,
    IQOO,
    MEIZU,
    HUAWEI,
    HONOR,
    SAMSUNG,
    GENERIC
}

fun detectLiveCardVendor(manufacturer: String = Build.MANUFACTURER): LiveCardVendor {
    return when (manufacturer.trim().lowercase()) {
        "oppo" -> LiveCardVendor.OPPO
        "oneplus" -> LiveCardVendor.ONEPLUS
        "realme" -> LiveCardVendor.REALME
        "xiaomi", "redmi", "poco" -> LiveCardVendor.XIAOMI
        "vivo" -> LiveCardVendor.VIVO
        "iqoo" -> LiveCardVendor.IQOO
        "meizu" -> LiveCardVendor.MEIZU
        "huawei" -> LiveCardVendor.HUAWEI
        "honor" -> LiveCardVendor.HONOR
        "samsung" -> LiveCardVendor.SAMSUNG
        else -> LiveCardVendor.GENERIC
    }
}

/**
 * Android 16 `ProgressStyle` 的标准传输格式。
 *
 * NotificationCompat 在 API 36+ 映射成平台样式，低版本降级成普通进度条 ——
 * 同一份构造覆盖所有系统，不需要厂商私有开关。
 */
fun progressStyleFor(progressPercent: Int): NotificationCompat.ProgressStyle =
    NotificationCompat.ProgressStyle()
        .setProgress(progressPercent.coerceIn(0, 100))
        // progressMax 默认就是 100；core 1.17 没有 setProgressMax，不为冗余 setter 升依赖
        .setStyledByProgress(true)

/**
 * 课程实时卡片通知构建（移植自 Sleepy `VendorLiveCardRenderer`）。
 *
 * 底座永远是标准 Android 通知（promoted ongoing + ProgressStyle + 短关键文本），
 * 厂商 extras 只是「在同一张通知上补充自有字段」，任何厂商字段被系统忽略时
 * 仍然是一张可用的普通进度通知。
 *
 * 来源：
 *  - 小米：与 v1.0.56 一致，不附任何 `miui.focus.*` 私有 extras。
 *    历史教训：加了 Focus Notification payload 之后，通知被重绘成普通卡、**不再上岛**。
 *  - vivo / iQOO：originos-toolkit（MIT）+ origin-isle 只读协议
 *  - 魅族：Pinme（Apache-2.0）
 *  - 三星：CompressorEdge（MIT）
 */
object VendorLiveCardRenderer {

    /** 探测委托注入点：单元测试不需要真 Context / 真机。 */
    interface SupportProbes {
        fun flymeLiveEnabled(ctx: Context): Boolean
        fun flymeVersion(): Int
        fun samsungNowBarFeature(ctx: Context): Boolean
        fun vivoRegisterSceneList(ctx: Context, scenes: List<String>): Boolean
    }

    private object RealSupport : SupportProbes {
        override fun flymeLiveEnabled(ctx: Context) = VendorLiveCardSupport.flymeLiveEnabled(ctx)
        override fun flymeVersion() = VendorLiveCardSupport.flymeVersion()
        override fun samsungNowBarFeature(ctx: Context) = VendorLiveCardSupport.samsungNowBarFeature(ctx)
        override fun vivoRegisterSceneList(ctx: Context, scenes: List<String>) =
            VendorLiveCardSupport.vivoRegisterSceneList(ctx, scenes)
    }

    fun build(
        context: Context,
        state: CourseLiveCardState,
        contentIntent: PendingIntent,
        channelId: String,
        vendor: LiveCardVendor = detectLiveCardVendor(),
        support: SupportProbes = RealSupport,
    ): Notification {
        val primary = ReminderPrefs.fluidPrimary(context)
        val primaryText = when (primary) {
            ReminderPrefs.FIELD_NAME -> state.courseName
            ReminderPrefs.FIELD_TIME -> state.startTime
            ReminderPrefs.FIELD_COUNTDOWN ->
                context.getString(R.string.fluid_coloros_capsule, state.minutesLeft)
            else -> state.room
        }.ifBlank { state.courseName }

        val themePrimaryArgb = ReminderNotify.accentColor(context)

        val isColorOs = vendor == LiveCardVendor.OPPO ||
            vendor == LiveCardVendor.ONEPLUS ||
            vendor == LiveCardVendor.REALME

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_time)
            .setColor(themePrimaryArgb)
            .setContentTitle(state.courseName)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)

        if (isColorOs) {
            // ColorOS 流体云：ProgressStyle 渲染底部倒计时进度条；正文行 =
            // "HH:mm - HH:mm · 教室 · 教师"，灰色副行 = "N 分钟后上课"；
            // 胶囊短文本跟随「胶囊主显示内容」设置。小图标 = 倒计时进度环（随 15s 刷新推进）。
            val countdown = context.getString(R.string.fluid_coloros_countdown, state.minutesLeft)
            builder
                .setContentText(state.colorOsDetailText)
                .setSubText(countdown)
                .setProgress(100, state.progress, false)
                .setStyle(progressStyleFor(state.progress))
                .setShortCriticalText(primaryText.take(7))
            progressRingIcon(state.progress)?.let { builder.setSmallIcon(it) }
        } else {
            builder
                .setContentText(state.detailText)
                .setSubText(state.room)
                .setProgress(100, state.progress, false)
                .setStyle(progressStyleFor(state.progress))
                .setShortCriticalText(primaryText.take(7))
        }

        when (vendor) {
            // 小米 / HyperOS：上面的公共构造本身就是当年「状态栏岛上出现的那条通知」，
            // 保持 extras-free。加私有字段会改变系统判定，反而不上岛。
            LiveCardVendor.XIAOMI -> Unit
            LiveCardVendor.VIVO, LiveCardVendor.IQOO ->
                addVivoExtras(builder, state, contentIntent, context, support, themePrimaryArgb)
            LiveCardVendor.MEIZU ->
                addMeizuExtras(builder, state, contentIntent, context, themePrimaryArgb, support)
            LiveCardVendor.SAMSUNG ->
                addSamsungExtras(builder, state, contentIntent, context, support)
            // OPPO / ONEPLUS / REALME / HUAWEI / HONOR / GENERIC：普通 ongoing 通知。
            // 华为 HarmonyOS 实况窗只有 ArkTS 实现；荣耀 Suggestions Kit 是白名单，均只能降级。
            else -> Unit
        }
        return builder.build()
    }

    /**
     * 倒计时进度环小图标：底环用低透明度、进度弧用全不透明 —— 系统对 small icon
     * 只取 alpha 通道着色，因此渲染出「暗环 + 亮弧」的进度环效果。
     * `FluidCloudService` 每 15s 重发通知 → 环每 15s 前进一格。
     */
    private fun progressRingIcon(progress: Int): androidx.core.graphics.drawable.IconCompat? {
        return try {
            val size = 96
            val bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 12f
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            val inset = 12f
            val oval = android.graphics.RectF(inset, inset, size - inset, size - inset)
            // 底环（暗）
            paint.color = -0x67000000 // alpha ~152
            canvas.drawArc(oval, 0f, 360f, false, paint)
            // 进度弧（亮），从 12 点顺时针
            val sweep = 360f * progress.coerceIn(0, 100) / 100f
            if (sweep > 1f) {
                paint.color = -0x1 // opaque
                canvas.drawArc(oval, -90f, sweep, false, paint)
            }
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)
        } catch (_: Throwable) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // vivo / iQOO — OriginOS 原子通知 / OriginIsland
    // 协议约束：渠道 IMPORTANCE_HIGH；标题与正文非空；operation 0=创建 2=结束；
    // displays 位掩码 65809 才有真正的胶囊面；clickResp 必填。
    // -----------------------------------------------------------------------
    private fun addVivoExtras(
        builder: NotificationCompat.Builder,
        state: CourseLiveCardState,
        contentIntent: PendingIntent,
        context: Context,
        support: SupportProbes,
        themeColor: Int,
    ) {
        // 隐藏 API 调成功只代表没抛异常，不代表场景被授权；系统仍自行决定是否渲染。
        val registered = support.vivoRegisterSceneList(
            context, listOf("NAVIGATION", "METTING", "TIMER", "TRAIN")
        )
        if (!registered) return

        val shortInfos = Bundle().apply {
            putString("notification.superx.shortInfos.describeShort", state.courseName)
            putString("notification.superx.shortInfos.coreInfoShort", state.room)
            putParcelable(
                "notification.superx.shortInfos.image",
                Icon.createWithResource(context, R.drawable.ic_notification_time)
            )
            putParcelable("notification.superx.shortInfos.imageClickResp", contentIntent)
        }

        val infos = Bundle().apply {
            putInt("notification.superx.infos.progress", state.progress)
            putParcelableArrayList(
                "notification.superx.infos.nodeIcon",
                arrayListOf(
                    Icon.createWithResource(context, R.drawable.ic_notification_time),
                    Icon.createWithResource(context, R.drawable.ic_notifications),
                )
            )
        }

        val extras = Bundle().apply {
            putInt(
                "notification.superx.operation",
                when {
                    !state.isActive -> 2          // 优雅收尾
                    state.updateSequence <= 1 -> 0 // 创建
                    else -> 1                      // 更新
                }
            )
            putBoolean("notification.superx.showNotify", true)
            putInt("notification.superx.template", 2) // 进度卡
            // vivo 白名单里只有 NAVIGATION 被确认可用；METTING/TIMER/TRAIN 一并注册，
            // 是否放行未知 —— 全被拒时通知仍以普通样式投递。
            putString("notification.superx.scene", "NAVIGATION")
            putInt("notification.superx.changedRecord", state.updateSequence)
            putInt(
                "notification.superx.displays",
                65809 // notification(1) | lockscreen(16) | statusbar(256) | AOD(65536)
            )
            putBoolean("notification.superx.islandNotify", true)
            putParcelable("notification.superx.clickResp", contentIntent)
            putBundle("notification.superx.baseInfos", Bundle().apply {
                putCharSequence("notification.superx.baseInfos.title", state.courseName)
                putCharSequence("notification.superx.baseInfos.content", state.detailText)
                putInt("notification.superx.baseInfos.progressState", if (state.isActive) 0 else 1)
            })
            putBundle("notification.superx.infos", infos)
            putBundle("notification.superx.shortInfos", shortInfos)
            // vivo 文档 896：岛与胶囊服务不同设备，胶囊兜底要保留。
            putBundle("notification.superx.capsule", Bundle().apply {
                putInt("notification.superx.capsule.state", 1)
                putParcelable(
                    "notification.superx.capsule.icon",
                    Icon.createWithResource(context, R.drawable.ic_notification_time)
                )
                putString("notification.superx.capsule.content", state.startTime)
                putParcelable("notification.superx.capsule.clickResp", contentIntent)
            })
            putBundle("notification.superx.island", Bundle().apply {
                putInt("island.superx.leftTemplate", 1)
                putInt("island.superx.rightTemplate", 2)
                putBoolean("island.superx.forceShow", true)
                putBoolean("island.superx.showBarWhenCard", false)
                putInt("island.superx.template", 4)
                putInt("island.superx.islandClick", 0)
                putParcelable("island.superx.clickResp", contentIntent)
                putBundle("island.superx.baseInfos", Bundle().apply {
                    // 岛 bundle 里的嵌套键保留 notification 前缀
                    putParcelable(
                        "notification.superx.baseInfos.icon",
                        Icon.createWithResource(context, R.drawable.ic_notification_time)
                    )
                    putCharSequence("notification.superx.baseInfos.title", state.courseName)
                    putCharSequence("notification.superx.baseInfos.content", state.detailText)
                    putInt("notification.superx.baseInfos.subInfo", 5)
                    putInt("notification.superx.baseInfos.subProgress", state.progress)
                    putInt(
                        "notification.superx.baseInfos.progressState",
                        if (state.isActive) 0 else 1
                    )
                    putInt("notification.superx.baseInfos.subProgressColor", themeColor)
                })
                putBundle("island.superx.leftInfo", Bundle().apply {
                    putParcelable(
                        "island.superx.leftInfo.icon",
                        Icon.createWithResource(context, R.drawable.ic_notification_time)
                    )
                    putString("island.superx.leftInfo.content", state.startTime)
                })
                putBundle("island.superx.rightInfo", Bundle().apply {
                    putInt("island.superx.rightInfo.progressValue", state.progress)
                    putInt(
                        "island.superx.rightInfo.progressState",
                        if (state.isActive) 0 else 1
                    )
                    putInt("island.superx.rightInfo.progressColor", themeColor)
                    putString("island.superx.rightInfo.progressContent", state.room)
                    putParcelable("island.superx.rightInfo.clickResp", contentIntent)
                })
            })
        }
        builder.setExtras(extras)
    }

    // -----------------------------------------------------------------------
    // 魅族 / Flyme — Flyme 实时通知
    // type=2 是社区多数；capsuleType=5 是各种实现的最小共识；胶囊配色要求不透明底。
    // -----------------------------------------------------------------------
    private fun addMeizuExtras(
        builder: NotificationCompat.Builder,
        state: CourseLiveCardState,
        contentIntent: PendingIntent,
        context: Context,
        themeColor: Int,
        support: SupportProbes,
    ) {
        val flymeVer = support.flymeVersion()
        val liveEnabled = support.flymeLiveEnabled(context)
        if (flymeVer < 11 || !liveEnabled) return

        val capsule = Bundle().apply {
            putInt("notification.live.capsuleStatus", 1)
            putInt("notification.live.capsuleType", 5)
            putString(
                "notification.live.capsuleContent",
                "${state.courseName} ${state.progress}%"
            )
            putParcelable(
                "notification.live.capsuleIcon",
                Icon.createWithResource(context, R.drawable.ic_notification_time)
            )
            // Flyme 要不透明胶囊底；alpha=0 时按亮度选前景色会在系统面上读不出来。
            val opaqueThemeColor = themeColor or 0xFF000000.toInt()
            putInt("notification.live.capsuleBgColor", opaqueThemeColor)
            putInt(
                "notification.live.capsuleContentColor",
                if (Color.luminance(opaqueThemeColor) > 0.7f) Color.BLACK else Color.WHITE
            )
        }
        builder.setExtras(Bundle().apply {
            putBoolean("is_live", true)
            putInt(
                "notification.live.operation",
                if (state.updateSequence <= 1) 0 else 1
            )
            putInt("notification.live.type", 2)
            putBundle("notification.live.capsule", capsule)
        })
    }

    // -----------------------------------------------------------------------
    // 三星 — One UI Live Updates（两层：标准 promoted ongoing 保证可用 +
    // 设备声明 nowbar 特性时附 `android.ongoingActivityNoti.*`）
    // -----------------------------------------------------------------------
    private fun addSamsungExtras(
        builder: NotificationCompat.Builder,
        state: CourseLiveCardState,
        contentIntent: PendingIntent,
        context: Context,
        support: SupportProbes,
    ) {
        if (!support.samsungNowBarFeature(context)) return

        val samsungExtras = Bundle().apply {
            putInt("android.ongoingActivityNoti.style", 1) // 进度样式
            putString("android.ongoingActivityNoti.primaryInfo", state.courseName)
            putString("android.ongoingActivityNoti.secondaryInfo", state.detailText)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", state.courseName)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", state.detailText)
        }
        // 三星是唯一在此写 extras 的分支，builder 之前没有 extras，直接 set 即可。
        builder.setExtras(samsungExtras)
    }
}
