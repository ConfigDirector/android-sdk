package com.configdirector.openfeature

import android.content.Context
import com.configdirector.ClientOptions
import com.configdirector.ConnectionMode
import com.google.common.truth.Truth.assertThat
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The provider registered with the OpenFeature SDK itself, the way an application uses it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenFeatureIntegrationTest {

    private val server = FakeSdkServer()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1, "main"))
    }

    @After
    fun tearDown() {
        runBlocking { OpenFeatureAPI.shutdown() }
        server.close()
        Dispatchers.resetMain()
    }

    private val unreachableBaseUrl: String = MockWebServer().let { closed ->
        closed.start()
        closed.url("/").toString().also { closed.shutdown() }
    }

    private fun provider(timeoutMillis: Long = 3_000, baseUrl: String = server.baseUrl) = ConfigDirectorProvider(
        RuntimeEnvironment.getApplication(),
        "client-sdk-key",
        ClientOptions.build {
            logger(SilentLogger)
            connection {
                baseUrl(baseUrl)
                timeoutMillis(timeoutMillis)
            }
        },
    )

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    /** A registration that connected and kept its values on the device, then shut down, as an earlier app run. */
    private suspend fun earlierLaunch() {
        OpenFeatureAPI.setProviderAndWait(provider(), proContext)
        waitFor("the earlier launch to keep its values") {
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("configdirector-sdk", Context.MODE_PRIVATE)
                .getString(CACHED_VALUES_KEY, null) != null
        }
        OpenFeatureAPI.shutdown()
    }

    private val proContext = ImmutableContext(
        targetingKey = "user-123",
        attributes = mapOf(
            "name" to Value.String("Ada"),
            "traits" to Value.Structure(mapOf("plan" to Value.String("pro"))),
        ),
    )

    @Test
    fun `serves flags through the OpenFeature client`() = runBlocking<Unit> {
        OpenFeatureAPI.setProviderAndWait(provider(), proContext)
        assertThat(OpenFeatureAPI.getStatus()).isEqualTo(OpenFeatureStatus.Ready)
        assertThat(OpenFeatureAPI.getProviderMetadata()?.name).isEqualTo("ConfigDirectorProvider")
        val client = OpenFeatureAPI.getClient()

        val darkMode = client.getBooleanDetails("dark-mode", false)
        assertThat(darkMode.value).isTrue()
        assertThat(darkMode.variant).isEqualTo("dark-mode-pro")
        assertThat(darkMode.reason).isEqualTo("TARGETING_MATCH")
        assertThat(client.getStringValue("welcome-message", "fallback")).isEqualTo("Hello, Ada")
        assertThat(client.getIntegerValue("max-items", 0)).isEqualTo(25)
        assertThat(client.getDoubleValue("sample-rate", 0.0)).isEqualTo(0.25)
        assertThat(client.getObjectValue("theme", Value.Structure(emptyMap())).asStructure())
            .containsEntry("primary", Value.String("#101010"))

        val missing = client.getBooleanDetails("no-such-config", true)
        assertThat(missing.value).isTrue()
        assertThat(missing.errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
        assertThat(missing.reason).isEqualTo("ERROR")
    }

    @Test
    fun `serves offline values through the OpenFeature client without a client SDK key`() = runBlocking<Unit> {
        val provider = ConfigDirectorProvider(
            RuntimeEnvironment.getApplication(),
            ClientOptions.build {
                logger(SilentLogger)
                connection {
                    mode(ConnectionMode.OFFLINE)
                    baseUrl(server.baseUrl)
                    values(mapOf("new-checkout" to true, "max-items" to 20))
                }
            },
        )

        OpenFeatureAPI.setProviderAndWait(provider, proContext)

        assertThat(OpenFeatureAPI.getStatus()).isEqualTo(OpenFeatureStatus.Ready)
        val client = OpenFeatureAPI.getClient()
        val newCheckout = client.getBooleanDetails("new-checkout", false)
        assertThat(newCheckout.value).isTrue()
        assertThat(newCheckout.reason).isEqualTo("TARGETING_MATCH")
        assertThat(client.getIntegerValue("max-items", 0)).isEqualTo(20)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `re-evaluates when the application sets a new context`() = runBlocking<Unit> {
        OpenFeatureAPI.setProviderAndWait(provider(), proContext)
        val client = OpenFeatureAPI.getClient()
        assertThat(client.getBooleanValue("dark-mode", false)).isTrue()

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(targetingKey = "user-456"))

        assertThat(OpenFeatureAPI.getStatus()).isEqualTo(OpenFeatureStatus.Ready)
        assertThat(client.getBooleanDetails("dark-mode", true).variant).isEqualTo("dark-mode-free")
        assertThat(client.getStringValue("welcome-message", "fallback")).isEqualTo("Hello, there")
    }

    @Test
    fun `reports a stale status and serves the kept values while ConfigDirector is unreachable`() = runBlocking<Unit> {
        earlierLaunch()

        OpenFeatureAPI.setProviderAndWait(provider(timeoutMillis = 300, baseUrl = unreachableBaseUrl), proContext)

        waitFor("the stale status") { OpenFeatureAPI.getStatus() == OpenFeatureStatus.Stale }
        val darkMode = OpenFeatureAPI.getClient().getBooleanDetails("dark-mode", false)
        assertThat(darkMode.value).isTrue()
        assertThat(darkMode.variant).isEqualTo("dark-mode-pro")
        assertThat(darkMode.reason).isEqualTo("CACHED")
        assertThat(darkMode.errorCode).isNull()
    }

    @Test
    fun `reports a ready status once live values replace the kept values`() = runBlocking<Unit> {
        earlierLaunch()
        server.responseDelayMillis = 500

        OpenFeatureAPI.setProviderAndWait(provider(), proContext)

        assertThat(OpenFeatureAPI.getStatus()).isEqualTo(OpenFeatureStatus.Ready)
        assertThat(OpenFeatureAPI.getClient().getBooleanDetails("dark-mode", false).reason).isEqualTo("TARGETING_MATCH")
    }

    @Test
    fun `reports an error status and serves defaults while ConfigDirector is unreachable`() = runBlocking<Unit> {
        server.sendsConfigState = false

        OpenFeatureAPI.setProviderAndWait(provider(timeoutMillis = 200), proContext)

        val status = OpenFeatureAPI.getStatus()
        assertThat(status).isInstanceOf(OpenFeatureStatus.Error::class.java)
        assertThat((status as OpenFeatureStatus.Error).error)
            .isInstanceOf(OpenFeatureError.ProviderNotReadyError::class.java)

        val darkMode = OpenFeatureAPI.getClient().getBooleanDetails("dark-mode", true)
        assertThat(darkMode.value).isTrue()
        assertThat(darkMode.errorCode).isEqualTo(ErrorCode.PROVIDER_NOT_READY)
    }
}

/** `cached-values:` followed by the SHA-256 hex digest of the client SDK key `client-sdk-key`. */
private const val CACHED_VALUES_KEY = "cached-values:24913e1336d021b669358fca9ac7212a3a52d53f77b98dca11ecb0e4441ab498"
