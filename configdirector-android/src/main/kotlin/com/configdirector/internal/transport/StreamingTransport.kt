package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.debug
import com.configdirector.error
import com.configdirector.info
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.eventsource.EventSourceClient
import com.configdirector.internal.eventsource.EventSourceEvent
import com.configdirector.internal.eventsource.EventSourceReconnection
import com.configdirector.warn
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException

/**
 * A [Transport] that holds a server-sent events connection open, receiving config state as soon as
 * it changes on the server.
 */
internal class StreamingTransport(
    private val options: TransportOptions,
    private val onConfigSet: (ConfigSet) -> Unit,
) : Transport {

    private val url = options.endpoint(PATH).toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streaming = AtomicReference<Job?>(null)
    private val closed = AtomicBoolean(false)

    override suspend fun connect(context: ConfigDirectorContext, timeoutMillis: Long) {
        if (closed.get()) return

        disconnect()

        val connected = CompletableDeferred<Unit>()
        val client = EventSourceClient(options.httpClient, configuration(context, connected))
        val job = scope.launch {
            client.events().collect { handle(it, connected) }
            connected.completeExceptionally(
                ConnectionFailedException("The connection ended and will not be retried."),
            )
        }
        streaming.getAndSet(job)?.cancel()

        try {
            withTimeout(timeoutMillis) { connected.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw ConnectionFailedException("Connection timed out after ${timeoutMillis}ms.")
        }
    }

    override fun disconnect() {
        streaming.getAndSet(null)?.cancel()
    }

    override fun close() {
        closed.set(true)
        disconnect()
        scope.cancel()
    }

    private fun configuration(
        context: ConfigDirectorContext,
        connected: CompletableDeferred<Unit>,
    ) = EventSourceClient.Configuration(
        url = url,
        method = "POST",
        body = options.payload(context).toRequestBody(JSON),
        shouldReconnect = { shouldReconnect(it, connected) },
        reconnectDelayMillis = ::reconnectDelayMillis,
    )

    private fun handle(event: EventSourceEvent, connected: CompletableDeferred<Unit>) {
        when (event) {
            is EventSourceEvent.Open -> {
                options.logger.debug { "[StreamingTransport] Connected" }
                connected.complete(Unit)
            }

            is EventSourceEvent.Message -> dispatch(event.message.data)

            is EventSourceEvent.Failure -> options.logger.debug(event.cause) {
                "[StreamingTransport] The connection ended"
            }
        }
    }

    private fun shouldReconnect(
        reconnection: EventSourceReconnection,
        connected: CompletableDeferred<Unit>,
    ): Boolean {
        val status = reconnection.statusCode
        if (status == null || !status.isFatalHttpStatus()) return true

        val failure = fatalFailure(status, reconnection.cause)
        // Nobody is waiting on a connection that already opened, so the failure has to be logged
        // rather than handed to the caller of connect.
        if (!connected.completeExceptionally(failure)) {
            options.logger.error { "[StreamingTransport] ${failure.message}" }
        }
        return false
    }

    private fun fatalFailure(status: Int, cause: Throwable?) = ConnectionFailedException(
        "Connection failed with status: $status." +
            cause?.let { " Error: $it." }.orEmpty() +
            " This is an unrecoverable error, will not attempt to reconnect.",
        status,
    )

    private fun reconnectDelayMillis(reconnection: EventSourceReconnection): Long {
        val delayMillis = options.retryDelayMillis(reconnection.attempt)
        val message = {
            "[StreamingTransport] Scheduling reconnect attempt #${reconnection.attempt} in " +
                "${delayMillis}ms."
        }

        if (reconnection.attempt <= QUIET_ATTEMPTS) {
            options.logger.info(message = message)
        } else {
            options.logger.warn(message = message)
        }
        return delayMillis
    }

    private fun dispatch(data: String) {
        try {
            onConfigSet(ConfigSetParser.parse(data))
        } catch (malformed: JSONException) {
            options.logger.error(malformed) {
                "[StreamingTransport] Error parsing and dispatching the config state update"
            }
        }
    }

    private companion object {
        private const val PATH = "client/sse/v1"

        /** Reconnecting a few times is routine; past this the logs should say something is wrong. */
        private const val QUIET_ATTEMPTS = 5
        private val JSON = "application/json".toMediaType()
    }
}
