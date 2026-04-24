package com.document.editor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AboutActivity : AppCompatActivity() {
    @Inject
    lateinit var premiumManager: PremiumManager

    @Inject
    lateinit var premiumStatusProvider: PremiumStatusProvider

    private lateinit var statusText: TextView
    private lateinit var premiumButton: View
    private lateinit var proButton: View
    private lateinit var scannerButton: View
    private lateinit var debugCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_about)

        val root = findViewById<View>(R.id.aboutRoot)
        val toolbar: MaterialToolbar = findViewById(R.id.aboutToolbar)
        statusText = findViewById(R.id.aboutStatusText)
        premiumButton = findViewById(R.id.btnAboutPremium)
        proButton = findViewById(R.id.btnAboutPro)
        scannerButton = findViewById(R.id.btnAboutScanner)
        debugCard = findViewById(R.id.debugCard)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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

        premiumButton.setOnClickListener {
            premiumManager.purchasePremium(this, ::showMessage)
        }
        proButton.setOnClickListener {
            premiumManager.purchasePro(this, ::showMessage)
        }
        scannerButton.setOnClickListener {
            if (premiumStatusProvider.isProUser()) {
                startActivity(Intent(this, DocumentScannerActivity::class.java))
            } else {
                showMessage(getString(R.string.pro_required_message))
            }
        }

        debugCard.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        if (BuildConfig.DEBUG) {
            findViewById<View>(R.id.btnDebugPremium).setOnClickListener {
                premiumManager.enableDebugPremium()
                showMessage(getString(R.string.debug_access_enabled))
            }
            findViewById<View>(R.id.btnDebugPro).setOnClickListener {
                premiumManager.enableDebugPro()
                showMessage(getString(R.string.debug_access_enabled))
            }
            findViewById<View>(R.id.btnDebugClear).setOnClickListener {
                premiumManager.clearDebugEntitlements()
                showMessage(getString(R.string.debug_access_enabled))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    premiumStatusProvider.isPremium.collect { updateUi(it, premiumStatusProvider.isProUser()) }
                }
                launch {
                    premiumStatusProvider.isPro.collect { updateUi(premiumStatusProvider.isPremiumUser(), it) }
                }
            }
        }

        premiumStatusProvider.refreshEntitlements()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }


    private fun updateUi(isPremium: Boolean, isPro: Boolean) {
        statusText.text = when {
            isPro -> getString(R.string.subscription_pro)
            isPremium -> getString(R.string.subscription_premium)
            else -> getString(R.string.subscription_free)
        }
        premiumButton.visibility = if (isPremium || isPro) View.GONE else View.VISIBLE
        proButton.visibility = if (isPro) View.GONE else View.VISIBLE
        scannerButton.visibility = if (isPro) View.VISIBLE else View.GONE
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

