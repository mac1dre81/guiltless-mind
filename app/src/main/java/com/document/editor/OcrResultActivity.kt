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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.rememberAsyncImagePainter
import com.document.editor.ui.theme.DocEditorTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class OcrResultActivity : ComponentActivity() {
    private lateinit var premiumManager: PremiumManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        premiumManager = PremiumManager(this)
        AppDiagnostics.logBreadcrumb(this, "OCR result screen created")

        val imageUris = intent.getStringArrayListExtra("imageUris") ?: emptyList()
        val pdfPath = intent.getStringExtra("pdfPath")

        setContent {
            val isPro by premiumManager.isPro.collectAsState(initial = false)

            DocEditorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isPro) {
                        OcrScreen(
                            imageUris = imageUris,
                            pdfPath = pdfPath,
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

    override fun onDestroy() {
        premiumManager.close()
        super.onDestroy()
    }
}

@Composable
fun UpgradePromptScreen(onUpgrade: () -> Unit, onClose: () -> Unit) {
    val title = stringResource(R.string.upgrade_to_pro_title)
    val message = stringResource(R.string.upgrade_to_pro_message)
    val actionText = stringResource(R.string.upgrade_to_pro_action)
    val closeText = stringResource(R.string.maybe_later)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(message)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
            Text(actionText)
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onClose) {
            Text(closeText)
        }
    }
}

@Composable
fun OcrScreen(imageUris: List<String>, pdfPath: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { RecentFilesRepository(context) }
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
    var recognizedText by remember { mutableStateOf("") }
    var errorState by remember { mutableStateOf(false) }

    fun processImages() {
        coroutineScope.launch {
            isLoading = true
            errorState = false
            AppDiagnostics.logBreadcrumb(context, "Starting OCR for ${imageUris.size} image(s)")
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val stringBuilder = StringBuilder()

                for (uriString in imageUris) {
                    val uri = uriString.toUri()
                    val image = InputImage.fromFilePath(context, uri)

                    val result = recognizer.process(image).await()
                    stringBuilder.append(result.text).append("\n\n")
                }
                recognizedText = stringBuilder.toString().trim()
            } catch (throwable: Exception) {
                errorState = true
                AppDiagnostics.logBreadcrumb(context, "OCR processing failed", throwable)
            } finally {
                isLoading = false
            }
        }
    }

    fun saveOcrResult(extension: String) {
        coroutineScope.launch {
            try {
                val file = File(context.filesDir, "OCR_Result_${System.currentTimeMillis()}$extension")
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use {
                        it.write(recognizedText.toByteArray())
                    }
                }
                repository.addRecentFile(Uri.fromFile(file).toString())
                if (pdfPath != null) {
                    repository.addRecentFile(Uri.fromFile(File(pdfPath)).toString())
                }
                AppDiagnostics.logBreadcrumb(context, "OCR result saved to ${AppDiagnostics.describeUri(Uri.fromFile(file).toString())}")
                Toast.makeText(context, savedAsTemplate.format(file.name), Toast.LENGTH_SHORT).show()
                onClose()
            } catch (_: Exception) {
                AppDiagnostics.logBreadcrumb(context, "OCR result save failed")
                Toast.makeText(context, saveErrorText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(imageUris) {
        if (imageUris.isNotEmpty()) {
            processImages()
        } else {
            isLoading = false
            recognizedText = noImagesFoundText
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (imageUris.isNotEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(model = imageUris.first()),
                contentDescription = scannedImageDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = 16.dp)
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(extractingText)
                }
            }
        } else if (errorState) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ocrErrorText, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { processImages() }) {
                        Text(retryText)
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = recognizedText,
                onValueChange = { recognizedText = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = { Text(ocrResultLabel) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(ocrResultLabel, recognizedText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                }) {
                    Text(copyText)
                }

                Button(onClick = {
                    saveOcrResult(".txt")
                }) {
                    Text(saveTxtText)
                }

                Button(onClick = {
                    saveOcrResult(".md")
                }) {
                    Text(saveMdText)
                }
            }
        }
    }
}
