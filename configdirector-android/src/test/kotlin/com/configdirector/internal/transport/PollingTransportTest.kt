package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.LogLevel
import com.configdirector.RecordingLogger
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigSetKind
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

class PollingTransportTest {

    private val server = MockWebServer()
    private val logger = RecordingLogger(LogLevel.DEBUG)
    private val received = CopyOnWriteArrayList<ConfigSet>()
    private var transport: PollingTransport? = null

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        transport?.close()
        server.shutdown()
    }

    private fun options(pollingIntervalMillis: Long = 60_000) = TransportOptions(
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
        pollingIntervalMillis = pollingIntervalMillis,
        httpClient = OkHttpClient(),
    )

    private fun polling(pollingIntervalMillis: Long = 60_000): PollingTransport =
        PollingTransport(options(pollingIntervalMillis)) { received += it }
            .also { transport = it }

    private fun oneTime(): PollingTransport =
        PollingTransport.oneTime(options()) { received += it }.also { transport = it }

    private fun configSetResponse(
        body: String = """
        {
          "environmentId": "env-1",
          "projectId": "project-1",
          "kind": "full",
          "timestamp": "2026-08-31T00:00:00Z",
          "configs": {
            "dark-mode": {"id": "c1", "key": "dark-mode", "type": "boolean", "value": "true", "valueId": "v1"},
            "beta-banner": {"id": "c2", "key": "beta-banner", "type": "boolean", "value": null}
          }
        }
        """.trimIndent(),
    ) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val context = ConfigDirectorContext.build {
        id("user-123")
        name("Ada")
        trait("plan", "pro")
        trait("seats", 12)
        trait("regions", listOf("us-east", null))
    }

    @Test
    fun `posts the context, the metadata and the key`() = runBlocking {
        server.enqueue(configSetResponse())

        polling().connect(context, timeoutMillis = 3_000)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/client/polling/v1")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("clientSdkKey")).isEqualTo("client-sdk-key")
        assertThat(body.getString("instanceId")).isEqualTo("instance-1")
        assertThat(body.has("lastUpdateTimestamp")).isFalse()

        val given = body.getJSONObject("givenContext")
        assertThat(given.getString("id")).isEqualTo("user-123")
        assertThat(given.getString("name")).isEqualTo("Ada")
        assertThat(given.getBoolean("anonymous")).isFalse()
        assertThat(given.getJSONObject("traits").getString("plan")).isEqualTo("pro")
        assertThat(given.getJSONObject("traits").getInt("seats")).isEqualTo(12)
        assertThat(given.getJSONObject("traits").getJSONArray("regions").getString(0))
            .isEqualTo("us-east")
        assertThat(given.getJSONObject("traits").getJSONArray("regions").isNull(1)).isTrue()

        val meta = body.getJSONObject("metaContext")
        assertThat(meta.getString("sdkName")).isEqualTo("android-client-sdk")
        assertThat(meta.getString("sdkVersion")).isEqualTo("0.1.0")
        assertThat(meta.getString("appName")).isEqualTo("Sample")
        assertThat(meta.getString("appVersion")).isEqualTo("1.0")
        assertThat(meta.getString("userAgent")).isEqualTo("Android")
    }

    @Test
    fun `hands over the config state the server sent`() = runBlocking {
        server.enqueue(configSetResponse())

        polling().connect(context, timeoutMillis = 3_000)

        val configSet = received.single()
        assertThat(configSet.kind).isEqualTo(ConfigSetKind.FULL)
        assertThat(configSet.timestamp).isEqualTo("2026-08-31T00:00:00Z")
        assertThat(configSet.configs.keys).containsExactly("dark-mode", "beta-banner")

        val darkMode = configSet.configs.getValue("dark-mode")
        assertThat(darkMode.type).isEqualTo(ConfigType.BOOLEAN)
        assertThat(darkMode.value).isEqualTo("true")
        assertThat(darkMode.valueId).isEqualTo("v1")
        // A config the server serves no value for is not the same as one holding an empty string.
        assertThat(configSet.configs.getValue("beta-banner").value).isNull()
    }

    @Test
    fun `reads a config set carrying only what changed`() = runBlocking {
        server.enqueue(
            configSetResponse(
                """{"kind": "delta", "configs": {"dark-mode": {"type": "boolean", "value": "false"}}}""",
            ),
        )

        polling().connect(context, timeoutMillis = 3_000)

        assertThat(received.single().kind).isEqualTo(ConfigSetKind.DELTA)
    }

    @Test
    fun `reads a config type it does not know as a custom one`() = runBlocking {
        server.enqueue(
            configSetResponse("""{"configs": {"odd": {"type": "quantum", "value": "1"}}}"""),
        )

        polling().connect(context, timeoutMillis = 3_000)

        assertThat(received.single().configs.getValue("odd").type).isEqualTo(ConfigType.CUSTOM)
    }

    @Test
    fun `echoes the last timestamp it was given on the next fetch`() = runBlocking {
        server.enqueue(configSetResponse())
        server.enqueue(configSetResponse())
        val transport = polling()

        transport.connect(context, timeoutMillis = 3_000)
        transport.connect(context, timeoutMillis = 3_000)

        server.takeRequest()
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertThat(second.getString("lastUpdateTimestamp")).isEqualTo("2026-08-31T00:00:00Z")
    }

    @Test
    fun `fetches again on the polling interval`() = runBlocking {
        repeat(3) { server.enqueue(configSetResponse()) }

        polling(pollingIntervalMillis = 50).connect(context, timeoutMillis = 3_000)

        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
    }

    @Test
    fun `fetches once only when it is one-time`() = runBlocking {
        repeat(2) { server.enqueue(configSetResponse()) }

        oneTime().connect(context, timeoutMillis = 3_000)

        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull()
        assertThat(server.takeRequest(500, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `stops fetching once disconnected`() = runBlocking {
        repeat(4) { server.enqueue(configSetResponse()) }
        val transport = polling(pollingIntervalMillis = 50)
        transport.connect(context, timeoutMillis = 3_000)
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()

        transport.disconnect()

        // Whatever poll was already in flight may still land, so this drains one before waiting.
        server.takeRequest(200, TimeUnit.MILLISECONDS)
        assertThat(server.takeRequest(500, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `refuses to reconnect after an unrecoverable status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid client SDK key"))
        val transport = polling(pollingIntervalMillis = 50)

        val failure = connectFailure(transport)

        assertThat(failure.statusCode).isEqualTo(401)
        assertThat(failure).hasMessageThat().contains("invalid client SDK key")
        assertThat(failure).hasMessageThat().contains("unrecoverable")

        transport.connect(context, timeoutMillis = 3_000)

        // The failed fetch is the only request the server ever sees, and polling never started.
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(logger.messagesContaining("Ignoring attempt to reconnect")).hasSize(1)
    }

    @Test
    fun `keeps polling after a status worth retrying`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(configSetResponse())
        val transport = polling(pollingIntervalMillis = 50)

        val failure = connectFailure(transport)

        assertThat(failure.statusCode).isEqualTo(503)
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
    }

    @Test
    fun `throws when the server sends something that is not a config set`() = runBlocking {
        server.enqueue(configSetResponse("this is not json"))

        val failure = connectFailure(polling())

        assertThat(failure).hasMessageThat().contains("Failed to parse the response")
    }

    @Test
    fun `hands over nothing when the server has no config state to send`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        polling().connect(context, timeoutMillis = 3_000)

        assertThat(received).isEmpty()
    }

    @Test
    fun `throws when the server does not answer in time`() = runBlocking {
        server.enqueue(configSetResponse().setHeadersDelay(2, TimeUnit.SECONDS))

        val failure = connectFailure(polling(), timeoutMillis = 200)

        assertThat(failure).hasMessageThat().contains("timed out after 200ms")
    }

    private fun connectFailure(
        transport: PollingTransport,
        timeoutMillis: Long = 3_000,
    ): ConnectionFailedException = assertThrows(ConnectionFailedException::class.java) {
        runBlocking { transport.connect(context, timeoutMillis) }
    }
}
