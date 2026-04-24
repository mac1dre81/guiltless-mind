package com.document.editor

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBoxToolFlowTest {
    @Test
    fun tappingEmptySpace_requestsNewBox() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(clickAt(0.2f, 0.2f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(1, activity.boxRequestCount)
                assertEquals(0, activity.boxEditCount)
                assertEquals(0, activity.boxDeleteCount)
            }
        }
    }

    @Test
    fun draggingEmptySpace_doesNotRequestNewBox() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(dragFromTo(0.2f, 0.2f, 0.6f, 0.6f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(0, activity.boxRequestCount)
            }
        }
    }

    @Test
    fun draggingEmptySpaceInHandMode_requestsPanInsteadOfNewBox() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
                activity.overlay.editTool = PdfEditTool.HAND
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(dragFromTo(0.2f, 0.2f, 0.6f, 0.6f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(0, activity.boxRequestCount)
                assertTrue(activity.panRequestCount > 0)
            }
        }
    }

    @Test
    fun tappingSelectedBoxAgain_requestsEdit() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
                activity.setBoxes(sampleBox)
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(clickAt(0.4f, 0.4f))
            waitForUi()
            onView(withId(R.id.pdfAnnotationOverlay)).perform(clickAt(0.4f, 0.4f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(1, activity.boxEditCount)
                assertEquals(1, activity.currentBoxCount())
            }
        }
    }

    @Test
    fun longPressingSelectedBox_deletesIt() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
                activity.setBoxes(sampleBox)
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(clickAt(0.4f, 0.4f))
            waitForUi()
            onView(withId(R.id.pdfAnnotationOverlay)).perform(longClickAt(0.4f, 0.4f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(1, activity.boxDeleteCount)
                assertEquals(0, activity.currentBoxCount())
            }
        }
    }

    @Test
    fun draggingCornerHandle_resizesSelectedBox() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
                activity.overlay.editTool = PdfEditTool.HAND
                activity.setBoxes(sampleBox)
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(clickAt(0.4f, 0.4f))
            waitForUi()
            onView(withId(R.id.pdfAnnotationOverlay)).perform(dragFromTo(0.3f, 0.3f, 0.24f, 0.24f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(1, activity.boxTransformCount)
                val resizedBox = activity.lastTransformedBox
                assertNotNull(resizedBox)
                assertTrue((resizedBox?.leftRatio ?: 1f) < sampleBox.leftRatio)
                assertTrue((resizedBox?.topRatio ?: 1f) < sampleBox.topRatio)
                assertTrue((resizedBox?.widthRatio ?: 0f) > sampleBox.widthRatio)
                assertTrue((resizedBox?.heightRatio ?: 0f) > sampleBox.heightRatio)
            }
        }
    }

    @Test
    fun draggingSideHandle_resizesSelectedBoxWithoutTriggeringPan() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
                activity.overlay.editTool = PdfEditTool.HAND
                activity.setBoxes(sampleBox)
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(clickAt(0.4f, 0.4f))
            waitForUi()
            onView(withId(R.id.pdfAnnotationOverlay)).perform(dragFromTo(0.5f, 0.4f, 0.58f, 0.4f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(1, activity.boxTransformCount)
                assertEquals(0, activity.panRequestCount)
                val resizedBox = activity.lastTransformedBox
                assertNotNull(resizedBox)
                assertEquals(sampleBox.leftRatio, resizedBox?.leftRatio ?: -1f, 0.0001f)
                assertEquals(sampleBox.topRatio, resizedBox?.topRatio ?: -1f, 0.0001f)
                assertTrue((resizedBox?.widthRatio ?: 0f) > sampleBox.widthRatio)
                assertEquals(sampleBox.heightRatio, resizedBox?.heightRatio ?: -1f, 0.0001f)
            }
        }
    }

    @Test
    fun draggingInsideSelectedBoxInHandMode_movesBoxInsteadOfPanning() {
        ActivityScenario.launch(TestPdfBoxOverlayActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetState()
                activity.overlay.editTool = PdfEditTool.HAND
                activity.setBoxes(sampleBox)
            }

            onView(withId(R.id.pdfAnnotationOverlay)).perform(clickAt(0.4f, 0.4f))
            waitForUi()
            onView(withId(R.id.pdfAnnotationOverlay)).perform(dragFromTo(0.4f, 0.4f, 0.48f, 0.46f))
            waitForUi()

            scenario.onActivity { activity ->
                assertEquals(1, activity.boxTransformCount)
                assertEquals(0, activity.panRequestCount)
                val movedBox = activity.lastTransformedBox
                assertNotNull(movedBox)
                assertTrue((movedBox?.leftRatio ?: 0f) > sampleBox.leftRatio)
                assertTrue((movedBox?.topRatio ?: 0f) > sampleBox.topRatio)
                assertEquals(sampleBox.widthRatio, movedBox?.widthRatio ?: -1f, 0.0001f)
                assertEquals(sampleBox.heightRatio, movedBox?.heightRatio ?: -1f, 0.0001f)
            }
        }
    }

    private fun clickAt(xRatio: Float, yRatio: Float): ViewAction {
        return GeneralClickAction(
            Tap.SINGLE,
            ratioCoordinates(xRatio, yRatio),
            Press.FINGER,
            InputDevice.SOURCE_TOUCHSCREEN,
            MotionEvent.BUTTON_PRIMARY
        )
    }

    @Suppress("SameParameterValue")
    private fun longClickAt(xRatio: Float, yRatio: Float): ViewAction {
        return GeneralClickAction(
            Tap.LONG,
            ratioCoordinates(xRatio, yRatio),
            Press.FINGER,
            InputDevice.SOURCE_TOUCHSCREEN,
            MotionEvent.BUTTON_PRIMARY
        )
    }

    @Suppress("SameParameterValue")
    private fun dragFromTo(startXRatio: Float, startYRatio: Float, endXRatio: Float, endYRatio: Float): ViewAction {
        return GeneralSwipeAction(
            Swipe.SLOW,
            ratioCoordinates(startXRatio, startYRatio),
            ratioCoordinates(endXRatio, endYRatio),
            Press.FINGER
        )
    }

    private fun ratioCoordinates(xRatio: Float, yRatio: Float): CoordinatesProvider {
        return CoordinatesProvider { view ->
            floatArrayOf(view.width * xRatio, view.height * yRatio)
        }
    }

    private fun waitForUi() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private companion object {
        val sampleBox = PdfBoxAnnotation(
            leftRatio = 0.3f,
            topRatio = 0.3f,
            widthRatio = 0.2f,
            heightRatio = 0.2f,
            content = "Sample",
            color = 0,
            createdAt = 42L
        )
    }
}

