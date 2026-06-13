package com.document.editor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private const val DEFAULT_EDITOR_FONT_SIZE_SP = 16
private const val MIN_EDITOR_FONT_SIZE_SP = 12
private const val MAX_EDITOR_FONT_SIZE_SP = 30

data class RecentFileEntry(
    val timestamp: Long,
    val uri: String
)

data class EditorPreferences(
    val darkModeEnabled: Boolean = false,
    val fontSizeSp: Int = DEFAULT_EDITOR_FONT_SIZE_SP,
    val autoSaveEnabled: Boolean = true
)

@Singleton
class RecentFilesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    // App-scoped writes prevent settings changes from being cancelled when the user
    // immediately leaves the Settings screen after toggling a preference.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentFilesKey = stringSetPreferencesKey("recent_files")
    private val darkModeKey = booleanPreferencesKey("dark_mode")
    private val fontSizeKey = intPreferencesKey("font_size")
    private val autoSaveKey = booleanPreferencesKey("auto_save")

    private val safePreferencesFlow: Flow<Preferences> = context.dataStore.data.catch { throwable ->
        if (throwable is IOException) {
            emit(emptyPreferences())
        } else {
            throw throwable
        }
    }

    val editorPreferencesFlow: Flow<EditorPreferences> = safePreferencesFlow.map { preferences ->
        EditorPreferences(
            darkModeEnabled = preferences[darkModeKey] ?: false,
            fontSizeSp = preferences[fontSizeKey] ?: DEFAULT_EDITOR_FONT_SIZE_SP,
            autoSaveEnabled = preferences[autoSaveKey] ?: true
        )
    }

    val isDarkModeFlow: Flow<Boolean> = editorPreferencesFlow.map { it.darkModeEnabled }

    val recentFileEntriesFlow: Flow<List<RecentFileEntry>> = safePreferencesFlow.map { preferences ->
        (preferences[recentFilesKey] ?: emptySet())
            .mapNotNull(::parseRecentFileEntry)
            .sortedByDescending { it.timestamp }
    }

    val recentFilesFlow: Flow<List<String>> = recentFileEntriesFlow.map { entries ->
        entries.map(RecentFileEntry::uri)
    }

    suspend fun setDarkMode(isDark: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[darkModeKey] = isDark
        }
    }

    fun setDarkModeAsync(isDark: Boolean): Job = repositoryScope.launch {
        setDarkMode(isDark)
    }

    suspend fun setEditorFontSize(fontSizeSp: Int) {
        context.dataStore.edit { preferences ->
            preferences[fontSizeKey] = fontSizeSp.coerceIn(MIN_EDITOR_FONT_SIZE_SP, MAX_EDITOR_FONT_SIZE_SP)
        }
    }

    fun setEditorFontSizeAsync(fontSizeSp: Int): Job = repositoryScope.launch {
        setEditorFontSize(fontSizeSp)
    }

    suspend fun setAutoSaveEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[autoSaveKey] = enabled
        }
    }

    fun setAutoSaveEnabledAsync(enabled: Boolean): Job = repositoryScope.launch {
        setAutoSaveEnabled(enabled)
    }

    suspend fun addRecentFile(uri: String) {
        val timestamp = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            val current = preferences[recentFilesKey] ?: emptySet()
            val updated = current.filterNot { it.substringAfter("::", "") == uri }.toMutableSet()
            updated.add("$timestamp::$uri")

            val latest = updated
                .mapNotNull { entry ->
                    parseRecentFileEntry(entry)?.let { parsed ->
                        parsed.timestamp to entry
                    }
                }
                .sortedByDescending { pair -> pair.first }
                .take(50)
                .map { pair -> pair.second }
                .toSet()

            preferences[recentFilesKey] = latest
        }
    }

    suspend fun removeRecentFile(uri: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[recentFilesKey] ?: emptySet()
            preferences[recentFilesKey] = current.filterNot { it.substringAfter("::", "") == uri }.toSet()
        }
    }

    private fun parseRecentFileEntry(entry: String): RecentFileEntry? {
        val parts = entry.split("::", limit = 2)
        if (parts.size != 2) {
            return null
        }

        val timestamp = parts[0].toLongOrNull() ?: return null
        val uri = parts[1]
        if (uri.isBlank()) {
            return null
        }

        return RecentFileEntry(timestamp = timestamp, uri = uri)
    }
}
