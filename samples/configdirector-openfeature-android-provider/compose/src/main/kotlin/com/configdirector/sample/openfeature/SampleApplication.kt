package com.configdirector.sample.openfeature

import android.app.Application
import com.configdirector.AndroidLogger
import com.configdirector.ClientOptions
import com.configdirector.LogLevel
import com.configdirector.openfeature.ConfigDirectorProvider
import dev.openfeature.kotlin.sdk.OpenFeatureAPI

/**
 * The provider is registered once, during startup, and the OpenFeature SDK owns it from then on:
 * every screen reads flags through `OpenFeatureAPI.getClient()` rather than being handed anything.
 *
 * Nothing shuts it down. It lives as long as the process does, and Android reclaims everything
 * when the process ends -- `onTerminate` only runs on an emulator. Call `OpenFeatureAPI.shutdown()`
 * when an app wants the connection gone before that, on sign-out for instance.
 */
class SampleApplication : Application() {

    val hasSdkKey: Boolean = BuildConfig.CLIENT_SDK_KEY.isNotEmpty()

    override fun onCreate() {
        super.onCreate()

        val provider = ConfigDirectorProvider(
            androidContext = this,
            clientSdkKey = BuildConfig.CLIENT_SDK_KEY.ifEmpty { PLACEHOLDER_SDK_KEY },
            // The SDK logs at WARN by default; turned up here so the connection can be followed
            // in logcat.
            options = ClientOptions.build { logger(AndroidLogger(LogLevel.DEBUG)) },
        )

        // Returns at once and initializes in the background; the screen follows the status.
        OpenFeatureAPI.setProvider(provider, initialContext = SampleUser.CONFIGURED.context)
    }

    private companion object {
        // The provider rejects a blank key, so with none configured the sample runs on a stand-in
        // that the server will not recognize, and says so on screen.
        private const val PLACEHOLDER_SDK_KEY = "no-client-sdk-key-configured"
    }
}
