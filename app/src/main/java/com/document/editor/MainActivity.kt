package com.document.editor

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.ui.setupWithNavController
import com.google.android.gms.ads.MobileAds
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var recentFilesRepository: RecentFilesRepository

    private var destinationChangedListener: androidx.navigation.NavController.OnDestinationChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val splashStart = SystemClock.elapsedRealtime()
        splashScreen.setKeepOnScreenCondition {
            SystemClock.elapsedRealtime() - splashStart < SPLASH_MIN_DURATION_MS
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        AppDiagnostics.logBreadcrumb(this, "MainActivity created")
        AppDiagnostics.consumeLastCrashReport(this)?.let {
            AppDiagnostics.logBreadcrumb(this, "Recovered from previous crash")
            Toast.makeText(this, getString(R.string.crash_recovery_message), Toast.LENGTH_LONG).show()
        }

        setContentView(R.layout.activity_main)

        val rootContainer = findViewById<android.view.View>(R.id.rootContainer)
        val toolbar: MaterialToolbar = findViewById(R.id.topAppBar)
        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val navHostView = findViewById<android.view.View>(R.id.nav_host_fragment)
        val toolbarStartPadding = toolbar.paddingLeft
        val toolbarEndPadding = toolbar.paddingRight
        val toolbarBottomPadding = toolbar.paddingBottom
        val navViewStartPadding = navView.paddingLeft
        val navViewTopPadding = navView.paddingTop
        val navViewEndPadding = navView.paddingRight

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                recentFilesRepository.isDarkModeFlow.collect { isDark ->
                    val mode = if (isDark) {
                        AppCompatDelegate.MODE_NIGHT_YES
                    } else {
                        AppCompatDelegate.MODE_NIGHT_NO
                    }
                    if (AppCompatDelegate.getDefaultNightMode() != mode) {
                        AppCompatDelegate.setDefaultNightMode(mode)
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                MobileAds.initialize(this@MainActivity) {}
            }.onFailure { throwable ->
                AppDiagnostics.logBreadcrumb(this@MainActivity, "AdMob initialization failed", throwable)
            }
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        toolbar.title = getString(R.string.app_name)
        toolbar.subtitle = getString(R.string.screen_subtitle_documents)
        navView.setupWithNavController(navController)

        destinationChangedListener =
            androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                toolbar.title = getString(R.string.app_name)
                toolbar.subtitle = when (destination.id) {
                    R.id.navigation_editor -> getString(R.string.screen_subtitle_editor)
                    else -> getString(R.string.screen_subtitle_documents)
                }
            }
        destinationChangedListener?.let { listener ->
            navController.addOnDestinationChangedListener(listener)
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(
                toolbarStartPadding + systemBars.left,
                systemBars.top,
                toolbarEndPadding + systemBars.right,
                toolbarBottomPadding
            )
            navView.setPadding(
                navViewStartPadding + systemBars.left,
                navViewTopPadding,
                navViewEndPadding + systemBars.right,
                systemBars.bottom
            )
            navHostView.setPadding(systemBars.left, 0, systemBars.right, 0)
            insets
        }
    }

    override fun onDestroy() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment?.navController
        destinationChangedListener?.let { listener ->
            navController?.removeOnDestinationChangedListener(listener)
        }
        destinationChangedListener = null
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_MIN_DURATION_MS = 450L
    }
}
