package com.lanhang.lite.reminder

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lanhang.lite.R
import com.lanhang.lite.ui.MainActivity

/**
 * 进程内的投放状态真源：服务存活 = 投放中。
 *
 * 不用通知可见性当信号 —— ColorOS 16 等系统在应用回前台时会自动收起 promoted
 * 实时通知（`activeNotifications` 查不到），但服务仍在投放。界面据此对账；
 * 服务启动路径置位、停止路径/onDestroy 清零，进程死则归零。
 */
object FluidCastState {
    @Volatile
    var casting: Boolean = false
        private set

    @Volatile
    var testCasting: Boolean = false
        private set

    fun markStarted(test: Boolean = false) {
        casting = true
        testCasting = test
    }

    fun markStopped() {
        casting = false
        testCasting = false
    }
}

/**
 * 前台服务：在「课前 N 分钟 → 上课」窗口内持续投放 promoted 进度通知（移植自 Sleepy
 * `FluidCloudService`）。
 *
 * 胶囊文案保持静态，只有进度条随 15s 心跳推进。服务存活期即投放期。
 */
class FluidCloudService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var courseName = ""
    private var room = ""
    private var teacher = ""
    private var startTime = ""
    private var endTime = ""
    private var startNode = 0
    private var notifyEpoch = 0L
    private var classEpoch = 0L
    private var activeIdentity: FluidNotificationIdentity? = null
    private val vendor by lazy { detectLiveCardVendor() }
    private val session = FluidNotificationSession(
        now = System::currentTimeMillis,
        schedule = { callback, delay -> handler.postDelayed(callback, delay) },
        cancel = { callback -> handler.removeCallbacks(callback) },
        publish = ::postProgressNotification,
        finished = ::stopCloudNotification,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ReminderNotify.ensureChannels(this)
        val preserveTest = intent?.getBooleanExtra(EXTRA_RECONCILE, false) == true &&
            FluidCastState.testCasting && classEpoch > System.currentTimeMillis()
        if (intent?.action == ACTION_STOP) {
            if (!preserveTest) stopCloudNotification()
            return START_NOT_STICKY
        }
        // 在服务工作线程上二次判定：对账查询可能早于一次新的测试投放
        if (preserveTest) return START_NOT_STICKY
        val nextIdentity = FluidNotificationIdentity(
            intent?.getLongExtra("notifyEpoch", 0L) ?: 0L,
            intent?.getLongExtra("classEpoch", 0L) ?: 0L,
            intent?.getIntExtra("startNode", 0) ?: 0,
            intent?.getStringExtra("courseName").orEmpty(),
        )
        val reuseSession = canReuseFluidSession(
            activeIdentity, nextIdentity, FluidCastState.casting,
            intent?.action == ACTION_TEST, System.currentTimeMillis(),
        )
        if (!reuseSession) {
            session.stop()
            cancelVivoCard(this)
        }
        if (intent?.action == ACTION_TEST) {
            // 流体云测试入口：用示例课程强制唤起一次，窗口 2 分钟（进度条真实推进）
            val now = System.currentTimeMillis()
            courseName = getString(R.string.reminder_fluid_test_course)
            room = getString(R.string.reminder_fluid_test_room)
            teacher = ""
            startTime = android.text.format.DateFormat.format("HH:mm", now + TEST_WINDOW_MS).toString()
            endTime = android.text.format.DateFormat
                .format("HH:mm", now + TEST_WINDOW_MS + 45 * 60_000L).toString()
            startNode = 1
            notifyEpoch = now
            classEpoch = now + TEST_WINDOW_MS
        } else {
            courseName = intent?.getStringExtra("courseName")
                ?: getString(R.string.default_course_name)
            room = intent?.getStringExtra("room").orEmpty()
                .ifBlank { getString(R.string.default_room) }
            teacher = intent?.getStringExtra("teacher").orEmpty()
            startTime = intent?.getStringExtra("startTime").orEmpty()
            endTime = intent?.getStringExtra("endTime").orEmpty()
            startNode = intent?.getIntExtra("startNode", 0) ?: 0
            notifyEpoch = intent?.getLongExtra("notifyEpoch", 0L) ?: 0L
            classEpoch = intent?.getLongExtra("classEpoch", 0L) ?: 0L
        }

        if (notifyEpoch <= 0L || classEpoch <= notifyEpoch) {
            val now = System.currentTimeMillis()
            notifyEpoch = now
            classEpoch = now + 1L
        }

        if (classEpoch <= System.currentTimeMillis()) {
            stopCloudNotification()
            return START_NOT_STICKY
        }

        // 调度器重复 start 时仍然刷新上面的展示字段；沿用已有 tick 发布，
        // 既满足 vivo 的 ≥10s 更新限制，也不会重置展开态。
        if (reuseSession) return START_NOT_STICKY
        activeIdentity = nextIdentity
        FluidCastState.markStarted(intent?.action == ACTION_TEST)
        session.start(
            classEpoch,
            intent?.action == ACTION_TEST && needsFluidFirstPostReinforcement(vendor),
        )
        return START_NOT_STICKY
    }

    private fun postProgressNotification(updateSequence: Int) {
        val now = System.currentTimeMillis()
        val state = CourseLiveCardState(
            courseName = courseName,
            room = room,
            teacher = teacher,
            startTime = startTime,
            notifyEpoch = notifyEpoch,
            classEpoch = classEpoch,
            nowEpoch = now,
            updateSequence = updateSequence,
            endTime = endTime,
            startNode = startNode
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = VendorLiveCardRenderer.build(
            context = this,
            state = state,
            contentIntent = contentIntent,
            channelId = ReminderNotify.CHANNEL_FLUID,
            vendor = vendor,
        )
        // 前台服务路径：startForeground 本身不需要 POST_NOTIFICATIONS 运行时权限
        startForeground(ReminderNotify.NOTIFY_BEFORE_CLASS, notification)
        android.util.Log.d(
            "FluidCloudService",
            "updated course progress=${state.progress} notify=$notifyEpoch class=$classEpoch"
        )
    }

    private fun stopCloudNotification() {
        activeIdentity = null
        session.stop()
        FluidCastState.markStopped()
        try {
            val placeholder = NotificationCompat.Builder(this, ReminderNotify.CHANNEL_FLUID)
                .setSmallIcon(R.drawable.ic_notification_time)
                .setContentTitle(getString(R.string.reminder_fluid_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(ReminderNotify.NOTIFY_BEFORE_CLASS, placeholder)
        } catch (_: Throwable) {
        }
        androidx.core.app.NotificationManagerCompat.from(this)
            .cancel(ReminderNotify.NOTIFY_BEFORE_CLASS)
        cancelVivoCard(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session.stop()
        cancelVivoCard(this)
        FluidCastState.markStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TEST_WINDOW_MS = 2 * 60_000L
        const val ACTION_TEST = "com.lanhang.lite.action.FLUID_TEST"
        const val ACTION_STOP = "com.lanhang.lite.action.FLUID_STOP"
        internal const val EXTRA_RECONCILE = "fluidReconcile"

        // vivo 文档 896：直接取消要用系统创建的 tag；前台通知本身无 tag，所以两条都做。
        private fun cancelVivoCard(context: android.content.Context) {
            val vendor = detectLiveCardVendor()
            if (vendor == LiveCardVendor.VIVO || vendor == LiveCardVendor.IQOO) {
                androidx.core.app.NotificationManagerCompat.from(context)
                    .cancel("VIVO_SUPERX_TAG", ReminderNotify.NOTIFY_BEFORE_CLASS)
            }
        }

        fun requestStop(context: android.content.Context, reconciliation: Boolean = false) {
            if (reconciliation && FluidCastState.testCasting) return
            val intent = Intent(context, FluidCloudService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_RECONCILE, reconciliation)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {
                if (reconciliation && FluidCastState.testCasting) return
                cancelVivoCard(context)
                androidx.core.app.NotificationManagerCompat.from(context)
                    .cancel(ReminderNotify.NOTIFY_BEFORE_CLASS)
            }
        }
    }
}
