package com.document.editor

import android.content.ContentResolver
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.document.editor.editor.EditorAutoSaveController
import com.document.editor.editor.EditorTextAppearanceApplier
import com.document.editor.editor.PdfEditorAccessibilityHelper
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class DocumentEditorFragment : Fragment() {
    private val viewModel: DocumentViewModel by viewModels()

    @Inject
    lateinit var recentFilesRepository: RecentFilesRepository

    @Inject
    lateinit var editorAutoSaveController: EditorAutoSaveController

    @Inject
    lateinit var editorTextAppearanceApplier: EditorTextAppearanceApplier

    @Inject
    lateinit var pdfEditorAccessibilityHelper: PdfEditorAccessibilityHelper

    private lateinit var progressBar: ProgressBar
    private lateinit var editText: EditText
    private lateinit var searchEditText: EditText
    private lateinit var markdownScrollView: ScrollView
    private lateinit var markdownView: TextView
    private lateinit var pdfPageStage: View
    private lateinit var pdfImageView: ImageView
    private lateinit var pdfAnnotationOverlay: PdfAnnotationOverlayView
    private lateinit var pdfSelectedBoxHintChip: Chip
    private lateinit var pdfToolbar: View
    private lateinit var pdfPageIndicator: TextView
    private lateinit var pdfZoomSlider: SeekBar
    private lateinit var pdfZoomValue: TextView
    private lateinit var emptyStateText: TextView

    private var searchToolbar: View? = null
    private var formatToolbar: View? = null
    private var markwon: Markwon? = null
    private var isEditMode = false
    private var uriString: String? = null
    private var lastSearchIndex = -1
    private var searchDebounceJob: Job? = null
    private var activePdfTool = PdfEditTool.HAND
    private var activePdfZoom = MIN_PDF_ZOOM
    private var activePdfTranslation = PdfStageTranslation()
    private var isUpdatingZoomSlider = false
    private var pendingPdfDeleteUndoSnackbar: Snackbar? = null
    private var selectedPdfBoxId: Long? = null
    private var isAutoSaveEnabled = true
    private var editorFontSizeSp = DEFAULT_EDITOR_FONT_SIZE_SP
    private var suppressEditorTextWatcher = false
    private var onAutoSavePerformedForTesting: ((String, String) -> Unit)? = null
    private val editorTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (suppressEditorTextWatcher) {
                return
            }
            scheduleEditorAutoSaveIfNeeded(s?.toString().orEmpty())
        }
    }

    private val createPdfCopyLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                Toast.makeText(requireContext(), getString(R.string.pdf_save_cancelled), Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            persistUriPermission(uri)
            performPdfSave(uri.toString())
        }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        progressBar = view.findViewById(R.id.progressBar)
        editText = view.findViewById(R.id.editText)
        searchEditText = view.findViewById(R.id.searchEditText)
        markdownScrollView = view.findViewById(R.id.markdownScrollView)
        markdownView = view.findViewById(R.id.markdownView)
        pdfPageStage = view.findViewById(R.id.pdfPageStage)
        pdfImageView = view.findViewById(R.id.pdfImageView)
        pdfAnnotationOverlay = view.findViewById(R.id.pdfAnnotationOverlay)
        pdfSelectedBoxHintChip = view.findViewById(R.id.pdfSelectedBoxHintChip)
        pdfToolbar = view.findViewById(R.id.pdfToolbar)
        pdfPageIndicator = view.findViewById(R.id.pdfPageIndicator)
        pdfZoomSlider = view.findViewById(R.id.pdfZoomSlider)
        pdfZoomValue = view.findViewById(R.id.pdfZoomValue)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        searchToolbar = view.findViewById(R.id.searchToolbar)
        formatToolbar = view.findViewById(R.id.formatToolbar)

        view.findViewById<View>(R.id.btnBold).setOnClickListener { insertMarkdown("**", "**") }
        view.findViewById<View>(R.id.btnItalic).setOnClickListener { insertMarkdown("*", "*") }
        view.findViewById<View>(R.id.btnList).setOnClickListener { insertMarkdown("- ", "") }
        view.findViewById<View>(R.id.btnSearchNext).setOnClickListener { findNextMatch() }
        view.findViewById<View>(R.id.btnSearchClose).setOnClickListener { closeSearch() }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                debounceSearchPreview()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        setupPdfControls(view)
        setupBackNavigationBehavior()
        markwon = Markwon.create(requireContext())
        editText.addTextChangedListener(editorTextWatcher)

        setupObservers()
        observeEditorPreferences()
        setupMenu()

        uriString = arguments?.getString("document_uri")
        if (uriString.isNullOrBlank()) {
            showEmptyState()
            Toast.makeText(requireContext(), getString(R.string.no_document_found), Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.loadDocument(requireNotNull(uriString))
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.editor_menu, menu)

                val type = viewModel.documentType.value
                val isText = type == DocumentViewModel.DocType.TEXT
                val isMarkdown = type == DocumentViewModel.DocType.MARKDOWN
                val isPdf = type == DocumentViewModel.DocType.PDF
                val isSearchable = isText || isMarkdown

                menu.findItem(R.id.action_save)?.isVisible = isSearchable || isPdf
                menu.findItem(R.id.action_search)?.isVisible = isSearchable

                if (isMarkdown) {
                    val toggleTitle = if (isEditMode) {
                        getString(R.string.markdown_mode_preview)
                    } else {
                        getString(R.string.markdown_mode_edit)
                    }
                    val toggleItem = menu.add(Menu.NONE, MENU_TOGGLE_MARKDOWN_MODE, Menu.NONE, toggleTitle)
                    toggleItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                when (menuItem.itemId) {
                    R.id.action_save -> {
                        val currentUri = uriString
                        if (currentUri != null) {
                            if (viewModel.documentType.value == DocumentViewModel.DocType.PDF) {
                                handlePdfSaveRequested(currentUri)
                            } else {
                                val currentText = editText.text.toString()
                                viewModel.saveDocument(currentUri, currentText)
                                editorAutoSaveController.seedLastPersistedText(currentText)
                                if (viewModel.documentType.value == DocumentViewModel.DocType.MARKDOWN) {
                                    markwon?.setMarkdown(markdownView, currentText)
                                }
                            }
                        }
                        return true
                    }

                    R.id.action_search -> {
                        openSearch()
                        return true
                    }

                    MENU_TOGGLE_MARKDOWN_MODE -> {
                        isEditMode = !isEditMode
                        updateDocumentTypeUi(viewModel.documentType.value)
                        requireActivity().invalidateOptionsMenu()
                        return true
                    }
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupPdfControls(root: View) {
        pdfAnnotationOverlay.onStrokeFinished = viewModel::addPdfStroke
        pdfAnnotationOverlay.onEraseRequested = viewModel::erasePdfAnnotationAt
        pdfAnnotationOverlay.onBoxRequested = { anchorPoint ->
            showPdfBoxDialog(anchorPoint = anchorPoint)
        }
        pdfAnnotationOverlay.onBoxEditRequested = { existingBox ->
            showPdfBoxDialog(existingBox = existingBox)
        }
        pdfAnnotationOverlay.onBoxDeleteRequested = { box ->
            deletePdfBox(box.createdAt)
        }
        pdfAnnotationOverlay.onBoxSelectionChanged = { selectedBox ->
            selectedPdfBoxId = selectedBox?.createdAt
            updatePdfSelectedBoxHintChip()
        }
        pdfAnnotationOverlay.onBoxTransformCommitted = viewModel::commitPdfBoxTransform
        pdfAnnotationOverlay.onPanRequested = { deltaX, deltaY ->
            panPdfPageBy(deltaX, deltaY)
        }
        pdfAnnotationOverlay.onScaleRequested = { scaleFactor ->
            applyPdfZoom((activePdfZoom * scaleFactor).coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM))
        }

        root.findViewById<View>(R.id.btnPdfPrevious).setOnClickListener { viewModel.showPreviousPdfPage() }
        root.findViewById<View>(R.id.btnPdfNext).setOnClickListener { viewModel.showNextPdfPage() }
        root.findViewById<View>(R.id.btnPdfHand).setOnClickListener { selectPdfTool(PdfEditTool.HAND) }
        root.findViewById<View>(R.id.btnPdfPen).setOnClickListener { selectPdfTool(PdfEditTool.PEN) }
        root.findViewById<View>(R.id.btnPdfHighlight).setOnClickListener { selectPdfTool(PdfEditTool.HIGHLIGHT) }
        root.findViewById<View>(R.id.btnPdfSignature).setOnClickListener { selectPdfTool(PdfEditTool.SIGNATURE) }
        root.findViewById<View>(R.id.btnPdfBox).setOnClickListener { selectPdfTool(PdfEditTool.BOX) }
        root.findViewById<View>(R.id.btnPdfEraser).setOnClickListener { selectPdfTool(PdfEditTool.ERASER) }
        root.findViewById<View>(R.id.btnPdfUndo).setOnClickListener { viewModel.undoLastPdfAnnotation() }
        pdfZoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingZoomSlider) {
                    applyPdfZoom(
                        (MIN_PDF_ZOOM + (progress / PDF_ZOOM_SLIDER_DIVISOR)).coerceIn(
                            MIN_PDF_ZOOM,
                            MAX_PDF_ZOOM
                        ), syncSlider = false
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        applyPdfZoom(MIN_PDF_ZOOM)
        selectPdfTool(activePdfTool, announce = false)
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }
        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (!errorMsg.isNullOrBlank()) {
                Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
            }
        }
        viewModel.documentType.observe(viewLifecycleOwner) { type ->
            updateDocumentTypeUi(type)
            requireActivity().invalidateOptionsMenu()
        }
        viewModel.content.observe(viewLifecycleOwner) { contentText ->
            when (viewModel.documentType.value) {
                DocumentViewModel.DocType.TEXT -> {
                    suppressEditorTextWatcher = true
                    editText.setText(contentText)
                    suppressEditorTextWatcher = false
                }

                DocumentViewModel.DocType.MARKDOWN -> {
                    suppressEditorTextWatcher = true
                    editText.setText(contentText)
                    suppressEditorTextWatcher = false
                    markwon?.setMarkdown(markdownView, contentText)
                }

                else -> Unit
            }
            editorAutoSaveController.seedLastPersistedText(contentText)
            lastSearchIndex = -1
        }
        viewModel.pdfPage.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap != null) {
                pdfImageView.setImageBitmap(bitmap)
                pdfAnnotationOverlay.setPageSize(bitmap.width, bitmap.height)
                pdfPageStage.post { applyPdfZoom(activePdfZoom) }
            }
        }
        viewModel.currentPdfAnnotations.observe(viewLifecycleOwner) { annotations ->
            pdfAnnotationOverlay.setAnnotations(annotations)
        }
        viewModel.currentPdfPageIndex.observe(viewLifecycleOwner) {
            updatePdfPageIndicator()
            updatePdfNavigationButtons()
        }
        viewModel.pdfPageCount.observe(viewLifecycleOwner) {
            updatePdfPageIndicator()
            updatePdfNavigationButtons()
        }
        viewModel.savedPdfUri.observe(viewLifecycleOwner) { savedUri ->
            if (!savedUri.isNullOrBlank()) {
                handleSavedPdf(savedUri)
            }
        }
    }

    private fun updateDocumentTypeUi(type: DocumentViewModel.DocType?) {
        hideAllViews()
        when (type) {
            DocumentViewModel.DocType.TEXT -> {
                editText.visibility = View.VISIBLE
                emptyStateText.visibility = View.GONE
                isEditMode = true
                applyEditorFontSize(editorFontSizeSp)
                applyPdfZoom(MIN_PDF_ZOOM)
            }

            DocumentViewModel.DocType.MARKDOWN -> {
                emptyStateText.visibility = View.GONE
                applyEditorFontSize(editorFontSizeSp)
                if (isEditMode) {
                    editText.visibility = View.VISIBLE
                    formatToolbar?.visibility = View.VISIBLE
                } else {
                    markdownScrollView.visibility = View.VISIBLE
                    markwon?.setMarkdown(markdownView, editText.text?.toString().orEmpty())
                }
            }

            DocumentViewModel.DocType.PDF -> {
                editorAutoSaveController.cancelPendingSave()
                pdfPageStage.visibility = View.VISIBLE
                pdfToolbar.visibility = View.VISIBLE
                emptyStateText.visibility = View.GONE
                isEditMode = false
                closeSearch()
                applyPdfZoom(activePdfZoom)
                selectPdfTool(activePdfTool, announce = false)
                updatePdfSelectedBoxHintChip()
                emptyStateText.text = getString(R.string.pdf_edit_mode_hint)
            }

            else -> {
                editorAutoSaveController.cancelPendingSave()
                showEmptyState()
            }
        }
    }

    private fun showEmptyState() {
        hideAllViews()
        closeSearch()
        editorAutoSaveController.cancelPendingSave()
        emptyStateText.visibility = View.VISIBLE
        isEditMode = false
    }

    private fun observeEditorPreferences() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentFilesRepository.editorPreferencesFlow.collectLatest { preferences ->
                    isAutoSaveEnabled = preferences.autoSaveEnabled
                    editorFontSizeSp = preferences.fontSizeSp
                    applyEditorFontSize(editorFontSizeSp)
                    if (!isAutoSaveEnabled) {
                        editorAutoSaveController.cancelPendingSave()
                    }
                }
            }
        }
    }

    private fun hideAllViews() {
        editText.visibility = View.GONE
        markdownScrollView.visibility = View.GONE
        pdfPageStage.visibility = View.GONE
        pdfToolbar.visibility = View.GONE
        emptyStateText.visibility = View.GONE
        pdfSelectedBoxHintChip.visibility = View.GONE
        formatToolbar?.visibility = View.GONE
    }

    private fun openSearch() {
        searchToolbar?.visibility = View.VISIBLE
        searchEditText.requestFocus()
        if (searchEditText.text.isNullOrEmpty()) {
            lastSearchIndex = -1
        }
    }

    private fun closeSearch() {
        searchToolbar?.visibility = View.GONE
        searchDebounceJob?.cancel()
        searchEditText.text?.clear()
        lastSearchIndex = -1
    }

    private fun debounceSearchPreview() {
        searchDebounceJob?.cancel()
        searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            val query = searchEditText.text?.toString().orEmpty().trim()
            if (query.isEmpty()) {
                clearSearchHighlight()
                return@launch
            }
            findNextMatch(fromDebouncedInput = true)
        }
    }

    private fun clearSearchHighlight() {
        if (editText.text.isNullOrEmpty()) {
            lastSearchIndex = -1
            return
        }
        val currentSelectionStart = editText.selectionStart
        val currentSelectionEnd = editText.selectionEnd
        if (currentSelectionStart != currentSelectionEnd) {
            editText.setSelection(currentSelectionEnd)
        }
        lastSearchIndex = -1
    }

    private fun findNextMatch(fromDebouncedInput: Boolean = false) {
        val query = searchEditText.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            return
        }

        val content = editText.text?.toString().orEmpty()
        if (content.isEmpty()) {
            if (!fromDebouncedInput) {
                Toast.makeText(requireContext(), getString(R.string.search_no_match), Toast.LENGTH_SHORT).show()
            }
            return
        }

        val startIndex = if (lastSearchIndex >= 0) lastSearchIndex + query.length else 0
        val nextIndex = content.indexOf(query, startIndex, ignoreCase = true)
        val matchIndex = if (nextIndex >= 0) nextIndex else content.indexOf(query, 0, ignoreCase = true)

        if (matchIndex >= 0) {
            lastSearchIndex = matchIndex
            if (viewModel.documentType.value == DocumentViewModel.DocType.MARKDOWN && !isEditMode) {
                isEditMode = true
                updateDocumentTypeUi(viewModel.documentType.value)
                requireActivity().invalidateOptionsMenu()
            }
            editText.requestFocus()
            editText.setSelection(matchIndex, matchIndex + query.length)
        } else {
            lastSearchIndex = -1
            if (!fromDebouncedInput) {
                Toast.makeText(requireContext(), getString(R.string.search_no_match), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun insertMarkdown(prefix: String, suffix: String) {
        val start = editText.selectionStart
        val end = editText.selectionEnd
        if (start != -1 && end != -1) {
            val selectedText = editText.text.substring(start, end)
            editText.text.replace(start, end, "$prefix$selectedText$suffix")
            editText.setSelection(
                if (selectedText.isEmpty()) {
                    start + prefix.length
                } else {
                    end + prefix.length + suffix.length
                }
            )
        }
    }

    private fun applyEditorFontSize(fontSizeSp: Int) {
        if (!::editText.isInitialized || !::markdownView.isInitialized || !::searchEditText.isInitialized) {
            return
        }
        val fontSize = fontSizeSp.toFloat().coerceIn(MIN_EDITOR_FONT_SIZE_SP, MAX_EDITOR_FONT_SIZE_SP)
        editorTextAppearanceApplier.applyFontSizeSp(fontSize, editText, markdownView, searchEditText)
    }

    private fun scheduleEditorAutoSaveIfNeeded(currentText: String) {
        editorAutoSaveController.scheduleIfNeeded(
            scope = viewLifecycleOwner.lifecycleScope,
            autoSaveEnabled = isAutoSaveEnabled,
            uriString = uriString,
            documentType = viewModel.documentType.value,
            latestDocumentTypeSnapshot = { viewModel.documentType.value },
            latestTextSnapshot = { editText.text?.toString().orEmpty() }
        ) { latestUri, latestText ->
            onAutoSavePerformedForTesting?.invoke(latestUri, latestText)
                ?: viewModel.saveDocument(latestUri, latestText, showFeedback = false)
        }
    }

    private fun handlePdfSaveRequested(currentUri: String) {
        when (pdfSaveActionForSession) {
            PdfSaveAction.OVERWRITE -> performPdfSave(currentUri)
            PdfSaveAction.SAVE_COPY -> launchPdfSaveCopy(currentUri)
            null -> showPdfSaveDecisionDialog(currentUri)
        }
    }

    private fun showPdfSaveDecisionDialog(currentUri: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pdf_save_decision, null)
        val detailsText = dialogView.findViewById<TextView>(R.id.pdfSaveDetails)
        val rememberChoice = dialogView.findViewById<CheckBox>(R.id.pdfSaveRememberChoice)
        detailsText.text = buildPdfSaveDetails(currentUri)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pdf_save_replace_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.pdf_replace_action, null)
            .setPositiveButton(R.string.pdf_save_copy, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                rememberPdfSaveChoice(PdfSaveAction.SAVE_COPY, rememberChoice.isChecked)
                dialog.dismiss()
                launchPdfSaveCopy(currentUri)
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                rememberPdfSaveChoice(PdfSaveAction.OVERWRITE, rememberChoice.isChecked)
                dialog.dismiss()
                performPdfSave(currentUri)
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus()
        }
        dialog.show()
    }

    private fun rememberPdfSaveChoice(action: PdfSaveAction, shouldRemember: Boolean) {
        pdfSaveActionForSession = if (shouldRemember) action else null
    }

    private fun launchPdfSaveCopy(currentUri: String) {
        createPdfCopyLauncher.launch(viewModel.buildSuggestedEditedPdfFileName(currentUri))
    }

    private fun performPdfSave(targetUriString: String) {
        AppDiagnostics.logBreadcrumb(
            requireContext(),
            "Saving PDF to ${AppDiagnostics.describeUri(targetUriString)}"
        )
        viewModel.saveEditedPdfToUri(targetUriString)
    }

    private fun handleSavedPdf(savedUri: String) {
        uriString = savedUri
        viewModel.clearSavedPdfUri()
        lifecycleScope.launch {
            recentFilesRepository.addRecentFile(savedUri)
        }
        viewModel.loadDocument(savedUri)
    }

    private fun selectPdfTool(tool: PdfEditTool, announce: Boolean = true) {
        activePdfTool = tool
        pdfAnnotationOverlay.editTool = tool
        updatePdfSelectedBoxHintChip()

        setToolButtonSelected(R.id.btnPdfHand, tool == PdfEditTool.HAND)
        setToolButtonSelected(R.id.btnPdfPen, tool == PdfEditTool.PEN)
        setToolButtonSelected(R.id.btnPdfHighlight, tool == PdfEditTool.HIGHLIGHT)
        setToolButtonSelected(R.id.btnPdfSignature, tool == PdfEditTool.SIGNATURE)
        setToolButtonSelected(R.id.btnPdfBox, tool == PdfEditTool.BOX)
        setToolButtonSelected(R.id.btnPdfEraser, tool == PdfEditTool.ERASER)

        if (announce) {
            when (tool) {
                PdfEditTool.HAND -> {
                    val message = getString(R.string.pdf_tool_hand_selected)
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    announceForAccessibility(message)
                }

                PdfEditTool.BOX -> {
                    val message = getString(R.string.pdf_tool_box_selected)
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    announceForAccessibility(message)
                }

                PdfEditTool.NONE -> Unit

                else -> {
                    val labelRes = when (tool) {
                        PdfEditTool.HAND -> R.string.pdf_tool_hand
                        PdfEditTool.PEN -> R.string.pdf_tool_pen
                        PdfEditTool.HIGHLIGHT -> R.string.pdf_tool_highlight
                        PdfEditTool.SIGNATURE -> R.string.pdf_tool_signature
                        PdfEditTool.ERASER -> R.string.pdf_tool_eraser
                        PdfEditTool.BOX,
                        PdfEditTool.NONE -> null
                    }
                    labelRes?.let { resId ->
                        val message = getString(R.string.pdf_tool_selected, getString(resId))
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        announceForAccessibility(message)
                    }
                }
            }
        }
    }

    private fun announceForAccessibility(message: String) {
        val rootView = view ?: return
        pdfEditorAccessibilityHelper.announce(requireContext(), rootView, message)
    }

    private fun setToolButtonSelected(buttonId: Int, selected: Boolean) {
        view?.findViewById<View>(buttonId)?.apply {
            isActivated = selected
            alpha = if (selected) 1f else 0.72f
            contentDescription = getPdfToolButtonContentDescription(buttonId, selected)
        }
    }

    private fun getPdfToolButtonContentDescription(buttonId: Int, selected: Boolean): String {
        return pdfEditorAccessibilityHelper.getToolButtonContentDescription(requireContext(), buttonId, selected)
    }

    private fun setupBackNavigationBehavior() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        dismissKeyboardIfVisiblePreservingSelection() -> Unit
                        searchToolbar?.visibility == View.VISIBLE -> closeSearch()
                        else -> {
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            })
    }

    private fun dismissKeyboardIfVisiblePreservingSelection(): Boolean {
        val root = view ?: return false
        val imeVisible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val textEditorFocused = editText.visibility == View.VISIBLE && editText.hasFocus()
        val searchFocused = searchToolbar?.visibility == View.VISIBLE && searchEditText.hasFocus()
        if (!imeVisible || (!textEditorFocused && !searchFocused)) {
            return false
        }

        WindowInsetsControllerCompat(requireActivity().window, root).hide(WindowInsetsCompat.Type.ime())
        return true
    }

    private fun applyPdfZoom(zoom: Float, syncSlider: Boolean = true) {
        activePdfZoom = zoom.coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM)
        if (activePdfZoom <= MIN_PDF_ZOOM) {
            activePdfTranslation = PdfStageTranslation()
        }
        pdfAnnotationOverlay.viewportScale = activePdfZoom
        pdfPageStage.post {
            applyPdfStageTransform(activePdfTranslation)
        }
        pdfZoomValue.text = getString(R.string.pdf_zoom_value, (activePdfZoom * 100).toInt())
        pdfZoomSlider.contentDescription = pdfEditorAccessibilityHelper.getZoomContentDescription(
            requireContext(),
            (activePdfZoom * 100).toInt()
        )
        if (syncSlider) {
            isUpdatingZoomSlider = true
            pdfZoomSlider.progress = ((activePdfZoom - MIN_PDF_ZOOM) * PDF_ZOOM_SLIDER_DIVISOR).toInt()
            isUpdatingZoomSlider = false
        }
    }

    private fun showPdfBoxDialog(anchorPoint: PdfPoint? = null, existingBox: PdfBoxAnnotation? = null) {
        val isEditingExistingBox = existingBox != null
        val padding = (resources.displayMetrics.density * 20f).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val hintText = TextView(requireContext()).apply {
            text = getString(R.string.pdf_box_dialog_hint)
        }
        val typeLabel = TextView(requireContext()).apply {
            text = getString(R.string.pdf_box_dialog_type)
            setPadding(0, padding / 2, 0, padding / 4)
        }
        val types = listOf(
            PdfBoxContentType.TEXT,
            PdfBoxContentType.EMOJI,
            PdfBoxContentType.STICKER,
            PdfBoxContentType.SIGNATURE
        )
        val typeLabels = listOf(
            getString(R.string.pdf_box_type_text),
            getString(R.string.pdf_box_type_emoji),
            getString(R.string.pdf_box_type_sticker),
            getString(R.string.pdf_box_type_signature)
        )
        val initialType = existingBox?.contentType ?: PdfBoxContentType.TEXT
        val initialTypeIndex = types.indexOf(initialType).coerceAtLeast(0)
        val spinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, typeLabels)
        }
        val input = EditText(requireContext()).apply {
            setPadding(0, padding / 2, 0, 0)
            setText(existingBox?.content.orEmpty())
        }
        configurePdfBoxInput(input, initialType)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                configurePdfBoxInput(input, types[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinner.setSelection(initialTypeIndex, false)
        if (input.text?.isNotEmpty() == true) {
            input.setSelection(input.text?.length ?: 0)
        }
        container.addView(hintText)
        container.addView(typeLabel)
        container.addView(spinner)
        container.addView(input)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEditingExistingBox) R.string.pdf_box_dialog_edit_title else R.string.pdf_box_dialog_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (isEditingExistingBox) R.string.pdf_box_dialog_save else R.string.pdf_box_dialog_add,
                null
            )
            .apply {
                if (isEditingExistingBox) {
                    setNeutralButton(R.string.pdf_box_dialog_delete, null)
                }
            }
            .create()

        dialog.setOnShowListener {
            if (isEditingExistingBox) {
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    deletePdfBox(existingBox.createdAt)
                    dialog.dismiss()
                }
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val content = input.text?.toString().orEmpty().trim()
                if (content.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.pdf_box_dialog_empty), Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }

                val selectedType = types[spinner.selectedItemPosition]
                val updatedBox = existingBox?.copy(
                    content = content,
                    contentType = selectedType
                ) ?: anchorPoint?.let { point ->
                    PdfBoxAnnotation(
                        leftRatio = (point.xRatio - DEFAULT_PDF_BOX_WIDTH_RATIO / 2f).coerceIn(
                            0f,
                            1f - DEFAULT_PDF_BOX_WIDTH_RATIO
                        ),
                        topRatio = (point.yRatio - DEFAULT_PDF_BOX_HEIGHT_RATIO / 2f).coerceIn(
                            0f,
                            1f - DEFAULT_PDF_BOX_HEIGHT_RATIO
                        ),
                        widthRatio = DEFAULT_PDF_BOX_WIDTH_RATIO,
                        heightRatio = DEFAULT_PDF_BOX_HEIGHT_RATIO,
                        content = content,
                        contentType = selectedType
                    )
                }

                if (updatedBox == null) {
                    dialog.dismiss()
                    return@setOnClickListener
                }

                if (isEditingExistingBox) {
                    viewModel.updatePdfBoxAnnotation(updatedBox)
                    pdfAnnotationOverlay.selectBox(updatedBox.createdAt)
                } else {
                    val addedBox = viewModel.addPdfBoxAnnotation(updatedBox)
                    pdfAnnotationOverlay.selectBox(addedBox?.createdAt)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun deletePdfBox(boxId: Long) {
        pendingPdfDeleteUndoSnackbar?.dismiss()
        viewModel.removePdfBoxAnnotation(boxId)
        pdfAnnotationOverlay.selectBox(null)
        val anchorView = view ?: return
        pendingPdfDeleteUndoSnackbar =
            Snackbar.make(anchorView, getString(R.string.pdf_box_deleted), Snackbar.LENGTH_LONG)
                .setAction(R.string.pdf_box_delete_undo_action) {
                    viewModel.undoLastPdfAnnotation()
                    pdfAnnotationOverlay.selectBox(boxId)
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        if (pendingPdfDeleteUndoSnackbar === transientBottomBar) {
                            pendingPdfDeleteUndoSnackbar = null
                        }
                    }
                })
        pendingPdfDeleteUndoSnackbar?.show()
    }

    private fun updatePdfSelectedBoxHintChip() {
        if (!::pdfSelectedBoxHintChip.isInitialized) {
            return
        }
        pdfSelectedBoxHintChip.visibility = if (
            (activePdfTool == PdfEditTool.BOX || activePdfTool == PdfEditTool.HAND) &&
            selectedPdfBoxId != null &&
            pdfPageStage.visibility == View.VISIBLE
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    internal fun preparePdfUiForTesting(
        annotations: List<PdfAnnotationElement>,
        selectedBoxId: Long? = null
    ) {
        viewModel.seedPdfStateForTesting(
            annotationsByPage = mapOf(0 to annotations),
            currentPageIndex = 0,
            pageCount = 1
        )
        pdfAnnotationOverlay.setPageSize(TEST_PDF_PAGE_WIDTH_PX, TEST_PDF_PAGE_HEIGHT_PX)
        updateDocumentTypeUi(DocumentViewModel.DocType.PDF)
        pdfAnnotationOverlay.selectBox(selectedBoxId)
        updatePdfSelectedBoxHintChip()
    }

    internal fun prepareTextUiForTesting(
        type: DocumentViewModel.DocType,
        content: String,
        documentUri: String = "content://com.document.editor.testing/document.txt"
    ) {
        require(type == DocumentViewModel.DocType.TEXT || type == DocumentViewModel.DocType.MARKDOWN) {
            "prepareTextUiForTesting only supports TEXT or MARKDOWN"
        }
        uriString = documentUri
        viewModel.seedTextStateForTesting(type = type, content = content)
        updateDocumentTypeUi(type)
        editorAutoSaveController.seedLastPersistedText(content)
    }

    internal fun applyEditorPreferencesForTesting(
        fontSizeSp: Int = editorFontSizeSp,
        autoSaveEnabled: Boolean = isAutoSaveEnabled
    ) {
        editorFontSizeSp = fontSizeSp
        isAutoSaveEnabled = autoSaveEnabled
        applyEditorFontSize(editorFontSizeSp)
        if (!isAutoSaveEnabled) {
            editorAutoSaveController.cancelPendingSave()
        }
    }

    internal fun configureAutoSaveForTesting(
        debounceMs: Long = EditorAutoSaveController.DEFAULT_DEBOUNCE_MS,
        onAutoSavePerformed: ((String, String) -> Unit)? = null
    ) {
        editorAutoSaveController.debounceMs = debounceMs.coerceAtLeast(0L)
        onAutoSavePerformedForTesting = onAutoSavePerformed
    }

    internal fun updateEditorTextForTesting(updatedText: String) {
        editText.setText(updatedText)
        editText.setSelection(editText.text?.length ?: 0)
    }

    internal fun deletePdfBoxForTesting(boxId: Long) {
        deletePdfBox(boxId)
    }

    internal fun currentPdfAnnotationCountForTesting(): Int {
        return viewModel.currentPdfAnnotations.value.orEmpty().size
    }

    internal fun isPdfSelectedBoxHintVisibleForTesting(): Boolean {
        return ::pdfSelectedBoxHintChip.isInitialized && pdfSelectedBoxHintChip.visibility == View.VISIBLE
    }

    internal fun currentEditorTextSizesSpForTesting(): Triple<Float, Float, Float> {
        val scaledDensity = resources.displayMetrics.density * resources.configuration.fontScale
        return Triple(
            editText.textSize / scaledDensity,
            markdownView.textSize / scaledDensity,
            searchEditText.textSize / scaledDensity
        )
    }

    override fun onDestroyView() {
        editorAutoSaveController.cancelPendingSave()
        searchDebounceJob?.cancel()
        searchDebounceJob = null
        onAutoSavePerformedForTesting = null
        editorAutoSaveController.debounceMs = EditorAutoSaveController.DEFAULT_DEBOUNCE_MS
        if (::editText.isInitialized) {
            editText.removeTextChangedListener(editorTextWatcher)
        }
        super.onDestroyView()
    }

    private fun configurePdfBoxInput(input: EditText, type: PdfBoxContentType) {
        input.hint = when (type) {
            PdfBoxContentType.TEXT -> getString(R.string.pdf_box_content_hint_text)
            PdfBoxContentType.EMOJI -> getString(R.string.pdf_box_content_hint_emoji)
            PdfBoxContentType.STICKER -> getString(R.string.pdf_box_content_hint_sticker)
            PdfBoxContentType.SIGNATURE -> getString(R.string.pdf_box_content_hint_signature)
        }
        when (type) {
            PdfBoxContentType.TEXT,
            PdfBoxContentType.SIGNATURE -> {
                input.minLines = 2
                input.maxLines = 4
                input.setSingleLine(false)
            }

            PdfBoxContentType.EMOJI,
            PdfBoxContentType.STICKER -> {
                input.minLines = 1
                input.maxLines = 1
                input.setSingleLine(true)
            }
        }
    }

    private fun updatePdfPageIndicator() {
        val pageCount = viewModel.pdfPageCount.value ?: 0
        val currentIndex = viewModel.currentPdfPageIndex.value ?: 0
        val safePageCount = if (pageCount <= 0) 1 else pageCount
        val currentPage = if (pageCount <= 0) 1 else currentIndex + 1
        pdfPageIndicator.text = getString(R.string.pdf_page_indicator, currentPage, safePageCount)
        pdfPageIndicator.contentDescription = pdfEditorAccessibilityHelper.getPageContentDescription(
            requireContext(),
            currentPage,
            safePageCount
        )
    }

    private fun updatePdfNavigationButtons() {
        val pageCount = viewModel.pdfPageCount.value ?: 0
        val currentIndex = viewModel.currentPdfPageIndex.value ?: 0
        view?.findViewById<View>(R.id.btnPdfPrevious)?.isEnabled = currentIndex > 0
        view?.findViewById<View>(R.id.btnPdfNext)?.isEnabled = currentIndex < pageCount - 1
    }

    private fun panPdfPageBy(deltaX: Float, deltaY: Float) {
        if (activePdfZoom <= MIN_PDF_ZOOM || !::pdfPageStage.isInitialized) {
            return
        }

        applyPdfStageTransform(
            PdfStageTranslation(
                xPx = activePdfTranslation.xPx + deltaX,
                yPx = activePdfTranslation.yPx + deltaY
            )
        )
    }

    private fun applyPdfStageTransform(requestedTranslation: PdfStageTranslation) {
        val clampedTranslation = clampPdfStageTranslation(
            requestedXpx = requestedTranslation.xPx,
            requestedYpx = requestedTranslation.yPx,
            viewportWidthPx = pdfPageStage.width,
            viewportHeightPx = pdfPageStage.height,
            zoom = activePdfZoom
        )
        activePdfTranslation = clampedTranslation
        pdfPageStage.pivotX = pdfPageStage.width / 2f
        pdfPageStage.pivotY = pdfPageStage.height / 2f
        pdfPageStage.scaleX = activePdfZoom
        pdfPageStage.scaleY = activePdfZoom
        pdfPageStage.translationX = activePdfTranslation.xPx
        pdfPageStage.translationY = activePdfTranslation.yPx
    }

    private fun persistUriPermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                IntentFlags.readWrite
            )
        }.onFailure { throwable ->
            AppDiagnostics.logBreadcrumb(
                requireContext(),
                "Persistable permission request failed for ${AppDiagnostics.describeUri(uri.toString())}",
                throwable
            )
        }
    }

    private fun buildPdfSaveDetails(uriString: String): String {
        val details = resolvePdfFileDetails(uriString)
        val unknownValue = getString(R.string.pdf_file_details_unknown)
        val sizeLabel =
            details.sizeBytes?.let { Formatter.formatShortFileSize(requireContext(), it) } ?: unknownValue
        val modifiedLabel = details.lastModified?.let {
            DateFormat.getMediumDateFormat(requireContext()).format(Date(it)) + " " +
                    DateFormat.getTimeFormat(requireContext()).format(Date(it))
        } ?: unknownValue

        return listOf(
            getString(R.string.pdf_file_details_name, details.displayName),
            getString(R.string.pdf_file_details_size, sizeLabel),
            getString(R.string.pdf_file_details_modified, modifiedLabel),
            "",
            getString(R.string.pdf_save_replace_message)
        ).joinToString(separator = "\n")
    }

    private fun resolvePdfFileDetails(uriString: String): PdfFileDetails {
        val uri = uriString.toUri()
        if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
            val file = File(uri.path ?: uriString)
            return PdfFileDetails(
                displayName = file.name.ifBlank { getString(R.string.unknown_file_name) },
                sizeBytes = file.takeIf { it.exists() }?.length(),
                lastModified = file.takeIf { it.exists() }?.lastModified()
            )
        }

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                requireContext().contentResolver.query(
                    uri,
                    arrayOf(
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.stringValue(OpenableColumns.DISPLAY_NAME)
                            ?: getString(R.string.unknown_file_name)
                        val size = cursor.longValue(OpenableColumns.SIZE)
                        val modified = cursor.longValue(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        return PdfFileDetails(name, size, modified)
                    }
                }
            }
        }

        return PdfFileDetails(uri.lastPathSegment ?: getString(R.string.unknown_file_name), null, null)
    }

    private fun android.database.Cursor.stringValue(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.longValue(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private data class PdfFileDetails(
        val displayName: String,
        val sizeBytes: Long?,
        val lastModified: Long?
    )

    private enum class PdfSaveAction {
        OVERWRITE,
        SAVE_COPY
    }

    private object IntentFlags {
        const val readWrite =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private companion object {
        const val MENU_TOGGLE_MARKDOWN_MODE = 1001
        var pdfSaveActionForSession: PdfSaveAction? = null
        const val MIN_PDF_ZOOM = 1f
        const val MAX_PDF_ZOOM = 4f
        const val PDF_ZOOM_SLIDER_DIVISOR = 100f
        const val DEFAULT_PDF_BOX_WIDTH_RATIO = 0.32f
        const val DEFAULT_PDF_BOX_HEIGHT_RATIO = 0.16f
        const val TEST_PDF_PAGE_WIDTH_PX = 1200
        const val TEST_PDF_PAGE_HEIGHT_PX = 1600
        const val DEFAULT_EDITOR_FONT_SIZE_SP = 16
        const val MIN_EDITOR_FONT_SIZE_SP = 12f
        const val MAX_EDITOR_FONT_SIZE_SP = 30f
    }
}
