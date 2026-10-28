package com.configdirector

import android.content.Context
import com.configdirector.internal.AndroidClientPlatform
import com.configdirector.internal.CachingPlan
import com.configdirector.internal.ClientPlatform
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigStore
import com.configdirector.internal.ConnectionPlan
import com.configdirector.internal.ContextAdmission
import com.configdirector.internal.LiveConnectionMode
import com.configdirector.internal.admissionOf
import com.configdirector.internal.lifecycle.AppLifecycleObserver
import com.configdirector.internal.lifecycle.AppLifecyclePhase
import com.configdirector.internal.lifecycle.NoLifecycleObserver
import com.configdirector.internal.planCaching
import com.configdirector.internal.planConnection
import com.configdirector.internal.transport.OfflineTransport
import com.configdirector.internal.transport.PollingTransport
import com.configdirector.internal.transport.StreamingTransport
import com.configdirector.internal.transport.Transport
import com.configdirector.internal.telemetry.DiscardingTelemetryClient
import com.configdirector.internal.telemetry.HttpEventReporter
import com.configdirector.internal.telemetry.TelemetryClient
import com.configdirector.internal.telemetry.TelemetryEventCollector
import com.configdirector.internal.transport.TransportOptions
import com.configdirector.internal.transport.sdkHttpClient
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 */
public class ConfigDirectorClient internal constructor(
    clientSdkKey: String?,
    options: ClientOptions,
    platform: ClientPlatform,
    telemetryFactory: TelemetryFactory,
    transportFactory: TransportFactory,
) : Closeable {

    /**
     * Creates a client.
     *
     * @param androidContext any Android context; the application behind it is what the client
     *   watches to tell when the app is backgrounded
     * @param clientSdkKey the client SDK key from the ConfigDirector dashboard
     * @param options settings for this client, read once here. Whichever of the app name and
     *   version its [Metadata] leaves unset is read from the application behind [androidContext]
     * @throws ConfigDirectorValidationException if [clientSdkKey] is blank and the connection mode
     *   is not [ConnectionMode.OFFLINE]
     */
    @OptIn(ConfigDirectorWrapperApi::class)
    @JvmOverloads
    public constructor(
        androidContext: Context,
        clientSdkKey: String,
        options: ClientOptions = ClientOptions.defaults(),
    ) : this(
        clientSdkKey,
        options,
        AndroidClientPlatform(androidContext, SdkIdentity.ANDROID_CLIENT_SDK, options),
        ::telemetryFor,
        ::transportFor,
    )

    /**
     * Creates a client that needs no client SDK key, because its connection mode is
     * [ConnectionMode.OFFLINE]: it never connects to ConfigDirector and serves the values in
     * [options].
     *
     * ```kotlin
     * val client = ConfigDirectorClient(
     *     application,
     *     ClientOptions.build {
     *         connection {
     *             mode(ConnectionMode.OFFLINE)
     *             values(mapOf("new-checkout" to true))
     *         }
     *     },
     * )
     * ```
     *
     * @param androidContext any Android context
     * @param options settings for this client, read once here, whose connection mode is
     *   [ConnectionMode.OFFLINE]
     * @throws ConfigDirectorValidationException if the connection mode is not
     *   [ConnectionMode.OFFLINE], since every other mode needs a client SDK key
     */
    @OptIn(ConfigDirectorWrapperApi::class)
    public constructor(
        androidContext: Context,
        options: ClientOptions,
    ) : this(
        null,
        options,
        AndroidClientPlatform(androidContext, SdkIdentity.ANDROID_CLIENT_SDK, options),
        ::telemetryFor,
        ::transportFor,
    )

    @OptIn(ConfigDirectorWrapperApi::class)
    internal constructor(
        androidContext: Context,
        clientSdkKey: String,
        options: ClientOptions,
        transportFactory: TransportFactory,
    ) : this(
        clientSdkKey,
        options,
        AndroidClientPlatform(androidContext, SdkIdentity.ANDROID_CLIENT_SDK, options),
        ::telemetryFor,
        transportFactory,
    )

    /**
     * Creates a client that reports [identity] to the server instead of this SDK's own name and
     * version. Otherwise identical to the constructor without it.
     *
     * This is how a wrapper maintained by ConfigDirector, such as the OpenFeature provider, shows
     * up as itself in the server's SDK usage. An application has no use for it.
     *
     * @param androidContext any Android context; the application behind it is what the client
     *   watches to tell when the app is backgrounded
     * @param clientSdkKey the client SDK key from the ConfigDirector dashboard
     * @param options settings for this client, read once here
     * @param identity the wrapper the client reports itself as
     * @throws ConfigDirectorValidationException if [clientSdkKey] is blank and the connection mode
     *   is not [ConnectionMode.OFFLINE]
     */
    @ConfigDirectorWrapperApi
    public constructor(
        androidContext: Context,
        clientSdkKey: String,
        options: ClientOptions,
        identity: SdkIdentity,
    ) : this(
        clientSdkKey,
        options,
        AndroidClientPlatform(androidContext, identity, options),
        ::telemetryFor,
        ::transportFor,
    )

    private val logger: ConfigDirectorLogger = options.logger
    private val timeoutMillis: Long = options.connection.timeoutMillis
    private val connectionPlan: ConnectionPlan = planConnection(clientSdkKey, options.connection, logger)
    private val store: ConfigStore
    private val telemetry: TelemetryClient
    private val transport: Transport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private val hasConnected = AtomicBoolean(false)
    private val pausedWhileBackgrounded = AtomicBoolean(false)
    private val pausesWhileBackgrounded: Boolean = options.connection.pausesWhileBackgrounded
    private val lifecycle: AppLifecycleObserver

    init {
        when (val plan = connectionPlan) {
            is ConnectionPlan.Connect -> {
                val transportOptions = TransportOptions(
                    clientSdkKey = plan.clientSdkKey,
                    baseUrl = plan.baseUrl,
                    metaContext = platform.metaContext(),
                    clientSession = platform.clientSession(),
                    logger = logger,
                    pollingIntervalMillis = plan.pollingIntervalMillis,
                    httpClient = sdkHttpClient(timeoutMillis, logger),
                )
                telemetry = telemetryFactory(transportOptions)
                val caching = planCaching(
                    options.caching,
                    plan.clientSdkKey,
                    transportOptions.clientSession.instanceId,
                    platformProvider = platform::cachingProvider,
                    logger = logger,
                )
                store = ConfigStore(logger, telemetry, caching)
                transport = transportFactory(plan.mode, transportOptions) { configSet ->
                    store.handleConfigSet(configSet)
                }
                lifecycle = platform.lifecycleObserver()
            }

            is ConnectionPlan.StayOffline -> {
                telemetry = DiscardingTelemetryClient
                store = ConfigStore(logger, telemetry, CachingPlan.KeepNothing)
                transport = OfflineTransport(plan.configs) { configSet -> store.handleConfigSet(configSet) }
                lifecycle = NoLifecycleObserver
            }
        }

        lifecycle.start(::handleLifecyclePhase)
    }

    /**
     * The context the client is currently evaluating configs against, or null when there is none,
     * including after [initialize] or [updateContext] ignored a context with an invalid context
     * kind.
     *
     * This does not change the moment [updateContext] is called: configs are evaluated against the
     * previous context until the connection for the new one succeeds or, if it times out, until the
     * values for the new one arrive.
     */
    public val context: ConfigDirectorContext?
        get() = store.context

    /**
     * Whether the client is ready, meaning the connection to the server succeeded and config state
     * was received. In [ConnectionMode.OFFLINE] it is ready once [initialize] has loaded the
     * offline values.
     */
    public val isReady: Boolean
        get() = store.isReady

    /**
     * Whether the client is trying to get its very first config state from the server. It is false
     * on creation, becomes true when [initialize] is called on a client that has never received
     * config state, stays true through timeouts and retries, and becomes false when the first config
     * state arrives, when an unrecoverable connection error stops the retries, or on [close].
     * [updateContext] and [resumeNetwork] never set it, and once config state has been received it
     * is never true again.
     */
    public val isInitializing: Boolean
        get() = initializing.get() && !store.hasReceivedConfigSet

    /**
     * Connects to ConfigDirector to retrieve config evaluations. Until initialization succeeds,
     * every config returns the default value passed to the accessor.
     *
     * A context whose [ConfigDirectorContext.kind] is invalid is ignored as a whole: the client logs
     * an error and connects as if no context was given.
     *
     * In [ConnectionMode.OFFLINE] it opens no connection: the client is ready with the offline
     * values when it returns.
     *
     * @param context the current user's context, used to evaluate targeting rules
     */
    @JvmSynthetic
    public suspend fun initialize(context: ConfigDirectorContext? = null) {
        initializing.set(true)
        val launchContext = contextToConnectWith(context, ConnectReason.INITIALIZATION)
        store.adoptCachedValuesOnce(launchContext)
        connect(launchContext, ConnectReason.INITIALIZATION)
    }

    /**
     * Connects to ConfigDirector to retrieve config evaluations, calling [callback] on the main
     * thread once the client is ready or the attempt times out.
     *
     * A context whose [ConfigDirectorContext.kind] is invalid is ignored as a whole: the client logs
     * an error and connects as if no context was given.
     *
     * In [ConnectionMode.OFFLINE] it opens no connection: the client is ready with the offline
     * values when [callback] is called.
     *
     * @param context the current user's context, or null to evaluate without one
     * @param callback told when the attempt finishes, whether or not it made the client ready
     */
    public fun initialize(context: ConfigDirectorContext?, callback: CompletionCallback) {
        launchThenCallBack(callback) { initialize(context) }
    }

    /**
     * Updates the user's context and re-evaluates every config against it.
     *
     * A context whose [ConfigDirectorContext.kind] is invalid is ignored as a whole: the client logs
     * an error, stops using the previous context, and re-evaluates as if no context was given.
     *
     * In [ConnectionMode.OFFLINE] the new context takes effect at once and no value changes.
     */
    @JvmSynthetic
    public suspend fun updateContext(context: ConfigDirectorContext) {
        val admittedContext = contextToConnectWith(context, ConnectReason.CONTEXT_UPDATE)
        when (connectionPlan) {
            is ConnectionPlan.Connect -> connect(admittedContext, ConnectReason.CONTEXT_UPDATE)
            is ConnectionPlan.StayOffline -> adoptContext(admittedContext)
        }
    }

    /**
     * Updates the user's context and re-evaluates every config against it, calling [callback] on
     * the main thread once the new context has taken effect or the attempt times out.
     *
     * A context whose [ConfigDirectorContext.kind] is invalid is ignored as a whole: the client logs
     * an error, stops using the previous context, and re-evaluates as if no context was given.
     *
     * In [ConnectionMode.OFFLINE] the new context takes effect at once and no value changes.
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
     * Evaluates [key] the way [getBoolean] does, and says why it evaluated the way it did.
     *
     * The returned [ConfigEvaluation.value] is what [getBoolean] would have returned, so it is
     * [defaultValue] whenever the config could not be served as a boolean, and
     * [ConfigEvaluation.reason] says why. Use this where the reason matters at the point of the
     * read, such as when reporting an evaluation to another system; [getBoolean] is enough
     * everywhere else. Like every read, it is published to the evaluation listeners and counted
     * in telemetry.
     *
     * There is a method per type a config can be read as, matching the getters. Kotlin callers have
     * `client.evaluate(key, default)`, which takes the type from the default value.
     *
     * ```kotlin
     * val evaluation = client.evaluateBoolean("dark-mode", false)
     * if (evaluation.reason == EvaluationReason.CLIENT_NOT_READY) { ... }
     * ```
     *
     * ```java
     * ConfigEvaluation evaluation = client.evaluateBoolean("dark-mode", false);
     * boolean darkMode = (Boolean) evaluation.getValue();
     * ```
     */
    public fun evaluateBoolean(key: String, defaultValue: Boolean): ConfigEvaluation =
        store.evaluateBoolean(key, defaultValue)

    /** Evaluates [key] the way [getString] does, and says why. See [evaluateBoolean]. */
    public fun evaluateString(key: String, defaultValue: String): ConfigEvaluation =
        store.evaluateString(key, defaultValue)

    /** Evaluates [key] the way [getInt] does, and says why. See [evaluateBoolean]. */
    public fun evaluateInt(key: String, defaultValue: Int): ConfigEvaluation =
        store.evaluateInt(key, defaultValue)

    /** Evaluates [key] the way [getDouble] does, and says why. See [evaluateBoolean]. */
    public fun evaluateDouble(key: String, defaultValue: Double): ConfigEvaluation =
        store.evaluateDouble(key, defaultValue)

    /** Evaluates [key] the way [getJsonObject] does, and says why. See [evaluateBoolean]. */
    public fun evaluateJsonObject(
        key: String,
        defaultValue: Map<String, @JvmSuppressWildcards Any?>,
    ): ConfigEvaluation = store.evaluateJsonObject(key, defaultValue)

    /** Evaluates [key] the way [getJsonArray] does, and says why. See [evaluateBoolean]. */
    public fun evaluateJsonArray(
        key: String,
        defaultValue: List<@JvmSuppressWildcards Any?>,
    ): ConfigEvaluation = store.evaluateJsonArray(key, defaultValue)

    /**
     * Watches [key] for changes, which can come from an update in the ConfigDirector dashboard or
     * from a call to [updateContext].
     *
     * [listener] is handed the config's current value straight away and then every time the
     * evaluated value changes; consecutive identical values are not delivered again. When a full
     * update no longer carries [key], the listener is handed [defaultValue], as a getter would now
     * return it. Close the returned subscription to stop watching.
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
     *
     * In [ConnectionMode.OFFLINE], which has no connection, it does nothing.
     */
    public fun pauseNetwork() {
        when (connectionPlan) {
            is ConnectionPlan.Connect -> {
                logger.debug { "pauseNetwork() called, pausing the connection to the server" }
                transport.disconnect()
                store.markNotReady()
            }

            is ConnectionPlan.StayOffline -> Unit
        }
    }

    /**
     * Reconnects a connection paused by [pauseNetwork], re-evaluating configs against the context
     * the client already had. In [ConnectionMode.OFFLINE], which has no connection, it does
     * nothing.
     */
    @JvmSynthetic
    public suspend fun resumeNetwork() {
        when (connectionPlan) {
            is ConnectionPlan.Connect -> connect(store.context, ConnectReason.NETWORK_RESUME)
            is ConnectionPlan.StayOffline -> Unit
        }
    }

    /**
     * Reconnects a connection paused by [pauseNetwork], calling [callback] on the main thread once
     * the client is ready again or the attempt times out. In [ConnectionMode.OFFLINE], which has no
     * connection, it only calls [callback].
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
        initializing.set(false)
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

    private fun contextToConnectWith(
        suppliedContext: ConfigDirectorContext?,
        reason: ConnectReason,
    ): ConfigDirectorContext? = when (val admission = admissionOf(suppliedContext)) {
        is ContextAdmission.Admitted -> admission.context
        is ContextAdmission.IgnoredForAnInvalidContextKind -> {
            logger.error {
                "Ignored the context supplied for ${reason.description} because its context kind " +
                    "'${admission.contextKindSample}' is invalid, so configs are evaluated as if no " +
                    "context was supplied. A context kind has 1 to 64 characters: lowercase " +
                    "letters, digits, '-' and '_'."
            }
            telemetry.recordInvalidContextSubmission(admission.contextKindSample)
            null
        }
    }

    private suspend fun connect(context: ConfigDirectorContext?, reason: ConnectReason) {
        if (closed.get()) {
            logger.warn { "The client is closed, so ${reason.description} was not attempted." }
            return
        }

        val attempt = store.beginConnect(reason, context) {
            hasConnected.set(true)
            adoptContext(context)
        }
        val startedAt = System.nanoTime()

        try {
            transport.connect(context ?: ConfigDirectorContext.empty(), timeoutMillis, reason)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            initializing.set(false)
            logger.error(failure) { "An error occurred during ${reason.description}" }
            return
        }

        attempt.markConnected()

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

    private fun adoptContext(context: ConfigDirectorContext?) {
        telemetry.updateContext(context)
        store.setContext(context)
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
    }
}

internal typealias TelemetryFactory = (options: TransportOptions) -> TelemetryClient

internal fun telemetryFor(options: TransportOptions): TelemetryClient =
    TelemetryEventCollector(HttpEventReporter(options), options.logger)

internal typealias TransportFactory =
    (mode: LiveConnectionMode, options: TransportOptions, onConfigSet: (ConfigSet) -> Unit) -> Transport

internal fun transportFor(
    mode: LiveConnectionMode,
    options: TransportOptions,
    onConfigSet: (ConfigSet) -> Unit,
): Transport = when (mode) {
    LiveConnectionMode.STREAMING -> StreamingTransport(options, onConfigSet)
    LiveConnectionMode.POLLING -> PollingTransport(options, onConfigSet)
}
