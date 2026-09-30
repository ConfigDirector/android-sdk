package com.configdirector.internal.testing

import com.configdirector.ClientEvent
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorTestingApi
import com.configdirector.ConfigDirectorValidationException
import com.configdirector.ConnectReason
import com.configdirector.EvaluationReason
import com.configdirector.RecordingLogger
import com.configdirector.internal.Constants
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 * Runs without Robolectric on purpose: the in-memory connection builds the client without an
 * Android `Context`, and this suite is what proves nothing in that path reaches for one.
 */
@OptIn(ConfigDirectorTestingApi::class, ExperimentalCoroutinesApi::class)
class InMemoryConnectionTest {

    private val logger = RecordingLogger()
    private val connections = mutableListOf<InMemoryConnection>()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        connections.forEach { it.client.close() }
        Dispatchers.resetMain()
    }

    private fun connection(
        values: Map<String, Any> = emptyMap(),
        timeoutMillis: Long = 3_000,
    ): InMemoryConnection = InMemoryConnection(values, timeoutMillis, logger).also { connections += it }

    private fun InMemoryConnection.recordEvents(): List<ClientEvent> =
        CopyOnWriteArrayList<ClientEvent>().also { events -> client.addEventListener { events += it } }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun InMemoryConnection.awaitHeld() = waitFor("the attempt to be held") { isHoldingAnAttempt }

    private fun errorsLogged(text: String) = logger.messagesContaining("ERROR: ").filter { text in it }

    @Test
    fun `S1 reads every seeded value with a matching accessor`() = runBlocking<Unit> {
        val connection = connection(
            values = mapOf(
                "flag" to true,
                "count" to 20,
                "big" to 3_000_000_000L,
                "ratio" to 2.5,
                "name" to "Ada",
                "settings" to mapOf("theme" to "dark", "limit" to 3),
                "tags" to listOf("x", "y"),
            ),
        )
        val client = connection.client

        client.initialize()

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(client.getInt("count", 0)).isEqualTo(20)
        assertThat(client.getDouble("big", 0.0)).isEqualTo(3e9)
        assertThat(client.evaluateInt("big", 0).reason).isEqualTo(EvaluationReason.INVALID_NUMBER)
        assertThat(client.getDouble("ratio", 0.0)).isEqualTo(2.5)
        assertThat(client.getString("name", "")).isEqualTo("Ada")
        val settings = client.getJsonObject("settings", emptyMap())
        assertThat(settings["theme"]).isEqualTo("dark")
        assertThat((settings["limit"] as Number).toInt()).isEqualTo(3)
        assertThat(client.getJsonArray("tags", emptyList())).containsExactly("x", "y").inOrder()
        assertThat(client.evaluateBoolean("flag", false).reason).isEqualTo(EvaluationReason.FOUND_MATCH)
    }

    @Test
    fun `S2 a boolean read as a string is a type mismatch`() = runBlocking<Unit> {
        val client = connection(mapOf("flag" to true)).client
        client.initialize()

        val evaluation = client.evaluateString("flag", "fallback")

        assertThat(evaluation.value).isEqualTo("fallback")
        assertThat(evaluation.isDefaultValue).isTrue()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.TYPE_MISMATCH)
    }

    @Test
    fun `S3 setValue on a connected client changes the next read`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        connection.client.initialize()

        connection.setValue("flag", false)

        assertThat(connection.client.getBoolean("flag", true)).isFalse()
    }

    @Test
    fun `S8 setValue before initialize is delivered by initialize`() = runBlocking<Unit> {
        val connection = connection()
        connection.setValue("count", 7)

        connection.client.initialize()

        assertThat(connection.client.getInt("count", 0)).isEqualTo(7)
    }

    @Test
    fun `S6 removeValue on a connected client reads as the default`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        connection.client.initialize()

        connection.removeValue("flag")

        val evaluation = connection.client.evaluateBoolean("flag", false)
        assertThat(evaluation.value).isEqualTo(false)
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
    }

    @Test
    fun `S24 removeValue before initialize reads as the default`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        connection.removeValue("flag")

        connection.client.initialize()

        val evaluation = connection.client.evaluateBoolean("flag", false)
        assertThat(evaluation.value).isEqualTo(false)
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
    }

    @Test
    fun `S32 replaceValues serves exactly the new values`() = runBlocking<Unit> {
        val connection = connection(mapOf("a" to 1, "b" to 2))
        val client = connection.client
        val bValues = CopyOnWriteArrayList<Int>()
        client.watchInt("b", 0) { bValues += it }
        client.initialize()

        connection.replaceValues(mapOf("a" to 10, "c" to 30))

        assertThat(client.getInt("a", 0)).isEqualTo(10)
        assertThat(client.getInt("c", 0)).isEqualTo(30)
        val b = client.evaluateInt("b", 0)
        assertThat(b.value).isEqualTo(0)
        assertThat(b.reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
        assertThat(bValues).containsExactly(0, 2, 0).inOrder()
    }

    @Test
    fun `S15 two connections never share values`() = runBlocking<Unit> {
        val first = connection(mapOf("flag" to true))
        val second = connection(mapOf("flag" to false, "only-second" to 1))
        first.client.initialize()
        second.client.initialize()

        first.setValue("flag", false)
        second.setValue("only-second", 2)

        assertThat(first.client.getBoolean("flag", true)).isFalse()
        assertThat(second.client.getBoolean("flag", true)).isFalse()
        assertThat(first.client.evaluateInt("only-second", 0).reason)
            .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
        assertThat(second.client.getInt("only-second", 0)).isEqualTo(2)
    }

    @Test
    fun `serves the same value to every context`() = runBlocking<Unit> {
        val client = connection(mapOf("flag" to true)).client
        client.initialize(USER_A)
        assertThat(client.getBoolean("flag", false)).isTrue()

        client.updateContext(USER_B)

        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(client.context).isEqualTo(USER_B)
    }

    @Test
    fun `a String spelling JSON is a string config`() = runBlocking<Unit> {
        val client = connection(mapOf("doc" to """{"a":1}""")).client
        client.initialize()

        assertThat(client.getString("doc", "")).isEqualTo("""{"a":1}""")
        assertThat(client.evaluateJsonObject("doc", emptyMap()).reason)
            .isEqualTo(EvaluationReason.TYPE_MISMATCH)
    }

    @Test
    fun `an empty string serves the default with value missing`() = runBlocking<Unit> {
        val client = connection(mapOf("name" to "")).client
        client.initialize()

        val evaluation = client.evaluateString("name", "fallback")
        assertThat(evaluation.value).isEqualTo("fallback")
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.VALUE_MISSING)
    }

    @Test
    fun `a float read as an int is truncated, as in production`() = runBlocking<Unit> {
        val client = connection(mapOf("ratio" to 2.5)).client
        client.initialize()

        assertThat(client.getInt("ratio", 1)).isEqualTo(2)
    }

    @Test
    fun `rejects invalid values without changing anything`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        connection.client.initialize()

        assertThrows(ConfigDirectorValidationException::class.java) { connection.setValue("", true) }
        assertThrows(ConfigDirectorValidationException::class.java) { connection.setValue("when", 'c') }
        assertThrows(ConfigDirectorValidationException::class.java) {
            connection.replaceValues(mapOf("flag" to false, "ratio" to Double.NaN))
        }
        assertThrows(ConfigDirectorValidationException::class.java) {
            InMemoryConnection(mapOf("k" to mapOf(1 to 2)), 3_000, logger)
        }

        assertThat(connection.client.getBoolean("flag", false)).isTrue()
    }

    @Test
    fun `S4 setValue fires the watcher and lists the key`() = runBlocking<Unit> {
        val connection = connection(mapOf("dark-mode" to false))
        val client = connection.client
        val seen = CopyOnWriteArrayList<Boolean>()
        val events = connection.recordEvents()
        client.watchBoolean("dark-mode", false) { seen += it }
        client.initialize()

        connection.setValue("dark-mode", true)

        assertThat(seen).containsExactly(false, true).inOrder()
        val update = events.last() as ClientEvent.ConfigsUpdated
        assertThat(update.keys).containsExactly("dark-mode")
        assertThat(update.removedKeys).isEmpty()
    }

    @Test
    fun `S5 setValue of another key leaves a watcher alone`() = runBlocking<Unit> {
        val connection = connection(mapOf("a" to 1, "b" to 2))
        val seen = CopyOnWriteArrayList<Int>()
        connection.client.watchInt("a", 0) { seen += it }
        connection.client.initialize()
        val before = seen.size

        connection.setValue("b", 3)

        assertThat(seen).hasSize(before)
    }

    @Test
    fun `S7 removeValue hands the watcher the default and reports the key as removed`() = runBlocking<Unit> {
        val connection = connection(mapOf("dark-mode" to true, "other" to 1))
        val client = connection.client
        val seen = CopyOnWriteArrayList<Boolean>()
        val events = connection.recordEvents()
        client.watchBoolean("dark-mode", false) { seen += it }
        client.initialize()

        connection.removeValue("dark-mode")

        assertThat(seen).containsExactly(false, true, false).inOrder()
        val update = events.last() as ClientEvent.ConfigsUpdated
        assertThat(update.keys).containsExactly("other")
        assertThat(update.removedKeys).containsExactly("dark-mode")
    }

    @Test
    fun `S39 initialize publishes ready, then configs updated, then context updated`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val events = connection.recordEvents()

        connection.client.initialize(USER_A)

        assertThat(events.map { it::class }).containsExactly(
            ClientEvent.Ready::class,
            ClientEvent.ConfigsUpdated::class,
            ClientEvent.ContextUpdated::class,
        ).inOrder()
        assertThat((events[0] as ClientEvent.Ready).reason).isEqualTo(ConnectReason.INITIALIZATION)
        assertThat((events[2] as ClientEvent.ContextUpdated).context).isEqualTo(USER_A)
    }

    @Test
    fun `S41 an operation called from a watcher is delivered after the outer update`() = runBlocking<Unit> {
        val connection = connection(mapOf("a" to 1))
        val client = connection.client
        val events = connection.recordEvents()
        client.initialize()
        client.watchInt("a", 0) { value -> connection.setValue("b", value * 2) }
        assertThat(client.getInt("b", 0)).isEqualTo(2)

        connection.setValue("a", 5)

        assertThat(client.getInt("a", 0)).isEqualTo(5)
        assertThat(client.getInt("b", 0)).isEqualTo(10)
        assertThat(events.filterIsInstance<ClientEvent.Ready>()).hasSize(1)
        assertThat(events.filterIsInstance<ClientEvent.ConfigsUpdated>().map { it.keys })
            .containsExactly(listOf("a"), listOf("b"), listOf("a"), listOf("b"))
            .inOrder()
    }

    @Test
    fun `S9 a held initialize stays pending until completed`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        val events = connection.recordEvents()
        connection.holdInitialization()

        val initialization = launch(Dispatchers.Default) { client.initialize() }
        connection.awaitHeld()
        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isTrue()
        assertThat(client.getBoolean("flag", false)).isFalse()

        connection.completeInitialization()
        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()

        initialization.join()
        assertThat(client.isInitializing).isFalse()
        connection.setValue("flag", false)
        assertThat(client.getBoolean("flag", true)).isFalse()
        assertThat(events.filterIsInstance<ClientEvent.Ready>().single().reason)
            .isEqualTo(ConnectReason.INITIALIZATION)
        assertThat(events.filterIsInstance<ClientEvent.ContextUpdated>()).hasSize(1)
    }

    @Test
    fun `S10 a value set while held is delivered on completion`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        connection.holdInitialization()

        val initialization = launch(Dispatchers.Default) { client.initialize() }
        connection.awaitHeld()
        connection.setValue("flag", false)
        connection.setValue("count", 3)
        connection.completeInitialization()
        initialization.join()

        assertThat(client.getBoolean("flag", true)).isFalse()
        assertThat(client.getInt("count", 0)).isEqualTo(3)
    }

    @Test
    fun `S11 a held initialize times out not ready and the next one is served`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true), timeoutMillis = 100)
        val client = connection.client
        val events = connection.recordEvents()
        connection.holdInitialization()

        val startedAt = System.nanoTime()
        client.initialize()
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(1_000)
        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isFalse()
        assertThat(errorsLogged("An error occurred during initialization")).hasSize(1)
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("timed out after 100ms")
        assertThat(connection.isHoldingAnAttempt).isFalse()

        connection.completeInitialization()
        assertThat(client.isReady).isFalse()
        assertThat(events.filterIsInstance<ClientEvent.Ready>()).isEmpty()

        client.initialize()

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(events.filterIsInstance<ClientEvent.Ready>()).hasSize(1)
    }

    @Test
    fun `S12 a failed initialize completes promptly and not ready`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true), timeoutMillis = 500)
        val client = connection.client
        val events = connection.recordEvents()
        connection.failInitialization()

        val startedAt = System.nanoTime()
        client.initialize(USER_A)

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(250)
        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isFalse()
        assertThat(client.context).isNull()
        assertThat(events).isEmpty()
        assertThat(errorsLogged("An error occurred during initialization")).hasSize(1)
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("status: 401")
        assertThat(logger.errors.filterNotNull().single())
            .hasMessageThat().contains("failed this initialization")
    }

    @Test
    fun `failInitialization fails an attempt that is already held`() = runBlocking<Unit> {
        val connection = connection(timeoutMillis = 500)
        val client = connection.client
        connection.holdInitialization()

        val initialization = launch(Dispatchers.Default) { client.initialize() }
        connection.awaitHeld()
        connection.failInitialization()
        initialization.join()

        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isFalse()
        assertThat(connection.isHoldingAnAttempt).isFalse()
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("failed this initialization")
    }

    @Test
    fun `S25 the attempt after a failure succeeds with the stored values`() = runBlocking<Unit> {
        val connection = connection()
        val client = connection.client
        connection.failInitialization()
        client.initialize()
        assertThat(client.isReady).isFalse()

        connection.setValue("flag", true)
        client.initialize()

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()
    }

    @Test
    fun `S38 completeInitialization before initialize disarms the hold`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        connection.holdInitialization()
        connection.completeInitialization()

        connection.client.initialize()

        assertThat(connection.client.isReady).isTrue()
    }

    @Test
    fun `S40 replaceValues disarms a hold and a failure`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        connection.holdInitialization()
        connection.replaceValues(mapOf("count" to 1))
        client.initialize()
        assertThat(client.isReady).isTrue()
        assertThat(client.getInt("count", 0)).isEqualTo(1)
        assertThat(client.evaluateBoolean("flag", false).reason)
            .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)

        connection.failInitialization()
        connection.replaceValues(mapOf("count" to 2))
        client.initialize()

        assertThat(client.isReady).isTrue()
        assertThat(client.getInt("count", 0)).isEqualTo(2)
        assertThat(logger.errors.filterNotNull()).isEmpty()
    }

    @Test
    fun `holding twice arms one hold`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        connection.holdInitialization()
        connection.holdInitialization()

        val initialization = launch(Dispatchers.Default) { connection.client.initialize() }
        connection.awaitHeld()
        connection.completeInitialization()
        initialization.join()
        assertThat(connection.client.isReady).isTrue()

        connection.client.initialize()

        assertThat(connection.client.isReady).isTrue()
    }

    @Test
    fun `S33 closing during a held initialize ends it promptly and holds nothing`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true), timeoutMillis = 3_000)
        val client = connection.client
        connection.holdInitialization()

        var readyOnReturn: Boolean? = null
        val initialization: Job = launch(Dispatchers.Default) { client.initialize(); readyOnReturn = client.isReady }
        connection.awaitHeld()

        val startedAt = System.nanoTime()
        client.close()
        initialization.join()

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(1_000)
        assertThat(readyOnReturn).isFalse()
        assertThat(connection.isHoldingAnAttempt).isFalse()
        assertThat(errorsLogged("An error occurred during initialization")).hasSize(1)
    }

    @Test
    fun `a new attempt ends a held one`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true), timeoutMillis = 3_000)
        val client = connection.client
        connection.holdInitialization()
        val initialization = launch(Dispatchers.Default) { client.initialize() }
        connection.awaitHeld()

        client.resumeNetwork()
        initialization.join()

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(connection.isHoldingAnAttempt).isFalse()
        assertThat(errorsLogged("An error occurred during initialization")).hasSize(1)
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("closed before it was established")
    }

    @Test
    fun `builds and initializes without an Android context and without a word above debug`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))

        connection.client.initialize()
        connection.setValue("flag", false)

        assertThat(connection.client.isReady).isTrue()
        assertThat(logger.messages.filterNot { it.startsWith("DEBUG: ") }).isEmpty()
    }

    @Test
    fun `S23 nothing reaches for the network`() = runBlocking<Unit> {
        val threadsBefore = sdkThreads()
        val connection = connection(mapOf("flag" to true, "count" to 2))
        val client = connection.client
        client.initialize()
        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(client.getInt("count", 0)).isEqualTo(2)
        connection.setValue("flag", false)
        assertThat(client.getBoolean("flag", true)).isFalse()

        client.close()
        Thread.sleep(200)

        assertThat(sdkThreads() - threadsBefore).isEmpty()
    }

    private fun sdkThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filterTo(mutableSetOf()) { it.name.startsWith("ConfigDirector") }

    @Test
    fun `S17 nothing is held after close`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        client.initialize()
        connection.setValue("flag", false)

        client.close()

        assertThat(connection.isHoldingAnAttempt).isFalse()
        assertThat(logger.errors.filterNotNull()).isEmpty()
    }

    @Test
    fun `S13 contextUpdates records initialize and updateContext only`() = runBlocking<Unit> {
        val connection = connection()
        val client = connection.client

        client.initialize(USER_A)
        client.updateContext(USER_B)
        client.pauseNetwork()
        client.resumeNetwork()

        assertThat(connection.contextUpdates).containsExactly(USER_A, USER_B).inOrder()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `initialize without a context records an empty context`() = runBlocking<Unit> {
        val connection = connection()

        connection.client.initialize()

        assertThat(connection.contextUpdates).containsExactly(ConfigDirectorContext.empty())
    }

    @Test
    fun `S26 a held updateContext stays pending until completed`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        client.initialize(USER_A)
        val events = connection.recordEvents()
        connection.holdContextUpdate()

        val update = launch(Dispatchers.Default) { client.updateContext(USER_B) }
        connection.awaitHeld()
        assertThat(client.isReady).isFalse()
        assertThat(client.context).isEqualTo(USER_A)
        assertThat(client.getBoolean("flag", false)).isTrue()

        connection.completeContextUpdate()
        update.join()

        assertThat(client.isReady).isTrue()
        assertThat(client.context).isEqualTo(USER_B)
        assertThat(connection.contextUpdates).containsExactly(USER_A, USER_B).inOrder()
        assertThat(events.filterIsInstance<ClientEvent.Ready>().single().reason)
            .isEqualTo(ConnectReason.CONTEXT_UPDATE)
    }

    @Test
    fun `S42 an initialization hold never holds an updateContext`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        client.initialize(USER_A)
        connection.holdInitialization()

        client.updateContext(USER_B)
        assertThat(client.isReady).isTrue()
        assertThat(connection.contextUpdates).containsExactly(USER_A, USER_B).inOrder()

        val initialization = launch(Dispatchers.Default) { client.initialize(USER_A) }
        connection.awaitHeld()
        assertThat(client.isReady).isFalse()
        connection.completeInitialization()
        initialization.join()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `S43 a failed updateContext completes promptly and serves the last values`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true), timeoutMillis = 500)
        val client = connection.client
        client.initialize(USER_A)
        connection.failContextUpdate()

        val startedAt = System.nanoTime()
        client.updateContext(USER_B)

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(250)
        assertThat(client.isReady).isFalse()
        assertThat(client.context).isEqualTo(USER_A)
        assertThat(client.getBoolean("flag", false)).isTrue()
        assertThat(errorsLogged("An error occurred during context update")).hasSize(1)
        assertThat(logger.errors.filterNotNull().single()).hasMessageThat().contains("failed this context update")
    }

    @Test
    fun `S44 a resume is never held and leaves the context update hold armed`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        client.initialize(USER_A)
        connection.holdContextUpdate()
        client.pauseNetwork()
        assertThat(client.isReady).isFalse()

        client.resumeNetwork()
        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("flag", false)).isTrue()

        val update = launch(Dispatchers.Default) { client.updateContext(USER_B) }
        connection.awaitHeld()
        assertThat(client.isReady).isFalse()
        connection.completeContextUpdate()
        update.join()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `values set while paused are delivered on resume`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        client.initialize()
        client.pauseNetwork()

        connection.setValue("flag", false)
        assertThat(client.getBoolean("flag", true)).isTrue()

        client.resumeNetwork()

        assertThat(client.getBoolean("flag", true)).isFalse()
    }

    @Test
    fun `S14 the controls are silent no-ops after close`() = runBlocking<Unit> {
        val connection = connection(mapOf("flag" to true))
        val client = connection.client
        val seen = CopyOnWriteArrayList<Boolean>()
        client.watchBoolean("flag", false) { seen += it }
        client.initialize()
        val before = seen.size

        client.close()
        connection.setValue("flag", false)
        connection.removeValue("flag")
        connection.replaceValues(mapOf("count" to 1))
        connection.holdInitialization()
        connection.completeInitialization()
        connection.failInitialization()
        connection.holdContextUpdate()
        connection.completeContextUpdate()
        connection.failContextUpdate()

        assertThat(seen).hasSize(before)
        assertThat(client.isReady).isFalse()
    }

    @Test
    fun `reports the version of the SDK it ships in`() {
        assertThat(InMemoryConnection.sdkVersion).isEqualTo(Constants.SDK_VERSION)
    }
}
