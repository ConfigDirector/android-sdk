package com.configdirector.openfeature

import com.configdirector.ClientOptions
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
import kotlinx.coroutines.test.setMain
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
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runBlocking { OpenFeatureAPI.shutdown() }
        server.close()
        Dispatchers.resetMain()
    }

    private fun provider(timeoutMillis: Long = 3_000) = ConfigDirectorProvider(
        RuntimeEnvironment.getApplication(),
        "client-sdk-key",
        ClientOptions.build {
            logger(SilentLogger)
            connection {
                baseUrl(server.baseUrl)
                timeoutMillis(timeoutMillis)
            }
        },
    )

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
