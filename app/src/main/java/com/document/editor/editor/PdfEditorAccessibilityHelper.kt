package com.document.editor.editor

import android.content.Context
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.IdRes
import com.document.editor.R
import javax.inject.Inject

/**
 * Centralizes PDF editor accessibility strings and announcement events so the fragment can keep a
 * smaller UI-focused surface.
 */
class PdfEditorAccessibilityHelper @Inject constructor() {
    fun getToolButtonContentDescription(
        context: Context,
        @IdRes buttonId: Int,
        selected: Boolean
    ): String {
        val labelRes = when (buttonId) {
            R.id.btnPdfHand -> R.string.pdf_tool_hand
            R.id.btnPdfPen -> R.string.pdf_tool_pen
            R.id.btnPdfHighlight -> R.string.pdf_tool_highlight
            R.id.btnPdfSignature -> R.string.pdf_tool_signature
            R.id.btnPdfBox -> R.string.pdf_tool_box
            R.id.btnPdfEraser -> R.string.pdf_tool_eraser
            R.id.btnPdfUndo -> R.string.pdf_tool_undo
            else -> null
        }
        val label = labelRes?.let(context::getString).orEmpty()
        return if (selected) {
            context.getString(R.string.pdf_tool_button_selected_content_description, label)
        } else {
            context.getString(R.string.pdf_tool_button_content_description, label)
        }
    }

    fun getZoomContentDescription(context: Context, zoomPercent: Int): String {
        return context.resources.getQuantityString(
            R.plurals.pdf_zoom_slider_content_description,
            zoomPercent,
            zoomPercent
        )
    }

    fun getPageContentDescription(context: Context, currentPage: Int, pageCount: Int): String {
        return context.getString(R.string.pdf_page_indicator_content_description, currentPage, pageCount)
    }

    fun announce(context: Context, rootView: View, message: String) {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager?.isEnabled != true) {
            return
        }

        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityEvent()
        } else {
            @Suppress("DEPRECATION")
            AccessibilityEvent.obtain()
        }.apply {
            @Suppress("DEPRECATION")
            eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
            className = rootView.javaClass.name
            packageName = context.packageName
            text.add(message)
        }
        accessibilityManager.sendAccessibilityEvent(event)
    }
}
