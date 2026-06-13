package com.document.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.document.editor.ocr.LineResult
import com.document.editor.ocr.OcrProcessor
import com.document.editor.ocr.OcrSessionResult
import com.document.editor.ui.theme.DocEditorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class OcrResultActivity : ComponentActivity() {

    @Inject
    lateinit var recentFilesRepository: RecentFilesRepository

    @Inject
    lateinit var premiumManager: PremiumManager

    @Inject
    lateinit var premiumStatusProvider: PremiumStatusProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppDiagnostics.logBreadcrumb(this, "OCR result screen created")
        premiumStatusProvider.refreshEntitlements()

        val imageUris = intent.getStringArrayListExtra("imageUris") ?: emptyList()
        val pdfPath = intent.getStringExtra("pdfPath")

        setContent {
            val isPro by premiumStatusProvider.isPro.collectAsState(initial = false)

            DocEditorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isPro) {
                        OcrScreen(
                            imageUris = imageUris,
                            pdfPath = pdfPath,
                            repository = recentFilesRepository,
                            onClose = { finish() }
                        )
                    } else {
                        UpgradePromptScreen(
                            onUpgrade = { premiumManager.purchasePro(this@OcrResultActivity) },
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UpgradePromptScreen(
    onUpgrade: () -> Unit,
    onClose: () -> Unit
) {
    val title = stringResource(R.string.upgrade_to_pro_title)
    val message = stringResource(R.string.upgrade_to_pro_message)
    val actionText = stringResource(R.string.upgrade_to_pro_action)
    val closeText = stringResource(R.string.maybe_later)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message)
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onUpgrade,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = actionText)
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onClose) {
            Text(text = closeText)
        }
    }
}

private data class OcrUiPage(
    val pageNumber: Int,
    val imageUri: Uri,
    val initialText: String,
    val editableText: String,
    val lines: List<LineResult>,
    val preprocessingSummary: String
)

@Composable
fun OcrScreen(
    imageUris: List<String>,
    pdfPath: String?,
    repository: RecentFilesRepository,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val noImagesFoundText = stringResource(R.string.no_images_found)
    val scannedImageDescription = stringResource(R.string.scanned_image_content_description)
    val extractingText = stringResource(R.string.extracting_text)
    val ocrErrorText = stringResource(R.string.ocr_error)
    val retryText = stringResource(R.string.retry)
    val ocrResultLabel = stringResource(R.string.ocr_result_label)
    val copiedText = stringResource(R.string.copied_to_clipboard)
    val copyText = stringResource(R.string.ocr_copy)
    val saveTxtText = stringResource(R.string.save_txt)
    val saveMdText = stringResource(R.string.save_md)
    val saveErrorText = stringResource(R.string.save_error)
    val savedAsTemplate = stringResource(R.string.saved_as, "%s")

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<List<OcrUiPage>>(emptyList()) }
    var selectedPageIndex by remember { mutableIntStateOf(0) }

    fun parseSession(result: OcrSessionResult): List<OcrUiPage> {
        return result.session.pages.map { page ->
            val meta = page.preprocessingMetadata
            val summary = buildString {
                append("Deskew: ")
                append(String.format(Locale.US, "%.2f°", meta.deskewAngleDegrees))
                append(" • Denoise: ")
                append(if (meta.denoised) "On" else "Off")
                append(" • Contrast: ")
                append(if (meta.normalizedContrast) "On" else "Off")
                append(" • Binarize: ")
                append(if (meta.adaptiveBinarization) "On" else "Off")
            }
            OcrUiPage(
                pageNumber = page.pageNumber,
                imageUri = page.imageUri,
                initialText = page.recognizedText,
                editableText = page.recognizedText,
                lines = page.linesWithConfidence,
                preprocessingSummary = summary
            )
        }
    }

    fun runOcr() {
        scope.launch {
            isLoading = true
            errorMessage = null
            AppDiagnostics.logBreadcrumb(context, "Starting OCR pipeline for ${imageUris.size} image(s)")

            try {
                val processor = OcrProcessor(context)
                val result = processor.processSession(imageUris)
                val parsedPages = parseSession(result)

                pages = if (parsedPages.isEmpty()) {
                    listOf(
                        OcrUiPage(
                            pageNumber = 1,
                            imageUri = Uri.EMPTY,
                            initialText = noImagesFoundText,
                            editableText = noImagesFoundText,
                            lines = emptyList(),
                            preprocessingSummary = "No preprocessing"
                        )
                    )
                } else {
                    parsedPages
                }
                selectedPageIndex = 0

                AppDiagnostics.logBreadcrumb(
                    context,
                    "OCR pipeline complete: success=${result.successCount}, failed=${result.failureCount}"
                )
            } catch (t: Throwable) {
                errorMessage = t.message ?: ocrErrorText
                AppDiagnostics.logBreadcrumb(context, "OCR pipeline failed", t)
            } finally {
                isLoading = false
            }
        }
    }

    fun updateCurrentPageText(newText: String) {
        if (pages.isEmpty()) return
        pages = pages.mapIndexed { index, page ->
            if (index == selectedPageIndex) page.copy(editableText = newText) else page
        }
    }

    fun mergedRecognizedText(): String {
        return pages.joinToString(separator = "\n\n--- Page Break ---\n\n") { page ->
            "Page ${page.pageNumber}\n${page.editableText}"
        }.trim()
    }

    fun saveOcrResult(extension: String) {
        scope.launch {
            try {
                val mergedText = mergedRecognizedText()
                val file = File(
                    context.filesDir,
                    "OCR_Result_${System.currentTimeMillis()}$extension"
                )

                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { stream ->
                        stream.write(mergedText.toByteArray())
                    }
                }

                repository.addRecentFile(Uri.fromFile(file).toString())
                if (pdfPath != null) {
                    repository.addRecentFile(Uri.fromFile(File(pdfPath)).toString())
                }

                AppDiagnostics.logBreadcrumb(
                    context,
                    "OCR result saved to ${AppDiagnostics.describeUri(Uri.fromFile(file).toString())}"
                )
                Toast.makeText(context, savedAsTemplate.format(file.name), Toast.LENGTH_SHORT).show()
                onClose()
            } catch (t: Throwable) {
                AppDiagnostics.logBreadcrumb(context, "OCR result save failed", t)
                Toast.makeText(context, saveErrorText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(imageUris) {
        if (imageUris.isNotEmpty()) {
            runOcr()
        } else {
            isLoading = false
            errorMessage = noImagesFoundText
        }
    }

    val currentPage = pages.getOrNull(selectedPageIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = ocrResultLabel,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = extractingText,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Applying deskew, denoise, contrast, and binarization before OCR",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Text(
                        text = errorMessage ?: ocrErrorText,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { runOcr() }) {
                        Text(text = retryText)
                    }
                }
            }
        } else {
            if (pages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(pages) { index, page ->
                        FilterChip(
                            selected = index == selectedPageIndex,
                            onClick = { selectedPageIndex = index },
                            label = { Text("Page ${page.pageNumber}") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            currentPage?.let { page ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        if (page.imageUri != Uri.EMPTY) {
                            Image(
                                painter = rememberAsyncImagePainter(model = page.imageUri),
                                contentDescription = scannedImageDescription,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Text(
                            text = page.preprocessingSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = page.editableText,
                    onValueChange = ::updateCurrentPageText,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    label = { Text("Extracted Text (Page ${page.pageNumber})") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (page.lines.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Tap a line to compare with source",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                page.lines.forEach { line ->
                                    Text(
                                        text = "• ${line.text}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.clickable {
                                            Toast.makeText(
                                                context,
                                                "Line selected for visual verification",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(ocrResultLabel, mergedRecognizedText())
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(text = copyText)
                }

                Button(onClick = { saveOcrResult(".txt") }) {
                    Text(text = saveTxtText)
                }

                Button(onClick = { saveOcrResult(".md") }) {
                    Text(text = saveMdText)
                }
            }
        }
    }
}
