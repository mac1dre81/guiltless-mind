package com.document.editor.fakes

import com.document.editor.PremiumStatusProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-only premium state fake that lets instrumentation tests toggle entitlements without talking
 * to Play Billing or shared preferences.
 */
class FakePremiumStatusProvider(
    initialPremium: Boolean = false,
    initialPro: Boolean = false
) : PremiumStatusProvider {
    private val premiumFlow = MutableStateFlow(initialPremium || initialPro)
    private val proFlow = MutableStateFlow(initialPro)

    override val isPremium: StateFlow<Boolean> = premiumFlow.asStateFlow()
    override val isPro: StateFlow<Boolean> = proFlow.asStateFlow()

    override fun isPremiumUser(): Boolean = isPremium.value

    override fun isProUser(): Boolean = isPro.value

    override fun refreshEntitlements() = Unit

    fun setEntitlements(isPremiumUser: Boolean, isProUser: Boolean) {
        proFlow.value = isProUser
        premiumFlow.value = isPremiumUser || isProUser
    }
}

