package com.document.editor

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumManager @Inject constructor(
    @ApplicationContext context: Context
) : PremiumStatusProvider, PurchasesUpdatedListener, Closeable {
    private val appContext = context.applicationContext
    private val sharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _isPremium = MutableStateFlow(false)
    override val isPremium: StateFlow<Boolean> = _isPremium
    private val _isPro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> = _isPro
    private val premiumProductId = "premium_upgrade"
    private val proMonthlyProductId = "pro_monthly"
    private val proYearlyProductId = "pro_yearly"
    private var hasPremiumPurchase = false
    private var hasProPurchase = false
    private var debugPremium = sharedPreferences.getBoolean(KEY_DEBUG_PREMIUM, false)
    private var debugPro = sharedPreferences.getBoolean(KEY_DEBUG_PRO, false)
    private var isClosed = false
    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        recomputeEntitlements()
        connectBillingClient()
    }

    override fun isPremiumUser(): Boolean = _isPremium.value
    override fun isProUser(): Boolean = _isPro.value
    override fun refreshEntitlements() {
        connectBillingClient(onReady = ::checkPurchases)
    }

    fun purchasePremium(activity: Activity, onMessage: (String) -> Unit = {}) {
        if (_isPremium.value || _isPro.value) {
            onMessage(appContext.getString(R.string.billing_already_premium))
            return
        }
        AppDiagnostics.logBreadcrumb(appContext, "Launching premium purchase flow")
        launchBillingFlow(activity, premiumProductId, BillingClient.ProductType.INAPP, onMessage)
    }

    fun purchasePro(activity: Activity, onMessage: (String) -> Unit = {}) {
        if (_isPro.value) {
            onMessage(appContext.getString(R.string.billing_already_pro))
            return
        }
        AppDiagnostics.logBreadcrumb(appContext, "Launching pro purchase flow")
        launchBillingFlow(activity, proMonthlyProductId, BillingClient.ProductType.SUBS, onMessage)
    }

    fun enableDebugPremium() {
        if (!BuildConfig.DEBUG) return
        debugPremium = true
        sharedPreferences.edit { putBoolean(KEY_DEBUG_PREMIUM, true) }
        recomputeEntitlements()
        AppDiagnostics.logBreadcrumb(appContext, "Debug Premium entitlement enabled")
    }

    fun enableDebugPro() {
        if (!BuildConfig.DEBUG) return
        debugPro = true
        sharedPreferences.edit { putBoolean(KEY_DEBUG_PRO, true) }
        recomputeEntitlements()
        AppDiagnostics.logBreadcrumb(appContext, "Debug Pro entitlement enabled")
    }

    fun clearDebugEntitlements() {
        debugPremium = false
        debugPro = false
        sharedPreferences.edit {
            putBoolean(KEY_DEBUG_PREMIUM, false)
            putBoolean(KEY_DEBUG_PRO, false)
        }
        recomputeEntitlements()
        AppDiagnostics.logBreadcrumb(appContext, "Debug entitlements cleared")
    }

    private fun connectBillingClient(onReady: (() -> Unit)? = null, onUnavailable: ((String) -> Unit)? = null) {
        if (isClosed) {
            return
        }
        if (billingClient.isReady) {
            onReady?.invoke()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                AppDiagnostics.logBreadcrumb(appContext, "Billing setup finished: ${result.responseCode}")
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    checkPurchases()
                    onReady?.invoke()
                } else {
                    onUnavailable?.invoke(
                        buildBillingMessage(
                            result.responseCode,
                            R.string.billing_unavailable_message
                        )
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                AppDiagnostics.logBreadcrumb(appContext, "Billing service disconnected")
            }
        })
    }

    private fun checkPurchases() {
        queryPurchaseStatus(BillingClient.ProductType.INAPP, setOf(premiumProductId)) { hasPurchase ->
            hasPremiumPurchase = hasPurchase
            recomputeEntitlements()
        }
        queryPurchaseStatus(
            BillingClient.ProductType.SUBS,
            setOf(proMonthlyProductId, proYearlyProductId)
        ) { hasPurchase ->
            hasProPurchase = hasPurchase
            recomputeEntitlements()
        }
    }

    private fun queryPurchaseStatus(
        productType: String,
        validProductIds: Set<String>,
        onStatus: (Boolean) -> Unit
    ) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(productType)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                AppDiagnostics.logBreadcrumb(
                    appContext,
                    "Purchase status query failed for $productType: ${result.responseCode}"
                )
                onStatus(false)
                return@queryPurchasesAsync
            }
            var hasValidPurchase = false
            purchases.forEach { purchase ->
                val hasProduct = purchase.products.any { it in validProductIds }
                if (hasProduct && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    hasValidPurchase = true
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }
                }
            }
            onStatus(hasValidPurchase)
            AppDiagnostics.logBreadcrumb(appContext, "Purchase status refreshed for $productType: $hasValidPurchase")
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(acknowledgeParams) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                if (purchase.products.any { it == proMonthlyProductId || it == proYearlyProductId }) {
                    hasProPurchase = true
                }
                if (purchase.products.any { it == premiumProductId }) {
                    hasPremiumPurchase = true
                }
                recomputeEntitlements()
                AppDiagnostics.logBreadcrumb(appContext, "Purchase acknowledged")
            }
        }
    }

    private fun launchBillingFlow(
        activity: Activity,
        productId: String,
        productType: String,
        onMessage: (String) -> Unit
    ) {
        connectBillingClient(onReady = {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(productType)
                            .build()
                    )
                )
                .build()
            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppDiagnostics.logBreadcrumb(
                        appContext,
                        "Product details query failed for $productId: ${billingResult.responseCode}"
                    )
                    onMessage(buildBillingMessage(billingResult.responseCode, R.string.billing_product_unavailable))
                    return@queryProductDetailsAsync
                }
                val productDetails = productDetailsResult.productDetailsList.firstOrNull()
                if (productDetails == null) {
                    AppDiagnostics.logBreadcrumb(appContext, "No product details returned for $productId")
                    onMessage(
                        buildBillingMessage(
                            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                            R.string.billing_product_unavailable
                        )
                    )
                    return@queryProductDetailsAsync
                }
                val detailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                if (productType == BillingClient.ProductType.SUBS) {
                    val offerToken = productDetails.subscriptionOfferDetails
                        ?.firstOrNull()
                        ?.offerToken
                    if (!offerToken.isNullOrBlank()) {
                        detailsParams.setOfferToken(offerToken)
                    }
                }
                val launchResult = billingClient.launchBillingFlow(
                    activity,
                    BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(detailsParams.build()))
                        .build()
                )
                if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppDiagnostics.logBreadcrumb(appContext, "Billing flow launch failed: ${launchResult.responseCode}")
                    onMessage(buildBillingMessage(launchResult.responseCode, R.string.billing_launch_failed))
                }
            }
        }, onUnavailable = onMessage)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        AppDiagnostics.logBreadcrumb(appContext, "Purchases updated: ${result.responseCode}")
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            return
        }
        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (purchase.products.any { it == proMonthlyProductId || it == proYearlyProductId }) {
                    hasProPurchase = true
                }
                if (purchase.products.any { it == premiumProductId }) {
                    hasPremiumPurchase = true
                }
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }
            }
        }
        recomputeEntitlements()
    }

    private fun recomputeEntitlements() {
        val proEnabled = hasProPurchase || debugPro
        val premiumEnabled = proEnabled || hasPremiumPurchase || debugPremium
        _isPro.value = proEnabled
        _isPremium.value = premiumEnabled
    }

    private fun buildBillingMessage(responseCode: Int, fallbackRes: Int): String {
        val base = when (responseCode) {
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> appContext.getString(R.string.billing_product_unavailable)
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> appContext.getString(R.string.billing_unavailable_message)

            else -> appContext.getString(fallbackRes)
        }
        return if (BuildConfig.DEBUG) {
            "$base ${appContext.getString(R.string.billing_debug_hint)}"
        } else {
            base
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }
        isClosed = true
        AppDiagnostics.logBreadcrumb(appContext, "Closing billing client")
        runCatching { billingClient.endConnection() }
    }

    private companion object {
        const val PREFS_NAME = "premium_debug_overrides"
        const val KEY_DEBUG_PREMIUM = "debug_premium"
        const val KEY_DEBUG_PRO = "debug_pro"
    }
}
