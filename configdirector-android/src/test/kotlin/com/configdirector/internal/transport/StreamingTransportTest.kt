package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.LogLevel
import com.configdirector.RecordingLogger
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigType
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class StreamingTransportTest {

    private val server = MockWebServer()
    private val logger = RecordingLogger(LogLevel.DEBUG)
    private val received = CopyOnWriteArrayList<ConfigSet>()
    private var transport: StreamingTransport? = null

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        transport?.close()
        server.shutdown()
    }

    private fun streaming(retryDelayMillis: Long = 20): StreamingTransport = StreamingTransport(
        TransportOptions(
            clientSdkKey = "client-sdk-key",
            baseUrl = server.url("/").toString(),
            metaContext = SdkMetaContext(
                sdkName = "android-client-sdk",
                sdkVersion = "0.1.0",
                appName = "Sample",
                appVersion = "1.0",
                userAgent = "Android",
            ),
            instanceId = "instance-1",
            logger = logger,
            pollingIntervalMillis = 60_000,
            httpClient = OkHttpClient(),
            retryDelayMillis = { retryDelayMillis },
        ),
    ) { received += it }.also { transport = it }

    private val configSetEvent =
        """{"kind":"full","timestamp":"2026-08-31T00:00:00Z","configs":""" +
            """{"dark-mode":{"id":"c1","key":"dark-mode","type":"boolean","value":"true","valueId":"v1"}}}"""

    private fun eventStream(vararg data: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    private val context = ConfigDirectorContext.build {
        id("user-123")
        name("Ada")
        trait("plan", "pro")
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    @Test
    fun `posts the context, the metadata and the key`() = runBlocking {
        server.enqueue(eventStream(configSetEvent))

        streaming().connect(context, timeoutMillis = 3_000)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/client/sse/v1")
        assertThat(request.getHeader("Accept")).isEqualTo("text/event-stream")
        assertThat(request.getHeader("Content-Type")).contains("application/json")

        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("clientSdkKey")).isEqualTo("client-sdk-key")
        assertThat(body.getString("instanceId")).isEqualTo("instance-1")
        assertThat(body.getJSONObject("givenContext").getString("id")).isEqualTo("user-123")
        assertThat(body.getJSONObject("givenContext").getJSONObject("traits").getString("plan"))
            .isEqualTo("pro")
        assertThat(body.getJSONObject("metaContext").getString("sdkName"))
            .isEqualTo("android-client-sdk")
    }

    @Test
    fun `hands over every config set the server sends`() = runBlocking {
        server.enqueue(eventStream(configSetEvent, configSetEvent))

        streaming().connect(context, timeoutMillis = 3_000)
        waitFor("two config sets") { received.size == 2 }

        val configSet = received.first()
        assertThat(configSet.timestamp).isEqualTo("2026-08-31T00:00:00Z")
        val darkMode = configSet.configs.getValue("dark-mode")
        assertThat(darkMode.type).isEqualTo(ConfigType.BOOLEAN)
        assertThat(darkMode.value).isEqualTo("true")
        assertThat(darkMode.valueId).isEqualTo("v1")
    }

    @Test
    fun `keeps reading after a config set it cannot parse`() = runBlocking {
        server.enqueue(eventStream("this is not json", configSetEvent))

        streaming().connect(context, timeoutMillis = 3_000)
        waitFor("the config set after the bad one") { received.size == 1 }

        assertThat(logger.messagesContaining("Error parsing")).hasSize(1)
    }

    @Test
    fun `reconnects after a connection that failed for a reason worth retrying`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(eventStream(configSetEvent))

        streaming().connect(context, timeoutMillis = 3_000)
        waitFor("the config set from the second connection") { received.size == 1 }

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(logger.messagesContaining("Scheduling reconnect attempt #1").first())
            .startsWith("INFO")
    }

    @Test
    fun `warns once reconnect attempts stop looking routine`() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(eventStream(configSetEvent))

        streaming(retryDelayMillis = 5).connect(context, timeoutMillis = 3_000)

        assertThat(logger.messagesContaining("Scheduling reconnect attempt #5").first())
            .startsWith("INFO")
        assertThat(logger.messagesContaining("Scheduling reconnect attempt #6").first())
            .startsWith("WARN")
    }

    @Test
    fun `refuses to reconnect after an unrecoverable status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val transport = streaming()

        val failure = connectFailure(transport)

        assertThat(failure.statusCode).isEqualTo(401)
        assertThat(failure).hasMessageThat().contains("Connection failed with status: 401")
        assertThat(failure).hasMessageThat().contains("unrecoverable")
        Thread.sleep(200)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `logs the unrecoverable status when the connection had already opened`() = runBlocking {
        server.enqueue(eventStream(configSetEvent))
        server.enqueue(MockResponse().setResponseCode(403))

        streaming().connect(context, timeoutMillis = 3_000)
        waitFor("the rejected reconnection") {
            logger.messagesContaining("Connection failed with status: 403").isNotEmpty()
        }

        assertThat(logger.messagesContaining("Connection failed with status: 403").single())
            .startsWith("ERROR")
    }

    @Test
    fun `throws when the stream ends for good without ever opening`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val failure = connectFailure(streaming())

        assertThat(failure).hasMessageThat().contains("will not be retried")
    }

    @Test
    fun `throws when the server does not answer in time`() = runBlocking {
        server.enqueue(eventStream(configSetEvent).setHeadersDelay(2, TimeUnit.SECONDS))

        val failure = connectFailure(streaming(), timeoutMillis = 200)

        assertThat(failure).hasMessageThat().contains("timed out after 200ms")
    }

    @Test
    fun `stops streaming once disconnected`() = runBlocking {
        server.enqueue(eventStream(configSetEvent))
        val transport = streaming(retryDelayMillis = 400)
        transport.connect(context, timeoutMillis = 3_000)
        assertThat(server.requestCount).isEqualTo(1)

        transport.disconnect()

        Thread.sleep(800)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `does not connect once closed`() = runBlocking {
        server.enqueue(eventStream(configSetEvent))
        val transport = streaming()
        transport.close()

        transport.connect(context, timeoutMillis = 3_000)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(received).isEmpty()
    }

    private fun connectFailure(
        transport: StreamingTransport,
        timeoutMillis: Long = 3_000,
    ): ConnectionFailedException = assertThrows(ConnectionFailedException::class.java) {
        runBlocking { transport.connect(context, timeoutMillis) }
    }
}
