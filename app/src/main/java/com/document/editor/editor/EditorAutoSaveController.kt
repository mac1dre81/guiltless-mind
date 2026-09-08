package com.document.editor.editor

import com.document.editor.DocumentViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Encapsulates editor auto-save debounce and save-eligibility decisions so the fragment can stay
 * focused on view coordination.
 */
class EditorAutoSaveController @Inject constructor() {
    private var pendingJob: Job? = null
    private var lastPersistedText: String? = null

    var debounceMs: Long = DEFAULT_DEBOUNCE_MS

    fun seedLastPersistedText(text: String?) {
        lastPersistedText = text
    }

    fun cancelPendingSave() {
        pendingJob?.cancel()
        pendingJob = null
    }

    fun scheduleIfNeeded(
        scope: CoroutineScope,
        autoSaveEnabled: Boolean,
        uriString: String?,
        documentType: DocumentViewModel.DocType?,
        latestDocumentTypeSnapshot: () -> DocumentViewModel.DocType?,
        latestTextSnapshot: () -> String,
        onSave: suspend (uri: String, text: String) -> Unit
    ) {
        val supportsAutoSave = documentType == DocumentViewModel.DocType.TEXT ||
            documentType == DocumentViewModel.DocType.MARKDOWN
        val currentText = latestTextSnapshot()
        if (!autoSaveEnabled || uriString.isNullOrBlank() || !supportsAutoSave || currentText == lastPersistedText) {
            cancelPendingSave()
            return
        }

        cancelPendingSave()
        pendingJob = scope.launch {
            delay(debounceMs.coerceAtLeast(0L))
            val latestUri = uriString
            val latestText = latestTextSnapshot()
            val latestType = latestDocumentTypeSnapshot()
            val stillSupportsAutoSave = latestType == DocumentViewModel.DocType.TEXT ||
                latestType == DocumentViewModel.DocType.MARKDOWN
            if (!autoSaveEnabled || latestUri.isNullOrBlank() || !stillSupportsAutoSave || latestText == lastPersistedText) {
                return@launch
            }

            onSave(latestUri, latestText)
            lastPersistedText = latestText
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 800L
    }
}

