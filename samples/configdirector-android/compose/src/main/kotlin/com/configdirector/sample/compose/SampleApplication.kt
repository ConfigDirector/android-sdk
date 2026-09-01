package com.configdirector.sample.compose

import android.app.Application
import com.configdirector.AndroidLogger
import com.configdirector.ClientOptions
import com.configdirector.ConfigDirectorClient
import com.configdirector.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The client belongs to the application: one instance, created once, initialized during startup and
 * shared by every screen.
 *
 * Nothing closes it. It lives as long as the process does, and Android reclaims everything when the
 * process ends -- `onTerminate` only runs on an emulator. Call `close()` when an app wants the
 * client gone before that, on sign-out for instance.
 */
class SampleApplication : Application() {

    lateinit var client: ConfigDirectorClient
        private set

    val hasSdkKey: Boolean = BuildConfig.CLIENT_SDK_KEY.isNotEmpty()

    private val scope: CoroutineScope = MainScope()

    override fun onCreate() {
        super.onCreate()

        client = ConfigDirectorClient(
            androidContext = this,
            clientSdkKey = BuildConfig.CLIENT_SDK_KEY.ifEmpty { PLACEHOLDER_SDK_KEY },
            // The SDK logs at WARN by default; turned up here so the connection can be followed
            // in logcat.
            options = ClientOptions.build { logger(AndroidLogger(LogLevel.DEBUG)) },
        )

        scope.launch { client.initialize(SampleUser.CONFIGURED.context) }
    }

    private companion object {
        // The client rejects a blank key, so with none configured the sample runs on a stand-in
        // that the server will not recognize, and says so on screen.
        private const val PLACEHOLDER_SDK_KEY = "no-client-sdk-key-configured"
    }
}
