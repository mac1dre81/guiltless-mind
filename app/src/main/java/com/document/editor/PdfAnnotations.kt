package com.document.editor

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.min

enum class PdfEditTool {
    NONE,
    HAND,
    PEN,
    HIGHLIGHT,
    ERASER,
    SIGNATURE,
    BOX
}

enum class PdfBoxHandle {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    RIGHT,
    BOTTOM_RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    LEFT;

    val adjustsLeft: Boolean
        get() = this == TOP_LEFT || this == LEFT || this == BOTTOM_LEFT

    val adjustsRight: Boolean
        get() = this == TOP_RIGHT || this == RIGHT || this == BOTTOM_RIGHT

    val adjustsTop: Boolean
        get() = this == TOP_LEFT || this == TOP || this == TOP_RIGHT

    val adjustsBottom: Boolean
        get() = this == BOTTOM_LEFT || this == BOTTOM || this == BOTTOM_RIGHT
}

enum class PdfStrokeKind {
    PEN,
    HIGHLIGHT,
    SIGNATURE
}

sealed interface PdfAnnotationElement {
    val createdAt: Long
}

sealed interface PdfUndoAction {
    val pageIndex: Int

    data class RestoreDeletedAnnotation(
        override val pageIndex: Int,
        val annotation: PdfAnnotationElement,
        val insertionIndex: Int
    ) : PdfUndoAction

    data class RestoreBoxReplacement(
        override val pageIndex: Int,
        val previousAnnotation: PdfBoxAnnotation,
        val updatedAnnotation: PdfBoxAnnotation,
        val targetIndex: Int
    ) : PdfUndoAction
}

enum class PdfBoxContentType {
    TEXT,
    EMOJI,
    STICKER,
    SIGNATURE
}

data class PdfPoint(
    val xRatio: Float,
    val yRatio: Float
)

data class PdfStageTranslation(
    val xPx: Float = 0f,
    val yPx: Float = 0f
)

internal fun resolvePdfGestureThresholdRatio(
    contentWidthPx: Float,
    contentHeightPx: Float,
    touchSlopPx: Float,
    multiplier: Float = 1f
): Float {
    val shortestSidePx = min(contentWidthPx, contentHeightPx).coerceAtLeast(1f)
    return ((touchSlopPx * multiplier) / shortestSidePx).coerceAtLeast(MIN_GESTURE_THRESHOLD_RATIO)
}

internal fun hasPdfGestureExceededThreshold(
    start: PdfPoint,
    current: PdfPoint,
    thresholdRatio: Float
): Boolean {
    return abs(current.xRatio - start.xRatio) > thresholdRatio ||
        abs(current.yRatio - start.yRatio) > thresholdRatio
}

data class PdfInkStroke(
    val points: List<PdfPoint>,
    val color: Int = Color.RED,
    val strokeWidthRatio: Float = 0.006f,
    val strokeKind: PdfStrokeKind = PdfStrokeKind.PEN,
    override val createdAt: Long = System.currentTimeMillis()
) : PdfAnnotationElement

data class PdfTextAnnotation(
    val text: String,
    val xRatio: Float,
    val yRatio: Float,
    val color: Int = Color.RED,
    val textSizeRatio: Float = 0.035f,
    override val createdAt: Long = System.currentTimeMillis()
) : PdfAnnotationElement

data class PdfBoxAnnotation(
    val leftRatio: Float,
    val topRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val content: String,
    val contentType: PdfBoxContentType = PdfBoxContentType.TEXT,
    val color: Int = when (contentType) {
        PdfBoxContentType.EMOJI -> Color.argb(255, 31, 31, 31)
        PdfBoxContentType.STICKER -> Color.argb(255, 31, 31, 31)
        PdfBoxContentType.SIGNATURE -> Color.argb(255, 24, 24, 24)
        PdfBoxContentType.TEXT -> Color.argb(255, 24, 24, 24)
    },
    override val createdAt: Long = System.currentTimeMillis()
) : PdfAnnotationElement {
    val rightRatio: Float get() = leftRatio + widthRatio
    val bottomRatio: Float get() = topRatio + heightRatio

    fun normalized(): PdfBoxAnnotation {
        val normalizedWidth = widthRatio.coerceAtLeast(MIN_BOX_SIDE_RATIO)
        val normalizedHeight = heightRatio.coerceAtLeast(MIN_BOX_SIDE_RATIO)
        val normalizedLeft = leftRatio.coerceIn(0f, 1f - normalizedWidth)
        val normalizedTop = topRatio.coerceIn(0f, 1f - normalizedHeight)
        return copy(
            leftRatio = normalizedLeft,
            topRatio = normalizedTop,
            widthRatio = normalizedWidth.coerceAtMost(1f - normalizedLeft),
            heightRatio = normalizedHeight.coerceAtMost(1f - normalizedTop)
        )
    }

    private companion object {
        const val MIN_BOX_SIDE_RATIO = 0.06f
    }
}

internal fun PdfBoxAnnotation.resizeByHandle(
    handle: PdfBoxHandle,
    deltaXRatio: Float,
    deltaYRatio: Float
): PdfBoxAnnotation {
    var left = leftRatio
    var top = topRatio
    var right = rightRatio
    var bottom = bottomRatio

    if (handle.adjustsLeft) {
        left = (left + deltaXRatio).coerceIn(0f, right - MIN_PDF_BOX_SIDE_RATIO)
    }
    if (handle.adjustsRight) {
        right = (right + deltaXRatio).coerceIn(left + MIN_PDF_BOX_SIDE_RATIO, 1f)
    }
    if (handle.adjustsTop) {
        top = (top + deltaYRatio).coerceIn(0f, bottom - MIN_PDF_BOX_SIDE_RATIO)
    }
    if (handle.adjustsBottom) {
        bottom = (bottom + deltaYRatio).coerceIn(top + MIN_PDF_BOX_SIDE_RATIO, 1f)
    }

    return copy(
        leftRatio = left,
        topRatio = top,
        widthRatio = right - left,
        heightRatio = bottom - top
    ).normalized()
}

internal fun clampPdfStageTranslation(
    requestedXpx: Float,
    requestedYpx: Float,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    zoom: Float
): PdfStageTranslation {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || zoom <= 1f) {
        return PdfStageTranslation()
    }

    val maxTranslationX = (viewportWidthPx * (zoom - 1f)) / 2f
    val maxTranslationY = (viewportHeightPx * (zoom - 1f)) / 2f
    return PdfStageTranslation(
        xPx = requestedXpx.coerceIn(-maxTranslationX, maxTranslationX),
        yPx = requestedYpx.coerceIn(-maxTranslationY, maxTranslationY)
    )
}

fun buildPdfStroke(tool: PdfEditTool, points: List<PdfPoint>): PdfInkStroke? {
    if (points.size < 2) {
        return null
    }

    return when (tool) {
        PdfEditTool.PEN -> PdfInkStroke(
            points = points,
            color = Color.RED,
            strokeWidthRatio = 0.006f,
            strokeKind = PdfStrokeKind.PEN
        )

        PdfEditTool.HIGHLIGHT -> PdfInkStroke(
            points = points,
            color = Color.argb(110, 255, 235, 59),
            strokeWidthRatio = 0.02f,
            strokeKind = PdfStrokeKind.HIGHLIGHT
        )

        PdfEditTool.SIGNATURE -> PdfInkStroke(
            points = points,
            color = Color.argb(255, 24, 24, 24),
            strokeWidthRatio = 0.009f,
            strokeKind = PdfStrokeKind.SIGNATURE
        )

        else -> null
    }
}

private const val MIN_GESTURE_THRESHOLD_RATIO = 0.001f
private const val MIN_PDF_BOX_SIDE_RATIO = 0.06f

