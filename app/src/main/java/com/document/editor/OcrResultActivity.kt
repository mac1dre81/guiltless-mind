package com.document.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
}

@Composable
fun UpgradePromptScreen(onUpgrade: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Pro Subscription Required", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("OCR text extraction is a Pro feature. Please upgrade to unleash the full power of DocEditor!")
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
            Text("Upgrade to Pro")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onClose) {
            Text("Maybe Later")
        }
    }
}

@Composable
fun OcrScreen(imageUris: List<String>, pdfPath: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { RecentFilesRepository(context) }

    var isLoading by remember { mutableStateOf(true) }
    var recognizedText by remember { mutableStateOf("") }
    var errorState by remember { mutableStateOf(false) }

    fun processImages() {
        coroutineScope.launch {
            isLoading = true
            errorState = false
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
            } catch (e: Exception) {
                e.printStackTrace()
                errorState = true
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(imageUris) {
        if (imageUris.isNotEmpty()) {
            processImages()
        } else {
            isLoading = false
            recognizedText = "No images found."
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (imageUris.isNotEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(model = imageUris.first()),
                contentDescription = "Scanned Image",
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
                    Text("Extracting text...")
                }
            }
        } else if (errorState) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Error extracting text.", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { processImages() }) {
                        Text("Retry")
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
                label = { Text("Extracted Text") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Extracted Text", recognizedText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }

                Button(onClick = {
                    coroutineScope.launch {
                        try {
                            val file = File(context.filesDir, "OCR_Result_${System.currentTimeMillis()}.txt")
                            withContext(Dispatchers.IO) {
                                FileOutputStream(file).use {
                                    it.write(recognizedText.toByteArray())
                                }
                            }
                            repository.addRecentFile(Uri.fromFile(file).toString())
                            if (pdfPath != null) {
                                repository.addRecentFile(Uri.fromFile(File(pdfPath)).toString())
                            }
                            Toast.makeText(context, "Saved as ${file.name}", Toast.LENGTH_SHORT).show()
                            onClose()
                        } catch (_: Exception) {
                            Toast.makeText(context, "Error saving file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("Save .txt")
                }

                Button(onClick = {
                    coroutineScope.launch {
                        try {
                            val file = File(context.filesDir, "OCR_Result_${System.currentTimeMillis()}.md")
                            withContext(Dispatchers.IO) {
                                FileOutputStream(file).use {
                                    it.write(recognizedText.toByteArray())
                                }
                            }
                            repository.addRecentFile(Uri.fromFile(file).toString())
                            if (pdfPath != null) {
                                repository.addRecentFile(Uri.fromFile(File(pdfPath)).toString())
                            }
                            Toast.makeText(context, "Saved as ${file.name}", Toast.LENGTH_SHORT).show()
                            onClose()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error saving file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("Save .md")
                }
            }
        }
    }
}
