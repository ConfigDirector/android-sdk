package com.configdirector.compose

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.configdirector.ClientOptions
import com.configdirector.ConfigDirectorClient
import com.configdirector.ConfigDirectorContext
import com.configdirector.LogLevel
import com.configdirector.AndroidLogger
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigValuesTest {

    @get:Rule
    val compose = createComposeRule()

    private val server = FakeSdkServer()

    private val client = ConfigDirectorClient(
        "client-sdk-key",
        ClientOptions.build {
            logger(AndroidLogger(LogLevel.OFF))
            connection { baseUrl(server.baseUrl) }
        },
    )

    private val proContext = ConfigDirectorContext.build { trait("plan", "pro") }

    @After
    fun tearDown() {
        client.close()
        server.close()
    }

    private fun composed(content: @Composable () -> Unit) {
        compose.setContent { ConfigDirectorProvider(client, content) }
        compose.waitForIdle()
    }

    /**
     * The SDK hands watches and events back on the main thread, and Robolectric's main looper only
     * runs when a test lets it. Everything queued there is what the composition is waiting for.
     */
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test
    fun `serves the default until the client is ready`() {
        val values = CopyOnWriteArrayList<Boolean>()

        composed { values += configValue("dark-mode", false) }

        assertThat(values.last()).isFalse()
    }

    @Test
    fun `recomposes with the value the server sent`() {
        val values = CopyOnWriteArrayList<Boolean>()
        composed { values += configValue("dark-mode", false) }

        runBlocking { client.initialize(proContext) }

        settle()
        assertThat(values.first()).isFalse()
        assertThat(values.last()).isTrue()
    }

    @Test
    fun `does not recompose when the value has not changed`() {
        val values = CopyOnWriteArrayList<Boolean>()
        composed { values += configValue("dark-mode", false) }
        runBlocking { client.initialize(proContext) }
        settle()
        val composedSoFar = values.size

        runBlocking { client.updateContext(proContext) }

        settle()
        assertThat(values).hasSize(composedSoFar)
    }

    @Test
    fun `recomposes when a context update changes the value`() {
        val values = CopyOnWriteArrayList<Boolean>()
        composed { values += configValue("dark-mode", false) }
        runBlocking { client.initialize(proContext) }
        settle()
        assertThat(values.last()).isTrue()

        runBlocking { client.updateContext(ConfigDirectorContext.build { trait("plan", "free") }) }

        settle()
        assertThat(values.last()).isFalse()
    }

    @Test
    fun `follows the key it is given to another config`() {
        val values = CopyOnWriteArrayList<Boolean>()
        var key by mutableStateOf("dark-mode")
        composed { values += configValue(key, false) }
        runBlocking { client.initialize(proContext) }
        settle()
        assertThat(values.last()).isTrue()

        key = "no-such-config"

        settle()
        assertThat(values.last()).isFalse()
    }

    @Test
    fun `reads a config the client was passed to directly`() {
        val values = CopyOnWriteArrayList<Boolean>()

        compose.setContent { values += configValue("dark-mode", false, client) }
        compose.waitForIdle()

        assertThat(values.last()).isFalse()
    }

    @Test
    fun `says which client is missing when there is no provider`() {
        val failure = runCatching {
            compose.setContent { configValue("dark-mode", false) }
            compose.waitForIdle()
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("No ConfigDirectorClient was provided")
    }

    @Test
    fun `follows the client becoming ready`() {
        val ready = CopyOnWriteArrayList<Boolean>()
        composed { ready += isClientReady() }
        assertThat(ready.last()).isFalse()

        runBlocking { client.initialize(proContext) }

        settle()
        assertThat(ready.last()).isTrue()
    }

    @Test
    fun `follows the context taking effect`() {
        val contexts = CopyOnWriteArrayList<ConfigDirectorContext?>()
        composed { contexts += configContext() }
        assertThat(contexts.last()).isNull()

        runBlocking { client.initialize(proContext) }

        settle()
        assertThat(contexts.last()).isEqualTo(proContext)
    }
}
