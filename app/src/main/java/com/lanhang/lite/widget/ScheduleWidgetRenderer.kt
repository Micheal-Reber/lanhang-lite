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
import com.lanhang.lite.schedule.ScheduleStyle
import com.lanhang.lite.session.Session
import com.lanhang.lite.core.LocaleHelper
import com.lanhang.lite.widget.ScheduleWidgetData.CourseBlock
import com.lanhang.lite.widget.ScheduleWidgetData.WeekSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

object ScheduleWidgetRenderer {

    /** 4×4 表头的日期格式：与课表页表头的「10/26」一致（不再用「10-26」） */
    private val todayDateFormatter = DateTimeFormatter.ofPattern("M/d")

    fun render(
        appContext: Context,
        snapshot: WeekSnapshot?,
        widthDp: Int,
        heightDp: Int
    ): Bitmap {
        // 小部件文案跟着应用语言走（桌面进程默认给的是系统语言），见 LocaleHelper
        val context = LocaleHelper.wrap(appContext)
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt().coerceAtLeast(1)
        val height = (heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = palette(context)
        // 卡片圆角对齐 M3 shape token（corner_lg 16dp），与 App 内卡片同一档
        val radius = 16f * density
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

    /** 2×2 底栏（胶囊 ◀ ⟳ ▶）占用的高度阈值：低于该高度完全不显示底栏 */
    const val FOOTER_MIN_HEIGHT_DP = 130

    /** 底栏高度：位图要在内容区里给它留出这么高，布局里的底栏也正好这么高 */
    const val FOOTER_HEIGHT_DP = 22

    /**
     * 今日课程最多同时显示的节数。
     *
     * 规则：上完的课立刻从小部件里消失，后面的课依次顶上来，所以任何时刻最多只有
     * 这么几节「还没上完」的课在列（不是按当天总节数截断）。
     */
    const val MAX_TODAY_ROWS = 3

    /* ---------- 4×4 周视图：课程名排版（竖排、最多两列、整组居中） ---------- */

    /** 课程名字号上限（块够高够宽时用这个；与课表页 11sp 的字号观感对齐，不是越大越好） */
    private const val NAME_MAX_SIZE_DP = 12f

    /** 课程名字号下限：再小就糊成一团，宁可用「…」截断 */
    private const val NAME_MIN_SIZE_DP = 7f

    /**
     * 竖排行距倍数（相对字号）：一个字一行的字距。
     *
     * 课程名是竖排的（用户要求「竖着两排」）——横排会把色块左右撑满，
     * 竖排只占两个字宽，块内两侧都留白。
     *
     * 1.14 而不是 1.0：汉字字形高约 1.0em，但基线以下还有 descent（约 0.22em），
     * 行距太紧最后一行会压到底部的教室行上。
     */
    private const val NAME_ROW_SPACING = 1.14f

    /**
     * 竖排列间距（相对字号）：两列之间留这么多空。
     *
     * 0.30 而不是 0.12：列距太近时「高等数学B3」会显示成一行行的「高学 / 等B / 数3」，
     * 眼睛会当成横向词组去读；拉开到 0.3 个字宽，两列才各自成行（列内仍按自上而下读）。
     */
    private const val NAME_COL_GAP = 0.30f

    /** 课程名最多排几列：固定两列，再多就不是「竖着两排」了 */
    private const val NAME_MAX_COLS = 2

    /** 课程名左右内边距（窄列里必须小，否则字数的余量全被 pad 吃掉） */
    private const val NAME_PAD_H_DP = 2.5f

    /** 课程名上下内边距 */
    private const val NAME_PAD_V_DP = 2f

    /** 当前主题是否为深色（胶囊/按钮底色、图标颜色与此保持一致） */
    fun isDark(context: Context): Boolean = palette(context).isDark

    /**
     * 底栏胶囊文案：今天「第 N 周」，翻走后「明天 / 昨天 / +N 天」。
     *
     * 胶囊现在是布局里的 TextView（位图不再画画它），所以文案由这里出、由
     * [ScheduleWidgetUpdater] 绑上去；放不下时交给 TextView 自己 ellipsize。
     *
     * @param compact 窄尺寸（vivo 等桌面的 2×2 实测只有 110~130dp）用无空格短排版，
     *   同样的宽度能多显示一两个字，避免退化成「…」
     */
    fun footerLabel(appContext: Context, week: Int, target: LocalDate, compact: Boolean): String {
        val context = LocaleHelper.wrap(appContext)
        val offset = ChronoUnit.DAYS.between(LocalDate.now(), target)
        return when (offset) {
            0L -> context.getString(
                if (compact) {
                    com.lanhang.lite.R.string.widget_week_compact
                } else {
                    com.lanhang.lite.R.string.widget_week_format
                },
                week
            )
            1L -> context.getString(com.lanhang.lite.R.string.widget_today_tomorrow)
            -1L -> context.getString(com.lanhang.lite.R.string.widget_today_yesterday)
            else -> context.getString(
                com.lanhang.lite.R.string.widget_day_offset_format,
                if (offset > 0) "+$offset" else "$offset"
            )
        }
    }

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
        appContext: Context,
        snapshot: WeekSnapshot?,
        widthDp: Int,
        heightDp: Int,
        targetDate: LocalDate? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): Bitmap {
        // 同 render：小部件文案跟着应用语言走
        val context = LocaleHelper.wrap(appContext)
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

    /** 倒计时进入「快下课」的阈值（分钟）：≤10 分钟转红提醒 */
    private const val COUNTDOWN_URGENT_MINUTES = 10

    /** 倒计时常态色：亮蓝，避免整行白字过于单调 */
    private const val COUNTDOWN_CALM_COLOR = 0xFF4FC3F7.toInt()

    /** 倒计时紧急色：亮红，与课程卡的暖色系区分明显 */
    private const val COUNTDOWN_URGENT_COLOR = 0xFFFF5252.toInt()

    /** 倒计时胶囊的压暗比例（把卡片色压深，蓝/红字才够对比） */
    private const val COUNTDOWN_BADGE_DEPTH = 0.26f

    /** 倒计时文字的对比度下限（9~10sp 加粗，WCAG AA 小字取 3:1） */
    private const val MIN_COUNTDOWN_CONTRAST = 3.0

    /**
     * 倒计时文字色：常态蓝、≤[COUNTDOWN_URGENT_MINUTES] 分钟转红。
     * 与胶囊底色对比不足时逐步提亮（保留色相），最差回落白色，保证任何卡片色都读得清。
     */
    private fun countdownTextColor(countdown: Int, badgeColor: Int): Int {
        val base = if (countdown <= COUNTDOWN_URGENT_MINUTES) {
            COUNTDOWN_URGENT_COLOR
        } else {
            COUNTDOWN_CALM_COLOR
        }
        if (ColorUtils.calculateContrast(base, badgeColor) >= MIN_COUNTDOWN_CONTRAST) return base
        var current = base
        repeat(6) {
            current = ColorUtils.blendARGB(current, Color.WHITE, 0.20f)
            if (ColorUtils.calculateContrast(current, badgeColor) >= MIN_COUNTDOWN_CONTRAST) {
                return current
            }
        }
        return Color.WHITE
    }

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
            // 底栏（胶囊 + ◀ ⟳ ▶）是布局里的真实 View，位图只负责给它留出高度
            val footerH = if (height >= dp(FOOTER_MIN_HEIGHT_DP.toFloat())) {
                dp(FOOTER_HEIGHT_DP.toFloat())
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
            // 倒计时做成小胶囊，文字两侧各留 [badgePadH]；上限放宽到 62%，
            // 保证「剩 105 分」这类三位数分钟也能完整显示（课程名有 ellipsize 兜底）
            val badgePadH = if (countdown != null) dp(5f) else 0f
            var statusW = 0f
            if (!status.isNullOrEmpty()) {
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = metaSize
                val maxStatusW = (textW * 0.62f - badgePadH * 2f).coerceAtLeast(dp(10f))
                statusW = minOf(paint.measureText(status), maxStatusW)
            }
            val nameAvail = (textW - if (statusW > 0f) statusW + badgePadH * 2f + dp(6f) else 0f)
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
                val shown = ellipsize(status, statusW)
                if (countdown != null) {
                    // 「剩 N 分」先垫一块压暗的胶囊：蓝/红字在任何课程色卡片上都够对比，
                    // 也不会因为卡片恰好是蓝色系而糊在一起。
                    val badgeColor = ColorUtils.blendARGB(cardColor, Color.BLACK, COUNTDOWN_BADGE_DEPTH)
                    val badgeH = (metaSize + dp(4f)).coerceAtMost(rowH * 0.92f)
                    val badgeW = paint.measureText(shown) + badgePadH * 2f
                    val centerY = statusBaseline - metaSize * 0.35f
                    paint.color = badgeColor
                    canvas.drawRoundRect(
                        RectF(
                            textRight - badgeW,
                            centerY - badgeH / 2f,
                            textRight,
                            centerY + badgeH / 2f
                        ),
                        badgeH / 2f,
                        badgeH / 2f,
                        paint
                    )
                    paint.color = countdownTextColor(countdown, badgeColor)
                } else {
                    paint.color = metaColor
                }
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(
                    shown,
                    textRight - badgePadH,
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

        /** 节次时间区间，如「08:00-11:40」；单节为整段「08:00-08:45」 */
        private fun timeRange(course: CourseBlock): String =
            PeriodClock.rangeText(
                course.start.coerceIn(1, PeriodClock.count),
                course.end.coerceIn(1, PeriodClock.count)
            )

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

    /**
     * 4×4 周视图。
     *
     * 视觉口径**对齐课表页**（`ScheduleFragment.renderGrid`），不再是一张自成一派的彩块图：
     * - 表头是**纯文字两行**（周几 + 日期），今天用主色强调，没有色块底板（旧版每列一块圆角实色块，
     *   最左时间列那块还是空的，看着像没画完）；
     * - 表格是**无缝网格 + 1dp 分隔线**（旧版靠 1.5/2.5dp 的缝分隔，块像浮在卡片上）；
     * - 课程卡走 M3 **tonal**（低饱和容器 + 同色相文字，见 `ScheduleStyle.courseColors`），
     *   不再是高饱和底 + 白字；
     * - 圆角统一到 shape token：卡片 16dp（corner_lg）、课程块 8dp（corner_sm）。
     *
     * 几何仍按像素手算（小部件尺寸随桌面变化，不能走布局 weight），但间距/圆角/字号取 token 的量级。
     */
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

        /** 星期名随界面语言（跟随 values / values-en） */
        private val dayLabels =
            context.resources.getStringArray(com.lanhang.lite.R.array.schedule_day_names)

        private val outerPad = dp(8f)

        /** 窄/矮小部件（≈3 列宽以下）用紧凑表头，否则两行文字会顶满 */
        private val compactHeader = width < dp(300f) || height < dp(240f)
        private val headH = if (compactHeader) dp(38f) else dp(46f)
        private val daySize = if (compactHeader) dp(10f) else dp(12f)
        private val dateSize = if (compactHeader) dp(8f) else dp(9.5f)

        /**
         * 节次列宽度。44dp 刚好放得下 11sp 的节次号和 8sp 的「08:00」（两侧各留 ~6dp），
         * 再窄就只能截断成「08:0…」，比分两行更难读。
         */
        private val timeW = if (width < dp(260f)) dp(36f) else dp(44f)

        /** 行/列间隙 = 分隔线所在的缝（线画在缝中央，两侧各留 0.5dp） */
        private val gapH = dp(2f)
        private val gapW = dp(2f)
        private val lineW = dp(1f)

        /**
         * 实际要画的节次数 = 整周最晚那节课的下课节次。
         *
         * 自适应收缩是 4×4 这类小部件能看的关键：12 节全画时每行只有 ~18dp，夹在两行
         * 文字之间；整周最晚只到第 8 节的话，把 11/12 节省掉，行高直接涨 1/3。
         *
         * 下限 4 行：再少就不像「一天的形状」了，而且单行会被拉成一块很厚的色块。
         * 整周无课时退化成 [EMPTY_PERIOD_ROWS]，留给「本周无课」那句提示居中。
         */
        private val periodRows: Int = run {
            val maxEnd = snapshot.days.asSequence()
                .flatMap { it.courses.asSequence() }
                .maxOfOrNull { it.end } ?: 0
            if (maxEnd <= 0) EMPTY_PERIOD_ROWS else maxEnd.coerceIn(4, PeriodClock.count)
        }

        /**
         * 实际要画的星期列数 = 最后一个有课的那天 + 1（周六周日没课就不占位置）。
         *
         * 中间断档（如周五没课、周六有课）不能跳列，否则日期与列对不上 —— 只裁尾部。
         * 下限 3 列：只剩一两天有课时，一天独占整个宽度反而更难看。
         * 整周无课时保留满 7 列，让表头的日期仍有参考价值。
         */
        private val dayCols: Int = run {
            val lastBusy = snapshot.days.indexOfLast { it.courses.isNotEmpty() }
            if (lastBusy < 0) 7 else (lastBusy + 1).coerceIn(3, 7)
        }

        private val bodyTop = outerPad + headH
        private val bodyBottom = height - outerPad
        private val bodyH = (bodyBottom - bodyTop).coerceAtLeast(1f)
        // 行高按**实际要画的节数**算，不是按作息表节数：省下来的空节全部变成可读高度
        private val slotH =
            ((bodyH - gapH * (periodRows + 1f)) / periodRows).coerceAtLeast(dp(3f))
        private val dayW = ((width - outerPad * 2f - timeW - gapW * (dayCols + 1)) / dayCols)
            .coerceAtLeast(1f)

        /** 今天整列淡底：浅色用容器色（白底上不发灰），深色用主色降低透明度 */
        private val todayTint: Int =
            if (palette.isDark) {
                ColorUtils.setAlphaComponent(palette.primary, 36)
            } else {
                palette.today
            }

        fun draw() {
            drawHeader()
            drawTodayColumn()
            drawGridLines()
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

        private fun colX(index: Int): Float =
            outerPad + timeW + gapW + index * (dayW + gapW)

        /** 列分隔线的横坐标：d=0 是时间列右边界，d=1..6 是星期列之间 */
        private fun separatorX(d: Int): Float =
            if (d == 0) outerPad + timeW + gapW / 2f else colX(d - 1) + dayW + gapW / 2f

        /**
         * 表头：两行纯文字（周几 + 日期），今天整列用主色。
         *
         * 不做底色块是刻意的：课表页的表头就是纯文字 + 一条分隔线，
         * 7 个实色圆角块在深色卡片上又重又碎，还把「今天」的强调稀释掉了。
         */
        private fun drawHeader() {
            val blockH = daySize + dp(2f) + dateSize
            val top = outerPad + (headH - blockH) / 2f
            paint.alpha = 255
            for (index in 0 until dayCols) {
                val day = snapshot.days.getOrNull(index)
                val isToday = day?.date == today
                val centerX = colX(index) + dayW / 2f
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = daySize
                paint.color = if (isToday) palette.primary else palette.onSurface
                canvas.drawText(
                    dayLabels[index],
                    centerX,
                    top + daySize * 0.82f,
                    paint
                )
                paint.typeface = Typeface.DEFAULT
                paint.textSize = dateSize
                paint.color = if (isToday) palette.primary else palette.onSurfaceVariant
                canvas.drawText(
                    day?.date?.format(todayDateFormatter).orEmpty(),
                    centerX,
                    top + daySize + dp(2f) + dateSize * 0.82f,
                    paint
                )
            }
            // 表头下方通栏分隔线（含时间列，与课表页一致）
            paint.style = Paint.Style.FILL
            paint.color = palette.divider
            canvas.drawRect(
                RectF(outerPad, outerPad + headH, width - outerPad, outerPad + headH + lineW),
                paint
            )
        }

        private fun drawTodayColumn() {
            val todayIndex = snapshot.days.indexOfFirst { it.date == today }
            // 今天落在被裁掉的周末列里（且那天没课）时不画，否则会涂到表格右侧外面
            if (todayIndex < 0 || todayIndex >= dayCols) return
            val x = colX(todayIndex)
            paint.style = Paint.Style.FILL
            // 底色自带 alpha，paint.alpha 必须复位，否则会被上一步（教室行 209）二次压淡
            paint.alpha = 255
            paint.color = todayTint
            canvas.drawRect(RectF(x, bodyTop, x + dayW, bodyBottom), paint)
        }

        /**
         * 网格线：每节一条横线 + 每列一条竖线。
         *
         * 课表页靠 1dp 的 `colorOutlineVariant` 分隔，这里同口径、只是压得更淡 ——
         * 小部件贴在壁纸上，线太重会抢走课程卡的注意力。
         * 课程块后画（见 [draw]），跨节卡会自然盖住块内部的横线。
         */
        private fun drawGridLines() {
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.color = palette.divider
            val left = outerPad + timeW + (gapW - lineW) / 2f
            // 每节底边一条线；最后一节的底边就是 body 下沿，不再单独画
            for (index in 0 until periodRows - 1) {
                val y = rowTop(index) + slotH + (gapH - lineW) / 2f
                canvas.drawRect(RectF(left, y, width - outerPad, y + lineW), paint)
            }
            for (d in 0..dayCols) {
                val x = separatorX(d) - lineW / 2f
                canvas.drawRect(RectF(x, bodyTop, x + lineW, bodyBottom), paint)
            }
        }

        /**
         * 左侧节次列：节次号 + **开始钟点**。
         *
         * 为什么不是「08:00-08:45」整段：4×4 的节次列只有 44dp，整段在 7.5sp 下也会被截成
         * 「08:00-08…」（实测），比分两行更糟。开始钟点是「这节课几点上」的唯一答案，
         * 下课时间可以由色块跨几节 + 课表页推算，所以这里只报开始。
         *
         * 两档自适应（4×4 这类矮小部件每行只有 ~18-20dp，两行放不下）：
         *  - 行高 ≥ 22dp：节次号在上、开始时间在下（与设计预览图一致）。
         *  - 12dp ≤ 行高 < 22dp：改成一行「节次号 开始时间」，字号随行高缩放；
         *    单行渲染绝不与相邻行竖向重叠，时间也不会再整列消失。
         *  - 更低：只留节次号。
         */
        private fun drawTimeColumn() {
            val centerX = outerPad + timeW / 2f
            val twoLine = slotH >= dp(22f)
            val showStart = twoLine || slotH >= dp(12f)
            for (index in 0 until periodRows) {
                val centerY = rowTop(index) + slotH / 2f
                val startText = PeriodClock.startText(index + 1).orEmpty()
                when {
                    twoLine -> {
                        val numberSize = dp(11f)
                        val startSize = dp(8f)
                        // 让「节次号中心」与「开始时间中心」的中点落在行垂直中心
                        val numberBaseline =
                            centerY - (startSize - 0.35f * numberSize) / 2f
                        paint.textAlign = Paint.Align.CENTER
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        paint.textSize = numberSize
                        paint.color = palette.onSurface
                        paint.alpha = 255
                        canvas.drawText(
                            (index + 1).toString(), centerX, numberBaseline, paint
                        )
                        paint.typeface = Typeface.DEFAULT
                        paint.textSize = startSize
                        paint.color = palette.onSurfaceVariant
                        canvas.drawText(
                            startText, centerX, numberBaseline + startSize * 1.35f, paint
                        )
                    }
                    showStart && startText.isNotEmpty() -> {
                        // 单行「N 08:00」：按行高缩放字号，横向居中
                        val numberSize = (slotH * 0.62f).coerceIn(dp(8f), dp(10f))
                        val startSize = (slotH * 0.5f).coerceIn(dp(6.5f), dp(8.5f))
                        val gap = dp(1.5f)
                        paint.textAlign = Paint.Align.LEFT
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        paint.textSize = numberSize
                        val numberW = paint.measureText((index + 1).toString())
                        paint.typeface = Typeface.DEFAULT
                        paint.textSize = startSize
                        val startW = paint.measureText(startText)
                        val left = centerX - (numberW + gap + startW) / 2f
                        val baseline = centerY + numberSize * 0.35f
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        paint.textSize = numberSize
                        paint.color = palette.onSurface
                        paint.alpha = 255
                        canvas.drawText(
                            (index + 1).toString(), left, baseline, paint
                        )
                        paint.typeface = Typeface.DEFAULT
                        paint.textSize = startSize
                        paint.color = palette.onSurfaceVariant
                        canvas.drawText(startText, left + numberW + gap, baseline, paint)
                    }
                    else -> {
                        val numberSize = (slotH * 0.55f).coerceIn(dp(7f), dp(11f))
                        paint.textAlign = Paint.Align.CENTER
                        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        paint.textSize = numberSize
                        paint.color = palette.onSurface
                        paint.alpha = 255
                        canvas.drawText(
                            (index + 1).toString(),
                            centerX,
                            centerY + numberSize * 0.35f,
                            paint
                        )
                    }
                }
            }
        }

        private fun courseLayouts(): List<CourseLayout> {
            val result = mutableListOf<CourseLayout>()
            for (dayIndex in 0 until dayCols) {
                val day = snapshot.days.getOrNull(dayIndex) ?: continue
                val lanes = layoutLanes(day.courses)
                val colX = outerPad + timeW + gapW + dayIndex * (dayW + gapW)
                for (lane in lanes) {
                    // 夹到可见行内：periodRows 已覆盖整周最晚的下课节次，这里只是防御脏数据
                    val start = (lane.course.start - 1).coerceIn(0, periodRows - 1)
                    val end = (lane.course.end - 1).coerceIn(start, periodRows - 1)
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
            for (layout in layouts) {
                // 与课表页同一套 M3 tonal 配色（低饱和容器 + 同色相文字），不再是高饱和底 + 白字
                val tone = ScheduleStyle.courseColors(layout.course.name, palette.isDark)
                val radius = minOf(dp(8f), minOf(layout.width, layout.height) / 2f)
                paint.style = Paint.Style.FILL
                paint.color = tone.container
                paint.alpha = 255
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
                // 字号**按块自适应**：4×4 里同一周既有整列宽的块，也有并排分成两半的窄块，
                // 取全网格最小值会把整周的字一起压到下限（并行块只有半列宽）。
                if (layout.height < slotH * 1.6f) {
                    // 单节课：一行的高度排不下竖排（一列一个字 → 「体…」），退回横排
                    drawShortName(layout, tone.onContainer)
                } else {
                    val nameSize = fitNameSize(layout, NAME_MAX_SIZE_DP * density)
                    drawCourseName(layout, nameSize, tone.onContainer)
                    drawCourseRoom(layout, nameSize, tone.onContainer)
                }
            }
        }

        /**
         * 单节块（高度≈一行）的课名：**横排**、字号在 7~11dp 内按宽度自适应。
         *
         * 竖排两列在 24dp 高的块里每列只能放一个字，3 个字的课名会被截成「体…」——
         * 为了保持"竖排"的形制而丢掉课名本身，是本末倒置。横排在 39dp 宽的列里
         * 放得下 3~4 个字（课表页也是横排），这一档不画教室：这行没有第二行的高度可用。
         */
        private fun drawShortName(layout: CourseLayout, textColor: Int) {
            val text = layout.course.name
            if (text.isBlank()) return
            val maxW = (layout.width - dp(6f)).coerceAtLeast(dp(4f))
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            var size = dp(11f)
            while (size > dp(7f)) {
                paint.textSize = size
                if (paint.measureText(text) <= maxW) break
                size -= 0.5f * density
            }
            paint.textSize = size
            paint.textAlign = Paint.Align.CENTER
            paint.color = textColor
            paint.alpha = 255
            canvas.drawText(
                ellipsize(text, maxW),
                layout.left + layout.width / 2f,
                layout.top + layout.height / 2f + size * 0.35f,
                paint
            )
        }

        /**
         * 该块能**完整放下**课程名的最大字号。
         *
         * 课程名是**竖排**（一个字一行），一列排不下就折到右边的第二列，
         * 最多 [NAME_MAX_COLS] 列，整组在色块里水平 + 垂直居中。
         * 判定：① 列数不超过两列 ② 每列行数 × 行距不超可用高度 ③ 两列总宽不超块宽。
         */
        private fun fitNameSize(layout: CourseLayout, start: Float): Float {
            val count = nameCharCount(layout.course.name)
            if (count == 0) return start
            val availW = nameMaxWidth(layout)
            val availH = nameAvailHeight(layout)
            var size = start
            while (size > NAME_MIN_SIZE_DP * density) {
                if (fitsColumns(count, size, availW, availH)) return size
                size -= 0.5f * density
            }
            return NAME_MIN_SIZE_DP * density
        }

        /** 该字号下能否排进最多 [NAME_MAX_COLS] 列，且两列总宽、每列高度都够 */
        private fun fitsColumns(count: Int, size: Float, availW: Float, availH: Float): Boolean {
            val rows = rowsPerColumn(size, availH)
            val cols = ceilDiv(count, rows)
            if (cols > NAME_MAX_COLS) return false
            return groupWidth(cols, size) <= availW + 0.01f
        }

        /** 一列最多放几个字（按可用高度与竖排行距算） */
        private fun rowsPerColumn(size: Float, availH: Float): Int =
            (availH / (size * NAME_ROW_SPACING)).toInt().coerceAtLeast(1)

        /** 整组文字的宽度：列宽 = 一个字宽，列间留 [NAME_COL_GAP] 个字的间隙 */
        private fun groupWidth(cols: Int, size: Float): Float {
            if (cols <= 1) return size
            return cols * size + (cols - 1) * size * NAME_COL_GAP
        }

        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /** 课程名逐字拆开（竖排一格一个字），空格丢弃 */
        private fun nameChars(name: String): List<String> =
            name.filterNot { it == '\n' || it == ' ' || it == '\u3000' }.map { it.toString() }

        private fun nameCharCount(name: String): Int =
            name.count { it != '\n' && it != ' ' && it != '\u3000' }

        /** 课程名可用的横向空间（扣掉左右内边距） */
        private fun nameMaxWidth(layout: CourseLayout): Float =
            (layout.width - NAME_PAD_H_DP * density * 2f).coerceAtLeast(1f)

        /** 课程名可用的纵向空间（扣掉上下内边距与底部的教室行） */
        private fun nameAvailHeight(layout: CourseLayout): Float =
            (layout.height - NAME_PAD_V_DP * density * 2f - roomReserve(layout)).coerceAtLeast(1f)

        /**
         * 底部教室行要预留的高度。
         *
         * 不能只留 7dp：教室字号约 7dp、基线距底约 5.6dp，字形顶部实际到距底 ~12dp，
         * 预留不足会让最后一行课程名和教室叠在一起（vivo 200% 字体缩放下更明显）。
         * 块太矮（单节）时按比例缩，最多占 32%。
         */
        private fun roomReserve(layout: CourseLayout): Float {
            if (layout.course.room.isBlank() || layout.height < dp(14f)) return 0f
            return minOf(dp(12f), layout.height * 0.32f)
        }

        /**
         * 课程名：**竖排、最多两列、整组在色块正中间**。
         *
         * 一个字一行往下排，一列排满就折到右边第二列（左列从「第一个字」开始，
         * 右列承接剩下的）——这样只占两个字的宽度，块里左右都留白，
         * 不会像横排那样把整块宽度撑满。两列整体水平居中、整体垂直居中。
         */
        private fun drawCourseName(layout: CourseLayout, nameSize: Float, textColor: Int) {
            val chars = nameChars(layout.course.name)
            if (chars.isEmpty()) return
            val availH = nameAvailHeight(layout)
            val rowH = nameSize * NAME_ROW_SPACING
            val rowsByHeight = rowsPerColumn(nameSize, availH)
            var shown = chars.size
            var cols = ceilDiv(chars.size, rowsByHeight)
            if (cols > NAME_MAX_COLS) {
                // 字号已到下限还排不下：固定在两列，多余的截断
                cols = NAME_MAX_COLS
                shown = minOf(chars.size, cols * rowsByHeight)
            }
            // 字数装得下一列就只排一列（短课名不该被硬拆成两列）
            cols = minOf(cols, ceilDiv(shown, rowsByHeight))
            val rows = ceilDiv(shown, cols)
            val truncated = shown < chars.size
            val drawable = if (truncated) (shown - 1).coerceAtLeast(1) else shown
            val totalW = groupWidth(cols, nameSize)
            val totalH = rows * rowH
            val groupLeft = layout.left + (layout.width - totalW) / 2f
            val groupTop = layout.top + NAME_PAD_V_DP * density + (availH - totalH) / 2f
            val colStep = nameSize * (1f + NAME_COL_GAP)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.color = textColor
            paint.alpha = 255
            paint.textSize = nameSize
            for (index in 0 until shown) {
                val col = index / rows
                val row = index % rows
                val text = if (truncated && index >= drawable) "…" else chars[index]
                val centerX = groupLeft + col * colStep + nameSize / 2f
                // 基线用 0.78em：字形上方留 0.78、下方 descent 约 0.22，正好填满一行
                val baseline = groupTop + row * rowH + nameSize * 0.78f
                canvas.drawText(text, centerX, baseline, paint)
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
            // 与课表页同口径：教室比课名淡一档（0.82），主次分明又不用第二种颜色
            paint.alpha = (0.82f * 255f).toInt()
            val maxWidth = (layout.width - pad * 2f).coerceAtLeast(1f)
            val text = ellipsize(room, maxWidth)
            // 只挤出「…」或「A…」时不如不画：一块没字的省略号比没有教室更让人困惑
            if (text.length <= 2 && text.endsWith("…")) return
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

        private fun dp(value: Float): Float = value * density

        private companion object {
            /** 整周无课时画的节次行数：只为给「本周无课」那句提示留出居中的表格形状 */
            const val EMPTY_PERIOD_ROWS = 5
        }
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

    private data class Palette(
        val background: Int,
        val today: Int,
        val primary: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        /** 网格分隔线：与课表页同口径（onSurface 降透明度），小部件上更淡一点 */
        val divider: Int,
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
                today = 0xFF123B52.toInt(),
                primary = 0xFF8ED8F8.toInt(),
                onSurface = 0xFFE8F4FA.toInt(),
                onSurfaceVariant = 0xFF9FB6C5.toInt(),
                divider = ColorUtils.setAlphaComponent(0xFFE8F4FA.toInt(), 31),
                isDark = true
            )
        } else {
            Palette(
                background = 0xFFEAF2F8.toInt(),
                today = 0xFFD4E9F7.toInt(),
                primary = 0xFF084168.toInt(),
                onSurface = 0xFF14232D.toInt(),
                onSurfaceVariant = 0xFF5E7482.toInt(),
                divider = ColorUtils.setAlphaComponent(0xFF14232D.toInt(), 31),
                isDark = false
            )
        }
    }
}
