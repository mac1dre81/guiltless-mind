package com.document.editor
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class DocumentEditorFragmentSnackbarTest {
    @Test
    fun deletingBox_showsUndoSnackbar_andRestoresBoxOnUndo() {
        val testBox = PdfBoxAnnotation(
            leftRatio = 0.2f,
            topRatio = 0.2f,
            widthRatio = 0.2f,
            heightRatio = 0.16f,
            content = "Delete me",
            color = 0,
            createdAt = 88L
        )
        ActivityScenario.launch(TestDocumentEditorFragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.fragment.preparePdfUiForTesting(
                    annotations = listOf(testBox),
                    selectedBoxId = testBox.createdAt
                )
            }
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(isDisplayed()))
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withText(R.string.pdf_box_long_press_hint)))
            scenario.onActivity { activity ->
                activity.fragment.deletePdfBoxForTesting(testBox.createdAt)
            }
            waitForUi()
            onView(withText(R.string.pdf_box_deleted)).check(matches(isDisplayed()))
            onView(withText(R.string.pdf_box_delete_undo_action)).check(matches(isDisplayed()))
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withText(R.string.pdf_box_delete_undo_action)).perform(click())
            waitForUi()
            scenario.onActivity { activity ->
                assertEquals(1, activity.fragment.currentPdfAnnotationCountForTesting())
                assertTrue(activity.fragment.isPdfSelectedBoxHintVisibleForTesting())
            }
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(isDisplayed()))
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withText(R.string.pdf_box_long_press_hint)))
        }
    }

    @Test
    fun selectedBoxChip_togglesVisibilityAcrossToolSwitches() {
        val testBox = PdfBoxAnnotation(
            leftRatio = 0.25f,
            topRatio = 0.25f,
            widthRatio = 0.22f,
            heightRatio = 0.18f,
            content = "Tool switch",
            color = 0,
            createdAt = 99L
        )

        ActivityScenario.launch(TestDocumentEditorFragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.fragment.preparePdfUiForTesting(
                    annotations = listOf(testBox),
                    selectedBoxId = testBox.createdAt
                )
            }
            waitForUi()

            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(isDisplayed()))
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withText(R.string.pdf_box_long_press_hint)))

            onView(withId(R.id.btnPdfPen)).perform(click())
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withId(R.id.btnPdfHighlight)).perform(click())
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withId(R.id.btnPdfSignature)).perform(click())
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withId(R.id.btnPdfEraser)).perform(click())
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withId(R.id.btnPdfHand)).perform(click())
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(isDisplayed()))
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withText(R.string.pdf_box_long_press_hint)))

            onView(withId(R.id.btnPdfBox)).perform(click())
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(isDisplayed()))
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withText(R.string.pdf_box_long_press_hint)))
        }
    }

    @Test
    fun selectedBoxChip_hidesAfterDelete_reappearsOnlyWhenUndoRestoresInBoxMode() {
        val testBox = PdfBoxAnnotation(
            leftRatio = 0.22f,
            topRatio = 0.22f,
            widthRatio = 0.2f,
            heightRatio = 0.16f,
            content = "Undo chip",
            color = 0,
            createdAt = 123L
        )

        ActivityScenario.launch(TestDocumentEditorFragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.fragment.preparePdfUiForTesting(
                    annotations = listOf(testBox),
                    selectedBoxId = testBox.createdAt
                )
            }
            waitForUi()

            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                activity.fragment.deletePdfBoxForTesting(testBox.createdAt)
            }
            waitForUi()

            onView(withText(R.string.pdf_box_deleted)).check(matches(isDisplayed()))
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            scenario.onActivity { activity ->
                assertEquals(0, activity.fragment.currentPdfAnnotationCountForTesting())
            }

            onView(withText(R.string.pdf_box_delete_undo_action)).perform(click())
            waitForUi()

            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                assertEquals(1, activity.fragment.currentPdfAnnotationCountForTesting())
                assertTrue(activity.fragment.isPdfSelectedBoxHintVisibleForTesting())
            }

            scenario.onActivity { activity ->
                activity.fragment.deletePdfBoxForTesting(testBox.createdAt)
            }
            waitForUi()

            onView(withId(R.id.btnPdfPen)).perform(click())
            waitForUi()
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withText(R.string.pdf_box_delete_undo_action)).perform(click())
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(1, activity.fragment.currentPdfAnnotationCountForTesting())
            }
            onView(withId(R.id.pdfSelectedBoxHintChip)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    private fun waitForUi() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
