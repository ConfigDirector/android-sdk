package com.configdirector.openfeature

import com.configdirector.testing.createTestClient
import com.google.common.truth.Truth.assertThat
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
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
import org.junit.Before
import org.junit.Test

/** The provider over a test client, registered with the OpenFeature SDK itself. */
@OptIn(ExperimentalCoroutinesApi::class, ConfigDirectorProviderTestingApi::class)
class OpenFeatureTestClientIntegrationTest {

    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        collectors.cancel()
        runBlocking { OpenFeatureAPI.shutdown() }
        Dispatchers.resetMain()
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    @Test
    fun `serves the test client's values through the OpenFeature client`() = runBlocking<Unit> {
        val testClient = createTestClient(mapOf("dark-mode" to true, "max-items" to 25), logger = SilentLogger)
        val changes = CopyOnWriteArrayList<OpenFeatureProviderEvents.ProviderConfigurationChanged>()
        collectors.launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderConfigurationChanged>().collect { changes += it }
        }

        OpenFeatureAPI.setProviderAndWait(
            ConfigDirectorProvider(testClient.client),
            ImmutableContext(targetingKey = "user-123"),
        )
        val client = OpenFeatureAPI.getClient()

        assertThat(OpenFeatureAPI.getStatus()).isEqualTo(OpenFeatureStatus.Ready)
        assertThat(client.getBooleanValue("dark-mode", false)).isTrue()
        assertThat(client.getIntegerValue("max-items", 0)).isEqualTo(25)
        assertThat(testClient.contextUpdates.map { it.id }).containsExactly("user-123")

        testClient.setValue("dark-mode", false)

        assertThat(client.getBooleanValue("dark-mode", true)).isFalse()
        waitFor("the configuration change") { changes.any { it.eventDetails?.flagsChanged == setOf("dark-mode") } }
        testClient.client.close()
    }

    @Test
    fun `s37 replacing the provider leaves the test client open`() = runBlocking<Unit> {
        val testClient = createTestClient(mapOf("dark-mode" to false), logger = SilentLogger)
        OpenFeatureAPI.setProviderAndWait(ConfigDirectorProvider(testClient.client))
        val replacement = createTestClient(mapOf("dark-mode" to false), logger = SilentLogger)

        OpenFeatureAPI.setProviderAndWait(ConfigDirectorProvider(replacement.client))
        testClient.setValue("dark-mode", true)

        assertThat(testClient.client.isReady).isTrue()
        assertThat(testClient.client.getBoolean("dark-mode", false)).isTrue()
        assertThat(OpenFeatureAPI.getClient().getBooleanValue("dark-mode", true)).isFalse()
        testClient.client.close()
        replacement.client.close()
    }
}
