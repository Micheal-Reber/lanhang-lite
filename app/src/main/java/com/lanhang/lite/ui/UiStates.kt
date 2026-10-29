package com.lanhang.lite.ui

import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.lanhang.lite.core.isCancellation
import com.lanhang.lite.core.userMessage

/**
 * 空态 / 失败态的统一渲染入口。
 *
 * 四个 Tab 页与「我的动态」原先各写一遍「设文案 + 设可见性 + 再 toast 一遍」，
 * 且都用 `e.message` —— 那既拿不到本地化文案（`ApiException` 的 message 只是日志标签），
 * 也无法区分"真的失败"和"协程被取消"。统一走这里。
 */

/**
 * 把失败写进空态 TextView 并显示。返回 false 表示这是取消，调用方应静默返回。
 *
 * [operationRes] 是形如「加载失败：%1$s」的模板资源，明细由 [userMessage] 决定
 * （`ApiException` 用它携带的本地化文案，其它异常用 cause 链）。
 */
fun TextView.showFailure(error: Throwable, @StringRes operationRes: Int): Boolean {
    if (error.isCancellation) return false
    text = error.userMessage(context, operationRes)
    isVisible = true
    return true
}

/** 空数据占位；[visible] 由调用方决定（通常 `scores.isEmpty()`） */
fun TextView.showEmpty(@StringRes textRes: Int, visible: Boolean = true) {
    text = context.getString(textRes)
    isVisible = visible
}
