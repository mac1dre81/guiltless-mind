package com.document.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.document.editor.ui.theme.DocEditorTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream

class DocumentScannerActivity : ComponentActivity() {

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val gmsResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)

                var savedPdfFile: File? = null
                gmsResult?.pdf?.let { pdf ->
                    savedPdfFile = savePdfToRecentFiles(pdf.uri)
                }

                val pages = gmsResult?.pages?.map { it.imageUri.toString() } ?: emptyList()
                if (pages.isNotEmpty()) {
                    val intent = Intent(this, OcrResultActivity::class.java).apply {
                        putStringArrayListExtra("imageUris", ArrayList(pages))
                        savedPdfFile?.let { putExtra("pdfPath", it.absolutePath) }
                    }
                    startActivity(intent)
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDiagnostics.logBreadcrumb(this, "Document scanner screen created")
        setContent {
            DocEditorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        launchScanner()
    }

    private fun launchScanner() {
        AppDiagnostics.logBreadcrumb(this, "Preparing ML Kit document scanner")
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                AppDiagnostics.logBreadcrumb(this, "Launching ML Kit scanner intent")
                scannerLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                AppDiagnostics.logBreadcrumb(this, "Failed to start scanner", e)
                Toast.makeText(this, "Failed to start scanner: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun savePdfToRecentFiles(uri: Uri): File? {
        return try {
            val file = File(filesDir, "Scanned_Document_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            AppDiagnostics.logBreadcrumb(this, "Scanner PDF saved to ${AppDiagnostics.describeUri(Uri.fromFile(file).toString())}")
            Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_LONG).show()
            file
        } catch (e: Exception) {
            AppDiagnostics.logBreadcrumb(this, "Error saving scanned document", e)
            Toast.makeText(this, "Error saving document", Toast.LENGTH_SHORT).show()
            null
        }
    }
}
