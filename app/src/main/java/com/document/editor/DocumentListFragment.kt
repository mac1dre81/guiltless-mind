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
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.min

@AndroidEntryPoint
class DocumentListFragment : Fragment() {
    @Inject
    lateinit var recentFilesRepository: RecentFilesRepository

    @Inject
    lateinit var premiumManager: PremiumManager

    @Inject
    lateinit var premiumStatusProvider: PremiumStatusProvider

    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private lateinit var recentFilesListView: ListView
    private lateinit var emptyView: TextView
    private lateinit var subscriptionStatusText: TextView
    private lateinit var scanButton: View

    private var recentFileCards: List<RecentFileCard> = emptyList()
    private var isPremiumUser = false
    private var isProUser = false
    private var visibleRecentItemCount = INITIAL_RECENT_PAGE_SIZE

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            AppDiagnostics.logBreadcrumb(requireContext(), "Document picker dismissed")
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

        recentFilesListView = view.findViewById(R.id.recentFilesListView)
        emptyView = view.findViewById(android.R.id.empty)
        subscriptionStatusText = view.findViewById(R.id.subscriptionStatusText)
        scanButton = view.findViewById(R.id.fab_scan)

        recentFilesAdapter = RecentFilesAdapter(requireContext())
        recentFilesListView.adapter = recentFilesAdapter
        recentFilesListView.emptyView = emptyView
        ViewCompat.setAccessibilityHeading(subscriptionStatusText, true)
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.recentFilesSectionTitle), true)

        view.findViewById<View>(R.id.btnOpenDocument).setOnClickListener {
            AppDiagnostics.logBreadcrumb(requireContext(), "Open document tapped")
            openDocumentLauncher.launch(arrayOf("*/*"))
        }
        view.findViewById<View>(R.id.btnSettings).setOnClickListener {
            AppDiagnostics.logBreadcrumb(requireContext(), "Settings tapped")
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        scanButton.setOnClickListener {
            checkProAndScan()
        }

        recentFilesListView.setOnItemClickListener { _, _, position, _ ->
            recentFileCards.getOrNull(position)?.let { card ->
                navigateToEditor(card.uri)
            }
        }
        recentFilesListView.setOnItemLongClickListener { _, _, position, _ ->
            val card = recentFileCards.getOrNull(position) ?: return@setOnItemLongClickListener false
            lifecycleScope.launch {
                recentFilesRepository.removeRecentFile(card.uri)
                showMessage(getString(R.string.recent_file_remove))
                AppDiagnostics.logBreadcrumb(
                    requireContext(),
                    "Recent file removed manually: ${AppDiagnostics.describeUri(card.uri)}"
                )
            }
            true
        }

        recentFilesListView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                if (totalItemCount == 0) return
                val reachedNearEnd =
                    firstVisibleItem + visibleItemCount >= totalItemCount - RECENT_LIST_PREFETCH_THRESHOLD
                if (reachedNearEnd) {
                    loadNextRecentPageIfNeeded()
                }
            }
        })

        observeRecentFiles()
        observeSubscriptionState()
    }

    override fun onDestroyView() {
        recentFilesListView.adapter = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        premiumStatusProvider.refreshEntitlements()
    }

    private fun observeSubscriptionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    premiumStatusProvider.isPremium.collectLatest { isPremium ->
                        isPremiumUser = isPremium
                        updateSubscriptionUi()
                    }
                }
                launch {
                    premiumStatusProvider.isPro.collectLatest { isPro ->
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
        val scanLabel = if (isProUser) {
            getString(R.string.scan_document_cta)
        } else {
            getString(R.string.scan_document_pro)
        }
        (scanButton as? TextView)?.text = scanLabel
        scanButton.contentDescription = if (isProUser) {
            getString(R.string.scan_document_ready_content_description)
        } else {
            getString(R.string.scan_document_locked_content_description)
        }
    }

    private fun observeRecentFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentFilesRepository.recentFileEntriesFlow.collectLatest { recentEntries ->
                    val validEntries = mutableListOf<RecentFileEntry>()
                    val removedUris = mutableListOf<String>()

                    recentEntries.forEach { entry ->
                        if (isUriFastAccessible(entry.uri) || isUriAccessible(entry.uri)) {
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
                            "Removed inaccessible recent files: ${
                                removedUris.joinToString {
                                    AppDiagnostics.describeUri(
                                        it
                                    )
                                }
                            }"
                        )
                        showMessage(getString(R.string.recent_files_cleanup_message))
                    }

                    if (visibleRecentItemCount < INITIAL_RECENT_PAGE_SIZE) {
                        visibleRecentItemCount = INITIAL_RECENT_PAGE_SIZE
                    }

                    val cappedCount = min(visibleRecentItemCount, validEntries.size)
                    recentFileCards = validEntries
                        .take(cappedCount)
                        .map { entry ->
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

        lifecycleScope.launch {
            recentFilesRepository.addRecentFile(uri.toString())
        }
        AppDiagnostics.logBreadcrumb(
            requireContext(),
            "Document selected: ${AppDiagnostics.describeUri(uri.toString())}"
        )
        navigateToEditor(uri.toString())
    }

    private fun navigateToEditor(uriString: String) {
        AppDiagnostics.logBreadcrumb(
            requireContext(),
            "Navigating to editor for ${AppDiagnostics.describeUri(uriString)}"
        )
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
            showScannerUpgradeDialog()
        }
    }

    private fun showScannerUpgradeDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scanner_upgrade_title)
            .setMessage(R.string.scanner_upgrade_message)
            .setPositiveButton(R.string.scanner_upgrade_positive) { _, _ ->
                premiumManager.purchasePro(requireActivity(), ::showBillingMessage)
            }
            .setNegativeButton(R.string.scanner_upgrade_negative, null)

        if (BuildConfig.DEBUG) {
            builder.setNeutralButton(R.string.about_enable_debug_pro) { _, _ ->
                premiumManager.enableDebugPro()
                showMessage(getString(R.string.debug_access_enabled))
            }
        } else {
            builder.setNeutralButton(R.string.view_plans_pricing) { _, _ ->
                startActivity(Intent(requireContext(), AboutActivity::class.java))
            }
        }

        builder.show()
    }

    private fun showBillingMessage(message: String) {
        showMessage(message)
    }

    private fun showMessage(message: String) {
        val anchorView = view
        if (anchorView != null) {
            Snackbar.make(anchorView, message, Snackbar.LENGTH_LONG).show()
        } else {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun isUriFastAccessible(uriString: String): Boolean {
        val uri = uriString.toUri()
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let { path -> File(path).exists() } == true
            null -> File(uriString).exists()
            ContentResolver.SCHEME_CONTENT -> true
            else -> false
        }
    }

    private fun isUriAccessible(uriString: String): Boolean {
        val uri = uriString.toUri()
        return try {
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    requireContext().contentResolver.openFileDescriptor(uri, "r")?.close()
                    true
                }

                ContentResolver.SCHEME_FILE -> uri.path?.let { path -> File(path).exists() } == true
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
            value.endsWith(".png") || value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".gif") -> getString(
                R.string.file_type_image
            )

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

    private companion object {
        const val INITIAL_RECENT_PAGE_SIZE = 20
        const val RECENT_PAGE_SIZE = 20
        const val RECENT_LIST_PREFETCH_THRESHOLD = 5
    }

    private fun loadNextRecentPageIfNeeded() {
        val currentVisible = recentFileCards.size
        if (currentVisible >= visibleRecentItemCount) {
            visibleRecentItemCount += RECENT_PAGE_SIZE
        }
    }

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
            view.contentDescription = item?.let {
                context.getString(
                    R.string.recent_file_item_content_description,
                    it.title,
                    it.subtitle
                )
            }.orEmpty()
            return view
        }
    }
}
