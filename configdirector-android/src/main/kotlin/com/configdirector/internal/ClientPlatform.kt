package com.configdirector.internal

import android.content.Context
import com.configdirector.CachingProvider
import com.configdirector.ClientOptions
import com.configdirector.ConfigDirectorWrapperApi
import com.configdirector.SdkIdentity
import com.configdirector.internal.lifecycle.AppLifecycleObserver
import com.configdirector.internal.lifecycle.appLifecycleObserver
import com.configdirector.internal.transport.SdkMetaContext
import com.configdirector.internal.transport.toSdkMetaContext

internal interface ClientPlatform {
    fun metaContext(): SdkMetaContext

    fun clientSession(): ClientSession

    fun lifecycleObserver(): AppLifecycleObserver

    fun cachingProvider(clientSdkKey: String): CachingProvider?
}

@OptIn(ConfigDirectorWrapperApi::class)
internal class AndroidClientPlatform(
    private val androidContext: Context,
    private val identity: SdkIdentity,
    private val options: ClientOptions,
) : ClientPlatform {
    override fun metaContext(): SdkMetaContext =
        identity.toSdkMetaContext(options.metadata.filledFromApplication(androidContext, options.logger))

    override fun clientSession(): ClientSession =
        currentClientSession(sharedPreferencesInstanceIdStorage(androidContext), options.logger)

    override fun lifecycleObserver(): AppLifecycleObserver = appLifecycleObserver(androidContext, options.logger)

    override fun cachingProvider(clientSdkKey: String): CachingProvider =
        SharedPreferencesCachingProvider(androidContext, clientSdkKey)
}
