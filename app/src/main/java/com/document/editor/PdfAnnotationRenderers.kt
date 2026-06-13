package com.document.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min

internal fun drawPdfTextAnnotation(
    canvas: Canvas,
    annotation: PdfTextAnnotation,
    contentRect: RectF,
    textPaint: Paint
) {
    textPaint.color = annotation.color
    textPaint.textSize = annotation.textSizeRatio * min(contentRect.width(), contentRect.height())
    canvas.drawText(
        annotation.text,
        contentRect.left + (annotation.xRatio * contentRect.width()),
        contentRect.top + (annotation.yRatio * contentRect.height()),
        textPaint
    )
}

internal fun resolvePdfBoxRect(box: PdfBoxAnnotation, contentRect: RectF): RectF {
    return RectF(
        contentRect.left + (box.leftRatio * contentRect.width()),
        contentRect.top + (box.topRatio * contentRect.height()),
        contentRect.left + (box.rightRatio * contentRect.width()),
        contentRect.top + (box.bottomRatio * contentRect.height())
    )
}

internal fun drawPdfBoxAnnotation(
    canvas: Canvas,
    annotation: PdfBoxAnnotation,
    boxRect: RectF,
    backgroundPaint: Paint? = null
) {
    if (annotation.content.isBlank() || boxRect.width() <= 0f || boxRect.height() <= 0f) {
        return
    }

    val workingRect = RectF(boxRect)
    val padding = max(8f, min(workingRect.width(), workingRect.height()) * 0.12f)
    workingRect.inset(padding, padding)
    if (workingRect.width() <= 0f || workingRect.height() <= 0f) {
        return
    }

    when (annotation.contentType) {
        PdfBoxContentType.EMOJI,
        PdfBoxContentType.STICKER -> drawSingleLineBoxContent(canvas, annotation, boxRect, workingRect, backgroundPaint)

        PdfBoxContentType.TEXT,
        PdfBoxContentType.SIGNATURE -> drawMultilineBoxContent(canvas, annotation, workingRect)
    }
}

private fun drawSingleLineBoxContent(
    canvas: Canvas,
    annotation: PdfBoxAnnotation,
    boxRect: RectF,
    workingRect: RectF,
    backgroundPaint: Paint?
) {
    if (annotation.contentType == PdfBoxContentType.STICKER) {
        val fillPaint = backgroundPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(42, 255, 193, 7)
        }
        canvas.drawRoundRect(
            boxRect,
            min(boxRect.width(), boxRect.height()) * 0.16f,
            min(boxRect.width(), boxRect.height()) * 0.16f,
            fillPaint
        )
    }

    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = annotation.color
        textAlign = Paint.Align.CENTER
        textSize = fitSingleLineTextSize(annotation.content, workingRect)
        typeface =
            if (annotation.contentType == PdfBoxContentType.EMOJI || annotation.contentType == PdfBoxContentType.STICKER) {
                Typeface.DEFAULT
            } else {
                Typeface.DEFAULT_BOLD
            }
    }
    val fontMetrics = textPaint.fontMetrics
    val baseline = workingRect.centerY() - ((fontMetrics.ascent + fontMetrics.descent) / 2f)
    canvas.drawText(annotation.content, workingRect.centerX(), baseline, textPaint)
}

private fun drawMultilineBoxContent(
    canvas: Canvas,
    annotation: PdfBoxAnnotation,
    workingRect: RectF
) {
    val availableWidth = workingRect.width().toInt().coerceAtLeast(1)
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = annotation.color
        typeface = if (annotation.contentType == PdfBoxContentType.SIGNATURE) {
            Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        } else {
            Typeface.DEFAULT
        }
    }

    var textSize = min(workingRect.height() * 0.32f, workingRect.width() * 0.22f).coerceAtLeast(24f)
    var layout = buildLayout(annotation.content, textPaint.apply { this.textSize = textSize }, availableWidth)
    while (textSize > 12f && layout.height > workingRect.height()) {
        textSize -= 2f
        textPaint.textSize = textSize
        layout = buildLayout(annotation.content, textPaint, availableWidth)
    }

    canvas.withTranslation(workingRect.left, workingRect.top) {
        layout.draw(this)
    }
}

private fun fitSingleLineTextSize(text: String, rect: RectF): Float {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    var textSize = min(rect.height() * 0.9f, rect.width() * 0.7f).coerceAtLeast(24f)
    while (textSize > 12f) {
        paint.textSize = textSize
        val widthFits = paint.measureText(text) <= rect.width()
        val metrics = paint.fontMetrics
        val heightFits = (metrics.descent - metrics.ascent) <= rect.height()
        if (widthFits && heightFits) {
            return textSize
        }
        textSize -= 2f
    }
    return 12f
}

private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()
}
