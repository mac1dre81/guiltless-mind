package com.document.editor
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
class TestPdfBoxOverlayActivity : AppCompatActivity() {
    lateinit var overlay: PdfAnnotationOverlayView
        private set
    private val boxes = mutableListOf<PdfBoxAnnotation>()
    var boxRequestCount: Int = 0
        private set
    var boxEditCount: Int = 0
        private set
    var boxDeleteCount: Int = 0
        private set
    var boxTransformCount: Int = 0
        private set
    var panRequestCount: Int = 0
        private set
    var lastRequestedPoint: PdfPoint? = null
        private set
    var lastTransformedBox: PdfBoxAnnotation? = null
        private set
    var lastPanDeltaX: Float = 0f
        private set
    var lastPanDeltaY: Float = 0f
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlay = PdfAnnotationOverlayView(this).apply {
            id = R.id.pdfAnnotationOverlay
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            editTool = PdfEditTool.BOX
        }
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(this@TestPdfBoxOverlayActivity.overlay)
        }
        setContentView(root)
        bindCallbacks()
        root.post {
            syncPageSize()
        }
    }
    fun resetState() {
        boxRequestCount = 0
        boxEditCount = 0
        boxDeleteCount = 0
        boxTransformCount = 0
        panRequestCount = 0
        lastRequestedPoint = null
        lastTransformedBox = null
        lastPanDeltaX = 0f
        lastPanDeltaY = 0f
        boxes.clear()
        overlay.editTool = PdfEditTool.BOX
        overlay.setAnnotations(emptyList())
        overlay.selectBox(null)
        syncPageSize()
    }
    fun setBoxes(vararg annotations: PdfBoxAnnotation) {
        boxes.clear()
        boxes.addAll(annotations)
        overlay.setAnnotations(boxes.toList())
    }
    fun currentBoxCount(): Int = boxes.size
    fun syncPageSize() {
        if (overlay.width > 0 && overlay.height > 0) {
            overlay.setPageSize(overlay.width, overlay.height)
        }
    }
    private fun bindCallbacks() {
        overlay.onBoxRequested = { point ->
            boxRequestCount += 1
            lastRequestedPoint = point
        }
        overlay.onBoxEditRequested = {
            boxEditCount += 1
        }
        overlay.onBoxDeleteRequested = { box ->
            boxDeleteCount += 1
            boxes.removeAll { it.createdAt == box.createdAt }
            overlay.setAnnotations(boxes.toList())
            overlay.selectBox(null)
        }
        overlay.onBoxTransformCommitted = { previous, transformed ->
            boxTransformCount += 1
            lastTransformedBox = transformed
            val targetIndex = boxes.indexOfFirst { it.createdAt == previous.createdAt }
            if (targetIndex >= 0) {
                boxes[targetIndex] = transformed
                overlay.setAnnotations(boxes.toList())
                overlay.selectBox(transformed.createdAt)
            }
        }
        overlay.onPanRequested = { deltaX, deltaY ->
            panRequestCount += 1
            lastPanDeltaX = deltaX
            lastPanDeltaY = deltaY
        }
    }
}
