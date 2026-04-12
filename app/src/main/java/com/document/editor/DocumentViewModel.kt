package com.document.editor
import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.net.toUri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
class DocumentViewModel(application: Application) : AndroidViewModel(application) {
    private val _content = MutableLiveData<String>()
    val content: LiveData<String> = _content
    private val _pdfPage = MutableLiveData<Bitmap?>()
    val pdfPage: LiveData<Bitmap?> = _pdfPage
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error
    val documentType = MutableLiveData<DocType>()
    enum class DocType {
        TEXT, MARKDOWN, PDF, UNKNOWN
    }
    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPage: PdfRenderer.Page? = null
    fun loadDocument(uriString: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val uri = uriString.toUri()
            try {
                val type = determineDocType(uri)
                documentType.value = type
                withContext(Dispatchers.IO) {
                    when (type) {
                        DocType.TEXT, DocType.MARKDOWN -> {
                            val text = loadText(uri)
                            withContext(Dispatchers.Main) { _content.value = text }
                        }
                        DocType.PDF -> {
                            loadPdf(uri)
                        }
                        else -> {
                            withContext(Dispatchers.Main) { _error.value = "Unsupported format" }
                        }
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
    fun saveDocument(uriString: String, text: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val uri = uriString.toUri()
            try {
                withContext(Dispatchers.IO) {
                    val cr = getApplication<Application>().contentResolver
                    cr.openOutputStream(uri, "wt")?.use { os ->
                        os.write(text.toByteArray())
                    }
                }
                _error.value = "Saved successfully"
            } catch (e: Exception) {
                _error.value = "Save failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    private fun determineDocType(uri: Uri): DocType {
        val cr = getApplication<Application>().contentResolver
        val mimeType = cr.getType(uri) ?: ""
        val path = uri.path ?: ""
        return if (mimeType.contains("pdf", ignoreCase = true) || path.endsWith(".pdf", true)) {
            DocType.PDF
        } else if (path.endsWith(".md", true) || mimeType.contains("markdown", ignoreCase = true)) {
            DocType.MARKDOWN
        } else {
            // Default to text if we can open it safely
            DocType.TEXT
        }
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
                val cr = getApplication<Application>().contentResolver
                val fd = cr.openFileDescriptor(uri, "r") ?: return@withContext
                fileDescriptor = fd
                pdfRenderer = PdfRenderer(fd)
                if ((pdfRenderer?.pageCount ?: 0) > 0) {
                    showPdfPage(0)
                }
            } catch (e: Exception) {
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
                // create a bitmap
                // for quality, usually multiply the page dimensions by 2
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = androidx.core.graphics.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Fill white background
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                withContext(Dispatchers.Main) {
                    _pdfPage.value = bitmap
                }
            }
        }
    }
    override fun onCleared() {
        super.onCleared()
        currentPage?.close()
        pdfRenderer?.close()
        fileDescriptor?.close()
    }
}
