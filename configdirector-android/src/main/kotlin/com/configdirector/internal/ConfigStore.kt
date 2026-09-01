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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the config state the client evaluates against, and everything that observes it: the ready
 * signal, the registered listeners, and the active watches.
 */
internal class ConfigStore(
    private val logger: ConfigDirectorLogger,
    private val telemetry: TelemetryClient,
) {

    private val configs = AtomicReference<Map<String, ConfigState>>(emptyMap())
    private val contextHolder = AtomicReference<ConfigDirectorContext?>(null)
    private val pendingReason = AtomicReference(ConnectReason.INITIALIZATION)
    private val hasReceivedConfigSet = AtomicBoolean(false)
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

    val isReady: Boolean get() = ready.value

    val context: ConfigDirectorContext? get() = contextHolder.get()

    val closed: StateFlow<Boolean> get() = closedState

    fun beginConnect(reason: ConnectReason) {
        pendingReason.set(reason)
        ready.value = false
    }

    fun markNotReady() {
        ready.value = false
    }

    fun setContext(context: ConfigDirectorContext?) {
        contextHolder.set(context)
        emit(ClientEvent.ContextUpdated(context))
    }

    fun handleConfigSet(configSet: ConfigSet) {
        val isDelta = configSet.kind == ConfigSetKind.DELTA && hasReceivedConfigSet.get()
        configs.set(if (isDelta) configs.get() + configSet.configs else configSet.configs)
        hasReceivedConfigSet.set(true)
        val keys = configSet.configs.keys.toList()

        markReady()
        emit(ClientEvent.ConfigsUpdated(keys))
        keys.forEach { key -> watchers[key]?.values?.forEach { reevaluate -> reevaluate() } }

        logger.debug { "Config state received from the server: $keys" }
    }

    /** Waits until config state arrives, at most [timeoutMillis], or until the client closes. */
    suspend fun waitUntilReady(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) {
            combine(ready, closedState) { isReady, isClosed -> isReady || isClosed }.first { it }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        evaluate(key, defaultValue) { it.asBoolean(defaultValue) }

    fun getString(key: String, defaultValue: String): String =
        evaluate(key, defaultValue) { it.asString(defaultValue) }

    fun getInt(key: String, defaultValue: Int): Int =
        evaluate(key, defaultValue) { it.asInt(defaultValue) }

    fun getDouble(key: String, defaultValue: Double): Double =
        evaluate(key, defaultValue) { it.asDouble(defaultValue) }

    fun getJsonObject(key: String, defaultValue: Map<String, Any?>): Map<String, Any?> =
        evaluate(key, defaultValue) { it.asJsonObject(defaultValue) }

    fun getJsonArray(key: String, defaultValue: List<Any?>): List<Any?> =
        evaluate(key, defaultValue) { it.asJsonArray(defaultValue) }

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
    }

    private fun <T : Any> evaluate(
        key: String,
        defaultValue: T,
        parse: (ConfigState) -> EvaluationResult<T>,
    ): T {
        val configState = configs.get()[key]
        val result = configState?.let(parse) ?: EvaluationResult.usedDefault(
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
            val evaluation = ConfigEvaluation(
                key = key,
                value = result.value,
                valueId = result.valueId,
                isDefaultValue = result.usedDefault,
                reason = result.reason,
                context = context,
            )
            evaluationListeners.forEach { listener -> deliver { listener.onEvaluation(evaluation) } }
        }

        logger.debug { "Evaluated '$key' to '${result.value}' (${result.reason.wireName})" }
        return result.value
    }

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
