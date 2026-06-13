package com.document.editor

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TestDocumentListFragmentHostActivity : AppCompatActivity() {
    lateinit var fragment: DocumentListFragment
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply {
            id = android.R.id.content
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(container)
        fragment = if (savedInstanceState == null) {
            DocumentListFragment().also { createdFragment ->
                supportFragmentManager.commitNow {
                    replace(android.R.id.content, createdFragment, FRAGMENT_TAG)
                }
            }
        } else {
            supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as DocumentListFragment
        }
    }

    private companion object {
        const val FRAGMENT_TAG = "document_list_fragment_under_test"
    }
}

