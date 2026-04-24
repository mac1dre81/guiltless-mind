package com.document.editor
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class DocumentViewModelPdfUndoInstrumentedTest {
    @Test
    fun undoRestoresDeletedBox() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DocumentViewModel(application)
        val box = PdfBoxAnnotation(
            leftRatio = 0.1f,
            topRatio = 0.1f,
            widthRatio = 0.2f,
            heightRatio = 0.2f,
            content = "Undo",
            color = 0,
            createdAt = 7L
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.addPdfBoxAnnotation(box)
            viewModel.removePdfBoxAnnotation(box.createdAt)
            assertTrue(viewModel.currentPdfAnnotations.value.orEmpty().isEmpty())
            viewModel.undoLastPdfAnnotation()
        }
        assertEquals(listOf(box), viewModel.currentPdfAnnotations.value.orEmpty())
    }

    @Test
    fun undoRestoresErasedStroke() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DocumentViewModel(application)
        val stroke = PdfInkStroke(
            points = listOf(
                PdfPoint(0.1f, 0.1f),
                PdfPoint(0.2f, 0.2f)
            ),
            createdAt = 11L
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.addPdfStroke(stroke)
            viewModel.erasePdfAnnotationAt(0.15f, 0.15f)
            assertTrue(viewModel.currentPdfAnnotations.value.orEmpty().isEmpty())
            viewModel.undoLastPdfAnnotation()
        }

        assertEquals(listOf(stroke), viewModel.currentPdfAnnotations.value.orEmpty())
    }

    @Test
    fun undoRestoresMovedBoxGeometry() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DocumentViewModel(application)
        val originalBox = PdfBoxAnnotation(
            leftRatio = 0.1f,
            topRatio = 0.1f,
            widthRatio = 0.2f,
            heightRatio = 0.2f,
            content = "Move undo",
            color = 0,
            createdAt = 21L
        )
        val movedBox = originalBox.copy(
            leftRatio = 0.45f,
            topRatio = 0.35f
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.addPdfBoxAnnotation(originalBox)
            viewModel.commitPdfBoxTransform(originalBox, movedBox)
            assertEquals(listOf(movedBox.normalized()), viewModel.currentPdfAnnotations.value.orEmpty())
            viewModel.undoLastPdfAnnotation()
        }

        assertEquals(listOf(originalBox.normalized()), viewModel.currentPdfAnnotations.value.orEmpty())
    }

    @Test
    fun undoRestoresEditedBoxContentAndType() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DocumentViewModel(application)
        val originalBox = PdfBoxAnnotation(
            leftRatio = 0.15f,
            topRatio = 0.15f,
            widthRatio = 0.22f,
            heightRatio = 0.18f,
            content = "Original",
            contentType = PdfBoxContentType.TEXT,
            color = 0,
            createdAt = 31L
        )
        val editedBox = originalBox.copy(
            content = "Edited",
            contentType = PdfBoxContentType.STICKER,
            color = 0
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.addPdfBoxAnnotation(originalBox)
            viewModel.updatePdfBoxAnnotation(editedBox)
            assertEquals(listOf(editedBox.normalized()), viewModel.currentPdfAnnotations.value.orEmpty())
            viewModel.undoLastPdfAnnotation()
        }

        assertEquals(listOf(originalBox.normalized()), viewModel.currentPdfAnnotations.value.orEmpty())
    }

    @Test
    fun unchangedBoxEdit_doesNotModifyStoredAnnotation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = DocumentViewModel(application)
        val originalBox = PdfBoxAnnotation(
            leftRatio = 0.18f,
            topRatio = 0.18f,
            widthRatio = 0.2f,
            heightRatio = 0.16f,
            content = "Same",
            color = 0,
            createdAt = 41L
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.addPdfBoxAnnotation(originalBox)
            viewModel.updatePdfBoxAnnotation(originalBox.copy(color = 0))
            assertEquals(listOf(originalBox.normalized()), viewModel.currentPdfAnnotations.value.orEmpty())
        }

        assertEquals(listOf(originalBox.normalized()), viewModel.currentPdfAnnotations.value.orEmpty())
    }
}
