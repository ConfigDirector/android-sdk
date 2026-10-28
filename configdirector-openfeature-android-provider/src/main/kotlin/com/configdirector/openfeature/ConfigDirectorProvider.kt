package com.configdirector.openfeature

import android.content.Context
import com.configdirector.ClientEvent
import com.configdirector.ClientOptions
import com.configdirector.ConfigDirectorClient
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorValidationException
import com.configdirector.ConfigDirectorWrapperApi
import com.configdirector.ConnectionMode
import com.configdirector.SdkIdentity
import com.configdirector.Subscription
import com.configdirector.events
import com.configdirector.openfeature.internal.Constants
import com.configdirector.openfeature.internal.toAny
import com.configdirector.openfeature.internal.toConfigDirectorContext
import com.configdirector.openfeature.internal.toProviderEvaluation
import com.configdirector.openfeature.internal.toValue
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An [OpenFeature](https://openfeature.dev) provider that resolves flags with the ConfigDirector
 * Android SDK.
 *
 * Register one instance with the OpenFeature Kotlin SDK during application startup:
 *
 * ```kotlin
 * val provider = ConfigDirectorProvider(applicationContext, "YOUR-CLIENT-SDK-KEY")
 * OpenFeatureAPI.setProviderAndWait(provider, ImmutableContext(targetingKey = "user-123"))
 *
 * val client = OpenFeatureAPI.getClient()
 * val darkMode = client.getBooleanValue("dark-mode", false)
 * ```
 *
 * The provider connects to ConfigDirector when it is registered and evaluates every flag from the
 * config state it holds locally, so resolving a flag never waits on the network.
 *
 * ## Evaluation context
 *
 * The OpenFeature evaluation context is sent to ConfigDirector as the user's context, and targeting
 * rules are evaluated against it:
 *
 * | OpenFeature                        | ConfigDirector |
 * | ---------------------------------- | -------------- |
 * | the targeting key, or else `id`    | `id`           |
 * | `kind`                             | `kind`         |
 * | `name`                             | `name`         |
 * | `traits`, a structure              | `traits`       |
 * | `anonymous`, a boolean             | `anonymous`    |
 *
 * Any other attribute is ignored. Put the values targeting rules depend on inside `traits`; a
 * `kind` inside `traits` is an ordinary trait. Leaving `kind` out means `user`, and a context with
 * an invalid `kind` is ignored as a whole: the SDK logs an error and flags resolve as if no context
 * was set. The context handed to an individual evaluation is ignored as well: the OpenFeature
 * Kotlin SDK holds one context at a time, and flags are evaluated against the one most recently
 * set.
 *
 * ## Resolution details
 *
 * A flag ConfigDirector served resolves with the reason `TARGETING_MATCH` and the served value's
 * id as its variant; one served from the values kept on the device, before live values arrive,
 * resolves the same way with the reason `CACHED`. One the config has no value for resolves to its
 * in-code default value with the reason `DEFAULT`.
 *
 * Every other read throws the matching OpenFeature error, so OpenFeature error hooks run for it:
 * `FlagNotFoundError` for a key with no config, `ProviderNotReadyError` before config state has
 * arrived, and `TypeMismatchError` for a value that cannot be read as the requested type. The
 * OpenFeature client then hands the application its in-code default value with the reason `ERROR`
 * and the error's code: `FLAG_NOT_FOUND`, `PROVIDER_NOT_READY` or `TYPE_MISMATCH`.
 *
 * An object flag takes its type from its in-code default value: a `Value.Structure` reads a JSON
 * config as a structure, a `Value.List` reads one as a list, and a string, integer, double or
 * boolean reads the config as that type. A `Value.Null` or `Value.Instant` in-code default value
 * names no type to read, so the read throws `TypeMismatchError`.
 *
 * ## Provider status
 *
 * The provider is ready once ConfigDirector has delivered config state, and it emits a ready event
 * when initialization succeeds. When config state does not arrive within
 * `ConnectionOptions.timeoutMillis`, initialization fails with a `ProviderNotReadyError` and the
 * OpenFeature SDK reports an error status; a context change that does not take in time fails the
 * same way. Once the OpenFeature SDK has recorded that error, the provider also emits an error
 * event with the same message and the `PROVIDER_NOT_READY` error code, and the OpenFeature Kotlin
 * SDK sets the status's error from that code, with a message of its own. The underlying client
 * keeps trying to connect, and the provider emits a ready event when values first arrive after the
 * failure, including through a later context change that succeeds. Until then flags resolve to the
 * config state received earlier, or to their in-code default values when there is none.
 *
 * A context change that succeeds emits no ready event: the OpenFeature SDK moves the status from
 * reconciling to ready by itself. Neither does a connection the client resumes after the app comes
 * back from the background, since the status stayed ready while it was paused.
 *
 * When the client loads the values it kept on the device from an earlier launch, the provider
 * emits a stale event and flags resolve to those values with the reason `CACHED` until live values
 * arrive. A timed-out initialization or context change still fails as above, and the provider emits
 * stale again right after its error event, so the status reads stale while kept values are served.
 * See `ClientOptions.caching` to turn this off.
 *
 * A configuration-changed event is emitted every time config state arrives, carrying the keys of
 * the configs in the update followed by the keys a full update removed.
 *
 * Shutting the provider down, which the OpenFeature SDK does when it is replaced or shut down,
 * closes the connection to ConfigDirector. An instance serves a single registration: after that,
 * create a new one.
 *
 * ## Testing
 *
 * A test creates the provider over a client of its own with the constructor that takes a
 * [ConfigDirectorClient], usually the `client` of a test client from
 * `com.configdirector:android-sdk-testing`. The provider never closes a client it was given:
 * shutting it down leaves the client to the test.
 */
public class ConfigDirectorProvider private constructor(
    private val client: ConfigDirectorClient,
    private val ownsClient: Boolean,
) : FeatureProvider {

    /**
     * Creates a provider that connects to ConfigDirector with [clientSdkKey].
     *
     * @param androidContext any Android context; the application behind it is what the client
     *   watches to tell when the app is backgrounded
     * @param clientSdkKey the client SDK key from the ConfigDirector dashboard
     * @param options settings for the underlying client: application metadata, the connection mode
     *   and timeout, and logging
     * @throws ConfigDirectorValidationException if [clientSdkKey] is blank and the connection mode
     *   is not [ConnectionMode.OFFLINE]
     */
    @OptIn(ConfigDirectorWrapperApi::class)
    public constructor(
        androidContext: Context,
        clientSdkKey: String,
        options: ClientOptions = ClientOptions.defaults(),
    ) : this(
        ConfigDirectorClient(
            androidContext,
            clientSdkKey,
            options,
            SdkIdentity.openFeatureProvider(Constants.PROVIDER_VERSION),
        ),
        ownsClient = true,
    )

    /**
     * Creates a provider that needs no client SDK key, because its connection mode is
     * [ConnectionMode.OFFLINE]: it never connects to ConfigDirector and serves the values in
     * [options].
     *
     * @param androidContext any Android context
     * @param options settings for the underlying client, whose connection mode is
     *   [ConnectionMode.OFFLINE]
     * @throws ConfigDirectorValidationException if the connection mode is not
     *   [ConnectionMode.OFFLINE], since every other mode needs a client SDK key
     */
    public constructor(androidContext: Context, options: ClientOptions) : this(androidContext, "", options)

    /**
     * Creates a provider over [client], a client the caller owns, for tests: usually the `client`
     * of a test client from `com.configdirector:android-sdk-testing`.
     *
     * The provider initializes the client when it is registered and updates its context when the
     * evaluation context changes, as it does with a client it creates itself, so a client that was
     * already initialized reconnects. It never closes the client: [shutdown] leaves it open for the
     * test to close.
     */
    @ConfigDirectorProviderTestingApi
    public constructor(client: ConfigDirectorClient) : this(client, ownsClient = false)

    private val servingCachedValues = AtomicBoolean(false)
    private val statusReports = MutableSharedFlow<OpenFeatureProviderEvents>(extraBufferCapacity = STATUS_REPORT_BUFFER)
    private val statusReportLock = Mutex()
    private var awaitingRecovery = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servedValuesTracking: Subscription = client.addEventListener { event -> trackServedValues(event) }

    override val hooks: List<Hook<*>> = emptyList()

    override val metadata: ProviderMetadata = Metadata

    override suspend fun initialize(initialContext: EvaluationContext?) {
        client.initialize(initialContext?.toContext())
        reportOutcome(LifecycleCall.INITIALIZATION)
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        client.updateContext(newContext.toContext())
        reportOutcome(LifecycleCall.CONTEXT_UPDATE)
    }

    override fun shutdown() {
        scope.cancel()
        servedValuesTracking.close()
        if (ownsClient) client.close()
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = channelFlow {
        val reporting = launch { statusReports.collect { report -> send(report) } }
        client.events.collect { event -> providerEventFor(event)?.let { send(it) } }
        reporting.cancel()
    }

    private fun providerEventFor(event: ClientEvent): OpenFeatureProviderEvents? = when (event) {
        is ClientEvent.ConfigsUpdated -> OpenFeatureProviderEvents.ProviderConfigurationChanged(
            OpenFeatureProviderEvents.EventDetails(flagsChanged = (event.keys + event.removedKeys).toSet()),
        )
        is ClientEvent.CachedValuesLoaded -> if (servingCachedValues.get()) OpenFeatureProviderEvents.ProviderStale() else null
        is ClientEvent.Ready, is ClientEvent.ContextUpdated -> null
    }

    private fun trackServedValues(event: ClientEvent) {
        when (event) {
            is ClientEvent.CachedValuesLoaded -> if (!client.isReady) servingCachedValues.set(true)
            is ClientEvent.Ready -> {
                servingCachedValues.set(false)
                scope.launch { statusReportLock.withLock { recoverIfTheClientIsReady() } }
            }
            is ClientEvent.ConfigsUpdated, is ClientEvent.ContextUpdated -> Unit
        }
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?,
    ): ProviderEvaluation<Boolean> =
        client.evaluateBoolean(key, defaultValue).let { it.toProviderEvaluation(it.value as Boolean) }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?,
    ): ProviderEvaluation<String> =
        client.evaluateString(key, defaultValue).let { it.toProviderEvaluation(it.value as String) }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?,
    ): ProviderEvaluation<Int> =
        client.evaluateInt(key, defaultValue).let { it.toProviderEvaluation(it.value as Int) }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?,
    ): ProviderEvaluation<Double> =
        client.evaluateDouble(key, defaultValue).let { it.toProviderEvaluation(it.value as Double) }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?,
    ): ProviderEvaluation<Value> {
        val evaluation = when (defaultValue) {
            is Value.Structure ->
                client.evaluateJsonObject(key, defaultValue.structure.mapValues { (_, entry) -> entry.toAny() })
            is Value.List -> client.evaluateJsonArray(key, defaultValue.list.map { entry -> entry.toAny() })
            is Value.String -> client.evaluateString(key, defaultValue.string)
            is Value.Integer -> client.evaluateInt(key, defaultValue.integer)
            is Value.Double -> client.evaluateDouble(key, defaultValue.double)
            is Value.Boolean -> client.evaluateBoolean(key, defaultValue.boolean)
            is Value.Instant, Value.Null -> throw OpenFeatureError.TypeMismatchError(
                "An in-code default value of ${defaultValue::class.simpleName} names no type to read '$key' as. " +
                    "Pass a Structure, List, String, Integer, Double or Boolean.",
            )
        }

        return evaluation.toProviderEvaluation(
            if (evaluation.isDefaultValue) defaultValue else evaluation.value.toValue(),
        )
    }

    private suspend fun reportOutcome(call: LifecycleCall) {
        statusReportLock.withLock {
            when (LifecycleReport.decide(call, client.isReady)) {
                LifecycleReport.READY -> statusReports.emit(OpenFeatureProviderEvents.ProviderReady())
                LifecycleReport.NO_EVENT -> Unit
                LifecycleReport.FAILURE -> {
                    awaitingRecovery = false
                    reportFailureOnceTheErrorIsRecorded(call.failureMessage)
                    throw OpenFeatureError.ProviderNotReadyError(call.failureMessage)
                }
            }
        }
    }

    private fun reportFailureOnceTheErrorIsRecorded(message: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            awaitTheRecordedErrorStatus()
            statusReportLock.withLock {
                statusReports.emit(
                    OpenFeatureProviderEvents.ProviderError(
                        OpenFeatureProviderEvents.EventDetails(message = message, errorCode = ErrorCode.PROVIDER_NOT_READY),
                    ),
                )
                when (FailureReport.decide(servingCachedValues.get())) {
                    FailureReport.ERROR -> Unit
                    FailureReport.ERROR_THEN_STALE -> statusReports.emit(OpenFeatureProviderEvents.ProviderStale())
                }
                awaitingRecovery = true
                recoverIfTheClientIsReady()
            }
        }
    }

    private suspend fun recoverIfTheClientIsReady() {
        if (awaitingRecovery && client.isReady) {
            awaitingRecovery = false
            statusReports.emit(OpenFeatureProviderEvents.ProviderReady())
        }
    }

    private suspend fun awaitTheRecordedErrorStatus() {
        withTimeoutOrNull(ERROR_RECORD_WAIT_MILLIS) {
            OpenFeatureAPI.statusFlow.first { status -> status is OpenFeatureStatus.Error }
        }
    }

    private fun EvaluationContext.toContext(): ConfigDirectorContext = try {
        toConfigDirectorContext()
    } catch (failure: ConfigDirectorValidationException) {
        throw OpenFeatureError.InvalidContextError(failure.message ?: "Invalid context")
    }

    private object Metadata : ProviderMetadata {
        override val name: String = Constants.PROVIDER_NAME
    }

    private companion object {
        private const val STATUS_REPORT_BUFFER = 16
        private const val ERROR_RECORD_WAIT_MILLIS = 1_000L
    }
}

private enum class LifecycleCall(val successReport: LifecycleReport, val failureMessage: String) {
    INITIALIZATION(
        LifecycleReport.READY,
        "ConfigDirector did not become ready during initialization. Flags resolve to their " +
            "in-code default values until the connection succeeds.",
    ),
    CONTEXT_UPDATE(
        LifecycleReport.NO_EVENT,
        "ConfigDirector did not become ready after the context changed. Flags resolve " +
            "against the previous context until the connection succeeds.",
    ),
}

private enum class LifecycleReport {
    READY,
    NO_EVENT,
    FAILURE,
    ;

    companion object {
        fun decide(call: LifecycleCall, isClientReady: Boolean): LifecycleReport =
            if (isClientReady) call.successReport else FAILURE
    }
}

private enum class FailureReport {
    ERROR,
    ERROR_THEN_STALE,
    ;

    companion object {
        fun decide(servingCachedValues: Boolean): FailureReport = if (servingCachedValues) ERROR_THEN_STALE else ERROR
    }
}
