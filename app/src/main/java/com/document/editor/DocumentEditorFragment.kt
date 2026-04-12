package com.document.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import io.noties.markwon.Markwon

class DocumentEditorFragment : Fragment() {
    private val viewModel: DocumentViewModel by viewModels()

    private lateinit var progressBar: ProgressBar
    private lateinit var editText: EditText
    private lateinit var searchEditText: EditText
    private lateinit var markdownScrollView: ScrollView
    private lateinit var markdownView: TextView
    private lateinit var pdfImageView: ImageView
    private lateinit var emptyStateText: TextView

    private var searchToolbar: View? = null
    private var formatToolbar: View? = null
    private var markwon: Markwon? = null
    private var isEditMode = false
    private var uriString: String? = null
    private var lastSearchIndex = -1

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
        pdfImageView = view.findViewById(R.id.pdfImageView)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        searchToolbar = view.findViewById(R.id.searchToolbar)
        formatToolbar = view.findViewById(R.id.formatToolbar)

        view.findViewById<View>(R.id.btnBold).setOnClickListener { insertMarkdown("**", "**") }
        view.findViewById<View>(R.id.btnItalic).setOnClickListener { insertMarkdown("*", "*") }
        view.findViewById<View>(R.id.btnList).setOnClickListener { insertMarkdown("- ", "") }
        view.findViewById<View>(R.id.btnSearchNext).setOnClickListener { findNextMatch() }
        view.findViewById<View>(R.id.btnSearchClose).setOnClickListener { closeSearch() }

        markwon = Markwon.create(requireContext())

        setupObservers()
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
                val isSearchable = isText || isMarkdown

                menu.findItem(R.id.action_save)?.isVisible = isSearchable
                menu.findItem(R.id.action_search)?.isVisible = isSearchable

                if (isMarkdown) {
                    val toggleTitle = if (isEditMode) "Preview" else "Edit"
                    val toggleItem = menu.add(Menu.NONE, MENU_TOGGLE_MARKDOWN_MODE, Menu.NONE, toggleTitle)
                    toggleItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                when (menuItem.itemId) {
                    R.id.action_save -> {
                        val currentUri = uriString
                        if (currentUri != null) {
                            viewModel.saveDocument(currentUri, editText.text.toString())
                            if (viewModel.documentType.value == DocumentViewModel.DocType.MARKDOWN) {
                                markwon?.setMarkdown(markdownView, editText.text.toString())
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
                    editText.setText(contentText)
                }
                DocumentViewModel.DocType.MARKDOWN -> {
                    editText.setText(contentText)
                    markwon?.setMarkdown(markdownView, contentText)
                }
                else -> Unit
            }
            lastSearchIndex = -1
        }
        viewModel.pdfPage.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap != null) {
                pdfImageView.setImageBitmap(bitmap)
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
            }
            DocumentViewModel.DocType.MARKDOWN -> {
                emptyStateText.visibility = View.GONE
                if (isEditMode) {
                    editText.visibility = View.VISIBLE
                    formatToolbar?.visibility = View.VISIBLE
                } else {
                    markdownScrollView.visibility = View.VISIBLE
                    markwon?.setMarkdown(markdownView, editText.text?.toString().orEmpty())
                }
            }
            DocumentViewModel.DocType.PDF -> {
                pdfImageView.visibility = View.VISIBLE
                emptyStateText.visibility = View.GONE
                isEditMode = false
            }
            else -> showEmptyState()
        }
    }

    private fun showEmptyState() {
        hideAllViews()
        closeSearch()
        emptyStateText.visibility = View.VISIBLE
        isEditMode = false
    }

    private fun hideAllViews() {
        editText.visibility = View.GONE
        markdownScrollView.visibility = View.GONE
        pdfImageView.visibility = View.GONE
        emptyStateText.visibility = View.GONE
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
        searchEditText.text?.clear()
        lastSearchIndex = -1
    }

    private fun findNextMatch() {
        val query = searchEditText.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            return
        }

        val content = editText.text?.toString().orEmpty()
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.search_no_match), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), getString(R.string.search_no_match), Toast.LENGTH_SHORT).show()
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

    private companion object {
        const val MENU_TOGGLE_MARKDOWN_MODE = 1001
    }
}
