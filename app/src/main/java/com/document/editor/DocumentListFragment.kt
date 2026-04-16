package com.document.editor

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
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
    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private lateinit var recentFilesListView: ListView
    private lateinit var emptyView: TextView
    private lateinit var subscriptionStatusText: TextView
    private lateinit var upgradePremiumButton: View

    private var recentFileCards: List<RecentFileCard> = emptyList()
    private var isPremiumUser = false
    private var isProUser = false

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            AppDiagnostics.logBreadcrumb(requireContext(), "Document picker dismissed")
            return@registerForActivityResult
        }

        handleSelectedDocument(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        premiumManager = PremiumManager(requireContext())
        recentFilesRepository = RecentFilesRepository(requireContext())
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

        recentFilesListView = view.findViewById(R.id.recentFilesListView)
        emptyView = view.findViewById(android.R.id.empty)
        subscriptionStatusText = view.findViewById(R.id.subscriptionStatusText)
        upgradePremiumButton = view.findViewById(R.id.btnUpgradePremium)

        recentFilesAdapter = RecentFilesAdapter(requireContext())
        recentFilesListView.adapter = recentFilesAdapter
        recentFilesListView.emptyView = emptyView

        view.findViewById<View>(R.id.btnOpenDocument).setOnClickListener {
            AppDiagnostics.logBreadcrumb(requireContext(), "Open document tapped")
            openDocumentLauncher.launch(arrayOf("text/*", "application/pdf", "application/octet-stream", "image/*"))
        }
        view.findViewById<View>(R.id.btnSettings).setOnClickListener {
            AppDiagnostics.logBreadcrumb(requireContext(), "Settings tapped")
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        upgradePremiumButton.setOnClickListener {
            AppDiagnostics.logBreadcrumb(requireContext(), "Premium upgrade tapped")
            premiumManager.purchasePremium(requireActivity())
        }
        view.findViewById<View>(R.id.fab_scan).setOnClickListener {
            checkProAndScan()
        }

        recentFilesListView.setOnItemClickListener { _, _, position, _ ->
            recentFileCards.getOrNull(position)?.let { card ->
                navigateToEditor(card.uri)
            }
        }
        recentFilesListView.setOnItemLongClickListener { _, _, position, _ ->
            val card = recentFileCards.getOrNull(position) ?: return@setOnItemLongClickListener false
            viewLifecycleOwner.lifecycleScope.launch {
                recentFilesRepository.removeRecentFile(card.uri)
                Toast.makeText(requireContext(), getString(R.string.recent_file_remove), Toast.LENGTH_SHORT).show()
                AppDiagnostics.logBreadcrumb(
                    requireContext(),
                    "Recent file removed manually: ${AppDiagnostics.describeUri(card.uri)}"
                )
            }
            true
        }

        observeRecentFiles()
        observeSubscriptionState()
    }

    override fun onDestroyView() {
        recentFilesListView.adapter = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        premiumManager.close()
        super.onDestroy()
    }

    private fun observeSubscriptionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    premiumManager.isPremium.collectLatest { isPremium ->
                        isPremiumUser = isPremium
                        updateSubscriptionUi()
                    }
                }
                launch {
                    premiumManager.isPro.collectLatest { isPro ->
                        isProUser = isPro
                        updateSubscriptionUi()
                    }
                }
            }
        }
    }

    private fun updateSubscriptionUi() {
        subscriptionStatusText.text = when {
            isProUser -> getString(R.string.subscription_pro)
            isPremiumUser -> getString(R.string.subscription_premium)
            else -> getString(R.string.subscription_free)
        }
        upgradePremiumButton.visibility = if (isPremiumUser || isProUser) View.GONE else View.VISIBLE
    }

    private fun observeRecentFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentFilesRepository.recentFileEntriesFlow.collectLatest { recentEntries ->
                    val validEntries = mutableListOf<RecentFileEntry>()
                    val removedUris = mutableListOf<String>()

                    recentEntries.forEach { entry ->
                        if (isUriAccessible(entry.uri)) {
                            validEntries.add(entry)
                        } else {
                            removedUris.add(entry.uri)
                        }
                    }

                    removedUris.forEach { uri ->
                        recentFilesRepository.removeRecentFile(uri)
                    }

                    if (removedUris.isNotEmpty()) {
                        AppDiagnostics.logBreadcrumb(
                            requireContext(),
                            "Removed inaccessible recent files: ${removedUris.joinToString { AppDiagnostics.describeUri(it) }}"
                        )
                        Toast.makeText(requireContext(), getString(R.string.recent_files_cleanup_message), Toast.LENGTH_SHORT).show()
                    }

                    recentFileCards = validEntries.map { entry ->
                        RecentFileCard(
                            uri = entry.uri,
                            title = resolveDisplayName(entry.uri),
                            subtitle = getString(
                                R.string.recent_file_subtitle,
                                resolveFileTypeLabel(entry.uri),
                                formatRelativeTimestamp(entry.timestamp)
                            )
                        )
                    }
                    recentFilesAdapter.replaceItems(recentFileCards)
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
        }.onFailure { throwable ->
            AppDiagnostics.logBreadcrumb(
                requireContext(),
                "Persistable permission request failed for ${AppDiagnostics.describeUri(uri.toString())}",
                throwable
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            recentFilesRepository.addRecentFile(uri.toString())
        }
        AppDiagnostics.logBreadcrumb(requireContext(), "Document selected: ${AppDiagnostics.describeUri(uri.toString())}")
        navigateToEditor(uri.toString())
    }

    private fun navigateToEditor(uriString: String) {
        AppDiagnostics.logBreadcrumb(requireContext(), "Navigating to editor for ${AppDiagnostics.describeUri(uriString)}")
        val args = Bundle().apply {
            putString("document_uri", uriString)
        }
        findNavController().navigate(
            R.id.navigation_editor,
            args
        )
    }

    private fun checkProAndScan() {
        if (isProUser) {
            AppDiagnostics.logBreadcrumb(requireContext(), "Launching document scanner")
            startActivity(Intent(requireContext(), DocumentScannerActivity::class.java))
        } else {
            AppDiagnostics.logBreadcrumb(requireContext(), "Scanner gated behind Pro subscription")
            Toast.makeText(requireContext(), getString(R.string.pro_required_message), Toast.LENGTH_SHORT).show()
            premiumManager.purchasePro(requireActivity())
        }
    }

    private fun isUriAccessible(uriString: String): Boolean {
        val uri = uriString.toUri()
        return try {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.close()
                    true
                }
                ContentResolver.SCHEME_FILE -> File(requireNotNull(uri.path)).exists()
                null -> File(uriString).exists()
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveDisplayName(uriString: String): String {
        val uri = uriString.toUri()
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return File(uri.path.orEmpty()).name.ifBlank { getString(R.string.unknown_file_name) }
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

        return uri.lastPathSegment ?: getString(R.string.unknown_file_name)
    }

    private fun resolveFileTypeLabel(uriString: String): String {
        val value = uriString.lowercase()
        return when {
            value.endsWith(".pdf") -> getString(R.string.file_type_pdf)
            value.endsWith(".md") -> getString(R.string.file_type_markdown)
            value.endsWith(".txt") -> getString(R.string.file_type_text)
            value.endsWith(".png") || value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".gif") -> getString(R.string.file_type_image)
            else -> getString(R.string.file_type_file)
        }
    }

    private fun formatRelativeTimestamp(timestamp: Long): String {
        return getString(
            R.string.recent_file_opened,
            DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
        )
    }

    private data class RecentFileCard(
        val uri: String,
        val title: String,
        val subtitle: String
    )

    private class RecentFilesAdapter(context: Context) : ArrayAdapter<RecentFileCard>(context, 0, mutableListOf()) {
        private val inflater = LayoutInflater.from(context)

        fun replaceItems(items: List<RecentFileCard>) {
            clear()
            addAll(items)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_recent_file, parent, false)
            val item = getItem(position)

            view.findViewById<TextView>(R.id.recentFileTitle).text = item?.title.orEmpty()
            view.findViewById<TextView>(R.id.recentFileSubtitle).text = item?.subtitle.orEmpty()
            return view
        }
    }
}
