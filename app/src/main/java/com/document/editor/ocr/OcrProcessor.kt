package com.document.editor.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import androidx.core.net.toUri
import com.document.editor.AppDiagnostics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * OCR processor that:
 * 1) Loads image from Uri
 * 2) Applies OpenCV preprocessing through [ImagePreprocessor]
 * 3) Runs ML Kit text recognition
 * 4) Produces line/word geometry for tap-to-verify
 *
 * Note:
 * - ML Kit confidence may be unavailable depending on recognizer/runtime; model keeps confidence nullable.
 */
class OcrProcessor(
    private val context: Context,
    private val imagePreprocessor: ImagePreprocessor = ImagePreprocessor(context)
) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun processSession(imageUris: List<String>): OcrSessionResult = withContext(Dispatchers.Default) {
        val pages = mutableListOf<OcrPage>()
        var successCount = 0
        var failureCount = 0

        imageUris.forEachIndexed { index, uriString ->
            val pageNumber = index + 1

            val page = runCatching {
                processSinglePage(pageNumber = pageNumber, imageUriString = uriString)
            }.getOrElse { throwable ->
                failureCount++
                AppDiagnostics.logBreadcrumb(
                    context,
                    "OCR page failed (#$pageNumber): ${AppDiagnostics.describeUri(uriString)}",
                    throwable
                )
                OcrPage(
                    pageNumber = pageNumber,
                    imageUri = uriString.toUri(),
                    recognizedText = "",
                    linesWithConfidence = emptyList(),
                    errorMessage = throwable.message ?: "Unknown OCR error"
                )
            }

            if (page.errorMessage == null && page.recognizedText.isNotBlank()) {
                successCount++
            } else if (page.errorMessage != null) {
                // already counted in failure branch above if exception; guard for future error paths
                failureCount++
            } else {
                // no text but no hard failure
                failureCount++
            }

            pages += page
        }

        OcrSessionResult(
            session = OcrSession(
                id = UUID.randomUUID().toString(),
                pages = pages,
                timestamp = System.currentTimeMillis()
            ),
            successCount = successCount,
            failureCount = failureCount
        )
    }

    private suspend fun processSinglePage(
        pageNumber: Int,
        imageUriString: String
    ): OcrPage = withContext(Dispatchers.Default) {
        val uri = imageUriString.toUri()

        val preprocessResult = runCatching {
            imagePreprocessor.preprocess(uri)
        }.getOrElse { throwable ->
            AppDiagnostics.logBreadcrumb(
                context,
                "Preprocessing crashed; falling back to original image",
                throwable
            )
            PreprocessResult.Error("Preprocessing exception: ${throwable.message ?: "unknown"}")
        }

        val (bitmapForOcr, metadata) = when (preprocessResult) {
            is PreprocessResult.Success -> {
                preprocessResult.bitmap to PreprocessingMetadata(
                    deskewAngleDegrees = preprocessResult.metadata.deskewAngleDegrees,
                    denoised = preprocessResult.metadata.denoised,
                    normalizedContrast = preprocessResult.metadata.normalizedContrast,
                    adaptiveBinarization = preprocessResult.metadata.adaptiveBinarization
                )
            }

            is PreprocessResult.Error -> {
                AppDiagnostics.logBreadcrumb(
                    context,
                    "Preprocessing failed; using source image. Reason: ${preprocessResult.message}"
                )
                val fallbackBitmap = loadBitmapFromUri(uri)
                    ?: throw IllegalStateException("Unable to decode image from Uri: $uri")
                fallbackBitmap to PreprocessingMetadata()
            }
        }

        val inputImage = InputImage.fromBitmap(bitmapForOcr, 0)
        val result = recognizer.process(inputImage).await()

        val lineResults = mutableListOf<LineResult>()
        val fullTextBuilder = StringBuilder()

        result.textBlocks.forEachIndexed { blockIndex, block ->
            block.lines.forEachIndexed { lineIndex, line ->
                val lineText = line.text.orEmpty().trim()
                if (lineText.isBlank()) return@forEachIndexed

                val wordResults = line.elements.map { element ->
                    WordResult(
                        text = element.text.orEmpty(),
                        confidence = element.confidence.coerceIn(0f, 1f),
                        boundingBox = element.boundingBox?.toSerializableRect()
                    )
                }

                val lineConfidence: Float? = wordResults
                    .mapNotNull { it.confidence }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()
                    ?.coerceIn(0f, 1f)

                lineResults += LineResult(
                    id = "p${pageNumber}_b${blockIndex}_l${lineIndex}",
                    text = lineText,
                    confidence = lineConfidence,
                    boundingBox = line.boundingBox?.toSerializableRect()
                )

                fullTextBuilder.append(lineText).append('\n')
            }

            if (blockIndex < result.textBlocks.lastIndex) {
                fullTextBuilder.append('\n')
            }
        }

        OcrPage(
            pageNumber = pageNumber,
            imageUri = uri,
            recognizedText = fullTextBuilder.toString().trim(),
            linesWithConfidence = lineResults,
            preprocessingMetadata = metadata,
            errorMessage = null
        )
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    }

    private fun Rect.toSerializableRect(): SerializableRect {
        return SerializableRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }
}

/** Session envelope with quick stats used by UI. */
data class OcrSessionResult(
    val session: OcrSession,
    val successCount: Int,
    val failureCount: Int
)
