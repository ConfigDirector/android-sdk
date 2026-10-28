package com.configdirector.openfeature

import android.content.Context
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
import okhttp3.mockwebserver.MockWebServer
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
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var provider: ConfigDirectorProvider? = null
    private val unreachableBaseUrl: String = MockWebServer().let { closed ->
        closed.start()
        closed.url("/").toString().also { closed.shutdown() }
    }

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1, "main"))
    }

    @After
    fun tearDown() {
        collectors.cancel()
        provider?.shutdown()
        server.close()
        Dispatchers.resetMain()
    }

    private fun provider(timeoutMillis: Long = 3_000, baseUrl: String = server.baseUrl): ConfigDirectorProvider =
        ConfigDirectorProvider(
            RuntimeEnvironment.getApplication(),
            "client-sdk-key",
            ClientOptions.build {
                logger(SilentLogger)
                metadata("Checkout", "4.2.0")
                connection {
                    baseUrl(baseUrl)
                    timeoutMillis(timeoutMillis)
                }
            },
        ).also { provider = it }

    /** A registration that connected and kept its values on the device, then shut down, as an earlier app run. */
    private suspend fun earlierLaunch() {
        val earlier = provider()
        earlier.initialize(proContext)
        waitFor("the earlier launch to keep its values") { keptEntry() != null }
        earlier.shutdown()
    }

    private fun keptEntry(): String? = RuntimeEnvironment.getApplication()
        .getSharedPreferences("configdirector-sdk", Context.MODE_PRIVATE)
        .getString(CACHED_VALUES_KEY, null)

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

    private val contextWithAnInvalidContextKind = ImmutableContext(
        targetingKey = "user-123",
        attributes = mapOf(
            "kind" to Value.String("Tenant"),
            "name" to Value.String("Ada"),
            "traits" to Value.Structure(mapOf("plan" to Value.String("pro"))),
        ),
    )

    @Test
    fun `names itself`() {
        assertThat(provider().metadata.name).isEqualTo("ConfigDirectorProvider")
        assertThat(provider().hooks).isEmpty()
    }

    @Test
    fun `identifies itself to the server as the OpenFeature provider`() = runBlocking<Unit> {
        provider().initialize(proContext)

        val request = checkNotNull(server.takeRequest())
        val meta = JSONObject(request.body).getJSONObject("metaContext")
        assertThat(meta.getString("sdkName")).isEqualTo("android-openfeature-client-provider")
        assertThat(meta.getString("sdkVersion")).isEqualTo(Constants.PROVIDER_VERSION)
        assertThat(meta.getString("appName")).isEqualTo("Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("4.2.0")
    }

    @Test
    fun `sends the evaluation context as the user's context`() = runBlocking<Unit> {
        provider().initialize(proContext)

        val request = checkNotNull(server.takeRequest())
        val given = JSONObject(request.body).getJSONObject("givenContext")
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
    fun `reads an object flag as the type of a primitive in-code default value`() = runBlocking<Unit> {
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

    @Test
    fun `throws TypeMismatchError for an object read with a null in-code default value`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)

        val failure = assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getObjectEvaluation("theme", Value.Null, null)
        }

        assertThat(failure).hasMessageThat().contains("Null")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `throws TypeMismatchError for an object read with an instant in-code default value`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)
        val instant = Value.Instant(Instant.parse("2026-08-31T00:00:00Z"))

        val failure = assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getObjectEvaluation("theme", instant, null)
        }

        assertThat(failure).hasMessageThat().contains("Instant")
    }

    @Test
    fun `hands back the in-code default value it was given when an object flag has no value`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)
        val inCodeDefaultValue = Value.Structure(mapOf("fell" to Value.String("back")))

        val evaluation = provider.getObjectEvaluation("beta-banner", inCodeDefaultValue, null)

        assertThat(evaluation.value).isSameInstanceAs(inCodeDefaultValue)
        assertThat(evaluation.reason).isEqualTo(Reason.DEFAULT.name)
    }

    @Test
    fun `throws ProviderNotReadyError for a read before values arrive`() {
        val provider = provider()

        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("dark-mode", true, null)
        }
    }

    @Test
    fun `throws FlagNotFoundError for a config that does not exist`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)

        val failure = assertThrows(OpenFeatureError.FlagNotFoundError::class.java) {
            provider.getBooleanEvaluation("no-such-config", true, null)
        }

        assertThat(failure).hasMessageThat().contains("no-such-config")
    }

    @Test
    fun `throws TypeMismatchError for a value of another type`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)

        val failure = assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getBooleanEvaluation("max-items", true, null)
        }

        assertThat(failure).hasMessageThat().contains("type-mismatch")
    }

    @Test
    fun `throws TypeMismatchError for a value that cannot be read as a number`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)

        val failure = assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getIntegerEvaluation("welcome-message", 7, null)
        }

        assertThat(failure).hasMessageThat().contains("invalid-number")
    }

    @Test
    fun `resolves a config with no value to the in-code default value with the reason DEFAULT`() = runBlocking<Unit> {
        val provider = provider()
        provider.initialize(proContext)

        val evaluation = provider.getBooleanEvaluation("beta-banner", true, null)

        assertThat(evaluation.value).isTrue()
        assertThat(evaluation.reason).isEqualTo(Reason.DEFAULT.name)
        assertThat(evaluation.errorCode).isNull()
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
    fun `resolves flags without a context whose context kind is invalid`() = runBlocking<Unit> {
        val provider = provider()

        provider.initialize(contextWithAnInvalidContextKind)

        assertThat(provider.getBooleanEvaluation("dark-mode", true, null).value).isFalse()
        assertThat(provider.getStringEvaluation("welcome-message", "fallback", null).value).isEqualTo("Hello, there")
    }

    @Test
    fun `resolves flags without a context whose context kind is invalid once the context changes`() =
        runBlocking<Unit> {
            val provider = provider()
            provider.initialize(proContext)

            provider.onContextSet(proContext, contextWithAnInvalidContextKind)

            assertThat(provider.getBooleanEvaluation("dark-mode", true, null).value).isFalse()
            assertThat(provider.getStringEvaluation("welcome-message", "fallback", null).value)
                .isEqualTo("Hello, there")
        }

    @Test
    fun `reports it is not ready when no config state arrives in time`() = runBlocking<Unit> {
        server.sendsConfigState = false
        val provider = provider(timeoutMillis = 200)
        val events = observing(provider)

        val failure = assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }

        assertThat(failure).hasMessageThat().contains("did not become ready during initialization")
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("dark-mode", true, null)
        }
        waitFor("the error event") { events.any { it is OpenFeatureProviderEvents.ProviderError } }
        val error = events.filterIsInstance<OpenFeatureProviderEvents.ProviderError>().single()
        assertThat(error.eventDetails?.errorCode).isEqualTo(ErrorCode.PROVIDER_NOT_READY)
        assertThat(error.eventDetails?.message).isEqualTo(failure.message)
    }

    @Test
    fun `reports it is not ready when a context change does not take in time`() = runBlocking<Unit> {
        val provider = provider(timeoutMillis = 200)
        val events = observing(provider)
        provider.initialize(proContext)
        server.sendsConfigState = false

        val failure = assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.onContextSet(proContext, freeContext) }
        }

        assertThat(failure).hasMessageThat().contains("after the context changed")
        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()
        waitFor("the error event") { events.any { it is OpenFeatureProviderEvents.ProviderError } }
        val error = events.filterIsInstance<OpenFeatureProviderEvents.ProviderError>().single()
        assertThat(error.eventDetails?.errorCode).isEqualTo(ErrorCode.PROVIDER_NOT_READY)
        assertThat(error.eventDetails?.message).isEqualTo(failure.message)
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
    fun `emits the keys a full update removed as changed after the keys it carried`() = runBlocking<Unit> {
        val provider = provider()
        val events = observing(provider)
        provider.initialize(proContext)
        waitFor("the first configuration change") {
            events.any { it is OpenFeatureProviderEvents.ProviderConfigurationChanged }
        }

        server.scriptFullWithOnly("welcome-message", "string", "Hello again")
        provider.onContextSet(proContext, ImmutableContext(targetingKey = "user-456"))

        waitFor("the second configuration change") {
            events.filterIsInstance<OpenFeatureProviderEvents.ProviderConfigurationChanged>().size == 2
        }
        val change = events.filterIsInstance<OpenFeatureProviderEvents.ProviderConfigurationChanged>()[1]
        assertThat(change.eventDetails?.flagsChanged).containsAtLeast("welcome-message", "dark-mode", "theme")
        assertThrows(OpenFeatureError.FlagNotFoundError::class.java) {
            provider.getBooleanEvaluation("dark-mode", false, null)
        }
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
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderReady>()).hasSize(1)
        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).value).isTrue()
    }

    @Test
    fun `emits ready once when initialization succeeds`() = runBlocking<Unit> {
        val provider = provider()
        val events = observing(provider)

        provider.initialize(proContext)

        waitFor("the configuration change") {
            events.any { it is OpenFeatureProviderEvents.ProviderConfigurationChanged }
        }
        Thread.sleep(200)
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderReady>()).hasSize(1)
    }

    @Test
    fun `resolves a flag served from the kept values with the reason CACHED`() = runBlocking<Unit> {
        earlierLaunch()
        val provider = provider(timeoutMillis = 300, baseUrl = unreachableBaseUrl)

        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }

        val darkMode = provider.getBooleanEvaluation("dark-mode", false, null)
        assertThat(darkMode.value).isTrue()
        assertThat(darkMode.variant).isEqualTo("dark-mode-pro")
        assertThat(darkMode.reason).isEqualTo("CACHED")
        assertThat(darkMode.errorCode).isNull()
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("no-such-config", true, null)
        }
    }

    @Test
    fun `emits stale when the kept values load and ready once live values arrive`() = runBlocking<Unit> {
        earlierLaunch()
        server.responseDelayMillis = 500
        val provider = provider()
        val events = observing(provider)

        provider.initialize(proContext)

        waitFor("the ready event") { events.any { it is OpenFeatureProviderEvents.ProviderReady } }
        assertThat(events.indexOfFirst { it is OpenFeatureProviderEvents.ProviderStale })
            .isLessThan(events.indexOfFirst { it is OpenFeatureProviderEvents.ProviderReady })
        assertThat(provider.getBooleanEvaluation("dark-mode", false, null).reason).isEqualTo("TARGETING_MATCH")
    }

    @Test
    fun `emits stale again after initialization times out while it serves the kept values`() = runBlocking<Unit> {
        earlierLaunch()
        val provider = provider(timeoutMillis = 300, baseUrl = unreachableBaseUrl)
        val events = observing(provider)

        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }

        waitFor("the second stale event") { events.count { it is OpenFeatureProviderEvents.ProviderStale } == 2 }
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderReady>()).isEmpty()
    }

    @Test
    fun `emits stale again after a context change that cannot connect while it serves the kept values`() =
        runBlocking<Unit> {
            earlierLaunch()
            val provider = provider(timeoutMillis = 300, baseUrl = unreachableBaseUrl)
            val events = observing(provider)
            assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
                runBlocking { provider.initialize(proContext) }
            }
            waitFor("the stale events of initialization") { events.count { it is OpenFeatureProviderEvents.ProviderStale } == 2 }

            assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
                runBlocking { provider.onContextSet(proContext, freeContext) }
            }

            waitFor("the third stale event") { events.count { it is OpenFeatureProviderEvents.ProviderStale } == 3 }
            assertThat(provider.getBooleanEvaluation("dark-mode", false, null).reason).isEqualTo("CACHED")
        }

    @Test
    fun `emits no stale event when nothing is kept on the device`() = runBlocking<Unit> {
        val provider = provider(timeoutMillis = 300, baseUrl = unreachableBaseUrl)
        val events = observing(provider)

        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            runBlocking { provider.initialize(proContext) }
        }

        Thread.sleep(200)
        assertThat(events.filterIsInstance<OpenFeatureProviderEvents.ProviderStale>()).isEmpty()
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getBooleanEvaluation("dark-mode", true, null)
        }
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

/** `cached-values:` followed by the SHA-256 hex digest of the client SDK key `client-sdk-key`. */
private const val CACHED_VALUES_KEY = "cached-values:24913e1336d021b669358fca9ac7212a3a52d53f77b98dca11ecb0e4441ab498"
