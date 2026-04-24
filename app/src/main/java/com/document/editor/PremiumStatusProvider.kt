package com.document.editor

import kotlinx.coroutines.flow.StateFlow

/**
 * Provides the current premium/pro entitlement state without exposing billing implementation
 * details to every UI surface.
 */
interface PremiumStatusProvider {
    val isPremium: StateFlow<Boolean>
    val isPro: StateFlow<Boolean>

    fun isPremiumUser(): Boolean

    fun isProUser(): Boolean

    fun refreshEntitlements()
}

