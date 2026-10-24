package com.configdirector

import android.content.Context
import android.content.SharedPreferences
import com.configdirector.internal.transport.PollingTransport
import com.configdirector.internal.transport.TransportOptions
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The values the client keeps on the device between launches, through the public API: a client
 * that connected once, closed, and was created again, as an app relaunch creates it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CachedValuesTest {

    private val server = FakeSdkServer()
    private val logger = RecordingLogger()
    private val clients = CopyOnWriteArrayList<ConfigDirectorClient>()
    private val unreachableBaseUrl: String = MockWebServer().let { closed ->
        closed.start()
        closed.url("/").toString().also { closed.shutdown() }
    }

    private class RecordingCachingProvider(private val sizeLimit: Int = 256 * 1024) : CachingProvider {
        @Volatile
        var entry: String? = null

        @Volatile
        var readFailure: Exception? = null

        @Volatile
        var writeFailure: Exception? = null

        @Volatile
        var removeFailure: Exception? = null

        @Volatile
        var readGate: CompletableDeferred<Unit>? = null
        val reads = AtomicInteger()
        val writes = CopyOnWriteArrayList<String>()
        val removes = AtomicInteger()

        override suspend fun read(): String? {
            reads.incrementAndGet()
            readGate?.await()
            readFailure?.let { throw it }
            return entry
        }

        override suspend fun write(value: String) {
            writes += value
            writeFailure?.let { throw it }
            entry = value
        }

        override suspend fun remove() {
            removes.incrementAndGet()
            removeFailure?.let { throw it }
            entry = null
        }

        override fun sizeLimit(): Int = sizeLimit
    }

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1, "main"))
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.close()
        Dispatchers.resetMain()
    }

    private fun client(
        baseUrl: String = server.baseUrl,
        timeoutMillis: Long = 300,
        mode: ConnectionMode = ConnectionMode.STREAMING,
        caching: CachingOptions.Builder.() -> Unit = {},
    ): ConfigDirectorClient = ConfigDirectorClient(
        RuntimeEnvironment.getApplication(),
        CLIENT_SDK_KEY,
        ClientOptions.build {
            logger(logger)
            connection {
                mode(mode)
                baseUrl(baseUrl)
                timeoutMillis(timeoutMillis)
            }
            caching(caching)
        },
    ).also { clients += it }

    private fun fastPollingClient(): ConfigDirectorClient = ConfigDirectorClient(
        RuntimeEnvironment.getApplication(),
        CLIENT_SDK_KEY,
        ClientOptions.build {
            logger(logger)
            connection {
                mode(ConnectionMode.POLLING)
                baseUrl(server.baseUrl)
            }
        },
    ) { _, options, onConfigSet -> PollingTransport(options.pollingEvery(50), onConfigSet) }.also { clients += it }

    private fun TransportOptions.pollingEvery(pollingIntervalMillis: Long) = TransportOptions(
        clientSdkKey = clientSdkKey,
        baseUrl = baseUrl,
        metaContext = metaContext,
        clientSession = clientSession,
        logger = logger,
        pollingIntervalMillis = pollingIntervalMillis,
        httpClient = httpClient,
        retryDelayMillis = retryDelayMillis,
    )

    /** A launch that connected and kept its values on the device, then closed, as an earlier app run. */
    private suspend fun earlierLaunch(
        context: ConfigDirectorContext,
        caching: CachingOptions.Builder.() -> Unit = {},
        keptEntry: () -> String? = { storedEntry() },
    ) {
        val client = client(timeoutMillis = 3_000, caching = caching)
        client.initialize(context)
        check(client.isReady) { "the earlier launch did not connect" }
        waitFor("the earlier launch to keep its values") { keptEntry() != null }
        client.close()
    }

    private fun ConfigDirectorClient.recordEvents(): List<ClientEvent> =
        CopyOnWriteArrayList<ClientEvent>().also { events -> addEventListener { events += it } }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun settle() {
        Thread.sleep(200)
    }

    private fun preferences(): SharedPreferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("configdirector-sdk", Context.MODE_PRIVATE)

    private fun storedEntry(): String? = preferences().getString(CACHED_VALUES_KEY, null)

    private fun storedConfigs(entry: String?): JSONObject = JSONObject(checkNotNull(entry)).getJSONObject("configs")

    private fun warnings(text: String): List<String> = logger.messagesContaining("WARN: ").filter { text in it }

    private val proContext = ConfigDirectorContext.build {
        id("user-123")
        name("Ada")
        trait("plan", "pro")
    }

    private val freeContext = ConfigDirectorContext.build {
        id("user-456")
        trait("plan", "free")
    }

    private val allKeys = listOf(
        "dark-mode", "welcome-message", "max-items", "sample-rate", "theme", "feature-list", "broken-json", "beta-banner",
    )

    @Test
    fun `serves the values kept on the device with the reason cached when it cannot connect`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        val client = client(baseUrl = unreachableBaseUrl)
        val events = client.recordEvents()

        client.initialize(proContext)

        val evaluation = client.evaluateBoolean("dark-mode", false)
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CACHED)
        assertThat(evaluation.isDefaultValue).isFalse()
        assertThat(evaluation.valueId).isEqualTo("dark-mode-pro")
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, Ada")
        assertThat(client.getJsonObject("theme", emptyMap())).containsEntry("primary", "#101010")
        assertThat(client.isReady).isFalse()
        waitFor("the configs updated event") { events.any { it is ClientEvent.ConfigsUpdated } }
        settle()
        assertThat(events.filterIsInstance<ClientEvent.Ready>()).isEmpty()
        assertThat(events.indexOf(ClientEvent.CachedValuesLoaded))
            .isLessThan(events.indexOfFirst { it is ClientEvent.ConfigsUpdated })
        val update = events.filterIsInstance<ClientEvent.ConfigsUpdated>().single()
        assertThat(update.keys).containsExactlyElementsIn(allKeys)
        assertThat(update.removedKeys).isEmpty()
    }

    @Test
    fun `reports a key the kept values do not carry as client not ready`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        val client = client(baseUrl = unreachableBaseUrl)

        client.initialize(proContext)

        val evaluation = client.evaluateBoolean("no-such-config", true)
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.isDefaultValue).isTrue()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CLIENT_NOT_READY)
        assertThat(client.evaluateString("max-items", "fallback").reason).isEqualTo(EvaluationReason.TYPE_MISMATCH)
    }

    @Test
    fun `hands a watch the kept values when it cannot connect`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        val client = client(baseUrl = unreachableBaseUrl)
        val values = CopyOnWriteArrayList<String>()
        client.watchString("welcome-message", "fallback") { values += it }

        client.initialize(proContext)

        waitFor("the kept value") { values.size == 2 }
        assertThat(values).containsExactly("fallback", "Hello, Ada").inOrder()
        assertThat(client.isReady).isFalse()
    }

    @Test
    fun `replaces the kept values once live values arrive`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        server.responseDelayMillis = 500
        val client = client(timeoutMillis = 3_000)
        val events = client.recordEvents()

        val initialization = launch(Dispatchers.Default) { client.initialize(proContext) }
        waitFor("the kept values") { client.evaluateBoolean("dark-mode", false).reason == EvaluationReason.CACHED }
        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isTrue()

        server.scriptFullWithOnly("dark-mode", "boolean", "true")
        initialization.join()

        assertThat(client.isReady).isTrue()
        assertThat(client.isInitializing).isFalse()
        assertThat(client.evaluateBoolean("dark-mode", false).reason).isEqualTo(EvaluationReason.FOUND_MATCH)
        assertThat(client.evaluateString("welcome-message", "fallback").reason)
            .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
        waitFor("the ready event") { events.any { it is ClientEvent.Ready } }
        settle()
        assertThat(events.filterIsInstance<ClientEvent.Ready>()).containsExactly(ClientEvent.Ready(ConnectReason.INITIALIZATION))
        val liveUpdate = events.filterIsInstance<ClientEvent.ConfigsUpdated>().last()
        assertThat(liveUpdate.keys).containsExactly("dark-mode")
        assertThat(liveUpdate.removedKeys).containsExactlyElementsIn(allKeys - "dark-mode")
        waitFor("the entry to be replaced") { storedConfigs(storedEntry()).length() == 1 }
    }

    @Test
    fun `reports an evaluation served from the kept values with the reason cached`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        server.responseDelayMillis = 500
        val client = client(timeoutMillis = 3_000)

        val initialization = launch(Dispatchers.Default) { client.initialize(proContext) }
        waitFor("the kept values") { client.getBoolean("dark-mode", false) }
        client.getInt("max-items", 0)
        initialization.join()

        waitFor("the telemetry report") { server.telemetryReports.isNotEmpty() }
        val events = server.telemetryReports.flatMap { report ->
            val evaluated = report.getJSONObject("aggregatedEvents").getJSONArray("evaluatedConfig")
            (0 until evaluated.length()).map { evaluated.getJSONObject(it).getJSONObject("event") }
        }
        val maxItems = events.single { it.getString("key") == "max-items" }
        assertThat(maxItems.getString("evaluationReason")).isEqualTo("cached")
        assertThat(maxItems.getBoolean("usedDefault")).isFalse()
        assertThat(maxItems.getString("evaluatedValueId")).isEqualTo("max-items-pro")
    }

    @Test
    fun `keeps the merged values on the device after a delta`() = runBlocking<Unit> {
        val client = fastPollingClient()
        client.initialize(proContext)
        waitFor("the first entry") { storedEntry() != null }

        server.scriptDelta("dark-mode", "boolean", "false")

        waitFor("the delta to arrive") { !client.getBoolean("dark-mode", true) }
        waitFor("the entry to hold the delta") {
            storedConfigs(storedEntry()).getJSONObject("dark-mode").getString("value") == "false"
        }
        val entry = JSONObject(checkNotNull(storedEntry()))
        assertThat(entry.getString("environmentId")).isEqualTo("env-1")
        assertThat(entry.getString("projectId")).isEqualTo("project-1")
        assertThat(entry.getJSONObject("contextTag").getString("contextId")).isEqualTo("user-123")
        val configs = entry.getJSONObject("configs")
        assertThat(configs.keys().asSequence().toList()).containsExactlyElementsIn(allKeys)
        assertThat(configs.getJSONObject("dark-mode").getString("valueId")).isEqualTo("dark-mode-delta")
        assertThat(configs.getJSONObject("welcome-message").getString("value")).isEqualTo("Hello, Ada")
    }

    @Test
    fun `serves the values a polling client kept when it cannot connect`() = runBlocking<Unit> {
        val earlier = client(timeoutMillis = 3_000, mode = ConnectionMode.POLLING)
        earlier.initialize(proContext)
        waitFor("the entry") { storedEntry() != null }
        earlier.close()
        val client = client(baseUrl = unreachableBaseUrl, mode = ConnectionMode.POLLING)

        client.initialize(proContext)

        val evaluation = client.evaluateBoolean("dark-mode", false)
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CACHED)
    }

    @Test
    fun `serves in-code default values for another launch context until its live values replace the entry`() =
        runBlocking<Unit> {
            earlierLaunch(proContext)
            server.responseDelayMillis = 500
            val client = client(timeoutMillis = 3_000)
            val events = client.recordEvents()

            val initialization = launch(Dispatchers.Default) { client.initialize(freeContext) }
            waitFor("initialize to start") { client.isInitializing }
            settle()
            val beforeLiveValues = client.evaluateBoolean("dark-mode", true)
            assertThat(beforeLiveValues.value).isEqualTo(true)
            assertThat(beforeLiveValues.reason).isEqualTo(EvaluationReason.CLIENT_NOT_READY)

            initialization.join()

            assertThat(client.evaluateBoolean("dark-mode", true).value).isEqualTo(false)
            assertThat(events).doesNotContain(ClientEvent.CachedValuesLoaded)
            waitFor("the entry to be replaced") {
                JSONObject(checkNotNull(storedEntry())).getJSONObject("contextTag").optString("contextId") == "user-456"
            }
        }

    @Test
    fun `serves in-code default values on a first launch`() = runBlocking<Unit> {
        val client = client(baseUrl = unreachableBaseUrl)
        val events = client.recordEvents()

        client.initialize(proContext)

        val evaluation = client.evaluateBoolean("dark-mode", true)
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CLIENT_NOT_READY)
        settle()
        assertThat(events).doesNotContain(ClientEvent.CachedValuesLoaded)
        assertThat(storedEntry()).isNull()
        assertThat(warnings("device")).isEmpty()
    }

    @Test
    fun `removes the entry and warns once when it exceeds the size limit`() = runBlocking<Unit> {
        val provider = RecordingCachingProvider(sizeLimit = 100)
        val client = client(timeoutMillis = 3_000) { provider(provider) }

        client.initialize(proContext)
        waitFor("the first removal") { provider.removes.get() == 1 }
        client.updateContext(freeContext)

        waitFor("the second removal") { provider.removes.get() == 2 }
        assertThat(provider.writes).isEmpty()
        assertThat(warnings("size limit")).hasSize(1)
        assertThat(client.getBoolean("dark-mode", true)).isFalse()
    }

    @Test
    fun `reads and writes nothing when caching is turned off`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        val provider = RecordingCachingProvider()
        val client = client(timeoutMillis = 3_000) {
            enabled(false)
            provider(provider)
        }
        val entryBefore = storedEntry()

        client.initialize(freeContext)

        settle()
        assertThat(provider.reads.get()).isEqualTo(0)
        assertThat(provider.writes).isEmpty()
        assertThat(provider.removes.get()).isEqualTo(0)
        assertThat(storedEntry()).isEqualTo(entryBefore)
        assertThat(client.getBoolean("dark-mode", true)).isFalse()
    }

    @Test
    fun `keeps the values in the caching provider it was given instead of the app's preferences`() = runBlocking<Unit> {
        val provider = RecordingCachingProvider()
        earlierLaunch(proContext, caching = { provider(provider) }, keptEntry = { provider.entry })
        val client = client(baseUrl = unreachableBaseUrl) { provider(provider) }

        client.initialize(proContext)

        assertThat(storedEntry()).isNull()
        val evaluation = client.evaluateInt("max-items", 0)
        assertThat(evaluation.value).isEqualTo(25)
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CACHED)
    }

    @Test
    fun `keeps nothing in offline mode`() = runBlocking<Unit> {
        val provider = RecordingCachingProvider()
        val client = ConfigDirectorClient(
            RuntimeEnvironment.getApplication(),
            ClientOptions.build {
                logger(logger)
                connection {
                    mode(ConnectionMode.OFFLINE)
                    values(mapOf("new-checkout" to true))
                }
                caching { provider(provider) }
            },
        ).also { clients += it }

        client.initialize(proContext)

        settle()
        assertThat(client.getBoolean("new-checkout", false)).isTrue()
        assertThat(provider.reads.get()).isEqualTo(0)
        assertThat(provider.writes).isEmpty()
        assertThat(storedEntry()).isNull()
    }

    @Test
    fun `starts without kept values and warns once when the provider cannot be read`() = runBlocking<Unit> {
        val provider = RecordingCachingProvider()
        earlierLaunch(proContext, caching = { provider(provider) }, keptEntry = { provider.entry })
        val keptEntry = provider.entry
        provider.readFailure = IllegalStateException("store unavailable")
        val client = client(baseUrl = unreachableBaseUrl) { provider(provider) }

        client.initialize(proContext)

        assertThat(client.evaluateBoolean("dark-mode", true).reason).isEqualTo(EvaluationReason.CLIENT_NOT_READY)
        assertThat(warnings("could not read")).hasSize(1)
        assertThat(provider.removes.get()).isEqualTo(0)
        assertThat(provider.entry).isEqualTo(keptEntry)
    }

    @Test
    fun `keeps running and warns once when the provider cannot be written`() = runBlocking<Unit> {
        val provider = RecordingCachingProvider().apply { writeFailure = IllegalStateException("disk full") }
        val client = client(timeoutMillis = 3_000) { provider(provider) }

        client.initialize(proContext)
        waitFor("the first write attempt") { provider.writes.size == 1 }
        client.updateContext(freeContext)

        waitFor("the second write attempt") { provider.writes.size == 2 }
        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("dark-mode", true)).isFalse()
        assertThat(warnings("could not keep")).hasSize(1)
        assertThat(provider.entry).isNull()
    }

    @Test
    fun `keeps running and warns once when the provider cannot remove the entry`() = runBlocking<Unit> {
        val provider = RecordingCachingProvider(sizeLimit = 100).apply { removeFailure = IllegalStateException("locked") }
        val client = client(timeoutMillis = 3_000) { provider(provider) }

        client.initialize(proContext)
        waitFor("the first removal attempt") { provider.removes.get() == 1 }
        client.updateContext(freeContext)

        waitFor("the second removal attempt") { provider.removes.get() == 2 }
        assertThat(client.isReady).isTrue()
        assertThat(warnings("could not remove")).hasSize(1)
    }

    @Test
    fun `keeps serving the kept values through a context update that cannot connect`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        val client = client(baseUrl = unreachableBaseUrl)
        client.initialize(proContext)

        client.updateContext(freeContext)

        val evaluation = client.evaluateBoolean("dark-mode", false)
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.CACHED)
        assertThat(client.isReady).isFalse()
    }

    @Test
    fun `removes an entry whose format version it does not know`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        val newerEntry = JSONObject(checkNotNull(storedEntry())).put("formatVersion", 2).toString()
        preferences().edit().putString(CACHED_VALUES_KEY, newerEntry).commit()
        val client = client(baseUrl = unreachableBaseUrl)

        client.initialize(proContext)

        assertThat(client.evaluateBoolean("dark-mode", true).reason).isEqualTo(EvaluationReason.CLIENT_NOT_READY)
        waitFor("the entry to be removed") { storedEntry() == null }
        assertThat(warnings("could not be read")).hasSize(1)
    }

    @Test
    fun `polls without a watermark after a relaunch`() = runBlocking<Unit> {
        val earlier = client(timeoutMillis = 3_000, mode = ConnectionMode.POLLING)
        earlier.initialize(proContext)
        waitFor("the entry") { storedEntry() != null }
        earlier.close()
        checkNotNull(server.takeRequest())
        val client = client(baseUrl = server.baseUrl, timeoutMillis = 3_000, mode = ConnectionMode.POLLING)

        client.initialize(proContext)

        val relaunchRequest = checkNotNull(server.takeRequest())
        assertThat(relaunchRequest.path).isEqualTo("/client/polling/v1")
        assertThat(JSONObject(relaunchRequest.body.readUtf8()).has("lastUpdateTimestamp")).isFalse()
    }

    @Test
    fun `leaves the instance ID storage alone and keeps the entry under its own key`() = runBlocking<Unit> {
        earlierLaunch(proContext)
        val instanceIdRecord = preferences().getString("instance-id", null)

        earlierLaunch(freeContext)

        assertThat(preferences().getString("instance-id", null)).isEqualTo(instanceIdRecord)
        assertThat(preferences().all.keys).containsExactly("instance-id", CACHED_VALUES_KEY)
    }

    @Test
    fun `ignores kept values whose read completes after live values arrived`() = runBlocking<Unit> {
        val provider = RecordingCachingProvider()
        earlierLaunch(proContext, caching = { provider(provider) }, keptEntry = { provider.entry })
        val readGate = CompletableDeferred<Unit>().also { provider.readGate = it }
        val client = client(timeoutMillis = 3_000) { provider(provider) }
        val events = client.recordEvents()

        client.initialize(proContext)
        assertThat(client.evaluateBoolean("dark-mode", false).reason).isEqualTo(EvaluationReason.FOUND_MATCH)
        readGate.complete(Unit)

        settle()
        assertThat(client.evaluateBoolean("dark-mode", false).reason).isEqualTo(EvaluationReason.FOUND_MATCH)
        assertThat(events).doesNotContain(ClientEvent.CachedValuesLoaded)
    }
}

private const val CLIENT_SDK_KEY = "client-sdk-key"

/** `cached-values:` followed by the SHA-256 hex digest of the client SDK key. */
private const val CACHED_VALUES_KEY = "cached-values:24913e1336d021b669358fca9ac7212a3a52d53f77b98dca11ecb0e4441ab498"
