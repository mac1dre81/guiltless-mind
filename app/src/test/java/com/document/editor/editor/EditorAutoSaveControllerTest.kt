package com.document.editor.editor

import com.document.editor.DocumentViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class EditorAutoSaveControllerTest {
    @Test
    fun debounce_savesOnlyLatestText() {
        runTest {
            val controller = EditorAutoSaveController().apply {
                debounceMs = 250L
                seedLastPersistedText("Initial")
            }
            val scope = TestScope(testScheduler)

            var latestEditorText = "Draft 1"
            val saveCount = AtomicInteger(0)
            var savedUri = ""
            var savedText = ""

            controller.scheduleIfNeeded(
                scope = scope,
                autoSaveEnabled = true,
                uriString = "content://com.document.editor.testing/document.txt",
                documentType = DocumentViewModel.DocType.TEXT,
                latestDocumentTypeSnapshot = { DocumentViewModel.DocType.TEXT },
                latestTextSnapshot = { latestEditorText }
            ) { uri, text ->
                saveCount.incrementAndGet()
                savedUri = uri
                savedText = text
            }

            latestEditorText = "Draft 2"

            advanceTimeBy(249L)
            runCurrent()
            assertEquals(0, saveCount.get())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, saveCount.get())
            assertEquals("content://com.document.editor.testing/document.txt", savedUri)
            assertEquals("Draft 2", savedText)
        }
    }

    @Test
    fun disabledAutoSave_doesNotSave() {
        runTest {
            val controller = EditorAutoSaveController().apply {
                debounceMs = 250L
                seedLastPersistedText("Initial")
            }
            val scope = TestScope(testScheduler)
            val saveCount = AtomicInteger(0)

            controller.scheduleIfNeeded(
                scope = scope,
                autoSaveEnabled = false,
                uriString = "content://com.document.editor.testing/document.txt",
                documentType = DocumentViewModel.DocType.TEXT,
                latestDocumentTypeSnapshot = { DocumentViewModel.DocType.TEXT },
                latestTextSnapshot = { "Changed" }
            ) { _, _ ->
                saveCount.incrementAndGet()
            }

            advanceTimeBy(300L)
            runCurrent()
            assertEquals(0, saveCount.get())
        }
    }

    @Test
    fun unchangedText_doesNotSave() {
        runTest {
            val controller = EditorAutoSaveController().apply {
                debounceMs = 250L
                seedLastPersistedText("Same")
            }
            val scope = TestScope(testScheduler)
            val saveCount = AtomicInteger(0)

            controller.scheduleIfNeeded(
                scope = scope,
                autoSaveEnabled = true,
                uriString = "content://com.document.editor.testing/document.txt",
                documentType = DocumentViewModel.DocType.TEXT,
                latestDocumentTypeSnapshot = { DocumentViewModel.DocType.TEXT },
                latestTextSnapshot = { "Same" }
            ) { _, _ ->
                saveCount.incrementAndGet()
            }

            advanceTimeBy(300L)
            runCurrent()
            assertEquals(0, saveCount.get())
        }
    }
}

