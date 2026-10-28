package com.configdirector.openfeature

import com.configdirector.ConfigDirectorContext
import com.configdirector.testing.TestClient
import com.configdirector.testing.createTestClient
import com.google.common.truth.Truth.assertThat
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import kotlin.reflect.KClass

/**
 * The provider over a test client from the testing tools, the way a consumer's test creates it.
 * Runs without Robolectric on purpose: the test client needs no Android, and this is what proves
 * the provider over it needs none either.
 */
@OptIn(ExperimentalCoroutinesApi::class, ConfigDirectorProviderTestingApi::class)
class TestClientProviderTest {

    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val testClients = mutableListOf<TestClient>()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        collectors.cancel()
        testClients.forEach { it.client.close() }
        Dispatchers.resetMain()
    }

    private fun testClient(values: Map<String, Any>, timeoutMillis: Long = 3_000): TestClient =
        createTestClient(values, timeoutMillis, SilentLogger).also { testClients += it }

    private fun observing(provider: ConfigDirectorProvider): List<OpenFeatureProviderEvents> {
        val events = CopyOnWriteArrayList<OpenFeatureProviderEvents>()
        collectors.launch { provider.observe().collect { events += it } }
        return events
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private val proContext = ImmutableContext(targetingKey = "user-123")
    private val freeContext = ImmutableContext(targetingKey = "user-456")

    @Test
    fun `resolves the values of the test client`() = runBlocking<Unit> {
        val testClient = testClient(
            mapOf(
                "dark-mode" to true,
                "welcome-message" to "Hello",
                "max-items" to 25,
                "sample-rate" to 0.25,
                "theme" to mapOf("primary" to "#101010", "spacing" to mapOf("small" to 4)),
                "feature-list" to listOf("alpha", "beta"),
            ),
        )
        val provider = ConfigDirectorProvider(testClient.client)

        provider.initialize(proContext)

        val darkMode = provider.getBooleanEvaluation("dark-mode", false, null)
        assertThat(darkMode.value).isTrue()
        assertThat(darkMode.reason).isEqualTo(Reason.TARGETING_MATCH.name)
        assertThat(darkMode.errorCode).isNull()
        assertThat(provider.getStringEvaluation("welcome-message", "fallback", null).value).isEqualTo("Hello")
        assertThat(provider.getIntegerEvaluation("max-items", 0, null).value).isEqualTo(25)
        assertThat(provider.getDoubleEvaluation("sample-rate", 0.0, null).value).isEqualTo(0.25)
        assertThat(provider.getObjectEvaluation("theme", Value.Structure(emptyMap()), null).value).isEqualTo(
            Value.Structure(
                mapOf(
                    "primary" to Value.String("#101010"),
                    "spacing" to Value.Structure(mapOf("small" to Value.Integer(4))),
                ),
            ),
        )
        assertThat(provider.getObjectEvaluation("feature-list", Value.List(emptyList()), null).value)
            .isEqualTo(Value.List(listOf(Value.String("alpha"), Value.String("beta"))))
        assertThrows(OpenFeatureError.FlagNotFoundError::class.java) {
            provider.getStringEvaluation("no-such-config", "fallback", null)
        }
    }

    @Test
    fun `follows a value set on the test client and reports it as a configuration change`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to false, "max-items" to 25))
        val provider = ConfigDirectorProvider(testClient.client)
        val events = observing(provider)
        provider.initialize(proContext)
        waitFor("the initial configuration change") { events.changedFlags().isNotEmpty() }

        testClient.setValue("dark-mode", true)

        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()
        waitFor("the configuration change") { events.changedFlags().size == 2 }
        assertThat(events.changedFlags()).containsExactly(setOf("dark-mode", "max-items"), setOf("dark-mode")).inOrder()
    }

    private fun List<OpenFeatureProviderEvents>.changedFlags(): List<Set<String>?> =
        filterIsInstance<OpenFeatureProviderEvents.ProviderConfigurationChanged>().map { it.eventDetails?.flagsChanged }

    @Test
    fun `emits a configuration change and no ready event when the network resumes`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        val provider = ConfigDirectorProvider(testClient.client)
        provider.initialize(proContext)
        val events = observing(provider)

        testClient.client.pauseNetwork()
        testClient.client.resumeNetwork()

        waitFor("the configuration change") { events.changedFlags().isNotEmpty() }
        Thread.sleep(200)
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderReady>()).isEmpty()
    }

    @Test
    fun `emits ready once a context update succeeds after initialization failed`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        testClient.failInitialization()
        val provider = ConfigDirectorProvider(testClient.client)
        val events = observing(provider)
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }
        waitFor("the error event") { events.any { it is OpenFeatureProviderEvents.ProviderError } }

        provider.onContextSet(proContext, freeContext)

        waitFor("the ready event") { events.any { it is OpenFeatureProviderEvents.ProviderReady } }
        Thread.sleep(200)
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderReady>()).hasSize(1)
    }

    @Test
    fun `emits no ready event when the network resumes after a recovery`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        testClient.failInitialization()
        val provider = ConfigDirectorProvider(testClient.client)
        val events = observing(provider)
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }
        waitFor("the error event") { events.any { it is OpenFeatureProviderEvents.ProviderError } }
        provider.onContextSet(proContext, freeContext)
        waitFor("the ready event of the recovery") { events.any { it is OpenFeatureProviderEvents.ProviderReady } }

        testClient.client.pauseNetwork()
        testClient.client.resumeNetwork()

        waitFor("the configuration change of the resumed connection") { events.changedFlags().size == 2 }
        Thread.sleep(200)
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderReady>()).hasSize(1)
    }

    @Test
    fun `emits ready once after the error when a context update succeeds right after a failed one`() =
        runBlocking<Unit> {
            val testClient = testClient(mapOf("dark-mode" to true))
            testClient.failInitialization()
            val provider = ConfigDirectorProvider(testClient.client)
            val events = observing(provider)
            assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
                runBlocking { provider.initialize(proContext) }
            }
            waitFor("the error event of initialization") { events.any { it is OpenFeatureProviderEvents.ProviderError } }
            testClient.failContextUpdate()
            assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
                runBlocking { provider.onContextSet(proContext, freeContext) }
            }

            provider.onContextSet(freeContext, proContext)

            waitFor("the ready event") { events.any { it is OpenFeatureProviderEvents.ProviderReady } }
            Thread.sleep(200)
            assertThat(events.statusEvents())
                .containsExactly(
                    OpenFeatureProviderEvents.ProviderError::class,
                    OpenFeatureProviderEvents.ProviderError::class,
                    OpenFeatureProviderEvents.ProviderReady::class,
                )
                .inOrder()
        }

    private fun List<OpenFeatureProviderEvents>.statusEvents(): List<KClass<out OpenFeatureProviderEvents>> =
        filterNot { it is OpenFeatureProviderEvents.ProviderConfigurationChanged }.map { it::class }

    @Test
    fun `throws FlagNotFoundError once the test client removes a value`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        val provider = ConfigDirectorProvider(testClient.client)
        provider.initialize(proContext)

        testClient.removeValue("dark-mode")

        assertThrows(OpenFeatureError.FlagNotFoundError::class.java) {
            provider.getBooleanEvaluation("dark-mode", false, null)
        }
    }

    @Test
    fun `s37 shutting the provider down leaves the test client open`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to false))
        val provider = ConfigDirectorProvider(testClient.client)
        provider.initialize(proContext)

        provider.shutdown()
        testClient.setValue("dark-mode", true)

        assertThat(testClient.client.isReady).isTrue()
        assertThat(testClient.client.getBoolean("dark-mode", false)).isTrue()
    }

    @Test
    fun `initializes the test client again when it was already initialized`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        testClient.client.initialize(ConfigDirectorContext.build { id("user-123") })
        val provider = ConfigDirectorProvider(testClient.client)

        provider.initialize(freeContext)

        assertThat(testClient.client.isReady).isTrue()
        assertThat(testClient.contextUpdates.map { it.id }).containsExactly("user-123", "user-456").inOrder()
    }

    @Test
    fun `updates the test client's context when the evaluation context changes`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        val provider = ConfigDirectorProvider(testClient.client)
        provider.initialize(proContext)

        provider.onContextSet(proContext, freeContext)

        assertThat(testClient.client.isReady).isTrue()
        assertThat(testClient.contextUpdates.map { it.id }).containsExactly("user-123", "user-456").inOrder()
    }

    @Test
    fun `initializes once a held initialization completes`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        testClient.holdInitialization()
        val provider = ConfigDirectorProvider(testClient.client)

        val initialization = launch(Dispatchers.Default) { provider.initialize(proContext) }
        waitFor("initialize to start") { testClient.client.isInitializing }
        Thread.sleep(100)
        assertThat(initialization.isCompleted).isFalse()
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("dark-mode", false, null)
        }

        testClient.completeInitialization()
        initialization.join()

        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()
    }

    @Test
    fun `reports it is not ready when a held initialization times out`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true), timeoutMillis = 200)
        testClient.holdInitialization()
        val provider = ConfigDirectorProvider(testClient.client)

        val failure = assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }

        assertThat(failure).hasMessageThat().contains("did not become ready during initialization")
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("dark-mode", false, null)
        }
    }

    @Test
    fun `reports it is not ready when initialization fails`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true))
        testClient.failInitialization()
        val provider = ConfigDirectorProvider(testClient.client)

        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }

        assertThat(testClient.client.isReady).isFalse()
    }
}
