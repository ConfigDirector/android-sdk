package com.configdirector.internal.eventsource

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * A server-sent events client: connects, publishes what the server sends, and reconnects when the
 * connection ends for a reason the caller says is worth retrying.
 *
 * OkHttp parses the stream; the reconnection policy is ours, because OkHttp deliberately has none.
 */
internal class EventSourceClient(
    httpClient: OkHttpClient,
    private val configuration: Configuration,
) {
    class Configuration(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: RequestBody? = null,
        val lastEventId: String? = null,
        val shouldReconnect: (EventSourceReconnection) -> Boolean = { true },
        val reconnectDelayMillis: (EventSourceReconnection) -> Long = { DEFAULT_RECONNECT_MILLIS },
    )

    private class Outcome(val statusCode: Int?, val cause: Throwable?, val didOpen: Boolean)

    // A server-sent events stream is idle by nature, so a read timeout would drop a healthy
    // connection that simply has nothing to say. The rest of the caller's client is kept, including
    // its connection pool.
    private val eventSourceFactory = EventSources.createFactory(
        httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
    )

    private val lastEventId = AtomicReference(configuration.lastEventId)

    /**
     * Connects and publishes everything that follows, reconnecting until the policy says to stop.
     * The flow completes when it stops; cancelling the collector cancels the connection.
     */
    fun events(): Flow<EventSourceEvent> = flow {
        var attempt = 0

        while (true) {
            val outcome = connectOnce()

            if (outcome.didOpen) {
                // A connection that opened starts the count over, so a long-lived stream dropping
                // once does not inherit the backoff of whatever failed before it.
                attempt = 0
            }
            attempt += 1

            emit(EventSourceEvent.Failure(outcome.statusCode, outcome.cause))

            val reconnection = EventSourceReconnection(attempt, outcome.statusCode, outcome.cause)
            if (outcome.statusCode == NO_CONTENT) break
            if (!configuration.shouldReconnect(reconnection)) break

            delay(
                configuration.reconnectDelayMillis(reconnection)
                    .coerceIn(0, MAX_RECONNECT_MILLIS),
            )
        }
    }

    private suspend fun FlowCollector<EventSourceEvent>.connectOnce(): Outcome {
        val ended = CompletableDeferred<Outcome>()
        val didOpen = AtomicBoolean(false)
        val events = Channel<EventSourceEvent>(Channel.UNLIMITED)

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                didOpen.set(true)
                events.trySend(EventSourceEvent.Open)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (id != null) {
                    lastEventId.set(id)
                }
                events.trySend(EventSourceEvent.Message(EventSourceMessage(id, type, data)))
            }

            override fun onClosed(eventSource: EventSource) {
                ended.complete(
                    Outcome(
                        statusCode = null,
                        cause = EventSourceException("The server closed the stream."),
                        didOpen = didOpen.get(),
                    ),
                )
                events.close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                ended.complete(Outcome(response?.code, t, didOpen.get()))
                events.close()
            }
        }

        val eventSource = eventSourceFactory.newEventSource(request(), listener)

        try {
            // The listener closes the channel when the connection ends, so this drains whatever
            // the server sent -- including anything buffered in the same breath as the close --
            // and then finishes.
            for (event in events) {
                emit(event)
            }
            return ended.await()
        } finally {
            eventSource.cancel()
        }
    }

    private fun request(): Request {
        val builder = Request.Builder()
            .url(configuration.url)
            .header("Accept", "text/event-stream")
            // A cached response would replay an old stream instead of opening a new one.
            .header("Cache-Control", "no-store")

        configuration.headers.forEach { (name, value) -> builder.header(name, value) }
        lastEventId.get()?.let { builder.header("Last-Event-ID", it) }

        return builder.method(configuration.method, configuration.body).build()
    }

    private companion object {
        private const val NO_CONTENT = 204
        private const val DEFAULT_RECONNECT_MILLIS = 2_000L
        private const val MAX_RECONNECT_MILLIS = 3_600_000L
    }
}
