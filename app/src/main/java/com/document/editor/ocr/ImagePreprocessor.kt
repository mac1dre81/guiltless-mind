package com.document.editor.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

/**
 * OpenCV preprocessing pipeline for OCR:
 * 1) Auto-deskew
 * 2) Denoise
 * 3) Contrast normalization
 * 4) Adaptive binarization
 *
 * API contract:
 * - input: Uri
 * - output: [PreprocessResult], carrying processed bitmap + metadata
 */
class ImagePreprocessor(
    private val context: Context
) {

    // OpenCV is provided by the bundled AAR dependency and loaded automatically.

    suspend fun preprocess(uri: Uri): PreprocessResult = withContext(Dispatchers.Default) {
        val sourceBitmap = decodeBitmap(uri)
            ?: return@withContext PreprocessResult.Error(
                message = "Unable to decode image from uri: $uri"
            )

        val srcBgr = bitmapToBgrMat(sourceBitmap)
        var deskewAngle = 0.0

        try {
            // 1) Deskew
            val deskewResult = autoDeskew(srcBgr)
            val deskewed = deskewResult.mat
            deskewAngle = deskewResult.angleDegrees

            // 2) Denoise
            val denoised = Mat()
            Imgproc.medianBlur(deskewed, denoised, 3)

            // 3) Normalize contrast
            val gray = Mat()
            Imgproc.cvtColor(denoised, gray, Imgproc.COLOR_BGR2GRAY)

            val normalized = Mat()
            Core.normalize(gray, normalized, 0.0, 255.0, Core.NORM_MINMAX)

            // 4) Adaptive threshold
            val binary = Mat()
            Imgproc.adaptiveThreshold(
                normalized,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                31, // odd block size
                15.0
            )

            val outputBitmap = matToBitmap(binary)

            releaseAll(
                srcBgr,
                deskewed,
                denoised,
                gray,
                normalized,
                binary
            )

            PreprocessResult.Success(
                bitmap = outputBitmap,
                metadata = PreprocessMetadata(
                    sourceUri = uri.toString(),
                    width = outputBitmap.width,
                    height = outputBitmap.height,
                    deskewAngleDegrees = deskewAngle,
                    denoised = true,
                    normalizedContrast = true,
                    adaptiveBinarization = true,
                    pipeline = "deskew>denoise>normalize>adaptiveThreshold"
                )
            )
        } catch (t: Throwable) {
            releaseAll(srcBgr)
            PreprocessResult.Error(
                message = "Preprocessing failed: ${t.message ?: "unknown error"}"
            )
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
    }

    private fun bitmapToBgrMat(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        when (mat.type()) {
            CvType.CV_8UC1 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA)
            else -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
        }

        val bitmap = createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    /**
     * Estimate skew angle from dominant near-horizontal lines and rotate image.
     */
    private fun autoDeskew(srcBgr: Mat): DeskewResult {
        val gray = Mat()
        Imgproc.cvtColor(srcBgr, gray, Imgproc.COLOR_BGR2GRAY)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        val lines = Mat()
        Imgproc.HoughLinesP(
            edges,
            lines,
            1.0,
            Math.PI / 180.0,
            100,
            100.0,
            10.0
        )

        var angleSum = 0.0
        var count = 0

        for (i in 0 until lines.rows()) {
            val values = lines.get(i, 0) ?: continue
            if (values.size < 4) continue

            val x1 = values[0]
            val y1 = values[1]
            val x2 = values[2]
            val y2 = values[3]

            val angle = Math.toDegrees(atan2(y2 - y1, x2 - x1))
            if (abs(angle) <= 30.0) {
                angleSum += angle
                count++
            }
        }

        val averageAngle = if (count > 0) angleSum / count else 0.0
        val center = Point(srcBgr.cols() / 2.0, srcBgr.rows() / 2.0)
        val rotationMatrix = Imgproc.getRotationMatrix2D(center, averageAngle, 1.0)

        val rotated = Mat()
        Imgproc.warpAffine(
            srcBgr,
            rotated,
            rotationMatrix,
            Size(srcBgr.cols().toDouble(), srcBgr.rows().toDouble()),
            Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT,
            Scalar(255.0, 255.0, 255.0)
        )

        releaseAll(gray, edges, lines, rotationMatrix)
        return DeskewResult(mat = rotated, angleDegrees = averageAngle)
    }

    private fun releaseAll(vararg mats: Mat) {
        mats.forEach { runCatching { it.release() } }
    }

    private data class DeskewResult(
        val mat: Mat,
        val angleDegrees: Double
    )
}

sealed class PreprocessResult {
    data class Success(
        val bitmap: Bitmap,
        val metadata: PreprocessMetadata
    ) : PreprocessResult()

    data class Error(
        val message: String
    ) : PreprocessResult()
}

data class PreprocessMetadata(
    val sourceUri: String,
    val width: Int,
    val height: Int,
    val deskewAngleDegrees: Double,
    val denoised: Boolean,
    val normalizedContrast: Boolean,
    val adaptiveBinarization: Boolean,
    val pipeline: String
)
