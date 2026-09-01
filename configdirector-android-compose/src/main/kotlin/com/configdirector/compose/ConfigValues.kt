package com.configdirector.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.configdirector.ConfigDirectorClient
import com.configdirector.ConfigDirectorContext
import com.configdirector.events
import com.configdirector.values

/**
 * The current value of [key], read as the type of [defaultValue].
 *
 * The composable is recomposed whenever the evaluated value changes, whether from an edit in the
 * ConfigDirector dashboard or from a call to `updateContext`. Until the client is ready, and for a
 * value that cannot be read as this type, it is [defaultValue].
 *
 * ```kotlin
 * val darkMode = configValue("dark-mode", false)
 * ```
 *
 * There is one overload per type a config can be read as, so a default of any other type is a
 * compile error rather than a failure at runtime.
 *
 * @param client the client to read from, taken from [LocalConfigDirectorClient] when not given
 */
@Composable
public fun configValue(
    key: String,
    defaultValue: Boolean,
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): Boolean = remember(client, key, defaultValue) { client.values(key, defaultValue) }
    .collectAsState(defaultValue)
    .value

/** The current value of [key], read as a string. See [configValue]. */
@Composable
public fun configValue(
    key: String,
    defaultValue: String,
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): String = remember(client, key, defaultValue) { client.values(key, defaultValue) }
    .collectAsState(defaultValue)
    .value

/** The current value of [key], read as a whole number. See [configValue]. */
@Composable
public fun configValue(
    key: String,
    defaultValue: Int,
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): Int = remember(client, key, defaultValue) { client.values(key, defaultValue) }
    .collectAsState(defaultValue)
    .value

/** The current value of [key], read as a decimal number. See [configValue]. */
@Composable
public fun configValue(
    key: String,
    defaultValue: Double,
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): Double = remember(client, key, defaultValue) { client.values(key, defaultValue) }
    .collectAsState(defaultValue)
    .value

/** The current value of [key], read as a JSON document. See [configValue]. */
@Composable
public fun configValue(
    key: String,
    defaultValue: Map<String, Any?>,
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): Map<String, Any?> = remember(client, key, defaultValue) { client.values(key, defaultValue) }
    .collectAsState(defaultValue)
    .value

/** The current value of [key], read as a JSON array. See [configValue]. */
@Composable
public fun configValue(
    key: String,
    defaultValue: List<Any?>,
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): List<Any?> = remember(client, key, defaultValue) { client.values(key, defaultValue) }
    .collectAsState(defaultValue)
    .value

/**
 * Whether the client is ready, meaning config state has arrived. Recomposes when that changes.
 *
 * @param client the client to read from, taken from [LocalConfigDirectorClient] when not given
 */
@Composable
public fun isClientReady(
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): Boolean = produceState(client.isReady, client) {
    client.events.collect { value = client.isReady }
}.value

/**
 * The context configs are currently evaluated against. Recomposes when a call to `updateContext`
 * takes effect.
 *
 * @param client the client to read from, taken from [LocalConfigDirectorClient] when not given
 */
@Composable
public fun configContext(
    client: ConfigDirectorClient = LocalConfigDirectorClient.current,
): ConfigDirectorContext? = produceState(client.context, client) {
    client.events.collect { value = client.context }
}.value
