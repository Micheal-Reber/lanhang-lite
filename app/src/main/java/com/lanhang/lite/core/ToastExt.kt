package com.lanhang.lite.core

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/**
 * 轻提示的统一入口（2026-10-31 收编）。
 *
 * 之前 **21 个页面各自写了一份** `private fun toast(...)`，实现还分了三种：
 * `LENGTH_SHORT`、`LENGTH_LONG`（校园网）、直接吃 `@StringRes`（OA、自建课）。
 * 同一种交互三种写法，改时长或改样式就得改 21 处 —— 这类"一行函数"最容易悄悄走岔。
 *
 * 这里给 [Context] 和 [Fragment] 两个接收者：Activity 里直接 `toast(...)`，
 * Fragment 里同样直接 `toast(...)`（内部转 `requireContext()`），**调用点一个字都不用改**。
 */
fun Context.toast(text: CharSequence, long: Boolean = false) {
    Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** 带格式化的资源版本：`toast(R.string.im_join_group_ok, name)` 等价 `getString(res, name)` */
fun Context.toast(@StringRes resId: Int, vararg args: Any) {
    toast(getString(resId, *args))
}

fun Fragment.toast(text: CharSequence, long: Boolean = false) {
    requireContext().toast(text, long)
}

fun Fragment.toast(@StringRes resId: Int, vararg args: Any) {
    requireContext().toast(resId, *args)
}
