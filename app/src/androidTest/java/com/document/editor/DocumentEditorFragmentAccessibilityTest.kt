package com.document.editor

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentEditorFragmentAccessibilityTest {
    @Test
    fun pdfControls_exposeAccessibleDescriptionsForSelectedToolZoomAndPage() {
        ActivityScenario.launch(TestDocumentEditorFragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.fragment.preparePdfUiForTesting(annotations = emptyList())
            }
            waitForUi()

            onView(withId(R.id.btnPdfHand)).check(
                matches(withContentDescription(R.string.pdf_tool_button_selected_content_description.formatWithRes(R.string.pdf_tool_hand)))
            )
            onView(withId(R.id.btnPdfPen)).check(
                matches(withContentDescription(R.string.pdf_tool_button_content_description.formatWithRes(R.string.pdf_tool_pen)))
            )
            onView(withId(R.id.pdfZoomSlider)).check(
                matches(withContentDescription(R.string.pdf_zoom_slider_content_description.formatWithValue(100)))
            )
            onView(withId(R.id.pdfPageIndicator)).check(
                matches(withContentDescription(R.string.pdf_page_indicator_content_description.formatWithValues(1, 1)))
            )

            onView(withId(R.id.btnPdfPen)).perform(click())
            waitForUi()

            onView(withId(R.id.btnPdfHand)).check(
                matches(withContentDescription(R.string.pdf_tool_button_content_description.formatWithRes(R.string.pdf_tool_hand)))
            )
            onView(withId(R.id.btnPdfPen)).check(
                matches(withContentDescription(R.string.pdf_tool_button_selected_content_description.formatWithRes(R.string.pdf_tool_pen)))
            )
        }
    }

    private fun Int.formatWithRes(argumentResId: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(this, context.getString(argumentResId))
    }

    private fun Int.formatWithValue(value: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(this, value)
    }

    private fun Int.formatWithValues(first: Int, second: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(this, first, second)
    }

    private fun waitForUi() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

