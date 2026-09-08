package com.document.editor.ocr

import android.net.Uri

/**
 * Represents a complete OCR run that may contain one or many pages/images.
 */
data class OcrSession(
    val id: String,
    val pages: List<OcrPage>,
    val timestamp: Long,
)

/**
 * Represents OCR output and processing metadata for a single source page/image.
 */
data class OcrPage(
    val pageNumber: Int,
    val imageUri: Uri,
    val recognizedText: String,
    val linesWithConfidence: List<LineResult>,
    val preprocessingMetadata: PreprocessingMetadata = PreprocessingMetadata(),
    val errorMessage: String? = null,
)

/**
 * Confidence-aware line unit used by review/edit UI.
 */
data class LineResult(
    val id: String,
    val text: String,
    val confidence: Float?, // null when unavailable from OCR engine
    val boundingBox: SerializableRect? = null,
    val reviewLevel: ReviewLevel = confidence.toReviewLevel(),
) {
    val normalizedConfidence: Float
        get() = confidence?.coerceIn(0f, 1f) ?: 0f
}

/**
 * Optional word-level OCR result for granular highlighting/tap-to-verify.
 */
data class WordResult(
    val text: String,
    val confidence: Float?, // null when unavailable
    val boundingBox: SerializableRect? = null,
)

/**
 * Lightweight rectangle model to avoid direct framework Rect usage in domain models.
 */
data class SerializableRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Metadata describing preprocessing transforms applied before OCR.
 */
data class PreprocessingMetadata(
    val deskewAngleDegrees: Double = 0.0,
    val denoised: Boolean = false,
    val normalizedContrast: Boolean = false,
    val adaptiveBinarization: Boolean = false,
)

/**
 * Discrete confidence categories for UX styling and review prioritization.
 */
enum class ReviewLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN,
}

/**
 * Convenience mapping from nullable confidence to a review tier.
 */
fun Float?.toReviewLevel(): ReviewLevel {
    val value = this ?: return ReviewLevel.UNKNOWN
    val c = value.coerceIn(0f, 1f)
    return when {
        c < 0.60f -> ReviewLevel.LOW
        c < 0.80f -> ReviewLevel.MEDIUM
        else -> ReviewLevel.HIGH
    }
}
