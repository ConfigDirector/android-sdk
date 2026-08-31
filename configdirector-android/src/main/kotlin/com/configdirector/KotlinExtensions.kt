package com.configdirector

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Evaluates [key] against the current context, reading the value as a boolean.
 *
 * ```kotlin
 * val darkMode = client.value("dark-mode", false)
 * val maxItems = client.value("max-items", 25)
 * ```
 *
 * There is one overload per type a config can be read as, so a default of any other type is a
 * compile error rather than a failure at runtime.
 */
@JvmSynthetic
public fun ConfigDirectorClient.value(key: String, defaultValue: Boolean): Boolean =
    getBoolean(key, defaultValue)

/** Evaluates [key], reading the value as a string. See [value]. */
@JvmSynthetic
public fun ConfigDirectorClient.value(key: String, defaultValue: String): String =
    getString(key, defaultValue)

/** Evaluates [key], reading the value as a whole number. See [value]. */
@JvmSynthetic
public fun ConfigDirectorClient.value(key: String, defaultValue: Int): Int = getInt(key, defaultValue)

/** Evaluates [key], reading the value as a decimal number. See [value]. */
@JvmSynthetic
public fun ConfigDirectorClient.value(key: String, defaultValue: Double): Double =
    getDouble(key, defaultValue)

/**
 * Watches [key] for changes, reading each value as a boolean.
 *
 * The flow emits the config's current value straight away and then every time the evaluated value
 * changes; consecutive identical values are not re-emitted. It completes when the client closes,
 * and cancelling the collector stops watching.
 *
 * ```kotlin
 * client.values("dark-mode", false).collect { darkMode -> this.darkMode = darkMode }
 * ```
 *
 * There is one overload per type a config can be read as, so a default of any other type is a
 * compile error rather than a failure at runtime.
 */
@JvmSynthetic
public fun ConfigDirectorClient.values(key: String, defaultValue: Boolean): Flow<Boolean> =
    subscriptionFlow { send -> watchBoolean(key, defaultValue) { send(it) } }

/** Watches [key] for changes, reading each value as a string. See [values]. */
@JvmSynthetic
public fun ConfigDirectorClient.values(key: String, defaultValue: String): Flow<String> =
    subscriptionFlow { send -> watchString(key, defaultValue) { send(it) } }

/** Watches [key] for changes, reading each value as a whole number. See [values]. */
@JvmSynthetic
public fun ConfigDirectorClient.values(key: String, defaultValue: Int): Flow<Int> =
    subscriptionFlow { send -> watchInt(key, defaultValue) { send(it) } }

/** Watches [key] for changes, reading each value as a decimal number. See [values]. */
@JvmSynthetic
public fun ConfigDirectorClient.values(key: String, defaultValue: Double): Flow<Double> =
    subscriptionFlow { send -> watchDouble(key, defaultValue) { send(it) } }

/** Every event the client publishes, from the moment the flow is collected. */
@get:JvmSynthetic
public val ConfigDirectorClient.events: Flow<ClientEvent>
    get() = subscriptionFlow { send -> addEventListener { event -> send(event) } }

/**
 * Every config evaluation the client makes, from the moment the flow is collected.
 *
 * One is emitted for every read, so reading a config from a Compose composable emits one per
 * recomposition. Do not drive state from here that a composable reads a config from: the state
 * change recomposes, which reads the config, which emits another evaluation.
 */
@get:JvmSynthetic
public val ConfigDirectorClient.evaluations: Flow<ConfigEvaluation>
    get() = subscriptionFlow { send -> addEvaluationListener { evaluation -> send(evaluation) } }

private fun <T : Any> ConfigDirectorClient.subscriptionFlow(
    register: (send: (T) -> Unit) -> Subscription,
): Flow<T> {
    val client = this
    return callbackFlow {
        val subscription = register { value -> trySend(value) }

        completeWhenClosed(client)
        awaitClose { subscription.close() }
    }
}

// A flow whose source is gone should end rather than hang a collector waiting on it.
private fun <T> ProducerScope<T>.completeWhenClosed(client: ConfigDirectorClient) {
    launch {
        client.closedState.first { isClosed -> isClosed }
        close()
    }
}
