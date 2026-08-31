package com.configdirector.internal.eventsource

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancelAndJoin
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test

class EventSourceClientTest {

    private val server = MockWebServer()
    private val httpClient = OkHttpClient()
    private val attempts = CopyOnWriteArrayList<Int>()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun request(): RecordedRequest =
        checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "The server received no request." }

    private fun eventStream(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun client(
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        shouldReconnect: (EventSourceReconnection) -> Boolean = { false },
    ) = EventSourceClient(
        httpClient,
        EventSourceClient.Configuration(
            url = server.url("/stream").toString(),
            method = method,
            headers = headers,
            body = body,
            shouldReconnect = shouldReconnect,
            reconnectDelayMillis = { reconnection ->
                attempts += reconnection.attempt
                0
            },
        ),
    )

    @Test
    fun `publishes every message the server sends`() = runTest {
        server.enqueue(eventStream("data: one\n\ndata: two\n\n"))

        val events = client().events().toList()

        assertThat(events.filterIsInstance<EventSourceEvent.Open>()).hasSize(1)
        assertThat(events.filterIsInstance<EventSourceEvent.Message>().map { it.message.data })
            .containsExactly("one", "two")
            .inOrder()
    }

    @Test
    fun `publishes the id and type the server sends`() = runTest {
        server.enqueue(eventStream("id: 42\nevent: config-set\ndata: {}\n\n"))

        val message = client().events().toList()
            .filterIsInstance<EventSourceEvent.Message>()
            .single()
            .message

        assertThat(message.id).isEqualTo("42")
        assertThat(message.event).isEqualTo("config-set")
        assertThat(message.data).isEqualTo("{}")
    }

    @Test
    fun `reports the connection ending when the server closes the stream`() = runTest {
        server.enqueue(eventStream("data: one\n\n"))

        val failure = client().events().toList()
            .filterIsInstance<EventSourceEvent.Failure>()
            .single()

        assertThat(failure.statusCode).isNull()
        assertThat(failure.cause).hasMessageThat().contains("closed the stream")
    }

    @Test
    fun `reconnects after the connection ends`() = runTest {
        server.enqueue(eventStream("data: one\n\n"))
        server.enqueue(eventStream("data: two\n\n"))

        val events = client(shouldReconnect = { server.requestCount < 2 }).events().toList()

        assertThat(events.filterIsInstance<EventSourceEvent.Open>()).hasSize(2)
        assertThat(events.filterIsInstance<EventSourceEvent.Message>().map { it.message.data })
            .containsExactly("one", "two")
            .inOrder()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `counts attempts from one again once a connection opens`() = runTest {
        repeat(3) { server.enqueue(eventStream("data: one\n\n")) }

        client(shouldReconnect = { server.requestCount < 3 }).events().toList()

        // Every connection opened, so each drop is attempt 1 rather than 1, 2, 3: a stream that
        // ran for a week does not come back with the backoff of whatever failed before it.
        assertThat(attempts).containsExactly(1, 1).inOrder()
    }

    @Test
    fun `counts attempts up while connections keep failing`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }

        client(shouldReconnect = { server.requestCount < 3 }).events().toList()

        // None of them opened, so the attempts keep climbing and the caller's backoff grows.
        assertThat(attempts).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `sends Last-Event-ID when it reconnects`() = runTest {
        server.enqueue(eventStream("id: 42\ndata: one\n\n"))
        server.enqueue(eventStream("data: two\n\n"))

        client(shouldReconnect = { server.requestCount < 2 }).events().toList()

        assertThat(request().getHeader("Last-Event-ID")).isNull()
        assertThat(request().getHeader("Last-Event-ID")).isEqualTo("42")
    }

    @Test
    fun `sends the method, body and headers it was given`() = runTest {
        server.enqueue(eventStream("data: one\n\n"))
        val body = """{"clientSdkKey":"key"}""".toRequestBody("application/json".toMediaTypeOrNull())

        client(method = "POST", body = body, headers = mapOf("X-Sample" to "yes")).events().toList()

        val request = request()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.body.readUtf8()).isEqualTo("""{"clientSdkKey":"key"}""")
        assertThat(request.getHeader("X-Sample")).isEqualTo("yes")
        assertThat(request.getHeader("Accept")).isEqualTo("text/event-stream")
    }

    @Test
    fun `reports the status when the server rejects the request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val failure = client().events().toList()
            .filterIsInstance<EventSourceEvent.Failure>()
            .single()

        assertThat(failure.statusCode).isEqualTo(401)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `does not reconnect when the server has no content to send`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val events = client(shouldReconnect = { true }).events().toList()

        assertThat(events.filterIsInstance<EventSourceEvent.Failure>().single().statusCode)
            .isEqualTo(204)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `reports a response that is not an event stream`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))

        val failure = client().events().toList()
            .filterIsInstance<EventSourceEvent.Failure>()
            .single()

        assertThat(failure.cause).hasMessageThat().contains("Invalid content-type")
    }

    @Test
    fun `stops connecting when the collector is cancelled`() = runTest {
        server.enqueue(eventStream("data: one\n\n"))

        val collected = CopyOnWriteArrayList<EventSourceEvent>()
        val collecting = backgroundScope.launch {
            client(shouldReconnect = { true }).events().collect { collected += it }
        }

        while (collected.none { it is EventSourceEvent.Message }) {
            kotlinx.coroutines.yield()
        }
        collecting.cancelAndJoin()

        assertThat(collecting.isCompleted).isTrue()
    }
}
