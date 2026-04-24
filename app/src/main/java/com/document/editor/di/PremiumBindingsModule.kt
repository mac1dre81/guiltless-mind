package com.document.editor.di
import com.document.editor.PremiumManager
import com.document.editor.PremiumStatusProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
/**
 * Exposes the app-wide premium status abstraction using the billing-backed PremiumManager so UI
 * surfaces can depend on a small, fakeable contract.
 */
@Module
@InstallIn(SingletonComponent::class)
object PremiumBindingsModule {
    @Provides
    @Singleton
    fun providePremiumStatusProvider(
        premiumManager: PremiumManager
    ): PremiumStatusProvider = premiumManager
}
