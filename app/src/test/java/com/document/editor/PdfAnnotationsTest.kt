package com.document.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfAnnotationsTest {
    @Test
    fun normalized_clampsBoxInsidePageAndKeepsMinimumSize() {
        val annotation = PdfBoxAnnotation(
            leftRatio = -0.2f,
            topRatio = 0.97f,
            widthRatio = 0.01f,
            heightRatio = 0.02f,
            content = "Test",
            color = 0
        )

        val normalized = annotation.normalized()

        assertEquals(0f, normalized.leftRatio, 0.0001f)
        assertEquals(0.94f, normalized.topRatio, 0.0001f)
        assertEquals(0.06f, normalized.widthRatio, 0.0001f)
        assertEquals(0.06f, normalized.heightRatio, 0.0001f)
    }

    @Test
    fun normalized_capsOversizedBoxToAvailableBounds() {
        val annotation = PdfBoxAnnotation(
            leftRatio = 0.85f,
            topRatio = 0.9f,
            widthRatio = 0.4f,
            heightRatio = 0.3f,
            content = "Overflow",
            color = 0
        )

        val normalized = annotation.normalized()

        assertEquals(0.6f, normalized.leftRatio, 0.0001f)
        assertEquals(0.7f, normalized.topRatio, 0.0001f)
        assertEquals(0.4f, normalized.widthRatio, 0.0001f)
        assertEquals(0.3f, normalized.heightRatio, 0.0001f)
    }

    @Test
    fun resolvePdfGestureThresholdRatio_scalesTouchSlopAgainstShortestSide() {
        val threshold = resolvePdfGestureThresholdRatio(
            contentWidthPx = 1200f,
            contentHeightPx = 800f,
            touchSlopPx = 12f,
            multiplier = 0.5f
        )

        assertEquals(0.0075f, threshold, 0.0001f)
    }

    @Test
    fun hasPdfGestureExceededThreshold_detectsIntentionalMovementOnly() {
        val start = PdfPoint(0.25f, 0.25f)
        val slightMove = PdfPoint(0.255f, 0.254f)
        val clearMove = PdfPoint(0.275f, 0.25f)

        assertFalse(hasPdfGestureExceededThreshold(start, slightMove, thresholdRatio = 0.01f))
        assertTrue(hasPdfGestureExceededThreshold(start, clearMove, thresholdRatio = 0.01f))
    }

    @Test
    fun resizeByHandle_expandsFromTopLeftWithoutBreakingRightAngles() {
        val box = PdfBoxAnnotation(
            leftRatio = 0.3f,
            topRatio = 0.3f,
            widthRatio = 0.2f,
            heightRatio = 0.2f,
            content = "Resize",
            color = 0
        )

        val resized = box.resizeByHandle(
            handle = PdfBoxHandle.TOP_LEFT,
            deltaXRatio = -0.05f,
            deltaYRatio = -0.04f
        )

        assertEquals(0.25f, resized.leftRatio, 0.0001f)
        assertEquals(0.26f, resized.topRatio, 0.0001f)
        assertEquals(0.25f, resized.widthRatio, 0.0001f)
        assertEquals(0.24f, resized.heightRatio, 0.0001f)
        assertEquals(box.rightRatio, resized.rightRatio, 0.0001f)
        assertEquals(box.bottomRatio, resized.bottomRatio, 0.0001f)
    }

    @Test
    fun resizeByHandle_sideHandleRespectsMinimumBoxSize() {
        val box = PdfBoxAnnotation(
            leftRatio = 0.2f,
            topRatio = 0.2f,
            widthRatio = 0.1f,
            heightRatio = 0.18f,
            content = "Resize",
            color = 0
        )

        val resized = box.resizeByHandle(
            handle = PdfBoxHandle.LEFT,
            deltaXRatio = 0.09f,
            deltaYRatio = 0f
        )

        assertEquals(0.24f, resized.leftRatio, 0.0001f)
        assertEquals(0.06f, resized.widthRatio, 0.0001f)
        assertEquals(box.topRatio, resized.topRatio, 0.0001f)
        assertEquals(box.heightRatio, resized.heightRatio, 0.0001f)
    }

    @Test
    fun resizeByHandle_topHandleMovesWholeTopEdgeWhileKeepingRectangleAxisAligned() {
        val box = PdfBoxAnnotation(
            leftRatio = 0.28f,
            topRatio = 0.32f,
            widthRatio = 0.18f,
            heightRatio = 0.14f,
            content = "Resize",
            color = 0
        )

        val resized = box.resizeByHandle(
            handle = PdfBoxHandle.TOP,
            deltaXRatio = 0f,
            deltaYRatio = -0.05f
        )

        assertEquals(box.leftRatio, resized.leftRatio, 0.0001f)
        assertEquals(box.rightRatio, resized.rightRatio, 0.0001f)
        assertEquals(0.27f, resized.topRatio, 0.0001f)
        assertEquals(0.19f, resized.heightRatio, 0.0001f)
        assertEquals(box.bottomRatio, resized.bottomRatio, 0.0001f)
    }

    @Test
    fun clampPdfStageTranslation_limitsPanToVisibleZoomedBounds() {
        val translation = clampPdfStageTranslation(
            requestedXpx = 520f,
            requestedYpx = -760f,
            viewportWidthPx = 1000,
            viewportHeightPx = 600,
            zoom = 2f
        )

        assertEquals(500f, translation.xPx, 0.0001f)
        assertEquals(-300f, translation.yPx, 0.0001f)
    }

    @Test
    fun clampPdfStageTranslation_resetsWhenZoomIsNotGreaterThanOne() {
        val translation = clampPdfStageTranslation(
            requestedXpx = 180f,
            requestedYpx = -240f,
            viewportWidthPx = 1000,
            viewportHeightPx = 600,
            zoom = 1f
        )

        assertEquals(0f, translation.xPx, 0.0001f)
        assertEquals(0f, translation.yPx, 0.0001f)
    }
}

