package com.document.editor
import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
class PremiumManager(private val context: Context) : PurchasesUpdatedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium
    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro
    private val PREMIUM_ID = "premium_upgrade"
    private val PRO_MONTHLY_ID = "pro_monthly"
    private val PRO_YEARLY_ID = "pro_yearly"
    private var billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()
    init {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    checkPurchases()
                }
            }
            override fun onBillingServiceDisconnected() {
                // Retry connection logic could go here
            }
        })
    }
    private fun checkPurchases() {
        queryPurchaseStatus(BillingClient.ProductType.INAPP, setOf(PREMIUM_ID), _isPremium)
        queryPurchaseStatus(BillingClient.ProductType.SUBS, setOf(PRO_MONTHLY_ID, PRO_YEARLY_ID), _isPro)
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
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase, validProductIds == setOf(PRO_MONTHLY_ID, PRO_YEARLY_ID))
                        }
                    }
                }
                stateFlow.value = hasValidPurchase
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
            }
        }
    }
    fun purchasePremium(activity: Activity) {
        launchBillingFlow(activity, PREMIUM_ID, BillingClient.ProductType.INAPP)
    }
    fun purchasePro(activity: Activity) {
        // defaulting to monthly for MVP
        launchBillingFlow(activity, PRO_MONTHLY_ID, BillingClient.ProductType.SUBS)
    }
    private fun launchBillingFlow(activity: Activity, productId: String, productType: String) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        scope.launch {
            val result = billingClient.queryProductDetails(params)
            val productDetailsList = result.productDetailsList ?: emptyList()
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val detailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetailsList[0])
                // Subs need an offer token
                val offerDetails = productDetailsList[0].subscriptionOfferDetails
                if (productType == BillingClient.ProductType.SUBS && offerDetails != null && offerDetails.isNotEmpty()) {
                    detailsParams.setOfferToken(offerDetails[0].offerToken)
                }
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(detailsParams.build()))
                    .build()
                billingClient.launchBillingFlow(activity, flowParams)
            }
        }
    }
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    val isProPurchase = purchase.products.any { it in setOf(PRO_MONTHLY_ID, PRO_YEARLY_ID) }
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase, isProPurchase)
                    } else {
                        if (isProPurchase) _isPro.value = true else _isPremium.value = true
                    }
                }
            }
        }
    }
}
