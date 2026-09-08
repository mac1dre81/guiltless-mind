package com.document.editor

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.settings_activity)
        val root = findViewById<android.view.View>(R.id.settingsRoot)
        val toolbar: MaterialToolbar = findViewById(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        val rootBottomPadding = root.paddingBottom
        val toolbarStartPadding = toolbar.paddingLeft
        val toolbarEndPadding = toolbar.paddingRight
        val toolbarBottomPadding = toolbar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(
                toolbarStartPadding + systemBars.left,
                systemBars.top,
                toolbarEndPadding + systemBars.right,
                toolbarBottomPadding
            )
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, rootBottomPadding + systemBars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @AndroidEntryPoint
    class SettingsFragment : PreferenceFragmentCompat() {
        @Inject
        lateinit var recentFilesRepository: RecentFilesRepository

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val darkModePreference = findPreference<SwitchPreferenceCompat>("dark_mode")
            val fontSizePreference = findPreference<ListPreference>("font_size")
            val autoSavePreference = findPreference<SwitchPreferenceCompat>("auto_save")
            val helpPreference = findPreference<Preference>("help_pricing")

            var isApplyingPreferenceState = false

            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    recentFilesRepository.editorPreferencesFlow.collectLatest { preferences ->
                        isApplyingPreferenceState = true
                        if (darkModePreference?.isChecked != preferences.darkModeEnabled) {
                            darkModePreference?.isChecked = preferences.darkModeEnabled
                        }
                        val fontSizeValue = preferences.fontSizeSp.toString()
                        if (fontSizePreference?.value != fontSizeValue) {
                            fontSizePreference?.value = fontSizeValue
                        }
                        if (autoSavePreference?.isChecked != preferences.autoSaveEnabled) {
                            autoSavePreference?.isChecked = preferences.autoSaveEnabled
                        }
                        isApplyingPreferenceState = false
                    }
                }
            }

            darkModePreference?.setOnPreferenceChangeListener { _, newValue ->
                if (isApplyingPreferenceState) {
                    return@setOnPreferenceChangeListener true
                }
                val isDark = newValue as Boolean
                // Persist from an app-scoped repository coroutine so the write is not
                // cancelled if the user backs out of Settings immediately after toggling.
                recentFilesRepository.setDarkModeAsync(isDark)
                val mode = if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                if (AppCompatDelegate.getDefaultNightMode() != mode) {
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
                true
            }
            fontSizePreference?.setOnPreferenceChangeListener { _, newValue ->
                val fontSizeSp = (newValue as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
                recentFilesRepository.setEditorFontSizeAsync(fontSizeSp)
                true
            }
            autoSavePreference?.setOnPreferenceChangeListener { _, newValue ->
                if (isApplyingPreferenceState) {
                    return@setOnPreferenceChangeListener true
                }
                recentFilesRepository.setAutoSaveEnabledAsync(newValue as Boolean)
                true
            }
            helpPreference?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AboutActivity::class.java))
                true
            }
        }
    }
}
