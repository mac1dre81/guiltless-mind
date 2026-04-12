package com.document.editor

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DocumentListFragment : Fragment() {
    private lateinit var premiumManager: PremiumManager
    private lateinit var recentFilesRepository: RecentFilesRepository
    private lateinit var recentFilesAdapter: ArrayAdapter<String>
    private lateinit var recentFilesListView: ListView
    private lateinit var emptyView: TextView

    private var recentFileUris: List<String> = emptyList()

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        handleSelectedDocument(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_document_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        premiumManager = PremiumManager(requireContext())
        recentFilesRepository = RecentFilesRepository(requireContext())

        recentFilesListView = view.findViewById(R.id.recentFilesListView)
        emptyView = view.findViewById(android.R.id.empty)
        recentFilesAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf())
        recentFilesListView.adapter = recentFilesAdapter
        recentFilesListView.emptyView = emptyView

        view.findViewById<View>(R.id.btnOpenDocument).setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/*", "application/pdf", "application/octet-stream"))
        }
        view.findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.fab_scan).setOnClickListener {
            checkProAndScan()
        }

        recentFilesListView.setOnItemClickListener { _, _, position, _ ->
            val uri = recentFileUris.getOrNull(position)
            if (uri != null) {
                navigateToEditor(uri)
            }
        }

        observeRecentFiles()
    }

    private fun observeRecentFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentFilesRepository.recentFilesFlow.collectLatest { recentUris ->
                    val validUris = recentUris.filter(::isUriAccessible)
                    val removedUris = recentUris - validUris.toSet()

                    removedUris.forEach { uri ->
                        recentFilesRepository.removeRecentFile(uri)
                    }

                    recentFileUris = validUris
                    val displayItems = validUris.map(::resolveDisplayName)
                    recentFilesAdapter.clear()
                    recentFilesAdapter.addAll(displayItems)
                    recentFilesAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun handleSelectedDocument(uri: Uri) {
        val contentResolver = requireContext().contentResolver
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            recentFilesRepository.addRecentFile(uri.toString())
        }
        navigateToEditor(uri.toString())
    }

    private fun navigateToEditor(uriString: String) {
        val args = Bundle().apply {
            putString("document_uri", uriString)
        }
        findNavController().navigate(
            R.id.navigation_editor,
            args
        )
    }

    private fun checkProAndScan() {
        if (premiumManager.isPro.value) {
            startActivity(Intent(requireContext(), DocumentScannerActivity::class.java))
        } else {
            Toast.makeText(requireContext(), getString(R.string.pro_required_message), Toast.LENGTH_SHORT).show()
            premiumManager.purchasePro(requireActivity())
        }
    }

    private fun isUriAccessible(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return try {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.close()
                    true
                }
                ContentResolver.SCHEME_FILE -> {
                    File(requireNotNull(uri.path)).exists()
                }
                null -> File(uriString).exists()
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveDisplayName(uriString: String): String {
        val uri = Uri.parse(uriString)
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return File(uri.path.orEmpty()).name.ifBlank { uriString }
        }

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            runCatching {
                requireContext().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            return cursor.getString(index)
                        }
                    }
                }
            }
        }

        return uri.lastPathSegment ?: uriString
    }
}
