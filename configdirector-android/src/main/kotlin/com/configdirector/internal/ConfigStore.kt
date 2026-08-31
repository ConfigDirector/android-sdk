package com.configdirector.internal

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorLogger
import com.configdirector.debug
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Holds the config state the client evaluates against, along with the ready signal. */
internal class ConfigStore(private val logger: ConfigDirectorLogger) {

    private val configs = AtomicReference<Map<String, ConfigState>>(emptyMap())
    private val contextHolder = AtomicReference<ConfigDirectorContext?>(null)
    private val ready = MutableStateFlow(false)
    private val closed = MutableStateFlow(false)

    val isReady: Boolean get() = ready.value

    val context: ConfigDirectorContext? get() = contextHolder.get()

    fun beginConnect() {
        ready.value = false
    }

    fun setContext(context: ConfigDirectorContext?) {
        contextHolder.set(context)
    }

    fun handleConfigSet(configSet: ConfigSet) {
        configs.set(configSet.configs)

        if (!closed.value) {
            ready.value = true
        }
        logger.debug { "Config state received from the server: ${configSet.configs.keys}" }
    }

    /** Waits until config state arrives, at most [timeoutMillis], or until the client closes. */
    suspend fun waitUntilReady(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) {
            combine(ready, closed) { isReady, isClosed -> isReady || isClosed }.first { it }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        evaluate(key, defaultValue) { ConfigValueParser.parseBoolean(it, defaultValue) }

    fun getString(key: String, defaultValue: String): String =
        evaluate(key, defaultValue) { ConfigValueParser.parseString(it, defaultValue) }

    fun getInt(key: String, defaultValue: Int): Int =
        evaluate(key, defaultValue) { ConfigValueParser.parseInt(it, defaultValue) }

    fun getDouble(key: String, defaultValue: Double): Double =
        evaluate(key, defaultValue) { ConfigValueParser.parseDouble(it, defaultValue) }

    fun close() {
        closed.value = true
        ready.value = false
    }

    private fun <T> evaluate(
        key: String,
        defaultValue: T,
        parse: (ConfigState) -> EvaluationResult<T>,
    ): T {
        val configState = configs.get()[key]
        val result = configState?.let(parse) ?: EvaluationResult.usedDefault(
            defaultValue,
            if (isReady) EvaluationReason.CONFIG_STATE_MISSING else EvaluationReason.CLIENT_NOT_READY,
        )

        logger.debug { "Evaluated '$key' to '${result.value}' (${result.reason.wireName})" }
        return result.value
    }
}
