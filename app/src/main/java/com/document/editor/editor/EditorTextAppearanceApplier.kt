package com.document.editor.editor

import android.util.TypedValue
import android.widget.TextView
import javax.inject.Inject

/**
 * Applies SP-based editor typography consistently across all text surfaces.
 */
class EditorTextAppearanceApplier @Inject constructor() {
    fun applyFontSizeSp(fontSizeSp: Float, vararg textViews: TextView?) {
        textViews.forEach { textView ->
            textView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        }
    }
}

