package com.configdirector.openfeature

import com.configdirector.ClientOptions
import com.configdirector.openfeature.internal.Constants
import com.google.common.truth.Truth.assertThat
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigDirectorProviderTest {

    private val server = FakeSdkServer()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var provider: ConfigDirectorProvider? = null

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        collectors.cancel()
        provider?.shutdown()
        server.close()
        Dispatchers.resetMain()
    }

    private fun provider(timeoutMillis: Long = 3_000): ConfigDirectorProvider = ConfigDirectorProvider(
        RuntimeEnvironment.getApplication(),
        "client-sdk-key",
        ClientOptions.build {
            logger(SilentLogger)
            metadata("Checkout", "4.2.0")
            connection {
                baseUrl(server.baseUrl)
                timeoutMillis(timeoutMillis)
            }
        },
    ).also { provider = it }

    private fun waitFor(description: String, timeoutSeconds: Long = 2, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun observing(provider: ConfigDirectorProvider): List<OpenFeatureProviderEvents> {
        val events = CopyOnWriteArrayList<OpenFeatureProviderEvents>()
        collectors.launch { provider.observe().collect { events += it } }
        return events
    }

    private val proContext = ImmutableContext(
        targetingKey = "user-123",
        attributes = mapOf(
            "name" to Value.String("Ada"),
            "traits" to Value.Structure(mapOf("plan" to Value.String("pro"))),
        ),
    )

    private val freeContext = ImmutableContext(targetingKey = "user-456")

    @Test
    fun `names itself`() {
        assertThat(provider().metadata.name).isEqualTo("ConfigDirectorProvider")
        assertThat(provider().hooks).isEmpty()
    }

    @Test
    fun `identifies itself to the server as the OpenFeature provider`() = runBlocking<Unit> {
        provider().initialize(proContext)

        val request = checkNotNull(server.takeRequest())
        val meta = JSONObject(request.body.readUtf8()).getJSONObject("metaContext")
        assertThat(meta.getString("sdkName")).isEqualTo("android-openfeature-client-provider")
        assertThat(meta.getString("sdkVersion")).isEqualTo(Constants.PROVIDER_VERSION)
        assertThat(meta.getString("appName")).isEqualTo("Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("4.2.0")
    }

    @Test
    fun `sends the evaluation context as the user's context`() = runBlocking<Unit> {
        provider().initialize(proContext)

        val request = checkNotNull(server.takeRequest())
        val given = JSONObject(request.body.readUtf8()).getJSONObject("givenContext")
        assertThat(given.getString("id")).isEqualTo("user-123")
        assertThat(given.getString("name")).isEqualTo("Ada")
        assertThat(given.getJSONObject("traits").getString("plan")).isEqualTo("pro")
    }

    @Test
    fun `sends no context when it was initialized without one`() = runBlocking<Unit> {
        val provider = provider()

        provider.initialize(null)

        assertThat(provider.getStringEvaluation("welcome-message", "fallback", null).value)
            .isEqualTo("Hello, there")
    }

    @Test
    fun `resolves every flag type with the variant and the reason`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)

        val boolean = provider.getBooleanEvaluation("dark-mode", false, null)
        assertThat(boolean.value).isTrue()
        assertThat(boolean.variant).isEqualTo("dark-mode-pro")
        assertThat(boolean.reason).isEqualTo("TARGETING_MATCH")
        assertThat(boolean.errorCode).isNull()

        val string = provider.getStringEvaluation("welcome-message", "fallback", null)
        assertThat(string.value).isEqualTo("Hello, Ada")
        assertThat(string.variant).isEqualTo("welcome-message-pro")

        val integer = provider.getIntegerEvaluation("max-items", 0, null)
        assertThat(integer.value).isEqualTo(25)
        assertThat(integer.variant).isEqualTo("max-items-pro")

        val double = provider.getDoubleEvaluation("sample-rate", 0.0, null)
        assertThat(double.value).isEqualTo(0.25)
        assertThat(double.variant).isEqualTo("sample-rate-only")

        val structure = provider.getObjectEvaluation("theme", Value.Structure(emptyMap()), null)
        assertThat(structure.value).isEqualTo(
            Value.Structure(
                mapOf(
                    "primary" to Value.String("#101010"),
                    "spacing" to Value.Structure(mapOf("small" to Value.Integer(4))),
                    "tags" to Value.List(listOf(Value.String("a"), Value.Null)),
                    "enabled" to Value.Boolean(true),
                ),
            ),
        )
        assertThat(structure.variant).isEqualTo("theme-only")
        assertThat(structure.reason).isEqualTo("TARGETING_MATCH")

        val list = provider.getObjectEvaluation("feature-list", Value.List(emptyList()), null)
        assertThat(list.value).isEqualTo(Value.List(listOf(Value.String("alpha"), Value.String("beta"))))
        assertThat(list.variant).isEqualTo("feature-list-only")
    }

    @Test
    fun `reads an object flag as the type of a primitive default`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)

        assertThat(provider.getObjectEvaluation("welcome-message", Value.String(""), null).value)
            .isEqualTo(Value.String("Hello, Ada"))
        assertThat(provider.getObjectEvaluation("max-items", Value.Integer(0), null).value)
            .isEqualTo(Value.Integer(25))
        assertThat(provider.getObjectEvaluation("sample-rate", Value.Double(0.0), null).value)
            .isEqualTo(Value.Double(0.25))
        assertThat(provider.getObjectEvaluation("dark-mode", Value.Boolean(false), null).value)
            .isEqualTo(Value.Boolean(true))
        assertThat(provider.getObjectEvaluation("theme", Value.String(""), null).value)
            .isEqualTo(Value.String(FakeSdkServer.THEME_DOCUMENT))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `cannot read an object flag as a null or an instant`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)
        val instant = Value.Instant(Instant.parse("2026-08-31T00:00:00Z"))

        val fromNull = provider.getObjectEvaluation("theme", Value.Null, null)
        assertThat(fromNull.value).isEqualTo(Value.Null)
        assertThat(fromNull.reason).isEqualTo("ERROR")
        assertThat(fromNull.errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)
        assertThat(fromNull.errorMessage).contains("Null")

        val fromInstant = provider.getObjectEvaluation("theme", instant, null)
        assertThat(fromInstant.value).isEqualTo(instant)
        assertThat(fromInstant.errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)
    }

    @Test
    fun `hands back the default it was given when an object flag falls back`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)
        val fallback = Value.Structure(mapOf("fell" to Value.String("back")))

        val evaluation = provider.getObjectEvaluation("no-such-config", fallback, null)

        assertThat(evaluation.value).isSameInstanceAs(fallback)
        assertThat(evaluation.errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
    }

    @Test
    fun `explains a flag it fell back on`() = runBlocking<Unit> {
        val provider = provider()

        val notReady = provider.getBooleanEvaluation("dark-mode", true, null)
        assertThat(notReady.value).isTrue()
        assertThat(notReady.variant).isNull()
        assertThat(notReady.reason).isEqualTo("ERROR")
        assertThat(notReady.errorCode).isEqualTo(ErrorCode.PROVIDER_NOT_READY)
        assertThat(notReady.errorMessage).contains("not delivered config state")

        provider.initialize(proContext)

        val notFound = provider.getBooleanEvaluation("no-such-config", true, null)
        assertThat(notFound.value).isTrue()
        assertThat(notFound.reason).isEqualTo("ERROR")
        assertThat(notFound.errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
        assertThat(notFound.errorMessage).contains("no-such-config")

        val mismatch = provider.getBooleanEvaluation("max-items", true, null)
        assertThat(mismatch.value).isTrue()
        assertThat(mismatch.errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)
        assertThat(mismatch.errorMessage).contains("type-mismatch")

        val unspellable = provider.getIntegerEvaluation("welcome-message", 7, null)
        assertThat(unspellable.value).isEqualTo(7)
        assertThat(unspellable.errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)
        assertThat(unspellable.errorMessage).contains("invalid-number")

        val unset = provider.getBooleanEvaluation("beta-banner", true, null)
        assertThat(unset.value).isTrue()
        assertThat(unset.reason).isEqualTo(Reason.DEFAULT.name)
        assertThat(unset.errorCode).isNull()
    }

    @Test
    fun `re-evaluates when the context is set`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)
        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()

        provider.onContextSet(proContext, freeContext)

        val evaluation = provider.getBooleanEvaluation("dark-mode", true, null)
        assertThat(evaluation.value).isFalse()
        assertThat(evaluation.variant).isEqualTo("dark-mode-free")
    }

    @Test
    fun `reports it is not ready when no config state arrives in time`() = runBlocking<Unit> {
        server.sendsConfigState = false
        val provider = provider(timeoutMillis = 200)

        val failure = assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }

        assertThat(failure).hasMessageThat().contains("did not become ready during initialization")
        assertThat(provider.getBooleanEvaluation("dark-mode", true, null).errorCode)
            .isEqualTo(ErrorCode.PROVIDER_NOT_READY)
    }

    @Test
    fun `reports it is not ready when a context change does not take in time`() = runBlocking<Unit> {
        val provider = provider(timeoutMillis = 200)
        provider.initialize(proContext)
        server.sendsConfigState = false

        val failure = assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.onContextSet(proContext, freeContext) }
        }

        assertThat(failure).hasMessageThat().contains("after the context changed")
        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()
    }

    @Test
    fun `rejects a context ConfigDirector could not send`() = runBlocking<Unit> {
        val provider = provider()
        val unsendable = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("traits" to Value.Structure(mapOf("ratio" to Value.Double(Double.NaN)))),
        )

        val failure = assertThrows(OpenFeatureError.InvalidContextError::class.java) {
            runBlocking { provider.initialize(unsendable) }
        }

        assertThat(failure).hasMessageThat().contains("ratio")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `emits the keys that arrived as a configuration change`() = runBlocking<Unit> {
        val provider = provider()
        val events = observing(provider)

        provider.initialize(proContext)

        waitFor("the configuration change") {
            events.any { it is OpenFeatureProviderEvents.ProviderConfigurationChanged }
        }
        val change = events.filterIsInstance<OpenFeatureProviderEvents.ProviderConfigurationChanged>().first()
        assertThat(change.eventDetails?.flagsChanged).containsAtLeast("dark-mode", "welcome-message", "theme")
    }

    @Test
    fun `emits ready once the connection recovers`() = runBlocking<Unit> {
        server.status = 503
        val provider = provider(timeoutMillis = 200)
        val events = observing(provider)
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderReady>()).isEmpty()

        server.status = 200

        waitFor("the provider to recover", timeoutSeconds = 10) {
            events.any { it is OpenFeatureProviderEvents.ProviderReady }
        }
        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()
    }

    @Test
    fun `stops talking to the server once shut down`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)
        val requestsBefore = server.requestCount

        provider.shutdown()

        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.onContextSet(proContext, freeContext) }
        }
        assertThat(server.requestCount).isEqualTo(requestsBefore)
        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()
    }
}
