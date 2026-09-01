package com.configdirector

import android.content.Context
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigStore
import com.configdirector.internal.Constants
import com.configdirector.internal.lifecycle.AppLifecycleObserver
import com.configdirector.internal.lifecycle.AppLifecyclePhase
import com.configdirector.internal.lifecycle.appLifecycleObserver
import com.configdirector.internal.transport.PollingTransport
import com.configdirector.internal.transport.StreamingTransport
import com.configdirector.internal.transport.Transport
import com.configdirector.internal.telemetry.HttpEventReporter
import com.configdirector.internal.telemetry.TelemetryClient
import com.configdirector.internal.telemetry.TelemetryEventCollector
import com.configdirector.internal.transport.TransportOptions
import com.configdirector.internal.transport.sdkHttpClient
import com.configdirector.internal.transport.toSdkMetaContext
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The ConfigDirector SDK client.
 *
 * Applications should create a single instance and initialize it during startup.
 *
 * ```kotlin
 * val client = ConfigDirectorClient(application, "YOUR-SDK-KEY")
 * client.initialize(ConfigDirectorContext.build { id("user-123") })
 *
 * val darkMode = client.getBoolean("dark-mode", false)
 * ```
 *
 * ```java
 * ConfigDirectorClient client = new ConfigDirectorClient(application, "YOUR-SDK-KEY");
 * client.initialize(context, () -> {
 *   boolean darkMode = client.getBoolean("dark-mode", false);
 * });
 * ```
 *
 * After initialization, call [updateContext] to re-evaluate configs against a new context, and
 * [close] when the client is no longer needed. While the app is in the background the client pauses
 * its connection and resumes it on the way back, which
 * `ConnectionOptions.pausesWhileBackgrounded` turns off.
 *
 * @param androidContext any Android context; the application behind it is what the client watches
 *   to tell when the app is backgrounded
 * @param clientSdkKey the client SDK key from the ConfigDirector dashboard
 * @param options settings for this client, read once here
 * @throws ConfigDirectorValidationException if [clientSdkKey] is blank
 */
public class ConfigDirectorClient @JvmOverloads constructor(
    androidContext: Context,
    clientSdkKey: String,
    options: ClientOptions = ClientOptions.defaults(),
) : Closeable {

    private val logger: ConfigDirectorLogger = options.logger
    private val timeoutMillis: Long = options.connection.timeoutMillis
    private val store: ConfigStore
    private val telemetry: TelemetryClient
    private val httpClient: OkHttpClient
    private val transport: Transport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private val hasConnected = AtomicBoolean(false)
    private val pausedWhileBackgrounded = AtomicBoolean(false)
    private val pausesWhileBackgrounded: Boolean = options.connection.pausesWhileBackgrounded
    private val lifecycle: AppLifecycleObserver = appLifecycleObserver(androidContext, options.logger)

    init {
        if (clientSdkKey.isBlank()) {
            throw ConfigDirectorValidationException(
                "No client SDK key was provided. The client cannot be created without a valid " +
                    "client SDK key.",
            )
        }

        val baseUrl = options.connection.baseUrl?.trim().orEmpty().ifEmpty { Constants.CLIENT_BASE_URL }
        if (!baseUrl.startsWith("https://", ignoreCase = true)) {
            logger.warn {
                "The base URL '$baseUrl' is not HTTPS. The client SDK key, every context you " +
                    "send, and every config value served back travel in plain text."
            }
        }

        httpClient = sdkHttpClient(timeoutMillis, logger)

        val transportOptions = TransportOptions(
            clientSdkKey = clientSdkKey,
            baseUrl = baseUrl,
            metaContext = options.metadata.toSdkMetaContext(),
            instanceId = UUID.randomUUID().toString(),
            logger = logger,
            pollingIntervalMillis = options.connection.pollingIntervalMillis,
            httpClient = httpClient,
        )

        telemetry = TelemetryEventCollector(HttpEventReporter(transportOptions), logger)
        store = ConfigStore(logger, telemetry)
        transport = transportFor(options.connection.mode, transportOptions) { configSet ->
            store.handleConfigSet(configSet)
        }

        lifecycle.start(::handleLifecyclePhase)
    }

    /**
     * The context the client is currently evaluating configs against, or null when there is none.
     *
     * This does not change the moment [updateContext] is called: configs are evaluated against the
     * previous context until the underlying connection succeeds or times out.
     */
    public val context: ConfigDirectorContext?
        get() = store.context

    /**
     * Whether the client is ready, meaning the connection to the server succeeded and config state
     * was received.
     */
    public val isReady: Boolean
        get() = store.isReady

    /**
     * Whether the client is currently initializing. It is false on creation, true after
     * [initialize] is called, and false again once initialization completes.
     */
    public val isInitializing: Boolean
        get() = initializing.get()

    /**
     * Connects to ConfigDirector to retrieve config evaluations. Until initialization succeeds,
     * every config returns the default value passed to the accessor.
     *
     * @param context the current user's context, used to evaluate targeting rules
     */
    @JvmSynthetic
    public suspend fun initialize(context: ConfigDirectorContext? = null) {
        initializing.set(true)
        try {
            connect(context, ConnectReason.INITIALIZATION)
        } finally {
            initializing.set(false)
        }
    }

    /**
     * Connects to ConfigDirector to retrieve config evaluations, calling [callback] on the main
     * thread once the client is ready or the attempt times out.
     *
     * @param context the current user's context, or null to evaluate without one
     * @param callback told when the attempt finishes, whether or not it made the client ready
     */
    public fun initialize(context: ConfigDirectorContext?, callback: CompletionCallback) {
        launchThenCallBack(callback) { initialize(context) }
    }

    /** Updates the user's context and re-evaluates every config against it. */
    @JvmSynthetic
    public suspend fun updateContext(context: ConfigDirectorContext) {
        connect(context, ConnectReason.CONTEXT_UPDATE)
    }

    /**
     * Updates the user's context and re-evaluates every config against it, calling [callback] on
     * the main thread once the new context has taken effect or the attempt times out.
     */
    public fun updateContext(context: ConfigDirectorContext, callback: CompletionCallback) {
        launchThenCallBack(callback) { updateContext(context) }
    }

    /**
     * Evaluates [key] against the current context and targeting rules, reading the value as a
     * boolean.
     *
     * Returns [defaultValue] when config state is unavailable, for instance when called before
     * initialization completes, or when the served value cannot be read as a boolean.
     *
     * There is a method per type a config can be read as, matching the watches. Kotlin callers have
     * `client.value(key, default)`, which takes the type from the default value.
     *
     * ```kotlin
     * val darkMode = client.getBoolean("dark-mode", false)
     * ```
     *
     * ```java
     * boolean darkMode = client.getBoolean("dark-mode", false);
     * ```
     */
    public fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        store.getBoolean(key, defaultValue)

    /**
     * Evaluates [key], reading the value as a string. Every config can be read as one, including a
     * JSON config's raw document. See [getBoolean].
     */
    public fun getString(key: String, defaultValue: String): String =
        store.getString(key, defaultValue)

    /**
     * Evaluates [key], reading the value as a whole number that fits an `int`. A value written as a
     * decimal is truncated. See [getBoolean].
     */
    public fun getInt(key: String, defaultValue: Int): Int = store.getInt(key, defaultValue)

    /** Evaluates [key], reading the value as a finite decimal number. See [getBoolean]. */
    public fun getDouble(key: String, defaultValue: Double): Double =
        store.getDouble(key, defaultValue)

    /**
     * Evaluates [key], reading a JSON config's document as a map. The values inside are String,
     * Number, Boolean, List, Map, or null, and the document cannot be modified.
     *
     * Only a config declared as JSON in the ConfigDirector dashboard reads as one; read any other
     * config as a string to get its raw value. See [getBoolean].
     */
    public fun getJsonObject(
        key: String,
        defaultValue: Map<String, @JvmSuppressWildcards Any?>,
    ): Map<String, Any?> = store.getJsonObject(key, defaultValue)

    /**
     * Evaluates [key], reading a JSON config's document as a list. The values inside are String,
     * Number, Boolean, List, Map, or null, and the document cannot be modified. See [getBoolean].
     */
    public fun getJsonArray(
        key: String,
        defaultValue: List<@JvmSuppressWildcards Any?>,
    ): List<Any?> = store.getJsonArray(key, defaultValue)

    /**
     * Watches [key] for changes, which can come from an update in the ConfigDirector dashboard or
     * from a call to [updateContext].
     *
     * [listener] is handed the config's current value straight away and then every time the
     * evaluated value changes; consecutive identical values are not delivered again. Close the
     * returned subscription to stop watching.
     *
     */
    public fun watchBoolean(
        key: String,
        defaultValue: Boolean,
        listener: ConfigListener<Boolean>,
    ): Subscription = store.watch(key, listener) { store.getBoolean(key, defaultValue) }

    /** Watches [key] for changes, reading each value as a string. See [watchBoolean]. */
    public fun watchString(
        key: String,
        defaultValue: String,
        listener: ConfigListener<String>,
    ): Subscription = store.watch(key, listener) { store.getString(key, defaultValue) }

    /** Watches [key] for changes, reading each value as a whole number. See [watchBoolean]. */
    public fun watchInt(
        key: String,
        defaultValue: Int,
        listener: ConfigListener<Int>,
    ): Subscription = store.watch(key, listener) { store.getInt(key, defaultValue) }

    /** Watches [key] for changes, reading each value as a decimal number. See [watchBoolean]. */
    public fun watchDouble(
        key: String,
        defaultValue: Double,
        listener: ConfigListener<Double>,
    ): Subscription = store.watch(key, listener) { store.getDouble(key, defaultValue) }

    /** Watches [key] for changes, reading each JSON document as a map. See [watchBoolean]. */
    public fun watchJsonObject(
        key: String,
        defaultValue: Map<String, @JvmSuppressWildcards Any?>,
        listener: ConfigListener<Map<String, @JvmSuppressWildcards Any?>>,
    ): Subscription = store.watch(key, listener) { store.getJsonObject(key, defaultValue) }

    /** Watches [key] for changes, reading each JSON document as a list. See [watchBoolean]. */
    public fun watchJsonArray(
        key: String,
        defaultValue: List<@JvmSuppressWildcards Any?>,
        listener: ConfigListener<List<@JvmSuppressWildcards Any?>>,
    ): Subscription = store.watch(key, listener) { store.getJsonArray(key, defaultValue) }

    /**
     * Pauses the connection to the server without discarding config state, watches, or listeners.
     *
     * Reads keep serving the last config state the client received, and [isReady] turns false until
     * [resumeNetwork] reconnects. The client does this on its own while the app is backgrounded,
     * unless that was turned off with `ConnectionOptions.pausesWhileBackgrounded`.
     */
    public fun pauseNetwork() {
        logger.debug { "pauseNetwork() called, pausing the connection to the server" }
        transport.disconnect()
        store.markNotReady()
    }

    /**
     * Reconnects a connection paused by [pauseNetwork], re-evaluating configs against the context
     * the client already had.
     */
    @JvmSynthetic
    public suspend fun resumeNetwork() {
        connect(store.context, ConnectReason.NETWORK_RESUME)
    }

    /**
     * Reconnects a connection paused by [pauseNetwork], calling [callback] on the main thread once
     * the client is ready again or the attempt times out.
     */
    public fun resumeNetwork(callback: CompletionCallback) {
        launchThenCallBack(callback) { resumeNetwork() }
    }

    /**
     * Registers [listener] for everything the client does, from now on. Close the returned
     * subscription to stop listening.
     */
    public fun addEventListener(listener: ClientEventListener): Subscription =
        store.addEventListener(listener)

    /**
     * Registers [listener] for every config evaluation the client makes, from now on. One is
     * published for every read, so a config read from a Compose composable publishes one per
     * recomposition. Close the returned subscription to stop listening.
     */
    public fun addEvaluationListener(listener: EvaluationListener): Subscription =
        store.addEvaluationListener(listener)

    /**
     * Closes the connection to the server, along with every watch and listener registration.
     *
     * The client cannot be used afterwards: it stops receiving config state and never becomes ready
     * again, though reads keep serving the last config state it received.
     */
    override fun close() {
        if (closed.getAndSet(true)) return

        logger.debug { "close() called, closing the connection to the server" }
        lifecycle.stop()
        telemetry.close()
        store.close()
        transport.close()
        scope.cancel()
        // The HTTP client is left to wind itself down: telemetry reports what it collected as the
        // client closes, and shutting the executor out from under that request would drop it. Idle
        // threads and connections are reaped on their own.
    }

    @get:JvmSynthetic
    internal val closedState: StateFlow<Boolean>
        get() = store.closed

    private fun launchThenCallBack(callback: CompletionCallback, work: suspend () -> Unit) {
        scope.launch {
            work()
            withContext(Dispatchers.Main) { callback.onComplete() }
        }
    }

    private suspend fun connect(context: ConfigDirectorContext?, reason: ConnectReason) {
        if (closed.get()) {
            logger.warn { "The client is closed, so ${reason.description} was not attempted." }
            return
        }

        store.beginConnect(reason)
        val startedAt = System.nanoTime()

        try {
            transport.connect(context ?: ConfigDirectorContext.empty(), timeoutMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.error(failure) { "An error occurred during ${reason.description}" }
            return
        }

        hasConnected.set(true)
        telemetry.updateContext(context)
        store.setContext(context)

        val remaining = timeoutMillis - (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        if (remaining > 0) {
            store.waitUntilReady(remaining)
        }

        if (!store.isReady) {
            logger.warn {
                "Timed out waiting for ${reason.description} after ${timeoutMillis}ms. Configs " +
                    "return their default value until the connection succeeds."
            }
        }
    }

    private fun handleLifecyclePhase(phase: AppLifecyclePhase) {
        when (phase) {
            AppLifecyclePhase.BACKGROUND -> {
                // The app may not come back, so telemetry goes out at the first sign of it leaving.
                scope.launch { telemetry.flush() }

                if (closed.get() || !hasConnected.get() || !pausesWhileBackgrounded) return
                if (pausedWhileBackgrounded.getAndSet(true)) return

                logger.info { "The app entered the background, pausing the connection to the server" }
                pauseNetwork()
            }

            AppLifecyclePhase.FOREGROUND -> {
                if (closed.get() || !pausedWhileBackgrounded.getAndSet(false)) return

                logger.info {
                    "The app returned to the foreground, resuming the connection to the server"
                }
                scope.launch { resumeNetwork() }
            }
        }
    }

    private companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        private fun transportFor(
            mode: ConnectionMode,
            options: TransportOptions,
            onConfigSet: (ConfigSet) -> Unit,
        ): Transport = when (mode) {
            ConnectionMode.STREAMING -> StreamingTransport(options, onConfigSet)
            ConnectionMode.POLLING -> PollingTransport(options, onConfigSet = onConfigSet)
            ConnectionMode.ONE_TIME -> PollingTransport.oneTime(options, onConfigSet)
        }
    }
}
