package com.document.editor

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DocumentEditorFragmentEditorSettingsTest {
    @Test
    fun editorFontSizePreference_appliesToEditorViews() {
        ActivityScenario.launch(TestDocumentEditorFragmentHostActivity::class.java).use { scenario ->
            val sizes = AtomicReference(Triple(0f, 0f, 0f))

            scenario.onActivity { activity ->
                activity.fragment.prepareTextUiForTesting(
                    type = DocumentViewModel.DocType.TEXT,
                    content = "Readable text"
                )
                activity.fragment.applyEditorPreferencesForTesting(fontSizeSp = 24, autoSaveEnabled = false)
                sizes.set(activity.fragment.currentEditorTextSizesSpForTesting())
            }
            waitForUi()

            val (editSize, markdownSize, searchSize) = sizes.get()
            assertEquals(24f, editSize, 0.6f)
            assertEquals(24f, markdownSize, 0.6f)
            assertEquals(24f, searchSize, 0.6f)
        }
    }

    @Test
    fun textEditorAutoSave_debouncesToLatestValueOnly() {
        ActivityScenario.launch(TestDocumentEditorFragmentHostActivity::class.java).use { scenario ->
            val saveCount = AtomicInteger(0)
            val savedUri = AtomicReference<String?>(null)
            val savedText = AtomicReference<String?>(null)
            val saveLatch = CountDownLatch(1)

            scenario.onActivity { activity ->
                activity.fragment.prepareTextUiForTesting(
                    type = DocumentViewModel.DocType.TEXT,
                    content = "Initial"
                )
                activity.fragment.applyEditorPreferencesForTesting(fontSizeSp = 16, autoSaveEnabled = true)
                activity.fragment.configureAutoSaveForTesting(debounceMs = 25L) { uri, text ->
                    saveCount.incrementAndGet()
                    savedUri.set(uri)
                    savedText.set(text)
                    saveLatch.countDown()
                }
                activity.fragment.updateEditorTextForTesting("Draft 1")
                activity.fragment.updateEditorTextForTesting("Draft 2")
            }

            assertTrue(saveLatch.await(2, TimeUnit.SECONDS))
            waitForUi()
            assertEquals(1, saveCount.get())
            assertEquals("content://com.document.editor.testing/document.txt", savedUri.get())
            assertEquals("Draft 2", savedText.get())
        }
    }

    @Test
    fun textEditorAutoSave_doesNotRunWhenDisabled() {
        ActivityScenario.launch(TestDocumentEditorFragmentHostActivity::class.java).use { scenario ->
            val saveCount = AtomicInteger(0)
            val saveLatch = CountDownLatch(1)

            scenario.onActivity { activity ->
                activity.fragment.prepareTextUiForTesting(
                    type = DocumentViewModel.DocType.TEXT,
                    content = "Initial"
                )
                activity.fragment.applyEditorPreferencesForTesting(fontSizeSp = 16, autoSaveEnabled = false)
                activity.fragment.configureAutoSaveForTesting(debounceMs = 25L) { _, _ ->
                    saveCount.incrementAndGet()
                    saveLatch.countDown()
                }
                activity.fragment.updateEditorTextForTesting("Changed but should not persist")
            }

            assertFalse(saveLatch.await(300, TimeUnit.MILLISECONDS))
            waitForUi()
            assertEquals(0, saveCount.get())
        }
    }

    private fun waitForUi() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

