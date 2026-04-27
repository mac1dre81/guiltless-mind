package com.document.editor
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TestDocumentEditorFragmentHostActivity : AppCompatActivity() {
    lateinit var fragment: DocumentEditorFragment
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
            DocumentEditorFragment().also { createdFragment ->
                supportFragmentManager.commitNow {
                    replace(android.R.id.content, createdFragment, FRAGMENT_TAG)
                }
            }
        } else {
            supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as DocumentEditorFragment
        }
    }
    private companion object {
        const val FRAGMENT_TAG = "document_editor_fragment_under_test"
    }
}
