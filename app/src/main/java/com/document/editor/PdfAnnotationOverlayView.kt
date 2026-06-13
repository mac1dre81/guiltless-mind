package com.document.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration

import kotlin.math.max
import kotlin.math.min

class PdfAnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    var editTool: PdfEditTool = PdfEditTool.NONE
        set(value) {
            field = value
            if (!value.supportsStrokeInput()) {
                activeStrokePoints.clear()
            }
            clearBoxInteraction()
            invalidate()
        }

    var viewportScale: Float = 1f
        set(value) {
            field = value.coerceAtLeast(1f)
            invalidate()
        }

    var onStrokeFinished: ((PdfInkStroke) -> Unit)? = null
    var onEraseRequested: ((Float, Float) -> Unit)? = null
    var onBoxRequested: ((PdfPoint) -> Unit)? = null
    var onBoxEditRequested: ((PdfBoxAnnotation) -> Unit)? = null
    var onBoxDeleteRequested: ((PdfBoxAnnotation) -> Unit)? = null
    var onBoxSelectionChanged: ((PdfBoxAnnotation?) -> Unit)? = null
    var onBoxTransformCommitted: ((PdfBoxAnnotation, PdfBoxAnnotation) -> Unit)? = null
    var onScaleRequested: ((Float) -> Unit)? = null
    var onPanRequested: ((Float, Float) -> Unit)? = null

    private var annotations: List<PdfAnnotationElement> = emptyList()
    private var pageWidthPx: Int = 0
    private var pageHeightPx: Int = 0
    private val activeStrokePoints = mutableListOf<PdfPoint>()
    private var selectedBoxId: Long? = null
    private var boxInteractionMode = BoxInteractionMode.NONE
    private var interactionStartPoint: PdfPoint? = null
    private var interactionStartBox: PdfBoxAnnotation? = null
    private var interactionPreviewBox: PdfBoxAnnotation? = null
    private var activeResizeHandle: PdfBoxHandle? = null
    private var didDragBox = false
    private var longPressTriggered = false
    private var shouldOpenSelectedBoxEditorOnUp = false
    private var lastPanRawX = 0f
    private var lastPanRawY = 0f
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var pendingLongPressRunnable: Runnable? = null
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onScaleRequested?.invoke(detector.scaleFactor)
            return true
        }
    })
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val boxChromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        color = Color.argb(255, 33, 150, 243)
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val selectedBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(34, 33, 150, 243)
    }
    private val resizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(255, 33, 150, 243)
    }
    private val resizeHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    fun setAnnotations(value: List<PdfAnnotationElement>) {
        annotations = value
        if (annotations.none { it.createdAt == selectedBoxId }) {
            selectedBoxId = null
            clearBoxInteraction()
        }
        notifyBoxSelectionChanged()
        invalidate()
    }

    fun setPageSize(widthPx: Int, heightPx: Int) {
        pageWidthPx = widthPx
        pageHeightPx = heightPx
        invalidate()
    }

    fun selectBox(boxId: Long?) {
        selectedBoxId = boxId
        clearBoxInteraction()
        notifyBoxSelectionChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentRect = resolveContentRect()
        annotations.forEach { annotation ->
            drawAnnotation(canvas, annotation, contentRect)
        }
        if (editTool.supportsBoxInteraction()) {
            annotations
                .filterIsInstance<PdfBoxAnnotation>()
                .firstOrNull { it.createdAt == selectedBoxId }
                ?.let { selectedBox ->
                    drawBoxChrome(canvas, selectedBox, contentRect)
                }
        }
        if (activeStrokePoints.size > 1) {
            buildPdfStroke(editTool, activeStrokePoints.toList())?.let { previewStroke ->
                drawStroke(canvas, previewStroke, contentRect)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }

        val scaleHandled = scaleGestureDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scaleGestureDetector.isInProgress) {
            activeStrokePoints.clear()
            clearBoxInteraction()
            parent?.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            invalidate()
            return true
        }

        val point = mapEventToPagePoint(event.x, event.y)
        val handled = when (editTool) {
            PdfEditTool.HAND -> handleBoxTouch(event.actionMasked, point, event.rawX, event.rawY, allowCreate = false, allowPan = true)
            PdfEditTool.PEN,
            PdfEditTool.HIGHLIGHT,
            PdfEditTool.SIGNATURE -> handleStrokeTouch(event.actionMasked, point)
            PdfEditTool.ERASER -> handleEraserTouch(event.actionMasked, point)
            PdfEditTool.BOX -> handleBoxTouch(event.actionMasked, point, event.rawX, event.rawY, allowCreate = true, allowPan = false)
            PdfEditTool.NONE -> scaleHandled
        }
        if (handled && event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return handled || scaleHandled
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleStrokeTouch(action: Int, point: PdfPoint?): Boolean {
        if (point == null) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activeStrokePoints.clear()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            return false
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeStrokePoints.clear()
                activeStrokePoints.add(point)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                activeStrokePoints.add(point)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeStrokePoints.size == 1) {
                    activeStrokePoints.add(point)
                }
                buildPdfStroke(editTool, activeStrokePoints.toList())?.let { stroke ->
                    onStrokeFinished?.invoke(stroke)
                }
                activeStrokePoints.clear()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeStrokePoints.clear()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun handleEraserTouch(action: Int, point: PdfPoint?): Boolean {
        if (point == null) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return false
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onEraseRequested?.invoke(point.xRatio, point.yRatio)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                onEraseRequested?.invoke(point.xRatio, point.yRatio)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }

        return false
    }

    private fun handleBoxTouch(
        action: Int,
        point: PdfPoint?,
        rawX: Float,
        rawY: Float,
        allowCreate: Boolean,
        allowPan: Boolean
    ): Boolean {
        val contentRect = resolveContentRect()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                didDragBox = false
                longPressTriggered = false
                lastPanRawX = rawX
                lastPanRawY = rawY

                val selectedBox = selectedBoxId?.let { selectedId ->
                    annotations
                        .filterIsInstance<PdfBoxAnnotation>()
                        .firstOrNull { it.createdAt == selectedId }
                }
                val selectedHandle = selectedBox?.let { box ->
                    point?.let { pagePoint -> findResizeHandle(box, pagePoint, contentRect) }
                }
                val hitBox = when {
                    selectedBox != null && selectedHandle != null -> selectedBox
                    point != null -> findTopmostBox(point)
                    else -> null
                }
                if (hitBox != null) {
                    val wasAlreadySelected = hitBox.createdAt == selectedBoxId
                    val resizeHandleTouched = selectedHandle ?: point?.let {
                        findResizeHandle(hitBox, it, contentRect)
                    }
                    shouldOpenSelectedBoxEditorOnUp = wasAlreadySelected && resizeHandleTouched == null
                    selectedBoxId = hitBox.createdAt
                    interactionStartPoint = point
                    interactionStartBox = hitBox
                    activeResizeHandle = resizeHandleTouched
                    boxInteractionMode = if (resizeHandleTouched != null) {
                        BoxInteractionMode.RESIZE
                    } else {
                        BoxInteractionMode.MOVE
                    }
                    if (wasAlreadySelected && resizeHandleTouched == null) {
                        scheduleLongPressDelete(hitBox)
                    }
                } else {
                    shouldOpenSelectedBoxEditorOnUp = false
                    selectedBoxId = null
                    interactionStartPoint = point
                    interactionStartBox = null
                    activeResizeHandle = null
                    boxInteractionMode = when {
                        allowCreate && point != null -> BoxInteractionMode.CREATE
                        allowPan -> BoxInteractionMode.PAN
                        else -> BoxInteractionMode.NONE
                    }
                    if (boxInteractionMode == BoxInteractionMode.NONE) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                notifyBoxSelectionChanged()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (boxInteractionMode == BoxInteractionMode.PAN) {
                    val deltaX = rawX - lastPanRawX
                    val deltaY = rawY - lastPanRawY
                    didDragBox = didDragBox || maxOf(kotlin.math.abs(deltaX), kotlin.math.abs(deltaY)) > touchSlopPx
                    lastPanRawX = rawX
                    lastPanRawY = rawY
                    onPanRequested?.invoke(deltaX, deltaY)
                    return true
                }

                val startPoint = interactionStartPoint
                val startBox = interactionStartBox
                if (point == null || startPoint == null) {
                    cancelPendingLongPress()
                    return boxInteractionMode == BoxInteractionMode.CREATE
                }

                if (boxInteractionMode == BoxInteractionMode.CREATE || startBox == null) {
                    didDragBox = didDragBox || hasPdfGestureExceededThreshold(
                        start = startPoint,
                        current = point,
                        thresholdRatio = resolveInteractionThresholdRatio(contentRect, BoxInteractionMode.CREATE)
                    )
                    if (didDragBox) {
                        shouldOpenSelectedBoxEditorOnUp = false
                    }
                    return true
                }

                val updatedBox = when (boxInteractionMode) {
                    BoxInteractionMode.MOVE -> startBox.copy(
                        leftRatio = startBox.leftRatio + (point.xRatio - startPoint.xRatio),
                        topRatio = startBox.topRatio + (point.yRatio - startPoint.yRatio)
                    ).normalized()

                    BoxInteractionMode.RESIZE -> activeResizeHandle?.let { handle ->
                        startBox.resizeByHandle(
                            handle = handle,
                            deltaXRatio = point.xRatio - startPoint.xRatio,
                            deltaYRatio = point.yRatio - startPoint.yRatio
                        )
                    }

                    else -> null
                }

                if (updatedBox != null) {
                    didDragBox = didDragBox || hasPdfGestureExceededThreshold(
                        start = startPoint,
                        current = point,
                        thresholdRatio = resolveInteractionThresholdRatio(contentRect, boxInteractionMode)
                    )
                    if (didDragBox) {
                        shouldOpenSelectedBoxEditorOnUp = false
                        cancelPendingLongPress()
                    }
                    selectedBoxId = updatedBox.createdAt
                    interactionPreviewBox = updatedBox
                    notifyBoxSelectionChanged()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                when {
                    boxInteractionMode == BoxInteractionMode.CREATE && point != null && !didDragBox -> {
                        onBoxRequested?.invoke(point)
                    }

                    !didDragBox && shouldOpenSelectedBoxEditorOnUp && !longPressTriggered -> {
                        interactionStartBox?.let { box ->
                            onBoxEditRequested?.invoke(box)
                        }
                    }

                    didDragBox -> {
                        val initialBox = interactionStartBox
                        val previewBox = interactionPreviewBox
                        if (initialBox != null && previewBox != null && initialBox != previewBox) {
                            onBoxTransformCommitted?.invoke(initialBox, previewBox)
                        }
                    }
                }
                clearBoxInteraction()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                clearBoxInteraction()
                invalidate()
                return true
            }
        }

        return false
    }

    private fun drawAnnotation(canvas: Canvas, annotation: PdfAnnotationElement, contentRect: RectF) {
        when (annotation) {
            is PdfInkStroke -> drawStroke(canvas, annotation, contentRect)
            is PdfTextAnnotation -> drawPdfTextAnnotation(canvas, annotation, contentRect, textPaint)
            is PdfBoxAnnotation -> {
                val boxToDraw = interactionPreviewBox?.takeIf { it.createdAt == annotation.createdAt } ?: annotation
                drawPdfBoxAnnotation(canvas, boxToDraw, resolvePdfBoxRect(boxToDraw, contentRect))
            }
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: PdfInkStroke, contentRect: RectF) {
        if (stroke.points.size < 2) {
            return
        }
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = stroke.strokeWidthRatio * min(contentRect.width(), contentRect.height())
        val path = Path().apply {
            moveTo(
                contentRect.left + (stroke.points.first().xRatio * contentRect.width()),
                contentRect.top + (stroke.points.first().yRatio * contentRect.height())
            )
            stroke.points.drop(1).forEach { point ->
                lineTo(
                    contentRect.left + (point.xRatio * contentRect.width()),
                    contentRect.top + (point.yRatio * contentRect.height())
                )
            }
        }
        canvas.drawPath(path, strokePaint)
    }

    private fun drawBoxChrome(canvas: Canvas, box: PdfBoxAnnotation, contentRect: RectF) {
        val boxRect = resolvePdfBoxRect(box, contentRect)
        val boxCornerRadius = BOX_CORNER_RADIUS_PX / viewportScale
        boxChromePaint.strokeWidth = (resources.displayMetrics.density * 2.5f) / viewportScale
        boxChromePaint.pathEffect = DashPathEffect(
            floatArrayOf(14f / viewportScale, 10f / viewportScale),
            0f
        )
        canvas.drawRoundRect(boxRect, boxCornerRadius, boxCornerRadius, selectedBoxFillPaint)
        canvas.drawRoundRect(boxRect, boxCornerRadius, boxCornerRadius, boxChromePaint)

        val handleRadius = resolveHandleRadiusPx(contentRect)
        resizeHandleStrokePaint.strokeWidth = (resources.displayMetrics.density * 2f) / viewportScale
        resolveResizeHandleCenters(boxRect).forEach { handleCenter ->
            canvas.drawCircle(handleCenter.x, handleCenter.y, handleRadius, resizeHandlePaint)
            canvas.drawCircle(handleCenter.x, handleCenter.y, handleRadius, resizeHandleStrokePaint)
        }
    }

    private fun resolveContentRect(): RectF {
        if (pageWidthPx <= 0 || pageHeightPx <= 0 || width <= 0 || height <= 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }

        val scale = min(width.toFloat() / pageWidthPx.toFloat(), height.toFloat() / pageHeightPx.toFloat())
        val scaledWidth = pageWidthPx * scale
        val scaledHeight = pageHeightPx * scale
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        return RectF(left, top, left + scaledWidth, top + scaledHeight)
    }

    private fun mapEventToPagePoint(x: Float, y: Float): PdfPoint? {
        val contentRect = resolveContentRect()
        if (!contentRect.contains(x, y) || contentRect.width() <= 0f || contentRect.height() <= 0f) {
            return null
        }

        return PdfPoint(
            xRatio = ((x - contentRect.left) / contentRect.width()).coerceIn(0f, 1f),
            yRatio = ((y - contentRect.top) / contentRect.height()).coerceIn(0f, 1f)
        )
    }

    private fun PdfEditTool.supportsStrokeInput(): Boolean {
        return this == PdfEditTool.PEN || this == PdfEditTool.HIGHLIGHT || this == PdfEditTool.SIGNATURE
    }

    private fun PdfEditTool.supportsBoxInteraction(): Boolean {
        return this == PdfEditTool.BOX || this == PdfEditTool.HAND
    }

    private fun findTopmostBox(point: PdfPoint): PdfBoxAnnotation? {
        return annotations.asReversed()
            .filterIsInstance<PdfBoxAnnotation>()
            .firstOrNull { box ->
                point.xRatio in box.leftRatio..box.rightRatio && point.yRatio in box.topRatio..box.bottomRatio
            }
    }

    private fun findResizeHandle(box: PdfBoxAnnotation, point: PdfPoint, contentRect: RectF): PdfBoxHandle? {
        val boxRect = resolvePdfBoxRect(box, contentRect)
        val pageX = contentRect.left + (point.xRatio * contentRect.width())
        val pageY = contentRect.top + (point.yRatio * contentRect.height())
        val touchRadius = resolveHandleTouchRadiusPx(contentRect)

        return resolveResizeHandleCenters(boxRect)
            .map { handleCenter ->
                val dx = pageX - handleCenter.x
                val dy = pageY - handleCenter.y
                handleCenter.handle to ((dx * dx) + (dy * dy))
            }
            .filter { (_, distanceSquared) -> distanceSquared <= touchRadius * touchRadius }
            .minByOrNull { (_, distanceSquared) -> distanceSquared }
            ?.first
    }

    private fun resolveResizeHandleCenters(boxRect: RectF): List<BoxHandleCenter> {
        val centerX = boxRect.centerX()
        val centerY = boxRect.centerY()
        return listOf(
            BoxHandleCenter(PdfBoxHandle.TOP_LEFT, boxRect.left, boxRect.top),
            BoxHandleCenter(PdfBoxHandle.TOP, centerX, boxRect.top),
            BoxHandleCenter(PdfBoxHandle.TOP_RIGHT, boxRect.right, boxRect.top),
            BoxHandleCenter(PdfBoxHandle.RIGHT, boxRect.right, centerY),
            BoxHandleCenter(PdfBoxHandle.BOTTOM_RIGHT, boxRect.right, boxRect.bottom),
            BoxHandleCenter(PdfBoxHandle.BOTTOM, centerX, boxRect.bottom),
            BoxHandleCenter(PdfBoxHandle.BOTTOM_LEFT, boxRect.left, boxRect.bottom),
            BoxHandleCenter(PdfBoxHandle.LEFT, boxRect.left, centerY)
        )
    }

    private fun resolveHandleRadiusPx(contentRect: RectF): Float {
        val desiredScreenRadius = max(
            resources.displayMetrics.density * 6f,
            min(contentRect.width(), contentRect.height()) * 0.012f
        )
        return desiredScreenRadius / viewportScale
    }

    private fun resolveHandleTouchRadiusPx(contentRect: RectF): Float {
        return resolveHandleRadiusPx(contentRect) * HANDLE_TOUCH_TARGET_MULTIPLIER
    }

    private fun notifyBoxSelectionChanged() {
        onBoxSelectionChanged?.invoke(
            interactionPreviewBox ?: annotations
                .filterIsInstance<PdfBoxAnnotation>()
                .firstOrNull { it.createdAt == selectedBoxId }
        )
    }

    private fun resolveInteractionThresholdRatio(contentRect: RectF, mode: BoxInteractionMode): Float {
        val multiplier = when (mode) {
            BoxInteractionMode.CREATE -> CREATE_DRAG_THRESHOLD_MULTIPLIER
            BoxInteractionMode.MOVE -> MOVE_DRAG_THRESHOLD_MULTIPLIER
            BoxInteractionMode.RESIZE -> RESIZE_DRAG_THRESHOLD_MULTIPLIER
            BoxInteractionMode.PAN -> PAN_DRAG_THRESHOLD_MULTIPLIER
            BoxInteractionMode.NONE -> 1f
        }
        return resolvePdfGestureThresholdRatio(
            contentWidthPx = contentRect.width(),
            contentHeightPx = contentRect.height(),
            touchSlopPx = touchSlopPx,
            multiplier = multiplier
        )
    }

    private fun scheduleLongPressDelete(box: PdfBoxAnnotation) {
        cancelPendingLongPress()
        val boxId = box.createdAt
        pendingLongPressRunnable = Runnable {
            val currentBox = annotations
                .filterIsInstance<PdfBoxAnnotation>()
                .firstOrNull { it.createdAt == boxId }
                ?: return@Runnable
            if (editTool.supportsBoxInteraction() && !didDragBox && boxInteractionMode == BoxInteractionMode.MOVE) {
                longPressTriggered = true
                selectedBoxId = null
                onBoxDeleteRequested?.invoke(currentBox)
                clearBoxInteraction()
                invalidate()
            }
        }.also { runnable ->
            postDelayed(runnable, longPressTimeoutMs)
        }
    }

    private fun cancelPendingLongPress() {
        pendingLongPressRunnable?.let(::removeCallbacks)
        pendingLongPressRunnable = null
    }

    private fun clearBoxInteraction() {
        cancelPendingLongPress()
        boxInteractionMode = BoxInteractionMode.NONE
        interactionStartPoint = null
        interactionStartBox = null
        interactionPreviewBox = null
        activeResizeHandle = null
        didDragBox = false
        longPressTriggered = false
        shouldOpenSelectedBoxEditorOnUp = false
        lastPanRawX = 0f
        lastPanRawY = 0f
    }

    private enum class BoxInteractionMode {
        NONE,
        CREATE,
        PAN,
        MOVE,
        RESIZE
    }

    private data class BoxHandleCenter(
        val handle: PdfBoxHandle,
        val x: Float,
        val y: Float
    )

    private companion object {
        const val BOX_CORNER_RADIUS_PX = 18f
        const val CREATE_DRAG_THRESHOLD_MULTIPLIER = 1f
        const val PAN_DRAG_THRESHOLD_MULTIPLIER = 0.6f
        const val MOVE_DRAG_THRESHOLD_MULTIPLIER = 0.6f
        const val RESIZE_DRAG_THRESHOLD_MULTIPLIER = 0.45f
        const val HANDLE_TOUCH_TARGET_MULTIPLIER = 1.8f
    }
}
