package com.document.editor

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.document.editor.fakes.FakePremiumStatusProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DocumentListFragmentPremiumTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var fakePremiumStatusProvider: FakePremiumStatusProvider

    @Before
    fun setUp() {
        hiltRule.inject()
        fakePremiumStatusProvider.setEntitlements(isPremiumUser = false, isProUser = false)
    }

    @Test
    fun scanButton_exposesLockedAccessibilityDescription_forFreeUsers() {
        ActivityScenario.launch(TestDocumentListFragmentHostActivity::class.java).use { scenario ->
            waitForUi()
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.scan_document_locked_content_description),
                    activity.findViewById<android.view.View>(R.id.fab_scan).contentDescription
                )
            }
        }
    }

    @Test
    fun scanButton_exposesReadyAccessibilityDescription_forProUsers() {
        fakePremiumStatusProvider.setEntitlements(isPremiumUser = true, isProUser = true)

        ActivityScenario.launch(TestDocumentListFragmentHostActivity::class.java).use { scenario ->
            waitForUi()
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.scan_document_ready_content_description),
                    activity.findViewById<android.view.View>(R.id.fab_scan).contentDescription
                )
            }
        }
    }

    private fun waitForUi() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

