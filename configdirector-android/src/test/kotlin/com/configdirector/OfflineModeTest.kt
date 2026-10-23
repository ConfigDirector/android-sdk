package com.configdirector

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineModeTest {

    private val server = FakeSdkServer()
    private val logger = RecordingLogger()
    private val clients = CopyOnWriteArrayList<ConfigDirectorClient>()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1, "main"))
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
        Dispatchers.resetMain()
    }

    private fun offlineOptions(
        values: Map<String, Any>? = null,
        configure: ConnectionOptions.Builder.() -> Unit = {},
    ): ClientOptions = ClientOptions.build {
        logger(logger)
        connection {
            mode(ConnectionMode.OFFLINE)
            baseUrl(server.baseUrl)
            values(values)
            configure()
        }
    }

    private fun offlineClient(
        values: Map<String, Any>? = null,
        configure: ConnectionOptions.Builder.() -> Unit = {},
    ): ConfigDirectorClient =
        ConfigDirectorClient(RuntimeEnvironment.getApplication(), offlineOptions(values, configure))
            .also { clients += it }

    private fun ConfigDirectorClient.recordEvents(): List<ClientEvent> =
        CopyOnWriteArrayList<ClientEvent>().also { events -> addEventListener { events += it } }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun settle() {
        Thread.sleep(200)
    }

    private val proContext = ConfigDirectorContext.build {
        id("user-123")
        trait("plan", "pro")
    }

    private val freeContext = ConfigDirectorContext.build {
        id("user-456")
        trait("plan", "free")
    }

    @Test
    fun `serves each inline value as a config of its value's type`() = runBlocking<Unit> {
        val client = offlineClient(
            values = mapOf(
                "new-checkout" to true,
                "max-items" to 20,
                "sample-rate" to 0.25,
                "greeting" to "hello",
                "theme" to mapOf("color" to "blue"),
                "regions" to listOf("eu", "us"),
            ),
        )

        client.initialize()

        assertThat(client.getBoolean("new-checkout", false)).isTrue()
        assertThat(client.getInt("max-items", 0)).isEqualTo(20)
        assertThat(client.getDouble("sample-rate", 0.0)).isEqualTo(0.25)
        assertThat(client.getString("greeting", "fallback")).isEqualTo("hello")
        assertThat(client.getJsonObject("theme", emptyMap())).containsExactly("color", "blue")
        assertThat(client.getJsonArray("regions", emptyList())).containsExactly("eu", "us").inOrder()
    }

    @Test
    fun `explains a value it serves with the reason found-match`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))

        client.initialize()

        val evaluation = client.evaluateBoolean("new-checkout", false)
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.isDefaultValue).isFalse()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.FOUND_MATCH)
    }

    @Test
    fun `serves the same values whichever context it is initialized with`() = runBlocking<Unit> {
        val proClient = offlineClient(values = mapOf("max-items" to 20))
        val freeClient = offlineClient(values = mapOf("max-items" to 20))

        proClient.initialize(proContext)
        freeClient.initialize(freeContext)

        assertThat(proClient.getInt("max-items", 0)).isEqualTo(20)
        assertThat(freeClient.getInt("max-items", 0)).isEqualTo(20)
    }

    @Test
    fun `serves every in-code default value with config-state-missing when given no values`() =
        runBlocking<Unit> {
            val client = offlineClient()

            client.initialize()

            val evaluation = client.evaluateBoolean("new-checkout", true)
            assertThat(client.isReady).isTrue()
            assertThat(evaluation.value).isEqualTo(true)
            assertThat(evaluation.isDefaultValue).isTrue()
            assertThat(evaluation.reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
        }

    @Test
    fun `serves the in-code default value for a value of another type`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))

        client.initialize()

        val evaluation = client.evaluateString("new-checkout", "fallback")
        assertThat(evaluation.value).isEqualTo("fallback")
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.TYPE_MISMATCH)
    }

    @Test
    fun `is ready when initialize returns and fires the ready event once`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))
        val events = client.recordEvents()

        client.initialize()

        assertThat(client.isReady).isTrue()
        assertThat(client.isInitializing).isFalse()
        waitFor("the ready event") { ClientEvent.Ready(ConnectReason.INITIALIZATION) in events }
        settle()
        assertThat(events.filterIsInstance<ClientEvent.Ready>())
            .containsExactly(ClientEvent.Ready(ConnectReason.INITIALIZATION))
    }

    @Test
    fun `calls a watch with the offline value once`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))
        client.initialize()
        val values = CopyOnWriteArrayList<Boolean>()

        client.watchBoolean("new-checkout", false) { values += it }
        client.updateContext(freeContext)

        waitFor("the offline value") { values.isNotEmpty() }
        settle()
        assertThat(values).containsExactly(true)
    }

    @Test
    fun `takes a new context at once without handing the values over again`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))
        client.initialize(proContext)
        val events = client.recordEvents()

        client.updateContext(freeContext)

        assertThat(client.context).isEqualTo(freeContext)
        assertThat(client.getBoolean("new-checkout", false)).isTrue()
        waitFor("the context updated event") { events.isNotEmpty() }
        settle()
        assertThat(events).containsExactly(ClientEvent.ContextUpdated(freeContext))
    }

    @Test
    fun `stays ready when its network is paused`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))
        client.initialize()

        client.pauseNetwork()

        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `does nothing when its network is resumed`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))
        client.initialize()
        val events = client.recordEvents()

        client.resumeNetwork()

        settle()
        assertThat(events).isEmpty()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `hands the same values over again when initialized a second time`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))
        val events = client.recordEvents()
        val values = CopyOnWriteArrayList<Boolean>()
        client.initialize()
        client.watchBoolean("new-checkout", false) { values += it }

        client.initialize()

        waitFor("the second ready event") { events.count { it is ClientEvent.Ready } == 2 }
        settle()
        assertThat(events.filterIsInstance<ClientEvent.ConfigsUpdated>()).containsExactly(
            ClientEvent.ConfigsUpdated(listOf("new-checkout"), emptyList()),
            ClientEvent.ConfigsUpdated(listOf("new-checkout"), emptyList()),
        )
        assertThat(values).containsExactly(true)
    }

    @Test
    fun `opens no connection and sends no telemetry`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))

        client.initialize(proContext)
        client.updateContext(freeContext)
        client.getBoolean("new-checkout", false)
        client.getString("missing", "fallback")
        client.close()

        assertThat(server.takeRequest(timeoutMillis = 2_000)).isNull()
    }

    @Test
    fun `never reads the app's preferences, package or lifecycle`() = runBlocking<Unit> {
        val reads = CopyOnWriteArrayList<String>()
        val recordingContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                reads += "preferences"
                return super.getSharedPreferences(name, mode)
            }

            override fun getPackageManager(): PackageManager {
                reads += "package"
                return super.getPackageManager()
            }

            override fun getApplicationContext(): Context {
                reads += "application"
                return super.getApplicationContext()
            }
        }
        val client = ConfigDirectorClient(recordingContext, offlineOptions(values = mapOf("new-checkout" to true)))
            .also { clients += it }

        client.initialize()
        client.getBoolean("new-checkout", false)

        assertThat(reads).isEmpty()
    }

    @Test
    fun `accepts a blank client SDK key in offline mode`() = runBlocking<Unit> {
        val client = ConfigDirectorClient(
            RuntimeEnvironment.getApplication(),
            "  ",
            offlineOptions(values = mapOf("new-checkout" to true)),
        ).also { clients += it }

        client.initialize()

        assertThat(client.getBoolean("new-checkout", false)).isTrue()
    }

    @Test
    fun `still requires a client SDK key in streaming mode`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConfigDirectorClient(
                RuntimeEnvironment.getApplication(),
                ClientOptions.build { connection { mode(ConnectionMode.STREAMING) } },
            )
        }

        assertThat(failure).hasMessageThat().contains("No client SDK key was provided")
    }

    @Test
    fun `ignores the polling interval, timeout and base URL in offline mode`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true)) {
            pollingIntervalMillis(1)
            timeoutMillis(1)
            baseUrl("http://proxy.example.com")
        }

        client.initialize()

        assertThat(client.getBoolean("new-checkout", false)).isTrue()
        assertThat(logger.messagesContaining("WARN: ")).isEmpty()
    }

    @Test
    fun `creates an offline client without a client SDK key`() = runBlocking<Unit> {
        val client = offlineClient(values = mapOf("new-checkout" to true))

        client.initialize()

        assertThat(client.getBoolean("new-checkout", false)).isTrue()
    }
}
