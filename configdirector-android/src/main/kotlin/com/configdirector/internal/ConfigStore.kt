package com.configdirector.internal

import com.configdirector.ClientEvent
import com.configdirector.ClientEventListener
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorLogger
import com.configdirector.ConfigEvaluation
import com.configdirector.ConfigListener
import com.configdirector.ConnectReason
import com.configdirector.EvaluationListener
import com.configdirector.EvaluationReason
import com.configdirector.Subscription
import com.configdirector.debug
import com.configdirector.internal.telemetry.EvaluatedConfigEvent
import com.configdirector.internal.telemetry.TelemetryClient
import com.configdirector.internal.telemetry.TelemetryValue
import com.configdirector.internal.telemetry.requestedTypeOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ConfigSetSource(val matchedReason: EvaluationReason) {
    NONE(EvaluationReason.FOUND_MATCH),
    CACHED_VALUES(EvaluationReason.CACHED),
    LIVE_PAYLOAD(EvaluationReason.FOUND_MATCH),
}

private class HeldConfigs(
    val configs: Map<String, ConfigState>,
    val source: ConfigSetSource,
    val environmentId: String = "",
    val projectId: String = "",
)

/**
 * Holds the config state the client evaluates against, and everything that observes it: the ready
 * signal, the registered listeners, and the active watches.
 */
internal class ConfigStore(
    private val logger: ConfigDirectorLogger,
    private val telemetry: TelemetryClient,
    caching: CachingPlan,
) {

    private val cachedValuesStore: CachedValuesStore? = when (caching) {
        is CachingPlan.KeepValues -> caching.store
        is CachingPlan.KeepNothing -> null
    }
    private val held = AtomicReference(HeldConfigs(emptyMap(), ConfigSetSource.NONE))
    private val heldLock = Any()
    private val contextHolder = AtomicReference<ConfigDirectorContext?>(null)
    private val submittedContext = AtomicReference<ConfigDirectorContext?>(null)
    private val pendingReason = AtomicReference(ConnectReason.INITIALIZATION)
    private val cachedValuesAdoptionStarted = AtomicBoolean(false)
    private val ready = MutableStateFlow(false)
    private val closedState = MutableStateFlow(false)

    private val eventListeners = CopyOnWriteArrayList<ClientEventListener>()
    private val evaluationListeners = CopyOnWriteArrayList<EvaluationListener>()
    private val watchers = ConcurrentHashMap<String, ConcurrentHashMap<Long, () -> Unit>>()
    private val nextWatcherId = AtomicLong()

    // Listeners are handed back on the main thread, so an old Java codebase can update views from
    // one. The scope is built on first use: reaching for the main dispatcher on a plain JVM throws,
    // and a client that never registers a listener should still work in a consumer's unit tests.
    private val callbackScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val adoptionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isReady: Boolean get() = ready.value

    val hasReceivedConfigSet: Boolean get() = held.get().source == ConfigSetSource.LIVE_PAYLOAD

    val context: ConfigDirectorContext? get() = contextHolder.get()

    val closed: StateFlow<Boolean> get() = closedState

    fun beginConnect(reason: ConnectReason, context: ConfigDirectorContext?) {
        pendingReason.set(reason)
        submittedContext.set(context)
        ready.value = false
    }

    fun adoptCachedValuesOnce(launchContext: ConfigDirectorContext?) {
        val store = cachedValuesStore ?: return
        if (cachedValuesAdoptionStarted.getAndSet(true)) return

        val launchContextTag = store.contextTag(launchContext)
        adoptionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            when (val read = store.read()) {
                is CachedValuesRead.Stored -> adopt(read.cachedValues, launchContextTag)
                CachedValuesRead.NothingStored, CachedValuesRead.NotAnEntry, CachedValuesRead.StorageFailed -> Unit
            }
        }
    }

    fun markNotReady() {
        ready.value = false
    }

    fun setContext(context: ConfigDirectorContext?) {
        contextHolder.set(context)
        emit(ClientEvent.ContextUpdated(context))
    }

    fun handleConfigSet(configSet: ConfigSet) {
        val live: HeldConfigs
        val removedKeys: List<String>
        synchronized(heldLock) {
            val previous = held.get()
            val holdsValues = previous.source != ConfigSetSource.NONE
            val isDelta = configSet.kind == ConfigSetKind.DELTA && holdsValues
            live = HeldConfigs(
                configs = if (isDelta) previous.configs + configSet.configs else configSet.configs,
                source = ConfigSetSource.LIVE_PAYLOAD,
                environmentId = configSet.environmentId.ifEmpty { previous.environmentId },
                projectId = configSet.projectId.ifEmpty { previous.projectId },
            )
            removedKeys = if (!isDelta && holdsValues) previous.configs.keys.filterNot { it in configSet.configs } else emptyList()
            held.set(live)
        }
        val keys = configSet.configs.keys.toList()

        markReady()
        emit(ClientEvent.ConfigsUpdated(keys, removedKeys))
        (keys + removedKeys).forEach { key -> watchers[key]?.values?.forEach { reevaluate -> reevaluate() } }
        keepOnDevice(live)

        logger.debug { "Config state received from the server: $keys, removed: $removedKeys" }
    }

    /** Waits until config state arrives, at most [timeoutMillis], or until the client closes. */
    suspend fun waitUntilReady(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) {
            combine(ready, closedState) { isReady, isClosed -> isReady || isClosed }.first { it }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        evaluate(key, defaultValue) { it.asBoolean(defaultValue) }.value

    fun getString(key: String, defaultValue: String): String =
        evaluate(key, defaultValue) { it.asString(defaultValue) }.value

    fun getInt(key: String, defaultValue: Int): Int =
        evaluate(key, defaultValue) { it.asInt(defaultValue) }.value

    fun getDouble(key: String, defaultValue: Double): Double =
        evaluate(key, defaultValue) { it.asDouble(defaultValue) }.value

    fun getJsonObject(key: String, defaultValue: Map<String, Any?>): Map<String, Any?> =
        evaluate(key, defaultValue) { it.asJsonObject(defaultValue) }.value

    fun getJsonArray(key: String, defaultValue: List<Any?>): List<Any?> =
        evaluate(key, defaultValue) { it.asJsonArray(defaultValue) }.value

    fun evaluateBoolean(key: String, defaultValue: Boolean): ConfigEvaluation =
        evaluate(key, defaultValue) { it.asBoolean(defaultValue) }.describe(key)

    fun evaluateString(key: String, defaultValue: String): ConfigEvaluation =
        evaluate(key, defaultValue) { it.asString(defaultValue) }.describe(key)

    fun evaluateInt(key: String, defaultValue: Int): ConfigEvaluation =
        evaluate(key, defaultValue) { it.asInt(defaultValue) }.describe(key)

    fun evaluateDouble(key: String, defaultValue: Double): ConfigEvaluation =
        evaluate(key, defaultValue) { it.asDouble(defaultValue) }.describe(key)

    fun evaluateJsonObject(key: String, defaultValue: Map<String, Any?>): ConfigEvaluation =
        evaluate(key, defaultValue) { it.asJsonObject(defaultValue) }.describe(key)

    fun evaluateJsonArray(key: String, defaultValue: List<Any?>): ConfigEvaluation =
        evaluate(key, defaultValue) { it.asJsonArray(defaultValue) }.describe(key)

    fun <T : Any> watch(key: String, listener: ConfigListener<T>, evaluate: () -> T): Subscription {
        if (closedState.value) return Subscription {}

        val id = nextWatcherId.getAndIncrement()
        val lastDelivered = AtomicReference<T?>(null)
        val deliverIfChanged = {
            val value = evaluate()
            if (lastDelivered.getAndSet(value) != value) {
                deliver { listener.onValue(value) }
            }
        }

        // computeIfAbsent is API 24, and the SDK runs on 21.
        val forKey = watchers[key] ?: ConcurrentHashMap<Long, () -> Unit>().let { created ->
            watchers.putIfAbsent(key, created) ?: created
        }
        forKey[id] = deliverIfChanged
        deliverIfChanged()

        return Subscription { watchers[key]?.remove(id) }
    }

    fun addEventListener(listener: ClientEventListener): Subscription {
        if (closedState.value) return Subscription {}

        eventListeners.add(listener)
        return Subscription { eventListeners.remove(listener) }
    }

    fun addEvaluationListener(listener: EvaluationListener): Subscription {
        if (closedState.value) return Subscription {}

        evaluationListeners.add(listener)
        return Subscription { evaluationListeners.remove(listener) }
    }

    fun close() {
        closedState.value = true
        ready.value = false
        watchers.clear()
        eventListeners.clear()
        evaluationListeners.clear()
        if (callbackScope.isInitialized()) {
            callbackScope.value.cancel()
        }
        adoptionScope.cancel()
        cachedValuesStore?.close()
    }

    private fun adopt(cachedValues: CachedValues, launchContextTag: ContextTag) {
        val adoption = synchronized(heldLock) {
            val adoption = decideCachedValuesAdoption(
                cachedValues,
                launchContextTag,
                hasReceivedLiveValues = hasReceivedConfigSet,
                isClosed = closedState.value,
            )
            if (adoption == CachedValuesAdoption.ADOPT) {
                held.set(
                    HeldConfigs(
                        cachedValues.configs,
                        ConfigSetSource.CACHED_VALUES,
                        cachedValues.environmentId,
                        cachedValues.projectId,
                    ),
                )
            }
            adoption
        }
        val keys = cachedValues.configs.keys.toList()

        when (adoption) {
            CachedValuesAdoption.ADOPT -> {
                emit(ClientEvent.CachedValuesLoaded)
                emit(ClientEvent.ConfigsUpdated(keys, emptyList()))
                keys.forEach { key -> watchers[key]?.values?.forEach { reevaluate -> reevaluate() } }
                logger.debug { "Serving the values kept on the device until live values arrive: $keys" }
            }
            CachedValuesAdoption.LIVE_VALUES_ALREADY_ARRIVED ->
                logger.debug { "The values kept on the device were not adopted: live values already arrived" }
            CachedValuesAdoption.CONTEXT_MISMATCH ->
                logger.debug { "The values kept on the device were not adopted: the launch context differs" }
            CachedValuesAdoption.CLIENT_CLOSED -> Unit
        }
    }

    private fun keepOnDevice(live: HeldConfigs) {
        val store = cachedValuesStore ?: return

        store.replace(
            CachedValues(
                contextTag = store.contextTag(submittedContext.get()),
                environmentId = live.environmentId,
                projectId = live.projectId,
                configs = live.configs,
                receivedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun <T : Any> evaluate(
        key: String,
        defaultValue: T,
        parse: (ConfigState) -> EvaluationResult<T>,
    ): EvaluationResult<T> {
        val heldConfigs = held.get()
        val configState = heldConfigs.configs[key]
        val result = configState?.let(parse)?.servedFrom(heldConfigs.source) ?: EvaluationResult.usedDefault(
            defaultValue,
            if (isReady) EvaluationReason.CONFIG_STATE_MISSING else EvaluationReason.CLIENT_NOT_READY,
        )

        telemetry.evaluatedConfig(
            EvaluatedConfigEvent(
                contextId = context?.id,
                key = key,
                type = configState?.type,
                defaultValue = TelemetryValue(defaultValue, valueId = null, type = configState?.type),
                requestedType = requestedTypeOf(defaultValue),
                evaluatedValue = TelemetryValue(result.value, result.valueId, configState?.type),
                evaluatedValueId = result.valueId,
                usedDefault = result.usedDefault,
                evaluationReason = result.reason,
            ),
        )

        if (evaluationListeners.isNotEmpty()) {
            val evaluation = result.describe(key)
            evaluationListeners.forEach { listener -> deliver { listener.onEvaluation(evaluation) } }
        }

        logger.debug { "Evaluated '$key' to '${result.value}' (${result.reason.wireName})" }
        return result
    }

    private fun <T> EvaluationResult<T>.servedFrom(source: ConfigSetSource): EvaluationResult<T> =
        if (usedDefault) this else copy(reason = source.matchedReason)

    private fun EvaluationResult<*>.describe(key: String): ConfigEvaluation = ConfigEvaluation(
        key = key,
        value = checkNotNull(value),
        valueId = valueId,
        isDefaultValue = usedDefault,
        reason = reason,
        context = context,
    )

    private fun markReady() {
        if (ready.value || closedState.value) return

        ready.value = true
        emit(ClientEvent.Ready(pendingReason.get()))
        logger.debug { "Received config state from the server, the client is ready" }
    }

    private fun emit(event: ClientEvent) {
        eventListeners.forEach { listener -> deliver { listener.onEvent(event) } }
    }

    private fun deliver(callback: () -> Unit) {
        callbackScope.value.launch { callback() }
    }
}
