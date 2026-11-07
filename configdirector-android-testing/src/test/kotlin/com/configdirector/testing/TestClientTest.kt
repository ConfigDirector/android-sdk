package com.configdirector.testing

import com.configdirector.ClientEvent
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorTestingApi
import com.configdirector.ConfigDirectorValidationException
import com.configdirector.ConnectReason
import com.configdirector.EvaluationReason
import com.configdirector.TrackOptions
import com.configdirector.internal.testing.InMemoryConnection
import com.configdirector.testing.internal.Constants
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

private val USER_A = ConfigDirectorContext.build { id("user-a") }
private val USER_B = ConfigDirectorContext.build { id("user-b") }

/**
 * Runs without Robolectric on purpose: a consumer's plain JVM test is the first place the test
 * client has to work, and this suite is what proves it needs nothing from Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestClientTest {

    private val logger = RecordingLogger()
    private val testClients = mutableListOf<TestClient>()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        testClients.forEach { it.client.close() }
        Dispatchers.resetMain()
    }

    private fun testClient(
        values: Map<String, Any> = emptyMap(),
        timeoutMillis: Long = 3_000,
    ): TestClient = createTestClient(values, timeoutMillis, logger).also { testClients += it }

    private fun TestClient.recordEvents(): List<ClientEvent> =
        CopyOnWriteArrayList<ClientEvent>().also { events -> client.addEventListener { events += it } }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun elapsedMillisSince(startedAt: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun errorsLogged(text: String) = logger.messagesContaining("ERROR: ").filter { text in it }

    @Test
    fun `starts uninitialized with no values`() = runBlocking<Unit> {
        val testClient = createTestClient().also { testClients += it }
        val client = testClient.client
        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isFalse()

        client.initialize()

        assertThat(client.isReady).isTrue()
        assertThat(client.evaluateBoolean("flag", false).reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
    }

    @Test
    fun `serves a value without a variation`() = runBlocking<Unit> {
        val client = testClient(values = mapOf("flag" to true)).client

        client.initialize()

        val evaluation = client.evaluateBoolean("flag", false)
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.variation).isNull()
    }

    @Test
    fun `S1 reads every seeded value with a matching accessor`() = runBlocking<Unit> {
        val client = testClient(
            values = mapOf(
                "flag" to true,
                "count" to 20,
                "big" to 3_000_000_000L,
                "ratio" to 2.5,
                "name" to "Ada",
                "settings" to mapOf("theme" to "dark", "limit" to 3),
                "tags" to listOf("x", "y"),
            ),
        ).client

        client.initialize()

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(client.getInt("count", 0)).isEqualTo(20)
        assertThat(client.getDouble("big", 0.0)).isEqualTo(3e9)
        assertThat(client.getDouble("ratio", 0.0)).isEqualTo(2.5)
        assertThat(client.getString("name", "")).isEqualTo("Ada")
        val settings = client.getJsonObject("settings", emptyMap())
        assertThat(settings["theme"]).isEqualTo("dark")
        assertThat((settings["limit"] as Number).toInt()).isEqualTo(3)
        assertThat(client.getJsonArray("tags", emptyList())).containsExactly("x", "y").inOrder()
    }

    @Test
    fun `rejects invalid values without changing anything`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        testClient.client.initialize()

        assertThrows(ConfigDirectorValidationException::class.java) { testClient.setValue("", true) }
        assertThrows(ConfigDirectorValidationException::class.java) { testClient.setValue("when", 'c') }
        assertThrows(ConfigDirectorValidationException::class.java) {
            testClient.replaceValues(mapOf("flag" to false, "ratio" to Double.NaN))
        }
        assertThrows(ConfigDirectorValidationException::class.java) {
            createTestClient(mapOf("k" to mapOf(1 to 2)))
        }

        assertThat(testClient.client.getBoolean("flag", false)).isTrue()
    }

    @Test
    fun `S3 setValue on a connected client changes the next read`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        testClient.client.initialize()

        testClient.setValue("flag", false)

        assertThat(testClient.client.getBoolean("flag", true)).isFalse()
    }

    @Test
    fun `S4 setValue fires the watcher and lists the key`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to false))
        val client = testClient.client
        val seen = CopyOnWriteArrayList<Boolean>()
        val events = testClient.recordEvents()
        client.watchBoolean("dark-mode", false) { seen += it }
        client.initialize()

        testClient.setValue("dark-mode", true)

        assertThat(seen).containsExactly(false, true).inOrder()
        val update = events.last() as ClientEvent.ConfigsUpdated
        assertThat(update.keys).containsExactly("dark-mode")
        assertThat(update.removedKeys).isEmpty()
    }

    @Test
    fun `S7 removeValue hands the watcher the default and reports the key as removed`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("dark-mode" to true, "other" to 1))
        val client = testClient.client
        val seen = CopyOnWriteArrayList<Boolean>()
        val events = testClient.recordEvents()
        client.watchBoolean("dark-mode", false) { seen += it }
        client.initialize()

        testClient.removeValue("dark-mode")

        assertThat(seen).containsExactly(false, true, false).inOrder()
        assertThat(client.evaluateBoolean("dark-mode", false).reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
        val update = events.last() as ClientEvent.ConfigsUpdated
        assertThat(update.keys).containsExactly("other")
        assertThat(update.removedKeys).containsExactly("dark-mode")
    }

    @Test
    fun `S32 replaceValues serves exactly the new values`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("a" to 1, "b" to 2))
        val client = testClient.client
        client.initialize()

        testClient.replaceValues(mapOf("a" to 10, "c" to 30))

        assertThat(client.getInt("a", 0)).isEqualTo(10)
        assertThat(client.getInt("c", 0)).isEqualTo(30)
        assertThat(client.evaluateInt("b", 0).reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
    }

    @Test
    fun `S15 two test clients never share values`() = runBlocking<Unit> {
        val first = testClient(mapOf("flag" to true))
        val second = testClient(mapOf("flag" to false, "only-second" to 1))
        first.client.initialize()
        second.client.initialize()

        first.setValue("flag", false)
        second.setValue("only-second", 2)

        assertThat(first.client.getBoolean("flag", true)).isFalse()
        assertThat(second.client.getBoolean("flag", true)).isFalse()
        assertThat(first.client.evaluateInt("only-second", 0).reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
        assertThat(second.client.getInt("only-second", 0)).isEqualTo(2)
    }

    @Test
    fun `S9 a held initialize stays pending until completed`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        val client = testClient.client
        val events = testClient.recordEvents()
        testClient.holdInitialization()

        val initialization = launch(Dispatchers.Default) { client.initialize() }
        waitFor("initialize to start") { client.isInitializing }
        Thread.sleep(100)
        assertThat(client.isReady).isFalse()
        assertThat(client.getBoolean("flag", false)).isFalse()

        testClient.completeInitialization()
        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()

        initialization.join()
        assertThat(events.filterIsInstance<ClientEvent.Ready>().single().reason).isEqualTo(ConnectReason.INITIALIZATION)
    }

    @Test
    fun `S11 a held initialize times out after the given timeout, not ready`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true), timeoutMillis = 100)
        val client = testClient.client
        testClient.holdInitialization()

        val startedAt = System.nanoTime()
        client.initialize()

        assertThat(elapsedMillisSince(startedAt)).isAtLeast(100)
        assertThat(elapsedMillisSince(startedAt)).isLessThan(1_000)
        assertThat(client.isReady).isFalse()
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("timed out after 100ms")
    }

    @Test
    fun `a held initialize waits the SDK's default timeout of 3 seconds`() = runBlocking<Unit> {
        val testClient = createTestClient(mapOf("flag" to true), logger = logger).also { testClients += it }
        val client = testClient.client
        testClient.holdInitialization()

        val startedAt = System.nanoTime()
        client.initialize()

        assertThat(elapsedMillisSince(startedAt)).isAtLeast(2_500)
        assertThat(elapsedMillisSince(startedAt)).isLessThan(6_000)
        assertThat(client.isReady).isFalse()
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("timed out after 3000ms")
    }

    @Test
    fun `S12 a failed initialize completes promptly and not ready`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true), timeoutMillis = 500)
        val client = testClient.client
        testClient.failInitialization()

        val startedAt = System.nanoTime()
        client.initialize(USER_A)

        assertThat(elapsedMillisSince(startedAt)).isLessThan(250)
        assertThat(client.isReady).isFalse()
        assertThat(client.context).isNull()
        assertThat(errorsLogged("An error occurred during initialization")).hasSize(1)
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("failed this initialization")
    }

    @Test
    fun `S38 completeInitialization before initialize disarms the hold`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        testClient.holdInitialization()
        testClient.completeInitialization()

        val startedAt = System.nanoTime()
        testClient.client.initialize()

        assertThat(elapsedMillisSince(startedAt)).isLessThan(1_000)
        assertThat(testClient.client.isReady).isTrue()
    }

    @Test
    fun `S40 replaceValues disarms a hold`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        testClient.holdInitialization()

        testClient.replaceValues(mapOf("count" to 1))
        val startedAt = System.nanoTime()
        testClient.client.initialize()

        assertThat(elapsedMillisSince(startedAt)).isLessThan(1_000)
        assertThat(testClient.client.isReady).isTrue()
        assertThat(testClient.client.getInt("count", 0)).isEqualTo(1)
    }

    @Test
    fun `S13 contextUpdates records initialize and updateContext only`() = runBlocking<Unit> {
        val testClient = testClient()
        val client = testClient.client

        client.initialize(USER_A)
        client.updateContext(USER_B)
        client.pauseNetwork()
        client.resumeNetwork()

        assertThat(testClient.contextUpdates).containsExactly(USER_A, USER_B).inOrder()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `contextUpdates records an empty context for an initialization whose context kind is invalid`() =
        runBlocking<Unit> {
            val testClient = testClient()
            val client = testClient.client

            client.initialize(ConfigDirectorContext.build { id("device-42"); kind("Device") })

            assertThat(testClient.contextUpdates).containsExactly(ConfigDirectorContext.empty())
            assertThat(client.context).isNull()
            assertThat(errorsLogged("'Device'")).hasSize(1)
        }

    @Test
    fun `contextUpdates records an empty context for an update whose context kind is invalid`() = runBlocking<Unit> {
        val testClient = testClient()
        val client = testClient.client

        client.initialize(USER_A)
        client.updateContext(ConfigDirectorContext.build { id("device-42"); kind("Device") })

        assertThat(testClient.contextUpdates).containsExactly(USER_A, ConfigDirectorContext.empty()).inOrder()
        assertThat(client.context).isNull()
        assertThat(errorsLogged("'Device'")).hasSize(1)
    }

    @Test
    fun `S26 a held updateContext stays pending until completed`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        val client = testClient.client
        client.initialize(USER_A)
        val events = testClient.recordEvents()
        testClient.holdContextUpdate()

        val update = launch(Dispatchers.Default) { client.updateContext(USER_B) }
        waitFor("updateContext to start") { !client.isReady }
        Thread.sleep(100)
        assertThat(client.context).isEqualTo(USER_A)

        testClient.completeContextUpdate()
        update.join()

        assertThat(client.isReady).isTrue()
        assertThat(client.context).isEqualTo(USER_B)
        assertThat(events.filterIsInstance<ClientEvent.Ready>().single().reason).isEqualTo(ConnectReason.CONTEXT_UPDATE)
    }

    @Test
    fun `S42 an initialization hold never holds an updateContext`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        val client = testClient.client
        client.initialize(USER_A)
        testClient.holdInitialization()

        val startedAt = System.nanoTime()
        client.updateContext(USER_B)

        assertThat(elapsedMillisSince(startedAt)).isLessThan(1_000)
        assertThat(client.isReady).isTrue()
        assertThat(client.context).isEqualTo(USER_B)
    }

    @Test
    fun `S43 a failed updateContext completes promptly and serves the last values`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true), timeoutMillis = 500)
        val client = testClient.client
        client.initialize(USER_A)
        testClient.failContextUpdate()

        val startedAt = System.nanoTime()
        client.updateContext(USER_B)

        assertThat(elapsedMillisSince(startedAt)).isLessThan(250)
        assertThat(client.isReady).isFalse()
        assertThat(client.context).isEqualTo(USER_A)
        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(errorsLogged("An error occurred during context update")).hasSize(1)
    }

    @Test
    fun `S14 the controls are silent no-ops after close`() = runBlocking<Unit> {
        val testClient = testClient(mapOf("flag" to true))
        val client = testClient.client
        val seen = CopyOnWriteArrayList<Boolean>()
        client.watchBoolean("flag", false) { seen += it }
        client.initialize()
        val before = seen.size

        client.close()
        testClient.setValue("flag", false)
        testClient.removeValue("flag")
        testClient.replaceValues(mapOf("count" to 1))
        testClient.holdInitialization()
        testClient.completeInitialization()
        testClient.failInitialization()
        testClient.holdContextUpdate()
        testClient.completeContextUpdate()
        testClient.failContextUpdate()

        assertThat(seen).hasSize(before)
        assertThat(client.isReady).isFalse()
        assertThat(logger.errors.filterNotNull()).isEmpty()
    }

    @Test
    fun `records an event with the current context, its value and its details`() = runBlocking<Unit> {
        val testClient = testClient()
        testClient.client.initialize(USER_A)

        testClient.client.track("checkout-completed", TrackOptions(value = 49.9, details = mapOf("plan" to "pro")))

        assertThat(testClient.trackedEvents)
            .containsExactly(TrackedEvent("checkout-completed", USER_A, 49.9, mapOf("plan" to "pro")))
    }

    @Test
    fun `records events in call order with the context each had`() = runBlocking<Unit> {
        val testClient = testClient()
        testClient.client.initialize(USER_A)

        testClient.client.track("first")
        testClient.client.updateContext(USER_B)
        testClient.client.track("second")

        assertThat(testClient.trackedEvents)
            .containsExactly(TrackedEvent("first", USER_A), TrackedEvent("second", USER_B))
            .inOrder()
    }

    @Test
    fun `records no context for an event tracked before the client has one`() {
        val testClient = testClient()

        testClient.client.track("app-opened")

        assertThat(testClient.trackedEvents).containsExactly(TrackedEvent("app-opened"))
    }

    @Test
    fun `records no context after a context with an invalid kind was ignored`() = runBlocking<Unit> {
        val testClient = testClient()
        testClient.client.initialize(ConfigDirectorContext.build { id("user-a"); kind("Not Valid") })

        testClient.client.track("checkout-completed")

        assertThat(testClient.trackedEvents).containsExactly(TrackedEvent("checkout-completed"))
    }

    @Test
    fun `does not record an event whose name would not be reported, and logs a warning`() {
        val testClient = testClient()

        testClient.client.track("")

        assertThat(testClient.trackedEvents).isEmpty()
        assertThat(logger.messagesContaining("WARN: [ConfigDirectorClient] A tracked event was not reported")).hasSize(1)
    }

    @Test
    fun `records an event without details the SDK would leave out, and logs a warning`() {
        val testClient = testClient()

        testClient.client.track("checkout-completed", TrackOptions(details = mapOf("k" to "a".repeat(1_017))))

        assertThat(testClient.trackedEvents).containsExactly(TrackedEvent("checkout-completed"))
        assertThat(logger.messagesContaining("WARN: [ConfigDirectorClient] A tracked event was reported without its details"))
            .hasSize(1)
    }

    @Test
    fun `records an event without a value the SDK would leave out, and logs a warning`() {
        val testClient = testClient()

        testClient.client.track("checkout-completed", TrackOptions(value = Double.NaN))

        assertThat(testClient.trackedEvents).containsExactly(TrackedEvent("checkout-completed"))
        assertThat(logger.messagesContaining("WARN: [ConfigDirectorClient] A tracked event was reported without its value"))
            .hasSize(1)
    }

    @Test
    fun `logs a warning every time, since no telemetry batch is ever sent`() {
        val testClient = testClient()

        testClient.client.track("first", TrackOptions(value = Double.NaN))
        testClient.client.track("second", TrackOptions(value = Double.NaN))

        assertThat(logger.messagesContaining("WARN: [ConfigDirectorClient] A tracked event was reported without its value"))
            .hasSize(2)
    }

    @Test
    fun `records every event, since no telemetry batch fills up`() {
        val testClient = testClient()

        repeat(1_001) { testClient.client.track("event-$it") }

        assertThat(testClient.trackedEvents).hasSize(1_001)
    }

    @Test
    fun `records a date inside details as the text the SDK sends`() {
        val testClient = testClient()

        testClient.client.track("renewed", TrackOptions(details = mapOf("at" to Instant.parse("2026-12-01T00:00:00Z"))))

        assertThat(testClient.trackedEvents.single().details).containsExactly("at", "2026-12-01T00:00:00.000Z")
    }

    @Test
    fun `records a Float value as the Double with the same digits`() {
        val testClient = testClient()

        testClient.client.track("rated", TrackOptions(value = 0.1f))

        assertThat(testClient.trackedEvents.single().value).isEqualTo(0.1)
    }

    @Test
    fun `replaceValues leaves the record alone`() {
        val testClient = testClient()
        testClient.client.track("first")

        testClient.replaceValues(mapOf("flag" to true))

        assertThat(testClient.trackedEvents).containsExactly(TrackedEvent("first"))
    }

    @Test
    fun `a shared test client clears the record, which stays the same list`() {
        val testClient = testClient()
        val record = testClient.trackedEvents
        testClient.client.track("first")

        record.clear()
        testClient.client.track("second")

        assertThat(testClient.trackedEvents).containsExactly(TrackedEvent("second"))
        assertThat(record).containsExactly(TrackedEvent("second"))
    }

    @Test
    fun `records nothing once the client is closed, as it would report nothing`() {
        val testClient = testClient()
        testClient.client.close()

        testClient.client.track("too-late", TrackOptions(value = Double.NaN))

        assertThat(testClient.trackedEvents).isEmpty()
        assertThat(logger.messagesContaining("tracked event")).isEmpty()
    }

    @Test
    fun `logs to standard error unless given a logger`() = runBlocking<Unit> {
        val standardError = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(standardError, true))
        try {
            val testClient = createTestClient().also { testClients += it }
            testClient.failInitialization()
            testClient.client.initialize()
        } finally {
            System.setErr(original)
        }

        assertThat(standardError.toString()).contains("ERROR: An error occurred during initialization")
    }

    @OptIn(ConfigDirectorTestingApi::class)
    @Test
    fun `ships with the SDK version it checks for`() {
        assertThat(InMemoryConnection.sdkVersion).isEqualTo(Constants.TESTING_VERSION)
    }

    @Test
    fun `refuses an SDK of another version, naming both`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            createTestClient(emptyMap(), 3_000, logger, sdkVersion = "9.9.9")
        }

        assertThat(failure).hasMessageThat().contains("com.configdirector:android-sdk-testing ${Constants.TESTING_VERSION}")
        assertThat(failure).hasMessageThat().contains("requires com.configdirector:android-sdk ${Constants.TESTING_VERSION}")
        assertThat(failure).hasMessageThat().contains("9.9.9 is on the classpath")
    }
}
