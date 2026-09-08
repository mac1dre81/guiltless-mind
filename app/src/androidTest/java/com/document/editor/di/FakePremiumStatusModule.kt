package com.document.editor.di

import com.document.editor.PremiumStatusProvider
import com.document.editor.fakes.FakePremiumStatusProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces the production premium status binding during androidTest runs so UI tests can drive
 * entitlement state deterministically.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [PremiumBindingsModule::class]
)
object FakePremiumStatusModule {
    @Provides
    @Singleton
    fun provideFakePremiumStatusProvider(): FakePremiumStatusProvider {
        return FakePremiumStatusProvider()
    }

    @Provides
    @Singleton
    fun providePremiumStatusProvider(
        fakePremiumStatusProvider: FakePremiumStatusProvider
    ): PremiumStatusProvider = fakePremiumStatusProvider
}

