package com.lanhang.lite.core

import android.content.Context
import androidx.annotation.StringRes
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/**
 * 沿 `cause` 链收集诊断信息，用于「用户能看懂的操作名 + 能定位的原因」。
 *
 * 关键约束：**`CancellationException` 必须原样上抛**。界面销毁、切换 Tab 都会取消协程，
 * 若在 `catch (e: Exception)` 里把它当成失败弹 toast，表现就是「切页时莫名报错」。
 */
fun Throwable.diagnosticMessage(): String {
    if (this is CancellationException) throw this
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val details = linkedSetOf<String>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        details += current.localizedMessage?.trim()?.takeIf { it.isNotEmpty() }
            ?: current.javaClass.simpleName.takeIf { it.isNotEmpty() }
            ?: current.javaClass.name
        current = current.cause
    }
    return details.joinToString("\n")
}

/** 失败明细：`ApiException` 用其本地化文案，其余异常用原因链 */
fun Throwable.userDetail(context: Context): String =
    (this as? ApiException)?.text?.resolve(context)
        ?: networkHint()
        ?: diagnosticMessage()

/**
 * 网络异常的**分级说明**。
 *
 * 「网络异常：UnknownHostException: Unable to resolve host "..."」对用户毫无意义 ——
 * 他不知道该换 DNS、连校园网还是换运营商。而这三类故障的解法完全不同：
 * - DNS 解析不到 → 运营商 DNS 没这条记录 / DNS 污染
 * - 连接超时 / 被拒 → 到学校服务器的路不通（移动数据到教育网经常不通）
 * - TLS 握手失败 → 运营商在干扰 HTTPS
 *
 * 所以按异常类型给一句话 + 一个**可执行的下一步**，末尾保留异常类名做线索。
 * 返回 null 表示这不是网络异常，交给原来的原因链处理。
 */
fun Throwable.networkHint(): String? {
    if (this is CancellationException) return null
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        val hint = when (current) {
            is java.net.UnknownHostException ->
                "域名解析不到，可能是运营商网络的问题；换校园网或其他网络再试"
            is java.net.SocketTimeoutException ->
                "连接超时，你的网络到学校服务器这条路不通；试试校园网或 VPN"
            is java.net.ConnectException ->
                "连接被拒绝，服务器可能限制了这类网络来源；试试校园网或 VPN"
            is javax.net.ssl.SSLHandshakeException ->
                "加密连接被打断，通常是运营商在干扰 HTTPS；换个网络或用 VPN"
            is java.net.SocketException ->
                "连接被中断，网络限制的可能性大；试试校园网或 VPN"
            else -> null
        }
        if (hint != null) return "$hint（${current.javaClass.simpleName}）"
        current = current.cause
    }
    return null
}

/** 「操作名 + 明细」；[operationRes] 是形如「加载失败：%1$s」的模板资源 */
fun Throwable.userMessage(context: Context, @StringRes operationRes: Int): String =
    context.getString(operationRes, userDetail(context))

/** 取消不算失败：调用方应直接 return，不弹提示、不写诊断 */
val Throwable.isCancellation: Boolean
    get() = this is CancellationException || cause is CancellationException
