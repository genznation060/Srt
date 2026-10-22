package com.srtcolorsection.studio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.srtcolorsection.studio.domain.Project
import com.srtcolorsection.studio.domain.SectionEngine
import com.srtcolorsection.studio.domain.TimelineSection
import com.srtcolorsection.studio.domain.formatClock
import com.srtcolorsection.studio.domain.formatDuration
import com.srtcolorsection.studio.domain.formatRange
import kotlin.math.max
import kotlin.math.min

data class FrameStyle(
    val backgroundArgb: Int,
    val textArgb: Int,
    val transparent: Boolean,
    val showTimestamps: Boolean,
    val showSectionNumber: Boolean,
    val showSectionName: Boolean,
    val showDuration: Boolean,
    val showSubtitles: Boolean,
    val showTimeline: Boolean,
    val projectName: String,
)

class TimelineRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val medium = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun createBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun drawFrame(bitmap: Bitmap, project: Project, timeMs: Long, style: FrameStyle) {
        val canvas = Canvas(bitmap)
        drawFrame(canvas, bitmap.width, bitmap.height, project, timeMs, style)
    }

    fun drawSection(bitmap: Bitmap, project: Project, section: TimelineSection, number: Int, style: FrameStyle) {
        val canvas = Canvas(bitmap)
        drawSection(canvas, bitmap.width, bitmap.height, project, section, number, style)
    }

    fun drawFrame(canvas: Canvas, width: Int, height: Int, project: Project, timeMs: Long, style: FrameStyle) {
        clear(canvas, width, height, style)
        val scale = width / 1280f
        val margin = width * 0.06f
        val ink = ink(style)
        val muted = muted(style)
        val section = project.sections
            .filter { timeMs >= it.startMs && timeMs < it.endMs }
            .maxByOrNull { it.startMs }
        val cue = project.cues
            .filter { timeMs >= it.startMs && timeMs < it.endMs }
            .maxByOrNull { it.startMs }

        text(canvas, style.projectName.ifBlank { "Timeline" }, margin, margin * 0.55f, 18f * scale, muted, width - margin * 2)
        if (style.showTimestamps) {
            val stamp = formatClock(timeMs)
            val paint = medium
            paint.color = ink
            paint.textSize = 22f * scale
            val w = paint.measureText(stamp)
            canvas.drawText(stamp, width - margin - w, margin * 0.55f + paint.textSize, paint)
        }

        val title = when {
            section == null -> "Gap"
            style.showSectionName -> section.name
            style.showSectionNumber -> "Section ${numberOf(project, section)}"
            else -> ""
        }
        var y = margin * 1.6f
        if (title.isNotEmpty()) {
            y = drawBlock(
                canvas, title, margin, y, width - margin * 2,
                54f * scale, ink, maxLines = 2, bold = true,
            )
        }
        if (section != null && style.showTimestamps) {
            y = drawBlock(
                canvas,
                formatRange(section.startMs, section.endMs),
                margin,
                y + 8f * scale,
                width - margin * 2,
                24f * scale,
                section.colorArgb,
                maxLines = 1,
                bold = false,
            )
        }
        if (style.showSubtitles) {
            val body = cue?.text?.ifBlank { null } ?: " "
            y = drawBlock(
                canvas,
                body,
                margin,
                max(y + 28f * scale, height * 0.38f),
                width - margin * 2,
                36f * scale,
                ink,
                maxLines = 6,
                bold = false,
            )
        }
        if (style.showTimeline) {
            drawTimeline(
                canvas,
                project,
                margin,
                height - margin * 1.8f,
                width - margin * 2,
                28f * scale,
                timeMs,
                section?.id,
            )
        }
    }

    fun drawSection(
        canvas: Canvas,
        width: Int,
        height: Int,
        project: Project,
        section: TimelineSection,
        number: Int,
        style: FrameStyle,
    ) {
        clear(canvas, width, height, style)
        val scale = width / 1280f
        val margin = width * 0.07f
        val ink = ink(style)
        val muted = muted(style)
        fill.color = section.colorArgb
        canvas.drawRoundRect(RectF(0f, 0f, 18f * scale, height.toFloat()), 0f, 0f, fill)

        var y = margin
        if (style.showSectionNumber) {
            y = drawBlock(
                canvas,
                "SECTION %03d".format(number),
                margin,
                y,
                width - margin * 2,
                20f * scale,
                section.colorArgb,
                maxLines = 1,
                bold = true,
            )
        }
        if (style.showSectionName) {
            y = drawBlock(
                canvas,
                section.name,
                margin,
                y + 12f * scale,
                width - margin * 2,
                64f * scale,
                ink,
                maxLines = 3,
                bold = true,
            )
        }
        if (style.showTimestamps) {
            y = drawBlock(
                canvas,
                formatRange(section.startMs, section.endMs),
                margin,
                y + 16f * scale,
                width - margin * 2,
                28f * scale,
                ink,
                maxLines = 1,
                bold = false,
            )
        }
        if (style.showDuration) {
            y = drawBlock(
                canvas,
                "Duration ${formatDuration(section.durationMs)}",
                margin,
                y + 8f * scale,
                width - margin * 2,
                22f * scale,
                muted,
                maxLines = 1,
                bold = false,
            )
        }
        if (style.showTimeline) {
            y += 28f * scale
            drawTimeline(
                canvas,
                project,
                margin,
                y,
                width - margin * 2,
                36f * scale,
                section.startMs,
                section.id,
            )
            y += 52f * scale
        }
        if (style.showSubtitles) {
            val cues = section.cueIndices.mapNotNull { project.cues.getOrNull(it) }
            val body = cues.joinToString("\n") { it.text }.ifBlank { section.notes.ifBlank { "No subtitle text in this section" } }
            val top = max(y + 12f * scale, height * 0.48f)
            fill.color = if (style.transparent) section.colorArgb and 0x33FFFFFF else 0x14FFFFFF
            if (!style.transparent) fill.color = 0x14FFFFFF
            else fill.color = (section.colorArgb and 0x00FFFFFF) or 0x33000000
            val box = RectF(margin, top, width - margin, height - margin)
            canvas.drawRoundRect(box, 18f * scale, 18f * scale, fill)
            drawBlock(
                canvas,
                body,
                margin + 24f * scale,
                top + 20f * scale,
                box.width() - 48f * scale,
                28f * scale,
                ink,
                maxLines = 8,
                bold = false,
            )
        }
    }

    private fun clear(canvas: Canvas, width: Int, height: Int, style: FrameStyle) {
        if (style.transparent) canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        else canvas.drawColor(style.backgroundArgb)
        // Touch width/height so callers passing a clipped canvas still clear the bitmap bounds.
        if (width < 0 || height < 0) canvas.drawColor(Color.TRANSPARENT)
    }

    private fun ink(style: FrameStyle): Int = if (style.transparent) 0xFF1A1C1E.toInt() else style.textArgb

    private fun muted(style: FrameStyle): Int = if (style.transparent) 0xFF3E4652.toInt() else 0xFFB7C0CC.toInt()

    private fun numberOf(project: Project, section: TimelineSection): Int =
        SectionEngine.numbered(project.sections).firstOrNull { it.second.id == section.id }?.first ?: 0

    private fun drawTimeline(
        canvas: Canvas,
        project: Project,
        left: Float,
        top: Float,
        width: Float,
        barHeight: Float,
        playheadMs: Long,
        highlightId: String?,
    ) {
        val end = project.exportDurationMs.coerceAtLeast(1L)
        fill.color = 0x22FFFFFF
        val track = RectF(left, top, left + width, top + barHeight)
        canvas.drawRoundRect(track, barHeight / 2f, barHeight / 2f, fill)
        for (section in project.sections) {
            val x0 = left + width * (section.startMs.coerceAtLeast(0L).toFloat() / end)
            val x1 = left + width * (section.endMs.coerceIn(0L, end).toFloat() / end)
            fill.color = if (section.id == highlightId) section.colorArgb else section.colorArgb and 0x88FFFFFF.toInt() or (section.colorArgb and 0x00FFFFFF)
            // Keep the section color; dim non-highlighted by blending toward transparent.
            if (section.id != highlightId) fill.color = (section.colorArgb and 0x00FFFFFF) or 0x66000000
            else fill.color = section.colorArgb
            val rect = RectF(min(x0, x1), top, max(x0, x1).coerceAtLeast(min(x0, x1) + 2f), top + barHeight)
            canvas.drawRoundRect(rect, 6f, 6f, fill)
        }
        val px = left + width * (playheadMs.coerceIn(0L, end).toFloat() / end)
        fill.color = Color.WHITE
        canvas.drawRect(px - 1.5f, top - 6f, px + 1.5f, top + barHeight + 6f, fill)
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        maxWidth: Float,
    ) {
        drawBlock(canvas, value, x, y, maxWidth, size, color, 1, false)
    }

    private fun drawBlock(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        size: Float,
        color: Int,
        maxLines: Int,
        bold: Boolean,
    ): Float {
        val paint = if (bold) medium else text
        paint.color = color
        paint.textSize = size
        val widthPx = max(1, maxWidth.toInt())
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }
}
