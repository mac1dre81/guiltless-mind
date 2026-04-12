package com.document.editor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class RecentFilesRepository(private val context: Context) {
    private val recentFilesKey = stringSetPreferencesKey("recent_files")
    private val darkModeKey = booleanPreferencesKey("dark_mode")

    private val safePreferencesFlow: Flow<Preferences> = context.dataStore.data.catch { throwable ->
        if (throwable is IOException) {
            emit(emptyPreferences())
        } else {
            throw throwable
        }
    }

    val isDarkModeFlow: Flow<Boolean?> = safePreferencesFlow.map { it[darkModeKey] }

    val recentFilesFlow: Flow<List<String>> = safePreferencesFlow.map { preferences ->
        (preferences[recentFilesKey] ?: emptySet())
            .mapNotNull(::parseRecentFileEntry)
            .sortedByDescending { it.first }
            .map { it.second }
    }

    suspend fun setDarkMode(isDark: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[darkModeKey] = isDark
        }
    }

    suspend fun addRecentFile(uri: String) {
        val timestamp = System.currentTimeMillis()
        context.dataStore.edit { preferences ->
            val current = preferences[recentFilesKey] ?: emptySet()
            val updated = current.filterNot { it.endsWith("::$uri") || it.substringAfter("::", "") == uri }.toMutableSet()
            updated.add("$timestamp::$uri")

            val latest = updated
                .mapNotNull { entry -> parseRecentFileEntry(entry)?.let { parsed -> parsed.first to entry } }
                .sortedByDescending { it.first }
                .take(50)
                .map { it.second }
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

    private fun parseRecentFileEntry(entry: String): Pair<Long, String>? {
        val parts = entry.split("::", limit = 2)
        if (parts.size != 2) {
            return null
        }

        val timestamp = parts[0].toLongOrNull() ?: return null
        val uri = parts[1]
        if (uri.isBlank()) {
            return null
        }

        return timestamp to uri
    }
}
