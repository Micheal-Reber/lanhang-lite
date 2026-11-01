package com.lanhang.lite.core

import android.content.Context
import com.lanhang.lite.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间文案的统一出口（2026-10-31 收编）。
 *
 * 之前私信、座位预约、成绩、工作圈各自 `new SimpleDateFormat(...)`，其中两处已经出现偏差
 * （一处 `HH:mm` 用 `Locale.US`、一处用默认区域，切语言后同屏会出 `下午3:05` 和 `15:05`）。
 * 聊天/列表这类"给人看的时间"集中到这里，只有两档语义：
 *
 * - [clock]：只要时分，用于聊天气泡下方、预约时段
 * - [dayLabel]：列表行尾那种"今天 HH:mm / 昨天 / 月-日 / 年-月-日"
 */
object TimeText {

    /**
     * `HH:mm`。
     *
     * 固定 `Locale.US`：`HH` 是 24 小时制，跟区域无关；用默认区域时部分语言会渲染成本地数字
     * 或 12 小时制，和别处的时间格式混排。
     */
    fun clock(millis: Long): String {
        if (millis <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
    }

    /**
     * 列表行尾的时间：今天 `HH:mm` → 昨天 → 今年 `M/d` → 往年 `yyyy/M/d`。
     *
     * 「昨天」这一档是必需的：只有「今天显示时分、更早显示月日」时，
     * 昨晚 23:50 显示 `23:50`、今早 00:10 显示 `00:10`，一眼分不出谁更新。
     */
    fun dayLabel(context: Context, millis: Long): String {
        if (millis <= 0L) return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        ) {
            return clock(millis)
        }
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        ) {
            return context.getString(R.string.im_yesterday)
        }
        return if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            SimpleDateFormat("M/d", Locale.US).format(Date(millis))
        } else {
            SimpleDateFormat("yyyy/M/d", Locale.US).format(Date(millis))
        }
    }
}
