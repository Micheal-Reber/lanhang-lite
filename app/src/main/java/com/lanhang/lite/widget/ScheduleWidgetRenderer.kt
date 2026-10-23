package com.lanhang.lite.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.lanhang.lite.schedule.PeriodClock
import com.lanhang.lite.session.Session
import com.lanhang.lite.widget.ScheduleWidgetData.CourseBlock
import com.lanhang.lite.widget.ScheduleWidgetData.WeekSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

object ScheduleWidgetRenderer {

    /** 节次时间文本（仅 4×4 网格的时间列用）；时间表的唯一来源是 [PeriodClock] */
    private val periodTimes = Array(PeriodClock.count) { PeriodClock.rangeText(it + 1, it + 1) }
    private val dateFormatter = DateTimeFormatter.ofPattern("M-d")
    private val todayDateFormatter = DateTimeFormatter.ofPattern("M/d")

    fun render(
        context: Context,
        snapshot: WeekSnapshot?,
        widthDp: Int,
        heightDp: Int
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt().coerceAtLeast(1)
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = palette(context)
        val radius = 18f * density
        paint.color = palette.background
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radius,
            radius,
            paint
        )
        if (snapshot == null) {
            drawEmpty(canvas, paint, context, width, height, density, palette)
            return bitmap
        }
        CanvasRenderer(canvas, paint, context, density, width, height, palette, snapshot).draw()
        return bitmap
    }

    private fun drawEmpty(
        canvas: Canvas,
        paint: Paint,
        context: Context,
        width: Int,
        height: Int,
        density: Float,
        palette: Palette
    ) {
        val title = if (Session.isLoggedIn) {
            context.getString(com.lanhang.lite.R.string.widget_empty_sync)
        } else {
            context.getString(com.lanhang.lite.R.string.widget_empty_login)
        }
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 15f * density
        paint.color = palette.onSurface
        canvas.drawText(title, width / 2f, height / 2f - 8f * density, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 11f * density
        paint.color = palette.onSurfaceVariant
        canvas.drawText(
            context.getString(com.lanhang.lite.R.string.widget_empty_hint),
            width / 2f,
            height / 2f + 14f * density,
            paint
        )
    }

    /** 2×2 底部导航条（◀ ⟳ ▶）占用的高度阈值：低于该高度不显示按钮 */
    const val TODAY_NAV_MIN_HEIGHT_DP = 130

    /** 底部导航条高度（与 [TodayRenderer] 的 footerH 一致） */
    const val TODAY_NAV_BAR_HEIGHT_DP = 22

    /**
     * 今日课程最多同时显示的节数。
     *
     * 规则：上完的课立刻从小部件里消失，后面的课依次顶上来，所以任何时刻最多只有
     * 这么几节「还没上完」的课在列（不是按当天总节数截断）。
     */
    const val MAX_TODAY_ROWS = 3

    /**
     * 底部「翻天」按钮组的几何（dp）。
     * 3 个按钮等宽等高、间距 4dp：◀（前一天）│ ⟳（回到今天）│ ▶（后一天）。
     * **必须与 `layout/widget_schedule_week.xml` 里的三个 ImageView 完全一致**，
     * 因为圆角底画在位图里、图标和点击热区在布局里，两边错 1dp 就会露馅。
     */
    const val TODAY_NAV_BUTTON_WIDTH_DP = 24
    const val TODAY_NAV_BUTTON_HEIGHT_DP = 22
    const val TODAY_NAV_GAP_DP = 4

    /** 按钮组距离小部件右边缘的留白（= outerPad 9dp） */
    const val TODAY_NAV_END_PAD_DP = 9

    /** 按钮组总宽：24×3 + 4×2 = 80dp */
    const val TODAY_NAV_GROUP_WIDTH_DP =
        TODAY_NAV_BUTTON_WIDTH_DP * 3 + TODAY_NAV_GAP_DP * 2

    /** 当前主题是否为深色（按钮底色/图标颜色与此保持一致） */
    fun isDark(context: Context): Boolean = palette(context).isDark

    /**
     * 「课表 · 小」2×2 紧凑档：今日课程列表。
     *
     * 排课规则（与桌面表现一致）：
     * - **上完即走**：今天已经下课的课不再出现，后面的课依次顶上来；始终最多 [MAX_TODAY_ROWS] 节
     * - **正在上课**：该行右侧显示「剩 N 分」（距下课），由 [ScheduleWidgetUpdater] 每分钟重绘一次
     * - 翻到别的日期（昨天/明天）时不做时间过滤，只按节次顺序取前几节
     * - 底部 ◀ ⟳ ▶：翻转前后一天 + 回到今天；复位按钮在「已翻走」时才高亮可点
     *
     * @param targetDate 要展示的日期（翻天导航）；null = 今天
     * @param now 渲染时刻（默认取系统时间；抽出参数便于测试与预览）
     */
    fun renderToday(
        context: Context,
        snapshot: WeekSnapshot?,
        widthDp: Int,
        heightDp: Int,
        targetDate: LocalDate? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt().coerceAtLeast(1)
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = palette(context)
        val radius = 18f * density
        paint.color = palette.background
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radius,
            radius,
            paint
        )
        if (snapshot == null) {
            drawEmpty(canvas, paint, context, width, height, density, palette)
            return bitmap
        }
        TodayRenderer(
            canvas, paint, context, density, width, height, palette, snapshot,
            targetDate ?: now.toLocalDate(), now
        ).draw()
        return bitmap
    }

    /**
     * 课程卡配色：同课同色、不同课色相差约 137°。
     *
     * 卡片上一律白字（与课表页统一），所以浅色主题原本的淡彩（L=0.82）配白字只有 ~2:1，
     * 会糊成一片 —— 由 [deepenForWhiteText] 按原色相压暗到白字可读；深色主题本来够深，不动。
     *
     * @param alpha 卡片实际绘制时的透明度（4×4 网格色块是半透明的）
     * @param behind 半透明卡片的叠底颜色
     */
    private fun courseColorFor(
        name: String,
        isDark: Boolean,
        alpha: Int = 255,
        behind: Int = Color.WHITE
    ): Int {
        val hue = ((name.hashCode() * 137.508f % 360f) + 360f) % 360f
        val base = if (isDark) {
            ColorUtils.HSLToColor(floatArrayOf(hue, 0.40f, 0.28f))
        } else {
            ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.82f))
        }
        return deepenForWhiteText(base, alpha, behind)
    }

    /**
     * 白字版卡片的最低对比度（与 `ScheduleStyle.MIN_WHITE_CONTRAST` 同一档）。
     * 课名是 10~14sp 加粗，WCAG AA 对大字号要求 3:1，取 3.2 留一点余量。
     */
    private const val MIN_CARD_WHITE_CONTRAST = 3.2

    /**
     * 保持色相/饱和度，仅降低明度直到白字可读；下限 0.24 防止压成黑色。
     * 半透明卡片按叠底后的实际颜色算对比度，否则白字会算得比看起来亮。
     */
    private fun deepenForWhiteText(color: Int, alpha: Int = 255, behind: Int = Color.WHITE): Int {
        fun composited(c: Int): Int = ColorUtils.blendARGB(behind, c, alpha / 255f)

        if (ColorUtils.calculateContrast(Color.WHITE, composited(color)) >= MIN_CARD_WHITE_CONTRAST) {
            return color
        }
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        var lightness = hsl[2]
        var current = color
        repeat(40) {
            lightness = (lightness - 0.015f).coerceAtLeast(0.24f)
            current = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], lightness))
            if (ColorUtils.calculateContrast(Color.WHITE, composited(current)) >=
                MIN_CARD_WHITE_CONTRAST || lightness <= 0.24f
            ) {
                return current
            }
        }
        return current
    }

    private class TodayRenderer(
        private val canvas: Canvas,
        private val paint: Paint,
        private val context: Context,
        private val density: Float,
        private val width: Int,
        private val height: Int,
        private val palette: Palette,
        private val snapshot: WeekSnapshot,
        private val target: LocalDate,
        private val now: LocalDateTime
    ) {
        private val today = now.toLocalDate()

        /** 星期名随界面语言（跟随 values / values-en） */
        private val dayLabels = context.resources.getStringArray(com.lanhang.lite.R.array.schedule_day_names)
        private val compactDayLabels =
            context.resources.getStringArray(com.lanhang.lite.R.array.schedule_day_names_compact)

        /** 相对今天的偏移天数：0=今天，>0=未来，<0=过去（翻天按钮改的就是它） */
        private val offsetDays = ChronoUnit.DAYS.between(today, target)

        /** 今天才按钟点过滤（上完的课消失）；翻到别的日期不过滤 */
        private val filterByClock = offsetDays == 0L

        /** 当前时刻的「当天第几分钟」，用于判断上课中 / 已下课 */
        private val nowMinute = PeriodClock.minutesOf(now.toLocalTime())

        private val outerPad = dp(9f)
        private val headerH = dp(24f)
        private val headerGap = dp(5f)
        private val cardGap = dp(4f)
        private val minRowH = dp(22f)

        fun draw() {
            drawHeader()
            val contentTop = outerPad + headerH + headerGap
            val footerH = if (height >= dp(TODAY_NAV_MIN_HEIGHT_DP.toFloat())) {
                dp(TODAY_NAV_BAR_HEIGHT_DP.toFloat())
            } else {
                0f
            }
            val contentH = (height - outerPad - footerH - contentTop).coerceAtLeast(0f)
            val all = snapshot.days.firstOrNull { it.date == target }?.courses.orEmpty()
            // 已经下课的课从这里开始就不存在了 → 后面的课自然顶上来
            val courses = if (filterByClock) all.filter { isPending(it) } else all
            when {
                courses.isNotEmpty() -> drawRows(courses, contentTop, contentH)
                // 今天本来有课、但全上完了：给一个「已上完」的空态，而不是「今天没有课程」
                filterByClock && all.isNotEmpty() -> drawDayDone(contentTop, contentH)
                else -> drawNoCourse(contentTop, contentH)
            }
            if (footerH > 0f) drawFooter(height - outerPad - footerH, footerH)
        }

        /** 这节课还没下课（下课时间严格晚于现在） */
        private fun isPending(course: CourseBlock): Boolean =
            PeriodClock.endMinute(course.end) > nowMinute

        /** 正在上的那节课（同一时刻最多一节） */
        private fun ongoingOf(courses: List<CourseBlock>): CourseBlock? {
            if (!filterByClock) return null
            return courses.firstOrNull {
                PeriodClock.startMinute(it.start) <= nowMinute && nowMinute < PeriodClock.endMinute(it.end)
            }
        }

        /** 距下课还剩几分钟（向上取整，最后一分钟显示「剩 1 分」而不是 0） */
        private fun minutesLeft(course: CourseBlock): Int {
            val endSecond = PeriodClock.endMinute(course.end) * 60
            val nowSecond = now.toLocalTime().toSecondOfDay()
            return ceil((endSecond - nowSecond) / 60.0).toInt().coerceAtLeast(1)
        }

        private fun drawHeader() {
            val centerY = outerPad + headerH / 2f
            val weekday = dayLabels[(target.dayOfWeek.value - 1).coerceIn(0, 6)]
            val prefix = when (offsetDays) {
                0L -> context.getString(com.lanhang.lite.R.string.widget_today_prefix)
                1L -> context.getString(com.lanhang.lite.R.string.widget_tomorrow_prefix)
                -1L -> context.getString(com.lanhang.lite.R.string.widget_yesterday_prefix)
                else -> ""
            }
            val title = "$prefix$weekday"
            val dateText = target.format(todayDateFormatter)
            paint.textAlign = Paint.Align.LEFT
            paint.alpha = 255
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(15f)
            paint.color = palette.onSurface
            canvas.drawText(ellipsize(title, width * 0.62f), outerPad, centerY + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = dp(14f)
            paint.color = palette.onSurfaceVariant
            canvas.drawText(dateText, width - outerPad, centerY + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        /**
         * 列出「还没上完」的课：最多 [MAX_TODAY_ROWS] 节，按当前可用高度决定实际行数。
         * 超出部分不再画「+N」胶囊（2×2 里没有多余高度），后面的课会在前面的课下课后自动补位。
         */
        private fun drawRows(courses: List<CourseBlock>, contentTop: Float, contentH: Float) {
            if (contentH <= 0f) return
            val capacity = ((contentH + cardGap) / (minRowH + cardGap)).toInt().coerceAtLeast(1)
            val shown = minOf(MAX_TODAY_ROWS, capacity, courses.size)
            val rowH = ((contentH - cardGap * (shown - 1)) / shown).coerceAtLeast(1f)
            val ongoing = ongoingOf(courses)
            for (index in 0 until shown) {
                val course = courses[index]
                val top = contentTop + index * (rowH + cardGap)
                // 只有「正在上」的课才在右侧显示距下课倒计时
                val left = if (course === ongoing) minutesLeft(course) else null
                drawRow(course, top, rowH, left)
            }
        }

        private fun drawRow(course: CourseBlock, top: Float, rowH: Float, countdown: Int?) {
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            val cardColor = courseColorFor(course.name, palette.isDark)
            paint.color = cardColor
            canvas.drawRoundRect(
                RectF(outerPad, top, width - outerPad, top + rowH),
                minOf(dp(12f), rowH / 2f),
                minOf(dp(12f), rowH / 2f),
                paint
            )
            // 卡片统一白字：「够不够白」由 cardColor 的压深保证（见 courseColorFor）
            val nameColor = Color.WHITE
            val metaColor = 0xE6FFFFFF.toInt()

            val textLeft = outerPad + dp(9f)
            val textRight = width - outerPad - dp(9f)
            val textW = (textRight - textLeft).coerceAtLeast(1f)
            val room = course.room
            val twoLine = rowH >= dp(26f)
            val nameSize =
                if (twoLine) minOf(dp(13f), rowH * 0.38f)
                else (rowH * 0.58f).coerceIn(dp(10f), dp(14f))
            val metaSize = (nameSize * 0.72f).coerceIn(dp(8f), dp(10f))

            // 右侧状态：「剩 N 分」（上课中）优先，其次教室；两行版把教室放进第二行
            val status = when {
                countdown != null -> context.getString(com.lanhang.lite.R.string.widget_today_countdown, countdown)
                twoLine -> null
                else -> room.ifBlank { null }
            }
            var statusW = 0f
            if (!status.isNullOrEmpty()) {
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = metaSize
                statusW = minOf(paint.measureText(status), textW * 0.5f)
            }
            val nameAvail = (textW - if (statusW > 0f) statusW + dp(6f) else 0f)
                .coerceAtLeast(dp(10f))

            val blockH = nameSize + if (twoLine) dp(2f) + metaSize else 0f
            val blockTop = top + (rowH - blockH) / 2f
            val nameBaseline = blockTop + nameSize * 0.82f

            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = nameSize
            paint.color = nameColor
            paint.alpha = 255
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(ellipsize(course.name, nameAvail), textLeft, nameBaseline, paint)

            if (!status.isNullOrEmpty()) {
                val statusBaseline = if (twoLine) {
                    nameBaseline - (nameSize - metaSize) * 0.12f
                } else {
                    top + rowH / 2f + metaSize * 0.35f
                }
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = metaSize
                paint.color = metaColor
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(
                    ellipsize(status, statusW),
                    textRight,
                    statusBaseline,
                    paint
                )
                paint.textAlign = Paint.Align.LEFT
            }

            if (twoLine) {
                val metaTop = blockTop + nameSize + dp(2f)
                val meta = timeRange(course) + (if (room.isNotBlank()) " · $room" else "")
                paint.typeface = Typeface.DEFAULT
                paint.textSize = metaSize
                paint.color = metaColor
                canvas.drawText(
                    ellipsize(meta, textW),
                    textLeft,
                    metaTop + metaSize * 0.82f,
                    paint
                )
            }
        }

        /** 今天本来有课、但都已下课：和「今天没有课程」区分开，避免误以为课表丢了 */
        private fun drawDayDone(contentTop: Float, contentH: Float) {
            val centerY = contentTop + contentH / 2f
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(13f)
            paint.color = palette.onSurface
            canvas.drawText(
                context.getString(com.lanhang.lite.R.string.widget_today_done),
                width / 2f,
                centerY - dp(2f),
                paint
            )
            paint.typeface = Typeface.DEFAULT
            paint.textSize = dp(10f)
            paint.color = palette.onSurfaceVariant
            canvas.drawText(
                context.getString(
                    com.lanhang.lite.R.string.widget_today_done_hint,
                    snapshot.week
                ),
                width / 2f,
                centerY + dp(14f),
                paint
            )
            paint.textAlign = Paint.Align.LEFT
        }

        private fun drawNoCourse(contentTop: Float, contentH: Float) {
            val centerY = contentTop + contentH / 2f
            paint.textAlign = Paint.Align.CENTER
            paint.alpha = 255
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(13f)
            paint.color = palette.onSurface
            canvas.drawText(
                context.getString(
                    if (offsetDays == 0L) com.lanhang.lite.R.string.widget_today_empty
                    else com.lanhang.lite.R.string.widget_day_empty
                ),
                width / 2f,
                centerY - dp(4f),
                paint
            )
            paint.typeface = Typeface.DEFAULT
            paint.textSize = dp(10f)
            paint.color = palette.onSurfaceVariant
            canvas.drawText(
                context.getString(com.lanhang.lite.R.string.widget_empty_hint),
                width / 2f,
                centerY + dp(14f),
                paint
            )
        }

        /**
         * 底部：左侧胶囊（今天显示「第 N 周」，翻天时显示「昨天 / 明天 / ±N 天」），
         * 右侧是布局里的 ◀ ⟳ ▶ 三个按钮，所以胶囊的可用宽度必须扣掉整个按钮组，
         * 否则文字会被按钮压住。
         */
        private fun drawFooter(top: Float, footerH: Float) {
            val label = when (offsetDays) {
                0L -> context.getString(com.lanhang.lite.R.string.widget_week_format, snapshot.week)
                1L -> context.getString(com.lanhang.lite.R.string.widget_today_tomorrow)
                -1L -> context.getString(com.lanhang.lite.R.string.widget_today_yesterday)
                else -> context.getString(
                    com.lanhang.lite.R.string.widget_day_offset_format,
                    if (offsetDays > 0) "+$offsetDays" else "$offsetDays"
                )
            }
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(10f)
            // 胶囊右边界 = 按钮组左边界 - 6dp；再扣掉胶囊自身左右内边距（各 5dp）
            val navLeft = width - outerPad - dp(TODAY_NAV_GROUP_WIDTH_DP.toFloat())
            val maxTextW = (navLeft - dp(6f) - outerPad - dp(10f)).coerceAtLeast(dp(8f))
            // 2×2 用 154dp 宽度时，「第 4 周」放不下 → 退成「第4周」，再不够才省略号
            val text = if (paint.measureText(label) <= maxTextW) {
                label
            } else {
                val compact = if (offsetDays == 0L) {
                    context.getString(com.lanhang.lite.R.string.widget_week_compact, snapshot.week)
                } else {
                    label
                }
                ellipsize(compact, maxTextW)
            }
            val labelW = paint.measureText(text) + dp(10f)
            val pillH = minOf(dp(18f), footerH)
            val pillTop = top + (footerH - pillH) / 2f
            paint.color = palette.today
            canvas.drawRoundRect(
                RectF(outerPad, pillTop, outerPad + labelW, pillTop + pillH),
                pillH / 2f,
                pillH / 2f,
                paint
            )
            paint.color = palette.primary
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                text,
                outerPad + labelW / 2f,
                pillTop + pillH / 2f + paint.textSize * 0.35f,
                paint
            )
            paint.textAlign = Paint.Align.LEFT
            drawNavButtons(top, footerH)
        }

        /**
         * 右下角三个「翻天」按钮的圆角底色：◀ 前一天 │ ⟳ 回到今天 │ ▶ 后一天。
         *
         * - 两侧箭头只有静态底色；三角图标由布局里的 ImageView 提供（浅/深两套 drawable）
         * - 中间复位按钮有状态：**已经翻走**时用主色淡底（可点、有存在感），
         *   **就在今天**时与两侧同底色、图标置灰（禁用态），点击不产生任何变化
         *
         * 这里只画底板，所以几何常量必须与 `layout/widget_schedule_week.xml` 保持一致：
         * 24×22dp、间距 4dp、右边距 9dp（= outerPad）、垂直居中于底部条。
         */
        private fun drawNavButtons(top: Float, footerH: Float) {
            val btnW = dp(TODAY_NAV_BUTTON_WIDTH_DP.toFloat())
            val btnH = dp(TODAY_NAV_BUTTON_HEIGHT_DP.toFloat())
            val gap = dp(TODAY_NAV_GAP_DP.toFloat())
            val endPad = outerPad
            val btnTop = top + (footerH - btnH) / 2f
            val r = dp(7f)
            val nextRight = width - endPad
            val nextLeft = nextRight - btnW
            val resetRight = nextLeft - gap
            val resetLeft = resetRight - btnW
            val prevRight = resetLeft - gap
            val prevLeft = prevRight - btnW
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            val neutral = if (palette.isDark) 0x33FFFFFF else 0x17000000
            paint.color = neutral
            canvas.drawRoundRect(
                RectF(prevLeft, btnTop, prevRight, btnTop + btnH), r, r, paint
            )
            canvas.drawRoundRect(
                RectF(nextLeft, btnTop, nextRight, btnTop + btnH), r, r, paint
            )
            // 复位按钮：翻走 = 主色淡底高亮；在今天就 = 与两侧一致的静态底（图标另行置灰）
            paint.color = if (offsetDays != 0L) {
                withAlpha(palette.primary, if (palette.isDark) 0x3D else 0x2E)
            } else {
                neutral
            }
            canvas.drawRoundRect(
                RectF(resetLeft, btnTop, resetRight, btnTop + btnH), r, r, paint
            )
        }

        /** 只替换 alpha，保留 RGB（用于主色淡底） */
        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha shl 24)

        /** 节次时间区间，如「08:00-11:40」；单节为整段「08:00-08:45」 */
        private fun timeRange(course: CourseBlock): String =
            PeriodClock.rangeText(course.start.coerceIn(1, 11), course.end.coerceIn(1, 11))

        private fun ellipsize(text: String, maxWidth: Float): String {
            if (maxWidth <= 0f) return ""
            if (paint.measureText(text) <= maxWidth) return text
            var result = text
            while (result.isNotEmpty() && paint.measureText("$result…") > maxWidth) {
                result = result.dropLast(1)
            }
            return "$result…"
        }

        private fun dp(value: Float): Float = value * density
    }

    private class CanvasRenderer(
        private val canvas: Canvas,
        private val paint: Paint,
        private val context: Context,
        private val density: Float,
        private val width: Int,
        private val height: Int,
        private val palette: Palette,
        private val snapshot: WeekSnapshot
    ) {
        private val today = LocalDate.now()
        private val outerPad = dp(6f)
        private val compactHeader = width < dp(320f)
        /** 星期名随界面语言（跟随 values / values-en） */
        private val dayLabels = context.resources.getStringArray(com.lanhang.lite.R.array.schedule_day_names)
        private val compactDayLabels =
            context.resources.getStringArray(com.lanhang.lite.R.array.schedule_day_names_compact)
        private val headH = if (compactHeader) dp(36f) else dp(56f)
        private val timeW = if (width < dp(260f)) dp(36f) else dp(40f)
        private val gapH = dp(1.5f)
        private val gapW = dp(2.5f)
        private val bodyTop = outerPad + headH
        private val bodyBottom = height - outerPad
        private val bodyH = (bodyBottom - bodyTop).coerceAtLeast(1f)
        private val slotH = ((bodyH - gapH * 12f) / 11f).coerceAtLeast(dp(3f))
        private val dayW = ((width - outerPad * 2f - timeW - gapW * 8f) / 7f)
            .coerceAtLeast(1f)

        fun draw() {
            drawHeaders()
            drawTodayColumn()
            drawTimeColumn()
            val layouts = courseLayouts()
            drawCourses(layouts)
            if (snapshot.days.all { it.courses.isEmpty() }) {
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.DEFAULT
                paint.textSize = dp(12f)
                paint.color = palette.onSurfaceVariant
                paint.alpha = 255
                canvas.drawText(
                    context.getString(com.lanhang.lite.R.string.widget_no_course_week),
                    width / 2f,
                    bodyTop + bodyH / 2f,
                    paint
                )
            }
        }

        private fun drawHeaders() {
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.color = palette.header
            val radius = dp(14f)
            canvas.drawRoundRect(
                RectF(outerPad, outerPad, outerPad + timeW, outerPad + headH),
                radius,
                radius,
                paint
            )
            for (index in 0..6) {
                val x = outerPad + timeW + gapW + index * (dayW + gapW)
                val day = snapshot.days.getOrNull(index)
                val isToday = day?.date == today
                paint.color = if (isToday) palette.today else palette.header
                canvas.drawRoundRect(
                    RectF(x, outerPad, x + dayW, outerPad + headH),
                    radius,
                    radius,
                    paint
                )
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = if (compactHeader) {
                    (headH * 0.42f).coerceIn(dp(9f), dp(13f))
                } else {
                    (headH * 0.24f).coerceIn(dp(9f), dp(13f))
                }
                paint.color = if (isToday) palette.primary else palette.onSurface
                canvas.drawText(
                    if (compactHeader) compactDayLabels[index] else dayLabels[index],
                    x + dayW / 2f,
                    if (compactHeader) outerPad + headH * 0.60f else outerPad + headH * 0.4f,
                    paint
                )
                if (compactHeader) continue
                paint.typeface = Typeface.DEFAULT
                paint.textSize = (headH * 0.18f).coerceIn(dp(7f), dp(10f))
                paint.color = if (isToday) palette.primary else palette.onSurfaceVariant
                val date = day?.date?.format(dateFormatter).orEmpty()
                canvas.drawText(date, x + dayW / 2f, outerPad + headH * 0.72f, paint)
            }
        }

        private fun drawTodayColumn() {
            val todayIndex = snapshot.days.indexOfFirst { it.date == today }
            if (todayIndex < 0) return
            val x = outerPad + timeW + gapW + todayIndex * (dayW + gapW)
            paint.color = palette.today
            paint.alpha = 45
            canvas.drawRect(
                RectF(x, bodyTop, x + dayW, bodyBottom),
                paint
            )
            paint.alpha = 255
        }

        private fun drawTimeColumn() {
            paint.textAlign = Paint.Align.CENTER
            val centerX = outerPad + timeW / 2f
            for (index in 0 until 11) {
                val centerY = rowTop(index) + slotH / 2f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = (slotH * 0.4f).coerceIn(dp(7f), dp(13f))
                paint.color = palette.onSurface
                canvas.drawText(
                    (index + 1).toString(),
                    centerX,
                    centerY + paint.textSize * 0.34f,
                    paint
                )
                if (slotH > dp(18f)) {
                    paint.typeface = Typeface.DEFAULT
                    paint.textSize = (slotH * 0.2f).coerceIn(dp(4f), dp(7f))
                    paint.color = palette.onSurfaceVariant
                    canvas.drawText(
                        periodTimes[index],
                        centerX,
                        centerY + paint.textSize * 1.9f,
                        paint
                    )
                }
            }
        }

        private fun courseLayouts(): List<CourseLayout> {
            val result = mutableListOf<CourseLayout>()
            for (dayIndex in 0..6) {
                val day = snapshot.days.getOrNull(dayIndex) ?: continue
                val lanes = layoutLanes(day.courses)
                val colX = outerPad + timeW + gapW + dayIndex * (dayW + gapW)
                for (lane in lanes) {
                    val start = (lane.course.start - 1).coerceIn(0, 10)
                    val end = (lane.course.end - 1).coerceIn(start, 10)
                    val span = end - start + 1
                    val laneGap = if (lane.laneCount > 1) dp(1f) else 0f
                    val laneW = (dayW - laneGap * (lane.laneCount - 1)) / lane.laneCount
                    val left = colX + lane.lane * (laneW + laneGap)
                    val top = rowTop(start)
                    val cardH = slotH * span + gapH * (span - 1)
                    result.add(
                        CourseLayout(
                            course = lane.course,
                            left = left,
                            top = top,
                            width = laneW,
                            height = cardH
                        )
                    )
                }
            }
            return result
        }

        private fun drawCourses(layouts: List<CourseLayout>) {
            if (layouts.isEmpty()) return
            val nameSize = unifiedNameSize(layouts)
            for (layout in layouts) {
                val baseColor = courseColor(layout.course.name)
                val radius = minOf(dp(10f), minOf(layout.width, layout.height) / 2f)
                paint.style = Paint.Style.FILL
                paint.color = baseColor
                paint.alpha = blockAlpha()
                canvas.drawRoundRect(
                    RectF(
                        layout.left,
                        layout.top,
                        layout.left + layout.width,
                        layout.top + layout.height
                    ),
                    radius,
                    radius,
                    paint
                )
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(0.5f)
                paint.color = baseColor
                paint.alpha = 80
                canvas.drawRoundRect(
                    RectF(
                        layout.left,
                        layout.top,
                        layout.left + layout.width,
                        layout.top + layout.height
                    ),
                    radius,
                    radius,
                    paint
                )
                paint.style = Paint.Style.FILL
                paint.strokeWidth = 0f
                paint.alpha = 255
                val textColor = Color.WHITE
                drawCourseName(layout, nameSize, textColor)
                drawCourseRoom(layout, nameSize, textColor)
            }
        }

        private fun unifiedNameSize(layouts: List<CourseLayout>): Float {
            var size = dp(13f)
            for (layout in layouts) {
                val tokens = tokenize(layout.course.name)
                val units = tokens.sumOf { it.units.toDouble() }.toFloat().coerceAtLeast(1f)
                val roomReserve = roomReserve(layout)
                val available = (layout.height - dp(8f) - roomReserve).coerceAtLeast(1f)
                val ideal = minOf(available / units, layout.width * 0.78f)
                if (ideal < size) size = ideal
            }
            return size.coerceIn(dp(7f), dp(13f))
        }

        private fun roomReserve(layout: CourseLayout): Float {
            if (layout.course.room.isBlank() || layout.height < dp(14f)) return 0f
            return minOf(dp(7f), layout.height * 0.28f)
        }

        private fun drawCourseName(layout: CourseLayout, nameSize: Float, textColor: Int) {
            val tokens = tokenize(layout.course.name)
            if (tokens.isEmpty()) return
            val pad = dp(4f)
            val roomReserve = roomReserve(layout)
            val available = (layout.height - pad * 2f - roomReserve).coerceAtLeast(1f)
            val blockUnits = tokens.sumOf { it.units.toDouble() }.toFloat()
            val maxUnits = available / nameSize
            val truncated = blockUnits > maxUnits + 0.01f
            val limit = if (truncated) (maxUnits - 1f).coerceAtLeast(0f) else maxUnits
            val visibleUnits = if (truncated) maxUnits else blockUnits
            var y = layout.top + pad + ((available - visibleUnits * nameSize).coerceAtLeast(0f) / 2f)
            var used = 0f
            val centerX = layout.left + layout.width / 2f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.color = textColor
            paint.alpha = 255
            for (token in tokens) {
                if (used + token.units > limit + 0.01f) break
                val tokenH = token.units * nameSize
                if (token.rotated) {
                    canvas.save()
                    canvas.translate(centerX, y + tokenH / 2f)
                    canvas.rotate(90f)
                    paint.textSize = nameSize
                    canvas.drawText(token.text, 0f, nameSize * 0.35f, paint)
                    canvas.restore()
                } else {
                    paint.textSize = nameSize
                    canvas.drawText(token.text, centerX, y + nameSize * 0.82f, paint)
                }
                y += tokenH
                used += token.units
            }
            if (truncated && used + 1f <= maxUnits + 0.01f) {
                paint.textSize = nameSize
                canvas.drawText("…", centerX, y + nameSize * 0.82f, paint)
            }
        }

        private fun drawCourseRoom(layout: CourseLayout, nameSize: Float, textColor: Int) {
            val room = layout.course.room
            if (room.isBlank() || layout.height < dp(14f)) return
            val pad = dp(4f)
            val roomSize = minOf(dp(7f), nameSize * 0.66f).coerceAtLeast(dp(4f))
            paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = roomSize
            paint.color = textColor
            paint.alpha = 255
            val maxWidth = (layout.width - pad * 2f).coerceAtLeast(1f)
            val text = ellipsize(room, maxWidth)
            canvas.drawText(
                text,
                layout.left + layout.width / 2f,
                layout.top + layout.height - pad - roomSize * 0.25f,
                paint
            )
        }

        private fun ellipsize(text: String, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var result = text
            while (result.isNotEmpty() && paint.measureText("$result…") > maxWidth) {
                result = result.dropLast(1)
            }
            return "$result…"
        }

        private fun layoutLanes(courses: List<CourseBlock>): List<LaneCourse> {
            if (courses.isEmpty()) return emptyList()
            val sorted = courses.sortedWith(compareBy({ it.start }, { it.end }))
            val result = mutableListOf<LaneCourse>()
            var index = 0
            while (index < sorted.size) {
                val cluster = mutableListOf<CourseBlock>()
                var clusterEnd = -1
                var next = index
                while (next < sorted.size &&
                    (cluster.isEmpty() || sorted[next].start <= clusterEnd)
                ) {
                    cluster.add(sorted[next])
                    if (sorted[next].end > clusterEnd) clusterEnd = sorted[next].end
                    next++
                }
                val laneEnds = mutableListOf<Int>()
                val assignments = mutableListOf<Pair<CourseBlock, Int>>()
                for (course in cluster) {
                    var lane = laneEnds.indexOfFirst { it < course.start }
                    if (lane < 0) {
                        lane = laneEnds.size
                        laneEnds.add(course.end)
                    } else {
                        laneEnds[lane] = course.end
                    }
                    assignments.add(course to lane)
                }
                val laneCount = laneEnds.size.coerceAtLeast(1)
                for ((course, lane) in assignments) {
                    result.add(LaneCourse(course, lane, laneCount))
                }
                index = next
            }
            return result
        }

        private fun rowTop(index: Int): Float {
            return bodyTop + gapH + index * (slotH + gapH)
        }

        /**
         * 4×4 网格的色块是半透明画的（alpha 见 [drawCourses]），叠在卡片底色上，
         * 白字的对比度必须按叠底后的颜色算，所以这里把 alpha/behind 一起传进去。
         */
        private fun courseColor(name: String): Int =
            courseColorFor(name, palette.isDark, blockAlpha(), palette.background)

        private fun blockAlpha(): Int = if (palette.isDark) 220 else 205

        private fun tokenize(name: String): List<TextToken> {
            val source = name.filter { it != '\n' && it != ' ' }
            if (source.isEmpty()) return emptyList()
            val tokens = mutableListOf<TextToken>()
            val latin = StringBuilder()
            fun flushLatin() {
                if (latin.isEmpty()) return
                val text = latin.toString()
                val units = if (text.length > 1) {
                    paint.textSize = 1f
                    paint.measureText(text)
                } else {
                    1f
                }
                tokens.add(TextToken(text, text.length > 1, units))
                latin.clear()
            }
            for (char in source) {
                val isLatin = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9'
                if (isLatin) {
                    latin.append(char)
                } else {
                    flushLatin()
                    tokens.add(TextToken(char.toString(), false, 1f))
                }
            }
            flushLatin()
            return tokens
        }

        private fun dp(value: Float): Float = value * density
    }

    private data class CourseLayout(
        val course: CourseBlock,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    private data class LaneCourse(
        val course: CourseBlock,
        val lane: Int,
        val laneCount: Int
    )

    private data class TextToken(
        val text: String,
        val rotated: Boolean,
        val units: Float
    )

    private data class Palette(
        val background: Int,
        val header: Int,
        val today: Int,
        val primary: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val isDark: Boolean
    )

    private fun palette(context: Context): Palette {
        val night = when (Session.themeMode) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return if (night) {
            Palette(
                background = 0xFF0D1B24.toInt(),
                header = 0xFF07141C.toInt(),
                today = 0xFF123B52.toInt(),
                primary = 0xFF8ED8F8.toInt(),
                onSurface = 0xFFE8F4FA.toInt(),
                onSurfaceVariant = 0xFF9FB6C5.toInt(),
                isDark = true
            )
        } else {
            Palette(
                background = 0xFFEAF2F8.toInt(),
                header = 0xFFF8FBFE.toInt(),
                today = 0xFFD4E9F7.toInt(),
                primary = 0xFF084168.toInt(),
                onSurface = 0xFF14232D.toInt(),
                onSurfaceVariant = 0xFF5E7482.toInt(),
                isDark = false
            )
        }
    }
}
