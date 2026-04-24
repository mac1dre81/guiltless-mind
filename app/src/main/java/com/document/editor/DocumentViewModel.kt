package com.document.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

@HiltViewModel
class DocumentViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {
    private val _content = MutableLiveData<String>()
    val content: LiveData<String> = _content

    private val _pdfPage = MutableLiveData<Bitmap?>()
    val pdfPage: LiveData<Bitmap?> = _pdfPage

    private val _pdfPageCount = MutableLiveData(0)
    val pdfPageCount: LiveData<Int> = _pdfPageCount

    private val _currentPdfPageIndex = MutableLiveData(0)
    val currentPdfPageIndex: LiveData<Int> = _currentPdfPageIndex

    private val _currentPdfAnnotations = MutableLiveData<List<PdfAnnotationElement>>(emptyList())
    val currentPdfAnnotations: LiveData<List<PdfAnnotationElement>> = _currentPdfAnnotations

    private val _savedPdfUri = MutableLiveData<String?>(null)
    val savedPdfUri: LiveData<String?> = _savedPdfUri

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    val documentType = MutableLiveData<DocType>()

    enum class DocType {
        TEXT, MARKDOWN, PDF, UNKNOWN
    }

    private enum class StreamSignature {
        PDF,
        TEXT,
        BINARY,
        UNKNOWN
    }

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentPdfUri: Uri? = null
    private val pdfAnnotations = mutableMapOf<Int, MutableList<PdfAnnotationElement>>()
    private var pendingPdfUndoAction: PdfUndoAction? = null

    fun loadDocument(uriString: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val uri = uriString.toUri()
            AppDiagnostics.logBreadcrumb(getApplication(), "Loading document ${AppDiagnostics.describeUri(uriString)}")
            try {
                val type = determineDocType(uri)
                AppDiagnostics.logBreadcrumb(
                    getApplication(),
                    "Resolved document type for ${AppDiagnostics.describeUri(uriString)} as $type"
                )
                documentType.value = type
                withContext(Dispatchers.IO) {
                    when (type) {
                        DocType.TEXT, DocType.MARKDOWN -> {
                            val text = loadText(uri)
                            withContext(Dispatchers.Main) {
                                _content.value = text
                                _pdfPage.value = null
                                _pdfPageCount.value = 0
                                _currentPdfPageIndex.value = 0
                                _currentPdfAnnotations.value = emptyList()
                            }
                        }

                        DocType.PDF -> {
                            loadPdf(uri)
                        }

                        else -> {
                            withContext(Dispatchers.Main) {
                                _content.value = ""
                                _pdfPage.value = null
                                _pdfPageCount.value = 0
                                _currentPdfPageIndex.value = 0
                                _currentPdfAnnotations.value = emptyList()
                                _error.value = getApplication<Application>().getString(R.string.unsupported_file_type)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppDiagnostics.logBreadcrumb(getApplication(), "Document load failed", e)
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveDocument(uriString: String, text: String, showFeedback: Boolean = true) {
        if (documentType.value == DocType.PDF) {
            saveEditedPdfToUri(uriString)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val uri = uriString.toUri()
            AppDiagnostics.logBreadcrumb(getApplication(), "Saving document ${AppDiagnostics.describeUri(uriString)}")
            try {
                withContext(Dispatchers.IO) {
                    val cr = getApplication<Application>().contentResolver
                    cr.openOutputStream(uri, "wt")?.use { os ->
                        os.write(text.toByteArray())
                    }
                }
                if (showFeedback) {
                    _error.value = "Saved successfully"
                }
            } catch (e: Exception) {
                AppDiagnostics.logBreadcrumb(getApplication(), "Document save failed", e)
                _error.value = "Save failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun showNextPdfPage() {
        val nextPage = (_currentPdfPageIndex.value ?: 0) + 1
        val pageCount = _pdfPageCount.value ?: 0
        if (nextPage < pageCount) {
            viewModelScope.launch {
                showPdfPage(nextPage)
            }
        }
    }

    fun showPreviousPdfPage() {
        val previousPage = (_currentPdfPageIndex.value ?: 0) - 1
        if (previousPage >= 0) {
            viewModelScope.launch {
                showPdfPage(previousPage)
            }
        }
    }

    fun addPdfStroke(stroke: PdfInkStroke) {
        clearPendingPdfUndoAction()
        val pageIndex = _currentPdfPageIndex.value ?: return
        val pageAnnotations = pdfAnnotations.getOrPut(pageIndex) { mutableListOf() }
        pageAnnotations.add(stroke)
        _currentPdfAnnotations.value = pageAnnotations.toList()
    }

    fun addPdfTextAnnotation(text: String, xRatio: Float, yRatio: Float) {
        clearPendingPdfUndoAction()
        val pageIndex = _currentPdfPageIndex.value ?: return
        val pageAnnotations = pdfAnnotations.getOrPut(pageIndex) { mutableListOf() }
        pageAnnotations.add(
            PdfTextAnnotation(
                text = text,
                xRatio = xRatio,
                yRatio = yRatio
            )
        )
        _currentPdfAnnotations.value = pageAnnotations.toList()
    }

    fun addPdfBoxAnnotation(annotation: PdfBoxAnnotation): PdfBoxAnnotation? {
        clearPendingPdfUndoAction()
        val pageIndex = _currentPdfPageIndex.value ?: return null
        val pageAnnotations = pdfAnnotations.getOrPut(pageIndex) { mutableListOf() }
        val normalizedAnnotation = annotation.normalized()
        pageAnnotations.add(normalizedAnnotation)
        _currentPdfAnnotations.value = pageAnnotations.toList()
        return normalizedAnnotation
    }

    fun updatePdfBoxAnnotation(annotation: PdfBoxAnnotation) {
        val pageIndex = _currentPdfPageIndex.value ?: return
        val pageAnnotations = pdfAnnotations.getOrPut(pageIndex) { mutableListOf() }
        val updatedAnnotation = annotation.normalized()
        val existingAnnotation = pageAnnotations
            .filterIsInstance<PdfBoxAnnotation>()
            .firstOrNull { it.createdAt == updatedAnnotation.createdAt }
            ?: return

        replacePdfBoxAnnotation(
            previous = existingAnnotation,
            updated = updatedAnnotation
        )
    }

    fun commitPdfBoxTransform(previous: PdfBoxAnnotation, transformed: PdfBoxAnnotation) {
        replacePdfBoxAnnotation(
            previous = previous,
            updated = transformed
        )
    }

    fun removePdfBoxAnnotation(boxId: Long) {
        val pageIndex = _currentPdfPageIndex.value ?: return
        val pageAnnotations = pdfAnnotations[pageIndex] ?: return
        val targetIndex = pageAnnotations.indexOfFirst {
            it is PdfBoxAnnotation && it.createdAt == boxId
        }
        if (targetIndex >= 0) {
            val removedAnnotation = pageAnnotations.removeAt(targetIndex)
            pendingPdfUndoAction = PdfUndoAction.RestoreDeletedAnnotation(
                pageIndex = pageIndex,
                annotation = removedAnnotation,
                insertionIndex = targetIndex
            )
            _currentPdfAnnotations.value = pageAnnotations.toList()
        }
    }

    fun undoLastPdfAnnotation() {
        pendingPdfUndoAction?.let { undoAction ->
            when (undoAction) {
                is PdfUndoAction.RestoreDeletedAnnotation -> {
                    val pageAnnotations = pdfAnnotations.getOrPut(undoAction.pageIndex) { mutableListOf() }
                    val insertionIndex = undoAction.insertionIndex.coerceIn(0, pageAnnotations.size)
                    pageAnnotations.add(insertionIndex, undoAction.annotation)
                    if ((_currentPdfPageIndex.value ?: -1) == undoAction.pageIndex) {
                        _currentPdfAnnotations.value = pageAnnotations.toList()
                    }
                    clearPendingPdfUndoAction()
                    return
                }

                is PdfUndoAction.RestoreBoxReplacement -> {
                    val pageAnnotations = pdfAnnotations.getOrPut(undoAction.pageIndex) { mutableListOf() }
                    val targetIndex = pageAnnotations.indexOfFirst {
                        it is PdfBoxAnnotation && it.createdAt == undoAction.updatedAnnotation.createdAt
                    }.takeIf { it >= 0 } ?: undoAction.targetIndex.coerceIn(
                        0,
                        pageAnnotations.lastIndex.coerceAtLeast(0)
                    )

                    if (pageAnnotations.isEmpty()) {
                        pageAnnotations.add(undoAction.previousAnnotation)
                    } else if (targetIndex in pageAnnotations.indices) {
                        pageAnnotations[targetIndex] = undoAction.previousAnnotation
                    } else {
                        pageAnnotations.add(undoAction.previousAnnotation)
                    }

                    if ((_currentPdfPageIndex.value ?: -1) == undoAction.pageIndex) {
                        _currentPdfAnnotations.value = pageAnnotations.toList()
                    }
                    clearPendingPdfUndoAction()
                    return
                }
            }
        }

        val pageIndex = _currentPdfPageIndex.value ?: return
        val pageAnnotations = pdfAnnotations[pageIndex] ?: return
        if (pageAnnotations.isNotEmpty()) {
            pageAnnotations.removeAt(pageAnnotations.lastIndex)
            _currentPdfAnnotations.value = pageAnnotations.toList()
        }
    }

    fun erasePdfAnnotationAt(xRatio: Float, yRatio: Float) {
        clearPendingPdfUndoAction()
        val pageIndex = _currentPdfPageIndex.value ?: return
        val pageAnnotations = pdfAnnotations[pageIndex] ?: return
        val targetIndex = pageAnnotations.indices.reversed().firstOrNull { index ->
            annotationContainsPoint(pageAnnotations[index], xRatio, yRatio)
        } ?: return

        val removedAnnotation = pageAnnotations.removeAt(targetIndex)
        pendingPdfUndoAction = PdfUndoAction.RestoreDeletedAnnotation(
            pageIndex = pageIndex,
            annotation = removedAnnotation,
            insertionIndex = targetIndex
        )
        _currentPdfAnnotations.value = pageAnnotations.toList()
    }

    internal fun seedPdfStateForTesting(
        annotationsByPage: Map<Int, List<PdfAnnotationElement>>,
        currentPageIndex: Int = 0,
        pageCount: Int = 1
    ) {
        pdfAnnotations.clear()
        annotationsByPage.forEach { (pageIndex, annotations) ->
            pdfAnnotations[pageIndex] = annotations.toMutableList()
        }
        _pdfPageCount.value = pageCount
        _currentPdfPageIndex.value = currentPageIndex
        _currentPdfAnnotations.value = pdfAnnotations[currentPageIndex]?.toList().orEmpty()
        documentType.value = DocType.PDF
        clearPendingPdfUndoAction()
    }

    internal fun seedTextStateForTesting(
        type: DocType,
        content: String
    ) {
        require(type == DocType.TEXT || type == DocType.MARKDOWN) {
            "seedTextStateForTesting only supports TEXT or MARKDOWN"
        }
        pdfAnnotations.clear()
        _pdfPage.value = null
        _pdfPageCount.value = 0
        _currentPdfPageIndex.value = 0
        _currentPdfAnnotations.value = emptyList()
        _content.value = content
        documentType.value = type
        clearPendingPdfUndoAction()
    }

    fun buildSuggestedEditedPdfFileName(uriString: String): String {
        return buildEditedPdfFileName(uriString.toUri())
    }

    private fun replacePdfBoxAnnotation(
        previous: PdfBoxAnnotation,
        updated: PdfBoxAnnotation
    ) {
        val pageIndex = _currentPdfPageIndex.value ?: return
        val pageAnnotations = pdfAnnotations.getOrPut(pageIndex) { mutableListOf() }
        val normalizedPrevious = previous.normalized()
        val normalizedUpdated = updated.normalized()
        val targetIndex = pageAnnotations.indexOfFirst {
            it is PdfBoxAnnotation && it.createdAt == normalizedUpdated.createdAt
        }
        if (targetIndex < 0 || normalizedPrevious == normalizedUpdated) {
            return
        }

        clearPendingPdfUndoAction()
        pendingPdfUndoAction = PdfUndoAction.RestoreBoxReplacement(
            pageIndex = pageIndex,
            previousAnnotation = normalizedPrevious,
            updatedAnnotation = normalizedUpdated,
            targetIndex = targetIndex
        )
        pageAnnotations[targetIndex] = normalizedUpdated
        _currentPdfAnnotations.value = pageAnnotations.toList()
    }

    fun clearSavedPdfUri() {
        _savedPdfUri.value = null
    }

    private fun determineDocType(uri: Uri): DocType {
        val cr = getApplication<Application>().contentResolver
        val mimeType = cr.getType(uri).orEmpty()
        val path = uri.path.orEmpty()
        val displayName = resolveDisplayName(uri)
        val signature = sniffStreamSignature(uri)

        val looksLikePdf =
            mimeType.contains("pdf", ignoreCase = true) ||
                    displayName.endsWith(".pdf", true) ||
                    path.endsWith(".pdf", true) ||
                    signature == StreamSignature.PDF

        val looksLikeMarkdown =
            displayName.endsWith(".md", true) ||
                    path.endsWith(".md", true) ||
                    mimeType.contains("markdown", ignoreCase = true)

        val looksLikeText =
            mimeType.startsWith("text/") ||
                    displayName.endsWith(".txt", true) ||
                    path.endsWith(".txt", true) ||
                    signature == StreamSignature.TEXT

        return if (looksLikePdf) {
            DocType.PDF
        } else if (looksLikeMarkdown) {
            DocType.MARKDOWN
        } else if (looksLikeText) {
            DocType.TEXT
        } else {
            DocType.UNKNOWN
        }
    }

    private fun sniffStreamSignature(uri: Uri): StreamSignature {
        return runCatching {
            val contentResolver = getApplication<Application>().contentResolver
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val sample = ByteArray(1024)
                val bytesRead = inputStream.read(sample)
                if (bytesRead <= 0) {
                    return@use StreamSignature.UNKNOWN
                }

                val snippet = String(sample, 0, bytesRead, Charsets.ISO_8859_1)
                if (snippet.contains("%PDF-")) {
                    return@use StreamSignature.PDF
                }

                val binaryControlCount = sample
                    .take(bytesRead)
                    .count { byte ->
                        val value = byte.toInt() and 0xFF
                        value == 0 || (value < 0x09) || (value in 0x0E..0x1F)
                    }

                return@use if (binaryControlCount > bytesRead / 10) {
                    StreamSignature.BINARY
                } else {
                    StreamSignature.TEXT
                }
            } ?: StreamSignature.UNKNOWN
        }.getOrDefault(StreamSignature.UNKNOWN)
    }

    private suspend fun loadText(uri: Uri): String {
        return withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }
    }

    private suspend fun loadPdf(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                currentPage?.close()
                pdfRenderer?.close()
                fileDescriptor?.close()

                val cr = getApplication<Application>().contentResolver
                val fd = cr.openFileDescriptor(uri, "r") ?: return@withContext
                fileDescriptor = fd
                pdfRenderer = PdfRenderer(fd)
                currentPdfUri = uri
                pdfAnnotations.clear()
                clearPendingPdfUndoAction()
                val pageCount = pdfRenderer?.pageCount ?: 0
                withContext(Dispatchers.Main) {
                    _pdfPageCount.value = pageCount
                }
                if (pageCount > 0) {
                    showPdfPage(0)
                }
            } catch (e: Exception) {
                AppDiagnostics.logBreadcrumb(getApplication(), "PDF load failed", e)
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to load PDF: ${e.message}"
                }
            }
        }
    }

    private suspend fun showPdfPage(index: Int) {
        withContext(Dispatchers.IO) {
            currentPage?.close()
            pdfRenderer?.let { renderer ->
                val page = renderer.openPage(index)
                currentPage = page
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                withContext(Dispatchers.Main) {
                    _pdfPage.value?.takeIf { !it.isRecycled }?.recycle()
                    _pdfPage.value = bitmap
                    _currentPdfPageIndex.value = index
                    _currentPdfAnnotations.value = pdfAnnotations[index]?.toList().orEmpty()
                }
            }
        }
    }

    fun saveEditedPdfToUri(targetUriString: String) {
        viewModelScope.launch {
            _isLoading.value = true
            AppDiagnostics.logBreadcrumb(
                getApplication(),
                "Saving edited PDF ${AppDiagnostics.describeUri(targetUriString)}"
            )
            try {
                val savedUri = withContext(Dispatchers.IO) {
                    val renderer = pdfRenderer ?: error("No PDF loaded")
                    val outputFile = File(
                        getApplication<Application>().cacheDir,
                        buildEditedPdfFileName(targetUriString.toUri())
                    )
                    val document = PdfDocument()

                    try {
                        for (pageIndex in 0 until renderer.pageCount) {
                            val page = renderer.openPage(pageIndex)
                            try {
                                val renderWidth = page.width * 2
                                val renderHeight = page.height * 2
                                val renderedBitmap = createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                                try {
                                    renderedBitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(renderedBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    applyAnnotationsToBitmap(
                                        bitmap = renderedBitmap,
                                        annotations = pdfAnnotations[pageIndex].orEmpty()
                                    )

                                    val pageInfo =
                                        PdfDocument.PageInfo.Builder(page.width, page.height, pageIndex + 1).create()
                                    val outputPage = document.startPage(pageInfo)
                                    outputPage.canvas.drawBitmap(
                                        renderedBitmap,
                                        null,
                                        Rect(0, 0, page.width, page.height),
                                        null
                                    )
                                    document.finishPage(outputPage)
                                } finally {
                                    if (!renderedBitmap.isRecycled) {
                                        renderedBitmap.recycle()
                                    }
                                }
                            } finally {
                                page.close()
                            }
                        }

                        FileOutputStream(outputFile).use { outputStream ->
                            document.writeTo(outputStream)
                        }
                    } finally {
                        document.close()
                    }

                    writeTempPdfToTarget(outputFile, targetUriString.toUri())
                    targetUriString
                }
                _savedPdfUri.value = savedUri
                _error.value = getApplication<Application>().getString(
                    R.string.saved_as,
                    resolveDisplayName(savedUri.toUri())
                )
            } catch (e: Exception) {
                AppDiagnostics.logBreadcrumb(getApplication(), "Saving edited PDF failed", e)
                _error.value = "Save failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun applyAnnotationsToBitmap(bitmap: Bitmap, annotations: List<PdfAnnotationElement>) {
        if (annotations.isEmpty()) {
            return
        }
        val canvas = Canvas(bitmap)
        val bitmapRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        annotations.forEach { annotation ->
            when (annotation) {
                is PdfInkStroke -> {
                    if (annotation.points.size > 1) {
                        strokePaint.color = annotation.color
                        strokePaint.strokeWidth = annotation.strokeWidthRatio * minOf(bitmap.width, bitmap.height)
                        val path = android.graphics.Path().apply {
                            moveTo(
                                annotation.points.first().xRatio * bitmap.width,
                                annotation.points.first().yRatio * bitmap.height
                            )
                            annotation.points.drop(1).forEach { point ->
                                lineTo(point.xRatio * bitmap.width, point.yRatio * bitmap.height)
                            }
                        }
                        canvas.drawPath(path, strokePaint)
                    }
                }

                is PdfTextAnnotation -> {
                    drawPdfTextAnnotation(canvas, annotation, bitmapRect, textPaint)
                }

                is PdfBoxAnnotation -> {
                    drawPdfBoxAnnotation(canvas, annotation, resolvePdfBoxRect(annotation, bitmapRect))
                }
            }
        }
    }

    private fun writeTempPdfToTarget(tempPdfFile: File, targetUri: Uri) {
        try {
            openPdfOutputStream(targetUri).use { outputStream ->
                tempPdfFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } finally {
            if (tempPdfFile.exists() && !tempPdfFile.delete()) {
                tempPdfFile.deleteOnExit()
            }
        }
    }

    private fun openPdfOutputStream(uri: Uri): OutputStream {
        return when (uri.scheme) {
            "content" -> getApplication<Application>().contentResolver.openOutputStream(uri, "w")
            "file", null -> FileOutputStream(File(requireNotNull(uri.path ?: uri.toString())))
            else -> null
        } ?: error("Unable to open output destination")
    }

    private fun annotationContainsPoint(annotation: PdfAnnotationElement, xRatio: Float, yRatio: Float): Boolean {
        return when (annotation) {
            is PdfInkStroke -> strokeContainsPoint(annotation, xRatio, yRatio)
            is PdfTextAnnotation -> {
                abs(annotation.xRatio - xRatio) <= 0.05f && abs(annotation.yRatio - yRatio) <= 0.05f
            }

            is PdfBoxAnnotation -> {
                xRatio in annotation.leftRatio..annotation.rightRatio && yRatio in annotation.topRatio..annotation.bottomRatio
            }
        }
    }

    private fun strokeContainsPoint(stroke: PdfInkStroke, xRatio: Float, yRatio: Float): Boolean {
        val threshold =
            max(0.03f, stroke.strokeWidthRatio * if (stroke.strokeKind == PdfStrokeKind.HIGHLIGHT) 1.75f else 4f)
        val points = stroke.points
        if (points.isEmpty()) {
            return false
        }
        if (points.size == 1) {
            return squaredDistance(
                points.first().xRatio,
                points.first().yRatio,
                xRatio,
                yRatio
            ) <= threshold * threshold
        }

        return points.zipWithNext().any { (start, end) ->
            distanceToSegmentSquared(
                px = xRatio,
                py = yRatio,
                ax = start.xRatio,
                ay = start.yRatio,
                bx = end.xRatio,
                by = end.yRatio
            ) <= threshold * threshold
        }
    }

    private fun squaredDistance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return (dx * dx) + (dy * dy)
    }

    private fun distanceToSegmentSquared(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0f && dy == 0f) {
            return squaredDistance(px, py, ax, ay)
        }

        val projection = (((px - ax) * dx) + ((py - ay) * dy)) / ((dx * dx) + (dy * dy))
        val clamped = projection.coerceIn(0f, 1f)
        val closestX = ax + (clamped * dx)
        val closestY = ay + (clamped * dy)
        return squaredDistance(px, py, closestX, closestY)
    }

    private fun buildEditedPdfFileName(uri: Uri): String {
        val displayName = resolveDisplayName(uri)
        val baseName = displayName.substringBeforeLast(".pdf", displayName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "Edited_Document" }
        return "${baseName}_edited_${System.currentTimeMillis()}.pdf"
    }

    private fun resolveDisplayName(uri: Uri): String {
        val contentResolver = getApplication<Application>().contentResolver
        if (uri.scheme == "content") {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    listOf(
                        OpenableColumns.DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ).forEach { columnName ->
                        val nameIndex = cursor.getColumnIndex(columnName)
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            }
        }
        return uri.lastPathSegment ?: "document.pdf"
    }

    override fun onCleared() {
        _pdfPage.value?.takeIf { !it.isRecycled }?.recycle()
        _pdfPage.value = null
        currentPage?.close()
        pdfRenderer?.close()
        fileDescriptor?.close()
        super.onCleared()
    }

    private fun clearPendingPdfUndoAction() {
        pendingPdfUndoAction = null
    }
}
