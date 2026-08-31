package com.configdirector

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigDirectorClientTest {

    private val server = FakeSdkServer()
    private val logger = RecordingLogger()
    private var client: ConfigDirectorClient? = null

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        client?.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun client(
        mode: ConnectionMode = ConnectionMode.STREAMING,
        pollingIntervalMillis: Long = 60_000,
        timeoutMillis: Long = 3_000,
        metadata: Metadata = Metadata.empty(),
        baseUrl: String = server.baseUrl,
    ): ConfigDirectorClient = ConfigDirectorClient(
        "client-sdk-key",
        ClientOptions.build {
            logger(logger)
            metadata(metadata)
            connection {
                mode(mode)
                pollingIntervalMillis(pollingIntervalMillis)
                timeoutMillis(timeoutMillis)
                baseUrl(baseUrl)
            }
        },
    ).also { client = it }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private val proContext = ConfigDirectorContext.build {
        id("user-123")
        name("Ada")
        trait("plan", "pro")
    }

    @Test
    fun `rejects a blank client SDK key`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConfigDirectorClient("   ")
        }

        assertThat(failure).hasMessageThat().contains("No client SDK key was provided")
    }

    @Test
    fun `warns that a base URL which is not HTTPS travels in plain text`() {
        client(baseUrl = "http://proxy.example.com")

        assertThat(logger.messagesContaining("is not HTTPS")).hasSize(1)
    }

    @Test
    fun `says nothing about a base URL that is HTTPS`() {
        client(baseUrl = "https://proxy.example.com")

        assertThat(logger.messagesContaining("is not HTTPS")).isEmpty()
    }

    @Test
    fun `serves default values before it is initialized`() {
        val client = client()

        assertThat(client.isReady).isFalse()
        assertThat(client.getBoolean("dark-mode", false)).isFalse()
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("fallback")
        assertThat(logger.messagesContaining("(client-not-ready)")).hasSize(2)
    }

    @Test
    fun `serves the config state it received once initialized`() = runBlocking {
        val client = client()

        client.initialize(proContext)

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("dark-mode", false)).isTrue()
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, Ada")
        assertThat(client.getInt("max-items", 0)).isEqualTo(25)
        assertThat(client.getDouble("sample-rate", 0.0)).isEqualTo(0.25)
    }

    @Test
    fun `sends the key, the context and the metadata it was built with`() = runBlocking {
        client(metadata = Metadata("Checkout", "4.2.0")).initialize(proContext)

        val request = checkNotNull(server.takeRequest())
        assertThat(request.path).isEqualTo("/client/sse/v1")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("clientSdkKey")).isEqualTo("client-sdk-key")
        assertThat(body.getString("instanceId")).isNotEmpty()
        assertThat(body.getJSONObject("givenContext").getString("id")).isEqualTo("user-123")

        val meta = body.getJSONObject("metaContext")
        assertThat(meta.getString("sdkName")).isEqualTo("android-client-sdk")
        assertThat(meta.getString("sdkVersion")).isNotEmpty()
        assertThat(meta.getString("appName")).isEqualTo("Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("4.2.0")
        assertThat(meta.getString("userAgent")).isEqualTo("Android")
    }

    @Test
    fun `serves a JSON config as its raw document`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getString("theme", "{}")).isEqualTo("""{"primary":"#101010"}""")
    }

    @Test
    fun `truncates a decimal read as a whole number`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getInt("sample-rate", 7)).isEqualTo(0)
    }

    @Test
    fun `falls back when no config carries the key`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("no-such-config", true)).isTrue()
        assertThat(logger.messagesContaining("(config-state-missing)")).hasSize(1)
    }

    @Test
    fun `falls back when the config holds a different type`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("max-items", true)).isTrue()
        assertThat(logger.messagesContaining("(type-mismatch)")).hasSize(1)
    }

    @Test
    fun `falls back when the config has no value for the context`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("beta-banner", true)).isTrue()
        assertThat(logger.messagesContaining("(value-missing)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a boolean`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("welcome-message", true)).isTrue()
        assertThat(logger.messagesContaining("(invalid-boolean)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a whole number`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getInt("welcome-message", 7)).isEqualTo(7)
        assertThat(logger.messagesContaining("(invalid-number)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a decimal number`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getDouble("welcome-message", 1.5)).isEqualTo(1.5)
        assertThat(logger.messagesContaining("(invalid-number)")).hasSize(1)
    }

    @Test
    fun `is initializing only while initializing`() = runBlocking {
        val client = client()
        assertThat(client.isInitializing).isFalse()

        client.initialize()

        assertThat(client.isInitializing).isFalse()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `takes the context it was initialized with`() = runBlocking {
        val client = client()
        assertThat(client.context).isNull()

        client.initialize(proContext)

        assertThat(client.context).isEqualTo(proContext)
    }

    @Test
    fun `re-evaluates against an updated context`() = runBlocking {
        val client = client()
        client.initialize(ConfigDirectorContext.build { id("user-123") })
        assertThat(client.getInt("max-items", 0)).isEqualTo(10)

        client.updateContext(proContext)

        assertThat(client.context).isEqualTo(proContext)
        assertThat(client.isReady).isTrue()
        assertThat(client.getInt("max-items", 0)).isEqualTo(25)
    }

    @Test
    fun `merges a config set that carries only what changed`() = runBlocking {
        val client = client(mode = ConnectionMode.POLLING, pollingIntervalMillis = 50)
        client.initialize()
        assertThat(client.getBoolean("dark-mode", false)).isFalse()

        server.scriptDelta("dark-mode", "boolean", "true")

        waitFor("the delta to arrive") { client.getBoolean("dark-mode", false) }
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, there")
    }

    @Test
    fun `fetches again on the polling interval`() = runBlocking {
        client(mode = ConnectionMode.POLLING, pollingIntervalMillis = 50).initialize()

        waitFor("a second fetch") { server.requestCount >= 2 }
    }

    @Test
    fun `fetches once only when it connects one time`() = runBlocking {
        client(mode = ConnectionMode.ONE_TIME, pollingIntervalMillis = 50).initialize()

        Thread.sleep(300)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `is not ready when the server rejects the request`() = runBlocking {
        server.status = 401
        val client = client()

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(logger.messagesContaining("An error occurred during initialization")).hasSize(1)
    }

    @Test
    fun `warns when no config state arrives in time`() = runBlocking {
        server.sendsConfigState = false
        val client = client(timeoutMillis = 300)

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(logger.messagesContaining("Timed out waiting for initialization")).hasSize(1)
    }

    @Test
    fun `stops talking to the server once closed`() = runBlocking {
        val client = client(mode = ConnectionMode.POLLING, pollingIntervalMillis = 50)
        client.initialize()
        waitFor("a second fetch") { server.requestCount >= 2 }

        client.close()

        val fetched = server.requestCount
        Thread.sleep(300)
        assertThat(server.requestCount).isEqualTo(fetched)
        // A transport left running would go on failing against the client it can no longer use.
        assertThat(logger.messagesContaining("Error during polling")).isEmpty()
    }

    @Test
    fun `keeps serving the last config state once closed`() = runBlocking {
        val client = client()
        client.initialize(proContext)

        client.close()

        assertThat(client.isReady).isFalse()
        assertThat(client.getBoolean("dark-mode", false)).isTrue()
    }

    @Test
    fun `closes only once`() {
        val client = client()

        client.close()
        client.close()

        assertThat(logger.messagesContaining("close() called")).hasSize(1)
    }

    @Test
    fun `will not connect once closed`() = runBlocking {
        val client = client()
        client.close()

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(logger.messagesContaining("The client is closed")).hasSize(1)
    }
}
