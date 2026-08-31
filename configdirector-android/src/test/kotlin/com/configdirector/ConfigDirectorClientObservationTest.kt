package com.configdirector

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigDirectorClientObservationTest {

    private val client = ConfigDirectorClient(
        "client-sdk-key",
        ClientOptions.build { logger(RecordingLogger(LogLevel.OFF)) },
    )

    private val proContext = ConfigDirectorContext.build { name("Ada"); trait("plan", "pro") }

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        client.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.collecting(collect: suspend CoroutineScope.() -> Unit): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), block = collect)

    @Test
    fun `hands a watch the current value straight away`() = runTest {
        val values = mutableListOf<String>()

        client.watchString("welcome-message", "fallback") { values += it }

        assertThat(values).containsExactly("fallback")
    }

    @Test
    fun `hands a watch every change to the value`() = runTest {
        val values = mutableListOf<Boolean>()
        client.watchBoolean("dark-mode", false) { values += it }

        client.initialize(proContext)

        assertThat(values).containsExactly(false, true).inOrder()
    }

    @Test
    fun `does not hand a watch a value that has not changed`() = runTest {
        val values = mutableListOf<Boolean>()
        client.watchBoolean("dark-mode", false) { values += it }
        client.initialize(proContext)

        client.updateContext(proContext)

        assertThat(values).containsExactly(false, true).inOrder()
    }

    @Test
    fun `stops watching once the subscription is closed`() = runTest {
        val values = mutableListOf<Boolean>()
        val subscription = client.watchBoolean("dark-mode", false) { values += it }

        subscription.close()
        client.initialize(proContext)

        assertThat(values).containsExactly(false)
    }

    @Test
    fun `never delivers to a watch registered on a closed client`() = runTest {
        val values = mutableListOf<Boolean>()
        client.close()

        client.watchBoolean("dark-mode", false) { values += it }
        client.initialize(proContext)

        assertThat(values).isEmpty()
    }

    @Test
    fun `tells a listener the client became ready`() = runTest {
        val events = mutableListOf<ClientEvent>()
        client.addEventListener { events += it }

        client.initialize(proContext)

        assertThat(events).contains(ClientEvent.Ready(ConnectReason.INITIALIZATION))
    }

    @Test
    fun `tells a listener which configs arrived`() = runTest {
        val events = mutableListOf<ClientEvent>()
        client.addEventListener { events += it }

        client.initialize(proContext)

        val updated = events.filterIsInstance<ClientEvent.ConfigsUpdated>().single()
        assertThat(updated.keys).containsExactly(
            "dark-mode",
            "welcome-message",
            "max-items",
            "sample-rate",
            "theme",
            "beta-banner",
        )
    }

    @Test
    fun `tells a listener the context took effect`() = runTest {
        val events = mutableListOf<ClientEvent>()
        client.addEventListener { events += it }

        client.initialize(proContext)

        assertThat(events).contains(ClientEvent.ContextUpdated(proContext))
    }

    @Test
    fun `tells a listener the reason it reconnected on a context update`() = runTest {
        client.initialize(proContext)
        val events = mutableListOf<ClientEvent>()
        client.addEventListener { events += it }

        client.updateContext(ConfigDirectorContext.build { name("Grace") })

        assertThat(events).contains(ClientEvent.Ready(ConnectReason.CONTEXT_UPDATE))
    }

    @Test
    fun `stops telling a listener once its subscription is closed`() = runTest {
        val events = mutableListOf<ClientEvent>()
        val subscription = client.addEventListener { events += it }

        subscription.close()
        client.initialize(proContext)

        assertThat(events).isEmpty()
    }

    @Test
    fun `tells a listener about a config it served`() = runTest {
        client.initialize(proContext)
        val evaluations = mutableListOf<ConfigEvaluation>()
        client.addEvaluationListener { evaluations += it }

        client.getBoolean("dark-mode", false)

        val evaluation = evaluations.single()
        assertThat(evaluation.key).isEqualTo("dark-mode")
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.valueId).isEqualTo("dark-mode-pro")
        assertThat(evaluation.isDefaultValue).isFalse()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.FOUND_MATCH)
        assertThat(evaluation.context).isEqualTo(proContext)
    }

    @Test
    fun `tells a listener about a config it fell back on`() = runTest {
        client.initialize(proContext)
        val evaluations = mutableListOf<ConfigEvaluation>()
        client.addEvaluationListener { evaluations += it }

        client.getInt("no-such-config", 7)

        val evaluation = evaluations.single()
        assertThat(evaluation.value).isEqualTo(7)
        assertThat(evaluation.valueId).isNull()
        assertThat(evaluation.isDefaultValue).isTrue()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
    }

    @Test
    fun `stops telling listeners once the client is closed`() = runTest {
        client.initialize(proContext)
        val evaluations = mutableListOf<ConfigEvaluation>()
        client.addEvaluationListener { evaluations += it }
        client.close()

        client.getBoolean("dark-mode", false)

        assertThat(evaluations).isEmpty()
    }

    @Test
    fun `reads a config as the type of the default value`() = runTest {
        client.initialize(proContext)

        assertThat(client.value("dark-mode", false)).isTrue()
        assertThat(client.value("welcome-message", "fallback")).isEqualTo("Hello, Ada")
        assertThat(client.value("max-items", 0)).isEqualTo(25)
        assertThat(client.value("sample-rate", 0.0)).isEqualTo(0.25)
    }

    @Test
    fun `emits the current value and each change on a flow`() = runTest {
        val values = mutableListOf<Boolean>()
        collecting { client.values("dark-mode", false).collect { values += it } }

        client.initialize(proContext)

        assertThat(values).containsExactly(false, true).inOrder()
    }

    @Test
    fun `emits the current value and each change on a flow of every readable type`() = runTest {
        val strings = mutableListOf<String>()
        val ints = mutableListOf<Int>()
        val doubles = mutableListOf<Double>()
        collecting { client.values("welcome-message", "fallback").collect { strings += it } }
        collecting { client.values("max-items", 0).collect { ints += it } }
        collecting { client.values("sample-rate", 0.0).collect { doubles += it } }

        client.initialize(proContext)

        assertThat(strings).containsExactly("fallback", "Hello, Ada").inOrder()
        assertThat(ints).containsExactly(0, 25).inOrder()
        assertThat(doubles).containsExactly(0.0, 0.25).inOrder()
    }

    @Test
    fun `emits events on a flow`() = runTest {
        val events = mutableListOf<ClientEvent>()
        collecting { client.events.collect { events += it } }

        client.initialize(proContext)

        assertThat(events).contains(ClientEvent.Ready(ConnectReason.INITIALIZATION))
    }

    @Test
    fun `emits evaluations on a flow`() = runTest {
        val evaluations = mutableListOf<ConfigEvaluation>()
        collecting { client.evaluations.collect { evaluations += it } }
        client.initialize(proContext)

        client.getBoolean("dark-mode", false)

        assertThat(evaluations.map { it.key }).contains("dark-mode")
    }

    @Test
    fun `completes a flow when the client closes`() = runTest {
        val collection = collecting { client.values("dark-mode", false).collect {} }

        client.close()

        assertThat(collection.isCompleted).isTrue()
    }
}
