package com.lanhang.lite.core

/**
 * 整页数据的三种状态。
 *
 * 约定：**数据没确认到位之前不要渲染默认值**——先 [Loading]；失败给可重试的 [Failed]；
 * 只有 [Ready] 才把值交给控件。否则会先显示 0 门 / 「暂无成绩」，再跳到真实值（闪一下）。
 *
 * 它只是状态词汇，不持有 View、不持有 Context，所以 Fragment / Activity / 小部件都能用。
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
    data class Failed(val error: Throwable) : LoadState<Nothing>
}

val LoadState<*>.isReady: Boolean
    get() = this is LoadState.Ready<*>

val LoadState<*>.errorOrNull: Throwable?
    get() = (this as? LoadState.Failed)?.error

fun <T, R> LoadState<T>.map(transform: (T) -> R): LoadState<R> = when (this) {
    LoadState.Loading -> LoadState.Loading
    is LoadState.Failed -> this
    is LoadState.Ready -> LoadState.Ready(transform(value))
}
