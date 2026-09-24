package com.tsfdroid.ai.di

import android.content.Context
import com.tsfdroid.ai.core.security.AndroidProviderCredentialStore
import com.tsfdroid.ai.core.security.AndroidSensitiveMemoryStore
import com.tsfdroid.ai.core.security.AndroidUserProfileStore
import com.tsfdroid.ai.core.security.ProviderCredentialStore
import com.tsfdroid.ai.core.security.SensitiveMemoryStore
import com.tsfdroid.ai.core.security.UserProfileStore
import com.tsfdroid.ai.core.settings.AndroidAppSettingsStore
import com.tsfdroid.ai.core.settings.AppSettingsStore
import com.tsfdroid.ai.accessibility.AndroidCallFlowVerifier
import com.tsfdroid.ai.accessibility.CallFlowVerifier
import com.tsfdroid.ai.actions.AndroidMediaPlaybackVerifier
import com.tsfdroid.ai.actions.ActionDispatcher
import com.tsfdroid.ai.actions.MediaPlaybackVerifier
import com.tsfdroid.ai.core.agent.ActionSequenceExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideProviderCredentialStore(@ApplicationContext context: Context): ProviderCredentialStore {
        return AndroidProviderCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideUserProfileStore(@ApplicationContext context: Context): UserProfileStore {
        return AndroidUserProfileStore(context)
    }

    @Provides
    @Singleton
    fun provideSensitiveMemoryStore(@ApplicationContext context: Context): SensitiveMemoryStore {
        return AndroidSensitiveMemoryStore(context)
    }

    @Provides
    @Singleton
    fun provideAppSettingsStore(@ApplicationContext context: Context): AppSettingsStore {
        return AndroidAppSettingsStore(context)
    }

    @Provides
    @Singleton
    fun provideSocialCredentialStore(@ApplicationContext context: Context): com.tsfdroid.ai.core.security.SocialCredentialStore {
        return com.tsfdroid.ai.core.security.AndroidSocialCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideCallFlowVerifier(): CallFlowVerifier = AndroidCallFlowVerifier()

    @Provides
    @Singleton
    fun provideMediaPlaybackVerifier(): MediaPlaybackVerifier = AndroidMediaPlaybackVerifier()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideActionSequenceExecutor(
        actionDispatcher: dagger.Lazy<ActionDispatcher>
    ): ActionSequenceExecutor = ActionSequenceExecutor(
        executeAction = { action, params, context ->
            actionDispatcher.get().execute(action, params, context)
        },
        hasAction = { action -> actionDispatcher.get().hasAction(action) }
    )
}
