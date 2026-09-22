package com.configdirector.openfeature

import android.content.Context
import com.configdirector.ClientEvent
import com.configdirector.ClientOptions
import com.configdirector.ConfigDirectorClient
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorValidationException
import com.configdirector.ConfigDirectorWrapperApi
import com.configdirector.SdkIdentity
import com.configdirector.events
import com.configdirector.openfeature.internal.Constants
import com.configdirector.openfeature.internal.toAny
import com.configdirector.openfeature.internal.toConfigDirectorContext
import com.configdirector.openfeature.internal.toProviderEvaluation
import com.configdirector.openfeature.internal.toValue
import com.configdirector.openfeature.internal.unsupportedDefault
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

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
 * | `name`                             | `name`         |
 * | `traits`, a structure              | `traits`       |
 * | `anonymous`, a boolean             | `anonymous`    |
 *
 * Any other attribute is ignored. Put the values targeting rules depend on inside `traits`. The
 * context handed to an individual evaluation is ignored as well: the OpenFeature Kotlin SDK holds
 * one context at a time, and flags are evaluated against the one most recently set.
 *
 * ## Resolution details
 *
 * A flag ConfigDirector served resolves with the reason `TARGETING_MATCH` and the served value's
 * id as its variant. One the config has no value for resolves to the default with the reason
 * `DEFAULT`. Everything else is an error carrying the default: `FLAG_NOT_FOUND` for an unknown
 * key, `PROVIDER_NOT_READY` before config state has arrived, and `TYPE_MISMATCH` for a value that
 * cannot be read as the requested type.
 *
 * An object flag takes its type from the default value: a `Value.Structure` reads a JSON config as
 * a structure, a `Value.List` reads one as a list, and a string, integer, double or boolean reads
 * the config as that type. A `Value.Null` or `Value.Instant` default names no type to read, so it
 * resolves to itself with `TYPE_MISMATCH`.
 *
 * ## Provider status
 *
 * The provider is ready once ConfigDirector has delivered config state. When that does not happen
 * within `ConnectionOptions.timeoutMillis`, initialization fails with a `ProviderNotReadyError` and
 * the OpenFeature SDK reports an error status. The underlying client keeps trying to connect, and
 * the provider emits a ready event as soon as it succeeds. Until then flags resolve to the config
 * state received earlier, or to their default values when there is none.
 *
 * A configuration-changed event is emitted every time config state arrives, carrying the keys of
 * the configs in the update.
 *
 * Shutting the provider down, which the OpenFeature SDK does when it is replaced or shut down,
 * closes the connection to ConfigDirector. An instance serves a single registration: after that,
 * create a new one.
 *
 * @param androidContext any Android context; the application behind it is what the client watches
 *   to tell when the app is backgrounded
 * @param clientSdkKey the client SDK key from the ConfigDirector dashboard
 * @param options settings for the underlying client: application metadata, the connection mode and
 *   timeout, and logging
 * @throws ConfigDirectorValidationException if [clientSdkKey] is blank
 */
public class ConfigDirectorProvider(
    androidContext: Context,
    clientSdkKey: String,
    options: ClientOptions = ClientOptions.defaults(),
) : FeatureProvider {

    @OptIn(ConfigDirectorWrapperApi::class)
    private val client = ConfigDirectorClient(
        androidContext,
        clientSdkKey,
        options,
        SdkIdentity.openFeatureProvider(Constants.PROVIDER_VERSION),
    )

    override val hooks: List<Hook<*>> = emptyList()

    override val metadata: ProviderMetadata = Metadata

    override suspend fun initialize(initialContext: EvaluationContext?) {
        client.initialize(initialContext?.toContext())
        requireReady(
            "ConfigDirector did not become ready during initialization. Flags resolve to their " +
                "default values until the connection succeeds.",
        )
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        client.updateContext(newContext.toContext())
        requireReady(
            "ConfigDirector did not become ready after the context changed. Flags resolve " +
                "against the previous context until the connection succeeds.",
        )
    }

    override fun shutdown() {
        client.close()
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = client.events.mapNotNull { event ->
        when (event) {
            is ClientEvent.Ready -> OpenFeatureProviderEvents.ProviderReady()
            is ClientEvent.ConfigsUpdated -> OpenFeatureProviderEvents.ProviderConfigurationChanged(
                OpenFeatureProviderEvents.EventDetails(flagsChanged = event.keys.toSet()),
            )
            is ClientEvent.ContextUpdated -> null
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
            is Value.Instant, Value.Null -> return unsupportedDefault(
                defaultValue,
                "A default of ${defaultValue::class.simpleName} names no type to read '$key' as. " +
                    "Pass a Structure, List, String, Integer, Double or Boolean.",
            )
        }

        return evaluation.toProviderEvaluation(
            if (evaluation.isDefaultValue) defaultValue else evaluation.value.toValue(),
        )
    }

    private fun requireReady(message: String) {
        if (!client.isReady) throw OpenFeatureError.ProviderNotReadyError(message)
    }

    private fun EvaluationContext.toContext(): ConfigDirectorContext = try {
        toConfigDirectorContext()
    } catch (failure: ConfigDirectorValidationException) {
        throw OpenFeatureError.InvalidContextError(failure.message ?: "Invalid context")
    }

    private object Metadata : ProviderMetadata {
        override val name: String = Constants.PROVIDER_NAME
    }
}
