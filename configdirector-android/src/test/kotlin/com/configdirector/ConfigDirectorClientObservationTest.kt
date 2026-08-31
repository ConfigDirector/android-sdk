package com.configdirector

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigDirectorClientObservationTest {

    private val server = FakeSdkServer()

    private val client = ConfigDirectorClient(
        "client-sdk-key",
        ClientOptions.build {
            logger(RecordingLogger(LogLevel.OFF))
            connection { baseUrl(server.baseUrl) }
        },
    )

    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val proContext = ConfigDirectorContext.build { name("Ada"); trait("plan", "pro") }

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        collectors.cancel()
        client.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun collecting(collect: suspend CoroutineScope.() -> Unit): Job =
        collectors.launch(block = collect)

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    /** Gives whatever the client was about to deliver the chance to arrive, so that an assertion
     * about something it must not deliver is not just early. */
    private fun settle() {
        Thread.sleep(200)
    }

    @Test
    fun `hands a watch the current value straight away`() {
        val values = CopyOnWriteArrayList<String>()

        client.watchString("welcome-message", "fallback") { values += it }

        waitFor("the current value") { values.isNotEmpty() }
        assertThat(values).containsExactly("fallback")
    }

    @Test
    fun `hands a watch every change to the value`() = runBlocking<Unit> {
        val values = CopyOnWriteArrayList<Boolean>()
        client.watchBoolean("dark-mode", false) { values += it }
        waitFor("the current value") { values.isNotEmpty() }

        client.initialize(proContext)

        waitFor("the value from the server") { values.size == 2 }
        assertThat(values).containsExactly(false, true).inOrder()
    }

    @Test
    fun `does not hand a watch a value that has not changed`() = runBlocking<Unit> {
        val values = CopyOnWriteArrayList<Boolean>()
        client.watchBoolean("dark-mode", false) { values += it }
        client.initialize(proContext)
        waitFor("the value from the server") { values.size == 2 }

        client.updateContext(proContext)

        settle()
        assertThat(values).containsExactly(false, true).inOrder()
    }

    @Test
    fun `stops watching once the subscription is closed`() = runBlocking<Unit> {
        val values = CopyOnWriteArrayList<Boolean>()
        val subscription = client.watchBoolean("dark-mode", false) { values += it }
        waitFor("the current value") { values.isNotEmpty() }

        subscription.close()
        client.initialize(proContext)

        settle()
        assertThat(values).containsExactly(false)
    }

    @Test
    fun `never delivers to a watch registered on a closed client`() = runBlocking<Unit> {
        val values = CopyOnWriteArrayList<Boolean>()
        client.close()

        client.watchBoolean("dark-mode", false) { values += it }
        client.initialize(proContext)

        settle()
        assertThat(values).isEmpty()
    }

    @Test
    fun `tells a listener the client became ready`() = runBlocking<Unit> {
        val events = CopyOnWriteArrayList<ClientEvent>()
        client.addEventListener { events += it }

        client.initialize(proContext)

        waitFor("the ready event") { ClientEvent.Ready(ConnectReason.INITIALIZATION) in events }
    }

    @Test
    fun `tells a listener which configs arrived`() = runBlocking<Unit> {
        val events = CopyOnWriteArrayList<ClientEvent>()
        client.addEventListener { events += it }

        client.initialize(proContext)

        waitFor("the configs updated event") {
            events.filterIsInstance<ClientEvent.ConfigsUpdated>().isNotEmpty()
        }
        assertThat(events.filterIsInstance<ClientEvent.ConfigsUpdated>().single().keys)
            .containsExactly(
                "dark-mode",
                "welcome-message",
                "max-items",
                "sample-rate",
                "theme",
                "feature-list",
                "broken-json",
                "beta-banner",
            )
    }

    @Test
    fun `tells a listener the context took effect`() = runBlocking<Unit> {
        val events = CopyOnWriteArrayList<ClientEvent>()
        client.addEventListener { events += it }

        client.initialize(proContext)

        waitFor("the context event") { ClientEvent.ContextUpdated(proContext) in events }
    }

    @Test
    fun `tells a listener the reason it reconnected on a context update`() = runBlocking<Unit> {
        client.initialize(proContext)
        val events = CopyOnWriteArrayList<ClientEvent>()
        client.addEventListener { events += it }

        client.updateContext(ConfigDirectorContext.build { name("Grace") })

        waitFor("the ready event") { ClientEvent.Ready(ConnectReason.CONTEXT_UPDATE) in events }
    }

    @Test
    fun `stops telling a listener once its subscription is closed`() = runBlocking<Unit> {
        val events = CopyOnWriteArrayList<ClientEvent>()
        val subscription = client.addEventListener { events += it }

        subscription.close()
        client.initialize(proContext)

        settle()
        assertThat(events).isEmpty()
    }

    @Test
    fun `tells a listener about a config it served`() = runBlocking<Unit> {
        client.initialize(proContext)
        val evaluations = CopyOnWriteArrayList<ConfigEvaluation>()
        client.addEvaluationListener { evaluations += it }

        client.getBoolean("dark-mode", false)

        waitFor("the evaluation") { evaluations.isNotEmpty() }
        val evaluation = evaluations.single()
        assertThat(evaluation.key).isEqualTo("dark-mode")
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.valueId).isEqualTo("dark-mode-pro")
        assertThat(evaluation.isDefaultValue).isFalse()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.FOUND_MATCH)
        assertThat(evaluation.context).isEqualTo(proContext)
    }

    @Test
    fun `tells a listener about a config it fell back on`() = runBlocking<Unit> {
        client.initialize(proContext)
        val evaluations = CopyOnWriteArrayList<ConfigEvaluation>()
        client.addEvaluationListener { evaluations += it }

        client.getInt("no-such-config", 7)

        waitFor("the evaluation") { evaluations.isNotEmpty() }
        val evaluation = evaluations.single()
        assertThat(evaluation.value).isEqualTo(7)
        assertThat(evaluation.valueId).isNull()
        assertThat(evaluation.isDefaultValue).isTrue()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
    }

    @Test
    fun `stops telling listeners once the client is closed`() = runBlocking<Unit> {
        client.initialize(proContext)
        val evaluations = CopyOnWriteArrayList<ConfigEvaluation>()
        client.addEvaluationListener { evaluations += it }
        client.close()

        client.getBoolean("dark-mode", false)

        settle()
        assertThat(evaluations).isEmpty()
    }

    @Test
    fun `reads a config as the type of the default value`() = runBlocking<Unit> {
        client.initialize(proContext)

        assertThat(client.value("dark-mode", false)).isTrue()
        assertThat(client.value("welcome-message", "fallback")).isEqualTo("Hello, Ada")
        assertThat(client.value("max-items", 0)).isEqualTo(25)
        assertThat(client.value("sample-rate", 0.0)).isEqualTo(0.25)
        assertThat(client.value("theme", emptyMap<String, Any?>())["primary"]).isEqualTo("#101010")
        assertThat(client.value("feature-list", emptyList<Any?>())).containsExactly("alpha", "beta")
    }

    @Test
    fun `hands a watch every change to a JSON config`() = runBlocking<Unit> {
        val documents = CopyOnWriteArrayList<Map<String, Any?>>()
        client.watchJsonObject("theme", emptyMap<String, Any?>()) { documents += it }
        waitFor("the default document") { documents.isNotEmpty() }

        client.initialize(proContext)

        waitFor("the document from the server") { documents.size == 2 }
        assertThat(documents.first()).isEmpty()
        assertThat(documents.last()["primary"]).isEqualTo("#101010")
    }

    @Test
    fun `emits a JSON config on a flow`() = runBlocking<Unit> {
        val documents = CopyOnWriteArrayList<Map<String, Any?>>()
        collecting { client.values("theme", emptyMap<String, Any?>()).collect { documents += it } }
        waitFor("the default document") { documents.isNotEmpty() }

        client.initialize(proContext)

        waitFor("the document from the server") { documents.size == 2 }
        assertThat(documents.last()["primary"]).isEqualTo("#101010")
    }

    @Test
    fun `emits the current value and each change on a flow`() = runBlocking<Unit> {
        val values = CopyOnWriteArrayList<Boolean>()
        collecting { client.values("dark-mode", false).collect { values += it } }
        waitFor("the current value") { values.isNotEmpty() }

        client.initialize(proContext)

        waitFor("the value from the server") { values.size == 2 }
        assertThat(values).containsExactly(false, true).inOrder()
    }

    @Test
    fun `emits the current value and each change on a flow of every readable type`() = runBlocking<Unit> {
        val strings = CopyOnWriteArrayList<String>()
        val ints = CopyOnWriteArrayList<Int>()
        val doubles = CopyOnWriteArrayList<Double>()
        collecting { client.values("welcome-message", "fallback").collect { strings += it } }
        collecting { client.values("max-items", 0).collect { ints += it } }
        collecting { client.values("sample-rate", 0.0).collect { doubles += it } }
        waitFor("the current values") {
            strings.isNotEmpty() && ints.isNotEmpty() && doubles.isNotEmpty()
        }

        client.initialize(proContext)

        waitFor("the values from the server") {
            strings.size == 2 && ints.size == 2 && doubles.size == 2
        }
        assertThat(strings).containsExactly("fallback", "Hello, Ada").inOrder()
        assertThat(ints).containsExactly(0, 25).inOrder()
        assertThat(doubles).containsExactly(0.0, 0.25).inOrder()
    }

    @Test
    fun `emits events on a flow`() = runBlocking<Unit> {
        val events = CopyOnWriteArrayList<ClientEvent>()
        collecting { client.events.collect { events += it } }
        settle()

        client.initialize(proContext)

        waitFor("the ready event") { ClientEvent.Ready(ConnectReason.INITIALIZATION) in events }
    }

    @Test
    fun `emits evaluations on a flow`() = runBlocking<Unit> {
        val evaluations = CopyOnWriteArrayList<ConfigEvaluation>()
        collecting { client.evaluations.collect { evaluations += it } }
        client.initialize(proContext)
        settle()

        client.getBoolean("dark-mode", false)

        waitFor("the evaluation") { evaluations.map { it.key }.contains("dark-mode") }
    }

    @Test
    fun `completes a flow when the client closes`() {
        val values = CopyOnWriteArrayList<Boolean>()
        val collection = collecting { client.values("dark-mode", false).collect { values += it } }
        waitFor("the current value") { values.isNotEmpty() }

        client.close()

        waitFor("the flow to complete") { collection.isCompleted }
    }
}
