package com.lanhang.lite.reminder

/**
 * 一次投放会话（移植自 Sleepy `FluidNotificationSession`）。
 *
 * 所有回调都跑在服务的主线程上。会话负责「立刻发一帧 → 之后每 15s 发一帧 → 到点收尾」，
 * 并用 generation 保证 stop 之后残留的 Runnable 不会再发通知。
 */
internal class FluidNotificationSession(
    private val now: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val publish: (Int) -> Unit,
    private val finished: () -> Unit,
) {
    private var generation = 0L
    private var active = false
    private var sequence = 0
    private val pending = mutableSetOf<Runnable>()

    fun start(endEpoch: Long, reinforce: Boolean) {
        stop()
        if (now() >= endEpoch) {
            finished()
            return
        }
        active = true
        sequence = 0
        val session = generation
        fun post() {
            sequence = (sequence + 1).coerceAtLeast(1)
            publish(sequence)
        }
        fun tick() {
            if (now() >= endEpoch) {
                stop()
                finished()
            } else {
                post()
                enqueue(session, minOf(15_000L, endEpoch - now()).coerceAtLeast(1), ::tick)
            }
        }
        post()
        if (reinforce) enqueue(session, 600L) {
            if (now() < endEpoch) post()
        }
        enqueue(session, minOf(15_000L, endEpoch - now()).coerceAtLeast(1), ::tick)
    }

    fun stop() {
        active = false
        generation++
        pending.forEach(cancel)
        pending.clear()
    }

    private fun enqueue(session: Long, delay: Long, block: () -> Unit) {
        val callback = object : Runnable {
            override fun run() {
                pending.remove(this)
                if (active && generation == session) block()
            }
        }
        pending.add(callback)
        schedule(callback, delay)
    }
}

/** 只有 ColorOS 观察到首次投放竞态；vivo 要求更新间隔 ≥10s。 */
internal fun needsFluidFirstPostReinforcement(vendor: LiveCardVendor): Boolean =
    vendor == LiveCardVendor.OPPO || vendor == LiveCardVendor.ONEPLUS || vendor == LiveCardVendor.REALME

/** 仅用于展示的教室/教师/时间变化不替换当前会话。 */
internal data class FluidNotificationIdentity(
    val notifyEpoch: Long,
    val classEpoch: Long,
    val startNode: Int,
    val courseName: String,
)

internal fun canReuseFluidSession(
    current: FluidNotificationIdentity?,
    next: FluidNotificationIdentity,
    casting: Boolean,
    explicitTest: Boolean,
    now: Long,
): Boolean = casting && !explicitTest && current == next && next.classEpoch > now
