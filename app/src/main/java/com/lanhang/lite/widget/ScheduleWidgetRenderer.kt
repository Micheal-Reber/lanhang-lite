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
import com.lanhang.lite.session.Session
import com.lanhang.lite.widget.ScheduleWidgetData.CourseBlock
import com.lanhang.lite.widget.ScheduleWidgetData.WeekSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object ScheduleWidgetRenderer {

    private val dayLabels = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val periodTimes = arrayOf(
        "08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
        "14:00-14:45", "14:55-15:40", "16:00-16:45", "16:55-17:40",
        "19:00-19:45", "19:55-20:40", "20:50-21:35"
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("M-d")

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
        private val headH = dp(56f)
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
                paint.textSize = (headH * 0.24f).coerceIn(dp(9f), dp(13f))
                paint.color = if (isToday) palette.primary else palette.onSurface
                canvas.drawText(
                    dayLabels[index],
                    x + dayW / 2f,
                    outerPad + headH * 0.4f,
                    paint
                )
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
                paint.alpha = if (palette.isDark) 220 else 205
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
                val textColor = if (ColorUtils.calculateLuminance(baseColor) < 0.5) {
                    Color.WHITE
                } else {
                    TEXT_DARK
                }
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

        private fun courseColor(name: String): Int {
            val hue = ((name.hashCode() * 137.508f % 360f) + 360f) % 360f
            return if (palette.isDark) {
                ColorUtils.HSLToColor(floatArrayOf(hue, 0.40f, 0.28f))
            } else {
                ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.82f))
            }
        }

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

    private val TEXT_DARK = 0xFF1D1B20.toInt()
}
