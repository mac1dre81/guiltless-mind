package com.document.editor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.gms.ads.MobileAds
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val repository = RecentFilesRepository(this)
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.isDarkModeFlow.collect { isDark ->
                    if (isDark != null) {
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
        }

        runCatching {
            MobileAds.initialize(this) {}
        }

        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController
        // Passing each menu ID as a set of Ids because each menu should be considered as top level destinations
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_documents,
                R.id.navigation_editor
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }
}
