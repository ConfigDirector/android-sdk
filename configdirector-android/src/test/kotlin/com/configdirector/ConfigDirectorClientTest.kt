package com.configdirector

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigDirectorClientTest {

    private val logger = RecordingLogger()

    private fun client(options: ClientOptions = ClientOptions.build { logger(logger) }) =
        ConfigDirectorClient("client-sdk-key", options)

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
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
        client(ClientOptions.build { logger(logger); connection { baseUrl("http://proxy.example.com") } })

        assertThat(logger.messagesContaining("is not HTTPS")).hasSize(1)
    }

    @Test
    fun `says nothing about a base URL that is HTTPS`() {
        client(ClientOptions.build { logger(logger); connection { baseUrl("https://proxy.example.com") } })

        assertThat(logger.messagesContaining("is not HTTPS")).isEmpty()
    }

    @Test
    fun `serves default values before it is initialized`() = runTest {
        val client = client()

        assertThat(client.isReady).isFalse()
        assertThat(client.getBoolean("dark-mode", false)).isFalse()
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("fallback")
        assertThat(logger.messagesContaining("(client-not-ready)")).hasSize(2)
    }

    @Test
    fun `serves the config state it received once initialized`() = runTest {
        val client = client()

        client.initialize(ConfigDirectorContext.build { id("user-123") })

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("dark-mode", false)).isTrue()
        assertThat(client.getString("welcome-message", "fallback"))
            .isEqualTo("Hello from ConfigDirector")
        assertThat(client.getInt("max-items", 0)).isEqualTo(25)
        assertThat(client.getDouble("sample-rate", 0.0)).isEqualTo(0.25)
    }

    @Test
    fun `serves a JSON config as its raw document`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getString("theme", "{}")).isEqualTo("""{"primary":"#101010"}""")
    }

    @Test
    fun `truncates a decimal read as a whole number`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getInt("sample-rate", 7)).isEqualTo(0)
    }

    @Test
    fun `falls back when no config carries the key`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("no-such-config", true)).isTrue()
        assertThat(logger.messagesContaining("(config-state-missing)")).hasSize(1)
    }

    @Test
    fun `falls back when the config holds a different type`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("max-items", true)).isTrue()
        assertThat(logger.messagesContaining("(type-mismatch)")).hasSize(1)
    }

    @Test
    fun `falls back when the config has no value for the context`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("beta-banner", true)).isTrue()
        assertThat(logger.messagesContaining("(value-missing)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a boolean`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("welcome-message", true)).isTrue()
        assertThat(logger.messagesContaining("(invalid-boolean)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a whole number`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getInt("welcome-message", 7)).isEqualTo(7)
        assertThat(logger.messagesContaining("(invalid-number)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a decimal number`() = runTest {
        val client = client()

        client.initialize()

        assertThat(client.getDouble("welcome-message", 1.5)).isEqualTo(1.5)
        assertThat(logger.messagesContaining("(invalid-number)")).hasSize(1)
    }

    @Test
    fun `is initializing only while initializing`() = runTest {
        val client = client()
        assertThat(client.isInitializing).isFalse()

        client.initialize()

        assertThat(client.isInitializing).isFalse()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `takes the context it was initialized with`() = runTest {
        val client = client()
        val context = ConfigDirectorContext.build { id("user-123") }
        assertThat(client.context).isNull()

        client.initialize(context)

        assertThat(client.context).isEqualTo(context)
    }

    @Test
    fun `re-evaluates against an updated context`() = runTest {
        val client = client()
        client.initialize(ConfigDirectorContext.build { id("user-123") })

        val updated = ConfigDirectorContext.build { id("user-456"); trait("plan", "pro") }
        client.updateContext(updated)

        assertThat(client.context).isEqualTo(updated)
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `serves default values once closed`() = runTest {
        val client = client()
        client.initialize()

        client.close()

        assertThat(client.isReady).isFalse()
        assertThat(client.getBoolean("dark-mode", false)).isTrue()
    }

    @Test
    fun `closes only once`() = runTest {
        val client = client()

        client.close()
        client.close()

        assertThat(logger.messagesContaining("close() called")).hasSize(1)
    }

    @Test
    fun `will not connect once closed`() = runTest {
        val client = client()
        client.close()

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(logger.messagesContaining("The client is closed")).hasSize(1)
    }
}
