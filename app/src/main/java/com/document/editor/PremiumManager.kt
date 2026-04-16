package com.document.editor

import android.app.Activity
import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

class PremiumManager(context: Context) : PurchasesUpdatedListener, Closeable {
    private val appContext = context.applicationContext
    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium
    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro
    private val premiumProductId = "premium_upgrade"
    private val proMonthlyProductId = "pro_monthly"
    private val proYearlyProductId = "pro_yearly"
    private var isClosed = false
    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        connectBillingClient()
    }

    private fun connectBillingClient(onReady: (() -> Unit)? = null) {
        if (isClosed) {
            return
        }
        if (billingClient.isReady) {
            onReady?.invoke()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                AppDiagnostics.logBreadcrumb(
                    appContext,
                    "Billing setup finished: ${result.responseCode}"
                )
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    checkPurchases()
                    onReady?.invoke()
                }
            }

            override fun onBillingServiceDisconnected() {
                AppDiagnostics.logBreadcrumb(appContext, "Billing service disconnected")
            }
        })
    }

    private fun checkPurchases() {
        queryPurchaseStatus(BillingClient.ProductType.INAPP, setOf(premiumProductId), _isPremium)
        queryPurchaseStatus(BillingClient.ProductType.SUBS, setOf(proMonthlyProductId, proYearlyProductId), _isPro)
    }

    private fun queryPurchaseStatus(
        productType: String,
        validProductIds: Set<String>,
        stateFlow: MutableStateFlow<Boolean>
    ) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(productType)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                var hasValidPurchase = false
                for (purchase in purchases) {
                    val hasProduct = purchase.products.any { it in validProductIds }
                    if (hasProduct && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        hasValidPurchase = true
                        if (!purchase.isAcknowledged && productType == BillingClient.ProductType.INAPP) {
                            acknowledgePurchase(
                                purchase = purchase,
                                isProPurchase = validProductIds.contains(proMonthlyProductId) || validProductIds.contains(proYearlyProductId)
                            )
                        }
                    }
                }
                stateFlow.value = hasValidPurchase
                AppDiagnostics.logBreadcrumb(
                    appContext,
                    "Purchase status refreshed for $productType: $hasValidPurchase"
                )
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase, isProPurchase: Boolean) {
        val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(acknowledgeParams) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                if (isProPurchase) _isPro.value = true else _isPremium.value = true
                AppDiagnostics.logBreadcrumb(appContext, "Purchase acknowledged")
            }
        }
    }

    fun purchasePremium(activity: Activity) {
        AppDiagnostics.logBreadcrumb(appContext, "Launching premium purchase flow")
        launchBillingFlow(activity, premiumProductId, BillingClient.ProductType.INAPP)
    }

    fun purchasePro(activity: Activity) {
        AppDiagnostics.logBreadcrumb(appContext, "Launching pro purchase flow")
        launchBillingFlow(activity, proMonthlyProductId, BillingClient.ProductType.SUBS)
    }

    private fun launchBillingFlow(activity: Activity, productId: String, productType: String) {
        connectBillingClient {
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
                    return@queryProductDetailsAsync
                }

                val productDetails = productDetailsResult.productDetailsList.firstOrNull() ?: run {
                    AppDiagnostics.logBreadcrumb(appContext, "No product details returned for $productId")
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

                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(detailsParams.build()))
                    .build()
                billingClient.launchBillingFlow(activity, flowParams)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        AppDiagnostics.logBreadcrumb(appContext, "Purchases updated: ${result.responseCode}")
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    val isProPurchase = purchase.products.any { it == proMonthlyProductId || it == proYearlyProductId }
                    if (!purchase.isAcknowledged && !isProPurchase) {
                        acknowledgePurchase(purchase, isProPurchase)
                    } else {
                        if (isProPurchase) _isPro.value = true else _isPremium.value = true
                    }
                }
            }
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
}
