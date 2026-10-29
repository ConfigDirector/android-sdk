package com.configdirector

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigSetKind
import com.configdirector.internal.ConfigState
import com.configdirector.internal.ConfigType
import com.configdirector.internal.transport.PollingTransport
import com.configdirector.internal.transport.StreamingTransport
import com.configdirector.internal.transport.Transport
import com.configdirector.internal.transport.TransportOptions
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigDirectorClientTest {

    private val server = FakeSdkServer()
    private val logger = RecordingLogger()
    private var client: ConfigDirectorClient? = null
    private var transportOptions: TransportOptions? = null

    private val capturingTransportFactory: TransportFactory = { mode, options ->
        transportOptions = options
        transportFor(mode, options)
    }

    private val fastPollingTransportFactory: TransportFactory = { _, options ->
        PollingTransport(options.pollingEvery(50))
    }

    private val fastReconnectingTransportFactory: TransportFactory = { _, options ->
        StreamingTransport(options.reconnectingAfter(20))
    }

    private val shortStallTimeoutTransportFactory: TransportFactory = { _, options ->
        StreamingTransport(options.reconnectingAfter(20, stallTimeoutMillis = 700))
    }

    private val connectReasons = CopyOnWriteArrayList<ConnectReason>()

    private val reasonRecordingTransportFactory: TransportFactory = { mode, options ->
        val transport = transportFor(mode, options)
        object : Transport by transport {
            override suspend fun connect(
                context: ConfigDirectorContext,
                timeoutMillis: Long,
                reason: ConnectReason,
                onConfigSet: (ConfigSet) -> Unit,
            ) {
                connectReasons += reason
                transport.connect(context, timeoutMillis, reason, onConfigSet)
            }
        }
    }

    private val unexpectedlyFailingTransportFactory: TransportFactory = { mode, options ->
        val transport = transportFor(mode, options)
        object : Transport by transport {
            override suspend fun connect(
                context: ConfigDirectorContext,
                timeoutMillis: Long,
                reason: ConnectReason,
                onConfigSet: (ConfigSet) -> Unit,
            ) {
                throw IllegalStateException("The transport broke.")
            }
        }
    }

    private val darkModeTurnedOff = ConfigSet(
        mapOf(
            "dark-mode" to ConfigState(
                "dark-mode",
                ConfigType.BOOLEAN,
                "false",
                id = "dark-mode-id",
                valueId = "dark-mode-free",
            ),
        ),
        ConfigSetKind.DELTA,
    )

    private val changeOnTheEarlierConnectionTransportFactory: TransportFactory = { mode, options ->
        val transport = transportFor(mode, options)
        var initializationOnConfigSet: ((ConfigSet) -> Unit)? = null
        object : Transport by transport {
            override suspend fun connect(
                context: ConfigDirectorContext,
                timeoutMillis: Long,
                reason: ConnectReason,
                onConfigSet: (ConfigSet) -> Unit,
            ) {
                when (reason) {
                    ConnectReason.INITIALIZATION -> initializationOnConfigSet = onConfigSet
                    ConnectReason.CONTEXT_UPDATE -> initializationOnConfigSet?.invoke(darkModeTurnedOff)
                    ConnectReason.NETWORK_RESUME -> Unit
                }
                transport.connect(context, timeoutMillis, reason, onConfigSet)
            }
        }
    }

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

    private fun TransportOptions.reconnectingAfter(
        retryDelayMillis: Long,
        stallTimeoutMillis: Long = this.stallTimeoutMillis,
    ) = TransportOptions(
        clientSdkKey = clientSdkKey,
        baseUrl = baseUrl,
        metaContext = metaContext,
        clientSession = clientSession,
        logger = logger,
        pollingIntervalMillis = pollingIntervalMillis,
        httpClient = httpClient,
        retryDelayMillis = { retryDelayMillis },
        stallTimeoutMillis = stallTimeoutMillis,
    )

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1, "main"))
    }

    @After
    fun tearDown() {
        client?.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun client(
        mode: ConnectionMode = ConnectionMode.STREAMING,
        pollingIntervalMillis: Long = 60_000,
        timeoutMillis: Long = 3_000,
        metadata: Metadata = Metadata.empty(),
        baseUrl: String = server.baseUrl,
        transportFactory: TransportFactory = capturingTransportFactory,
    ): ConfigDirectorClient = ConfigDirectorClient(
        RuntimeEnvironment.getApplication(),
        "client-sdk-key",
        ClientOptions.build {
            logger(logger)
            metadata(metadata)
            connection(
                ConnectionOptions(
                    mode = mode,
                    pollingIntervalMillis = pollingIntervalMillis,
                    timeoutMillis = timeoutMillis,
                    baseUrl = baseUrl,
                    pausesWhileBackgrounded = true,
                    values = null,
                    offlineConfigs = emptyMap(),
                ),
            )
        },
        transportFactory,
    ).also { client = it }

    private fun fastPollingClient(): ConfigDirectorClient =
        client(mode = ConnectionMode.POLLING, transportFactory = fastPollingTransportFactory)

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun settle() {
        Thread.sleep(200)
    }

    private val proContext = ConfigDirectorContext.build {
        id("user-123")
        name("Ada")
        trait("plan", "pro")
    }

    private val freeContext = ConfigDirectorContext.build { id("user-456") }

    private val contextWithAnInvalidContextKind = ConfigDirectorContext.build {
        id("user-123")
        kind("Tenant")
        name("Ada")
        trait("plan", "pro")
    }

    private val noContextJson = """{"anonymous":false}"""

    private fun errorsLogged(text: String) = logger.messagesContaining("ERROR: ").filter { text in it }

    private fun ConfigDirectorClient.recordContextUpdates(): List<ClientEvent.ContextUpdated> =
        CopyOnWriteArrayList<ClientEvent.ContextUpdated>().also { contextUpdates ->
            addEventListener { event -> if (event is ClientEvent.ContextUpdated) contextUpdates += event }
        }

    private fun invalidContextSubmissionCounts(): Map<String?, Int> = server.telemetryReports
        .flatMap { report ->
            val submissions = report.getJSONObject("aggregatedEvents").getJSONArray("invalidContext")
            (0 until submissions.length()).map { submissions.getJSONObject(it) }
        }
        .groupBy(
            { it.getJSONObject("event").optString("kindSample").ifEmpty { null } },
            { it.getInt("count") },
        )
        .mapValues { (_, counts) -> counts.sum() }

    private fun sentContexts(path: String, count: Int): List<String> =
        generateSequence { server.takeRequest() }
            .filter { it.path == path }
            .take(count)
            .map { JSONObject(it.body).getJSONObject("givenContext").toString() }
            .toList()

    private fun latestSentContextId(path: String): String? =
        generateSequence { server.takeRequest(timeoutMillis = 0) }
            .filter { it.path == path }
            .lastOrNull()
            ?.let { JSONObject(it.body).getJSONObject("givenContext").getString("id") }

    @Test
    fun `rejects a blank client SDK key`() {
        val failure = assertThrows(ConfigDirectorValidationException::class.java) {
            ConfigDirectorClient(RuntimeEnvironment.getApplication(), "   ")
        }

        assertThat(failure).hasMessageThat().contains("No client SDK key was provided")
    }

    @Test
    fun `warns that a base URL which is not HTTPS travels in plain text`() {
        client(baseUrl = "http://proxy.example.com")

        assertThat(logger.messagesContaining("is not HTTPS")).hasSize(1)
    }

    @Test
    fun `says nothing about a base URL that is HTTPS`() {
        client(baseUrl = "https://proxy.example.com")

        assertThat(logger.messagesContaining("is not HTTPS")).isEmpty()
    }

    @Test
    fun `polls every 60 seconds by default`() {
        client = ConfigDirectorClient(
            RuntimeEnvironment.getApplication(),
            "client-sdk-key",
            ClientOptions.build {
                logger(logger)
                connection { mode(ConnectionMode.POLLING) }
            },
            capturingTransportFactory,
        )

        assertThat(transportOptions?.pollingIntervalMillis).isEqualTo(60_000)
        assertThat(logger.messagesContaining("below the minimum")).isEmpty()
    }

    @Test
    fun `raises a polling interval below the minimum and warns once`() = runBlocking {
        val client = client(mode = ConnectionMode.POLLING, pollingIntervalMillis = 10_000)

        assertThat(transportOptions?.pollingIntervalMillis).isEqualTo(30_000)
        assertThat(logger.messagesContaining("below the minimum")).containsExactly(
            "WARN: pollingIntervalMillis of 10000 ms is below the minimum of 30000 ms. " +
                "Using 30000 ms.",
        )

        client.initialize()
        client.updateContext(proContext)

        assertThat(logger.messagesContaining("below the minimum")).hasSize(1)
    }

    @Test
    fun `uses a polling interval of exactly the minimum unchanged`() {
        client(mode = ConnectionMode.POLLING, pollingIntervalMillis = 30_000)

        assertThat(transportOptions?.pollingIntervalMillis).isEqualTo(30_000)
        assertThat(logger.messagesContaining("below the minimum")).isEmpty()
    }

    @Test
    fun `raises a zero polling interval to the minimum`() {
        client(mode = ConnectionMode.POLLING, pollingIntervalMillis = 0)

        assertThat(transportOptions?.pollingIntervalMillis).isEqualTo(30_000)
        assertThat(logger.messagesContaining("below the minimum")).hasSize(1)
    }

    @Test
    fun `raises a negative polling interval to the minimum`() {
        client(mode = ConnectionMode.POLLING, pollingIntervalMillis = -1)

        assertThat(transportOptions?.pollingIntervalMillis).isEqualTo(30_000)
        assertThat(logger.messagesContaining("below the minimum")).hasSize(1)
    }

    @Test
    fun `says nothing about a low polling interval when streaming`() {
        client(mode = ConnectionMode.STREAMING, pollingIntervalMillis = 10_000)

        assertThat(logger.messagesContaining("below the minimum")).isEmpty()
    }

    @Test
    fun `serves default values before it is initialized`() {
        val client = client()

        assertThat(client.isReady).isFalse()
        assertThat(client.getBoolean("dark-mode", false)).isFalse()
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("fallback")
        assertThat(logger.messagesContaining("(client-not-ready)")).hasSize(2)
    }

    @Test
    fun `serves the config state it received once initialized`() = runBlocking {
        val client = client()

        client.initialize(proContext)

        assertThat(client.isReady).isTrue()
        assertThat(client.getBoolean("dark-mode", false)).isTrue()
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, Ada")
        assertThat(client.getInt("max-items", 0)).isEqualTo(25)
        assertThat(client.getDouble("sample-rate", 0.0)).isEqualTo(0.25)
    }

    @Test
    fun `sends the key, the context and the metadata it was built with`() = runBlocking {
        client(metadata = Metadata("Checkout", "4.2.0")).initialize(proContext)

        val request = checkNotNull(server.takeRequest())
        assertThat(request.path).isEqualTo("/client/sse/v1")
        val body = JSONObject(request.body)
        assertThat(body.getString("clientSdkKey")).isEqualTo("client-sdk-key")
        assertThat(body.getString("instanceId")).isNotEmpty()
        assertThat(body.getJSONObject("givenContext").getString("id")).isEqualTo("user-123")

        val meta = body.getJSONObject("metaContext")
        assertThat(meta.getString("sdkName")).isEqualTo("android-client-sdk")
        assertThat(meta.getString("sdkVersion")).isNotEmpty()
        assertThat(meta.getString("appName")).isEqualTo("Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("4.2.0")
        assertThat(meta.getString("userAgent")).isEqualTo("Android")
    }

    private fun sentPayload(): JSONObject =
        JSONObject(checkNotNull(server.takeRequest()).body)

    private fun instanceIdPreferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("configdirector-sdk", Context.MODE_PRIVATE)

    private fun storedInstanceIdRecord(): JSONObject =
        JSONObject(checkNotNull(instanceIdPreferences().getString("instance-id", null)))

    @Test
    fun `stores the instance ID it generates and a client created later sends the same one`() =
        runBlocking {
            val first = client()
            first.initialize(proContext)

            val firstPayload = sentPayload()
            val instanceId = firstPayload.getString("instanceId")
            assertThat(storedInstanceIdRecord().getString("id")).isEqualTo(instanceId)
            assertThat(firstPayload.has("ephemeral")).isFalse()

            first.close()
            client().initialize(proContext)

            val secondPayload = sentPayload()
            assertThat(secondPayload.getString("instanceId")).isEqualTo(instanceId)
            assertThat(secondPayload.has("ephemeral")).isFalse()
        }

    @Test
    fun `connects with the instance ID an earlier version of the SDK stored`() = runBlocking {
        val storedByEarlierVersion =
            """{"v":1,"id":"$STORED_INSTANCE_ID","createdAt":1760000000000}"""
        instanceIdPreferences().edit().putString("instance-id", storedByEarlierVersion).commit()

        client().initialize(proContext)

        val payload = sentPayload()
        assertThat(payload.getString("instanceId")).isEqualTo(STORED_INSTANCE_ID)
        assertThat(payload.has("ephemeral")).isFalse()
        assertThat(instanceIdPreferences().getString("instance-id", null)).isEqualTo(storedByEarlierVersion)
    }

    @Test
    fun `sends an ephemeral instance ID when the app's preferences cannot be read`() = runBlocking {
        val withUnreadablePreferences = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("preferences unavailable")
        }
        val clientWithoutStorage = ConfigDirectorClient(
            withUnreadablePreferences,
            "client-sdk-key",
            ClientOptions.build {
                logger(logger)
                connection { baseUrl(server.baseUrl) }
            },
        ).also { client = it }

        clientWithoutStorage.initialize(proContext)

        val body = sentPayload()
        assertThat(body.getString("instanceId")).isNotEmpty()
        assertThat(body.getBoolean("ephemeral")).isTrue()
        assertThat(clientWithoutStorage.isReady).isTrue()
    }

    private fun installedApplication(label: String, versionName: String?) {
        val application = RuntimeEnvironment.getApplication()
        val packageInfo = shadowOf(application.packageManager)
            .getInternalMutablePackageInfo(application.packageName)
        packageInfo.versionName = versionName
        checkNotNull(packageInfo.applicationInfo).nonLocalizedLabel = label
    }

    private fun sentMetaContext(): JSONObject = sentPayload().getJSONObject("metaContext")

    @Test
    fun `reads the app name and version from the application when metadata leaves them unset`() =
        runBlocking {
            installedApplication(label = "Robo Checkout", versionName = "7.1.0")

            client().initialize(proContext)

            val meta = sentMetaContext()
            assertThat(meta.getString("appName")).isEqualTo("Robo Checkout")
            assertThat(meta.getString("appVersion")).isEqualTo("7.1.0")
            assertThat(logger.messagesContaining("could not find")).isEmpty()
        }

    @Test
    fun `keeps the app name it was given and reads the version from the application`() = runBlocking {
        installedApplication(label = "Robo Checkout", versionName = "7.1.0")

        client(metadata = Metadata(appName = "Checkout")).initialize(proContext)

        val meta = sentMetaContext()
        assertThat(meta.getString("appName")).isEqualTo("Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("7.1.0")
    }

    @Test
    fun `keeps the app version it was given and reads the name from the application`() = runBlocking {
        installedApplication(label = "Robo Checkout", versionName = "7.1.0")

        client(metadata = Metadata(appVersion = "4.2.0")).initialize(proContext)

        val meta = sentMetaContext()
        assertThat(meta.getString("appName")).isEqualTo("Robo Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("4.2.0")
    }

    @Test
    fun `says which of the app name and version it could not find`() = runBlocking {
        installedApplication(label = "Robo Checkout", versionName = null)

        client().initialize(proContext)

        val meta = sentMetaContext()
        assertThat(meta.getString("appName")).isEqualTo("Robo Checkout")
        assertThat(meta.has("appVersion")).isFalse()
        assertThat(logger.messagesContaining("could not find an app version")).hasSize(1)
        assertThat(logger.messagesContaining("could not find an app name")).isEmpty()
    }

    @OptIn(ConfigDirectorWrapperApi::class)
    @Test
    fun `reads the app name and version from the application for a wrapper too`() = runBlocking {
        installedApplication(label = "Robo Checkout", versionName = "7.1.0")

        wrapperClient(SdkIdentity.openFeatureProvider("9.9.9"), Metadata.empty()).initialize(proContext)

        val meta = sentMetaContext()
        assertThat(meta.getString("sdkName")).isEqualTo("android-openfeature-client-provider")
        assertThat(meta.getString("appName")).isEqualTo("Robo Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("7.1.0")
    }

    @OptIn(ConfigDirectorWrapperApi::class)
    private fun wrapperClient(
        identity: SdkIdentity,
        metadata: Metadata = Metadata("Checkout", "4.2.0"),
    ): ConfigDirectorClient = ConfigDirectorClient(
        RuntimeEnvironment.getApplication(),
        "client-sdk-key",
        ClientOptions.build {
            logger(logger)
            metadata(metadata)
            connection { baseUrl(server.baseUrl) }
        },
        identity,
    ).also { client = it }

    @OptIn(ConfigDirectorWrapperApi::class)
    @Test
    fun `identifies itself as the wrapper it was built for`() = runBlocking {
        wrapperClient(SdkIdentity.openFeatureProvider("9.9.9")).initialize(proContext)

        val request = checkNotNull(server.takeRequest())
        val meta = JSONObject(request.body).getJSONObject("metaContext")
        assertThat(meta.getString("sdkName")).isEqualTo("android-openfeature-client-provider")
        assertThat(meta.getString("sdkVersion")).isEqualTo("9.9.9")
        assertThat(meta.getString("appName")).isEqualTo("Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("4.2.0")
        assertThat(meta.getString("userAgent")).isEqualTo("Android")
    }

    @OptIn(ConfigDirectorWrapperApi::class)
    @Test
    fun `reports telemetry as the wrapper it was built for`() = runBlocking {
        val client = wrapperClient(SdkIdentity.openFeatureProvider("9.9.9"))
        client.initialize(proContext)
        client.getBoolean("dark-mode", false)

        client.close()

        waitFor("the telemetry report") { server.telemetryReports.isNotEmpty() }
        val meta = server.telemetryReports.first().getJSONObject("metaContext")
        assertThat(meta.getString("sdkName")).isEqualTo("android-openfeature-client-provider")
        assertThat(meta.getString("sdkVersion")).isEqualTo("9.9.9")
        assertThat(meta.getString("appName")).isEqualTo("Checkout")
        assertThat(meta.getString("appVersion")).isEqualTo("4.2.0")
        assertThat(meta.getString("userAgent")).isEqualTo("Android")
    }

    @Test
    fun `serves a JSON config as its raw document`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getString("theme", "{}")).isEqualTo(FakeSdkServer.THEME_DOCUMENT)
    }

    @Test
    fun `serves the default when a boolean or numeric config is read as a string`() = runBlocking {
        val client = client()

        client.initialize()

        val booleanAsString = client.evaluateString("dark-mode", "fallback")
        assertThat(booleanAsString.value).isEqualTo("fallback")
        assertThat(booleanAsString.isDefaultValue).isTrue()
        assertThat(booleanAsString.valueId).isNull()
        assertThat(booleanAsString.reason).isEqualTo(EvaluationReason.TYPE_MISMATCH)
        assertThat(client.evaluateString("max-items", "fallback").reason)
            .isEqualTo(EvaluationReason.TYPE_MISMATCH)
        assertThat(client.evaluateString("sample-rate", "fallback").reason)
            .isEqualTo(EvaluationReason.TYPE_MISMATCH)
    }

    @Test
    fun `serves a JSON config as a map`() = runBlocking {
        val client = client()

        client.initialize()

        val theme = client.getJsonObject("theme", emptyMap<String, Any?>())
        assertThat(theme["primary"]).isEqualTo("#101010")
        assertThat(theme["enabled"]).isEqualTo(true)
        assertThat(theme["spacing"]).isEqualTo(mapOf("small" to 4))
        // JSONObject.NULL equals null, so only an identity check can tell them apart.
        val tags = theme["tags"] as List<*>
        assertThat(tags).hasSize(2)
        assertThat(tags[0]).isEqualTo("a")
        assertThat(tags[1]).isNull()
    }

    @Test
    fun `leaves a JSON default value unserialized on the reading thread`() = runBlocking {
        val client = client()
        val fallback = CountingMap(mapOf("primary" to "#ffffff"))

        client.initialize()
        repeat(3) { client.getJsonObject("theme", fallback) }

        assertThat(fallback.iterations).isEqualTo(0)
    }

    @Test
    fun `parses a JSON config once however often it is read`() = runBlocking {
        val client = client()

        client.initialize()

        val first = client.getJsonObject("theme", emptyMap<String, Any?>())
        assertThat(client.getJsonObject("theme", emptyMap<String, Any?>())).isSameInstanceAs(first)
    }

    @Test
    fun `serves a JSON config as a document no caller can alter`() = runBlocking<Unit> {
        val client = client()

        client.initialize()

        val theme = client.getJsonObject("theme", emptyMap<String, Any?>())
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (theme as MutableMap<String, Any?>)["primary"] = "#ffffff"
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (theme["spacing"] as MutableMap<String, Any?>)["small"] = 8
        }
    }

    @Test
    fun `serves a JSON config as a list`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getJsonArray("feature-list", emptyList<Any?>()))
            .containsExactly("alpha", "beta")
            .inOrder()
    }

    @Test
    fun `falls back when the JSON document cannot be read`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getJsonObject("broken-json", mapOf<String, Any?>("fell" to "back")))
            .containsExactly("fell", "back")
        assertThat(logger.messagesContaining("(invalid-json)")).hasSize(1)
    }

    @Test
    fun `falls back when a JSON object is read as a list`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getJsonArray("theme", listOf<Any?>("fallback"))).containsExactly("fallback")
        assertThat(logger.messagesContaining("(invalid-json)")).hasSize(1)
    }

    @Test
    fun `falls back when the config is not a JSON config`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getJsonObject("welcome-message", emptyMap<String, Any?>())).isEmpty()
        assertThat(logger.messagesContaining("(type-mismatch)")).hasSize(1)
    }

    @Test
    fun `explains a config it served`() = runBlocking {
        val client = client()

        client.initialize(proContext)

        val evaluation = client.evaluateBoolean("dark-mode", false)
        assertThat(evaluation.key).isEqualTo("dark-mode")
        assertThat(evaluation.value).isEqualTo(true)
        assertThat(evaluation.valueId).isEqualTo("dark-mode-pro")
        assertThat(evaluation.isDefaultValue).isFalse()
        assertThat(evaluation.reason).isEqualTo(EvaluationReason.FOUND_MATCH)
        assertThat(evaluation.context).isEqualTo(proContext)
    }

    @Test
    fun `explains a config of every readable type`() = runBlocking {
        val client = client()

        client.initialize(proContext)

        val string = client.evaluateString("welcome-message", "fallback")
        assertThat(string.value).isEqualTo("Hello, Ada")
        assertThat(string.valueId).isEqualTo("welcome-message-pro")
        assertThat(string.reason).isEqualTo(EvaluationReason.FOUND_MATCH)

        val int = client.evaluateInt("max-items", 0)
        assertThat(int.value).isEqualTo(25)
        assertThat(int.valueId).isEqualTo("max-items-pro")
        assertThat(int.reason).isEqualTo(EvaluationReason.FOUND_MATCH)

        val double = client.evaluateDouble("sample-rate", 0.0)
        assertThat(double.value).isEqualTo(0.25)
        assertThat(double.valueId).isEqualTo("sample-rate-only")
        assertThat(double.reason).isEqualTo(EvaluationReason.FOUND_MATCH)

        val jsonObject = client.evaluateJsonObject("theme", emptyMap<String, Any?>())
        assertThat((jsonObject.value as Map<*, *>)["primary"]).isEqualTo("#101010")
        assertThat(jsonObject.valueId).isEqualTo("theme-only")
        assertThat(jsonObject.reason).isEqualTo(EvaluationReason.FOUND_MATCH)

        val jsonArray = client.evaluateJsonArray("feature-list", emptyList<Any?>())
        assertThat(jsonArray.value as List<*>).containsExactly("alpha", "beta").inOrder()
        assertThat(jsonArray.valueId).isEqualTo("feature-list-only")
        assertThat(jsonArray.reason).isEqualTo(EvaluationReason.FOUND_MATCH)
    }

    @Test
    fun `explains why it fell back on the default value`() = runBlocking {
        val client = client()

        val notReady = client.evaluateBoolean("dark-mode", true)
        assertThat(notReady.value).isEqualTo(true)
        assertThat(notReady.valueId).isNull()
        assertThat(notReady.isDefaultValue).isTrue()
        assertThat(notReady.reason).isEqualTo(EvaluationReason.CLIENT_NOT_READY)
        assertThat(notReady.context).isNull()

        client.initialize(proContext)

        assertThat(client.evaluateBoolean("no-such-config", true).reason)
            .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING)
        assertThat(client.evaluateBoolean("max-items", true).reason)
            .isEqualTo(EvaluationReason.TYPE_MISMATCH)
        assertThat(client.evaluateBoolean("beta-banner", true).reason)
            .isEqualTo(EvaluationReason.VALUE_MISSING)
        assertThat(client.evaluateBoolean("welcome-message", true).reason)
            .isEqualTo(EvaluationReason.INVALID_BOOLEAN)
        assertThat(client.evaluateInt("welcome-message", 7).reason)
            .isEqualTo(EvaluationReason.INVALID_NUMBER)
        assertThat(client.evaluateDouble("welcome-message", 1.5).reason)
            .isEqualTo(EvaluationReason.INVALID_NUMBER)
        assertThat(client.evaluateJsonObject("broken-json", emptyMap<String, Any?>()).reason)
            .isEqualTo(EvaluationReason.INVALID_JSON)
        assertThat(client.evaluateJsonArray("theme", emptyList<Any?>()).reason)
            .isEqualTo(EvaluationReason.INVALID_JSON)
        assertThat(client.evaluateJsonObject("welcome-message", emptyMap<String, Any?>()).reason)
            .isEqualTo(EvaluationReason.TYPE_MISMATCH)

        val fallback = client.evaluateInt("welcome-message", 7)
        assertThat(fallback.value).isEqualTo(7)
        assertThat(fallback.isDefaultValue).isTrue()
        assertThat(fallback.context).isEqualTo(proContext)
    }

    @Test
    fun `counts an explained evaluation in telemetry`() = runBlocking {
        val client = client()
        client.initialize(proContext)
        client.evaluateBoolean("dark-mode", false)

        client.close()

        waitFor("the telemetry report") { server.telemetryReports.isNotEmpty() }
        val events = server.telemetryReports.first()
            .getJSONObject("aggregatedEvents")
            .getJSONArray("evaluatedConfig")
        assertThat(events.length()).isEqualTo(1)
        val event = events.getJSONObject(0).getJSONObject("event")
        assertThat(event.getString("key")).isEqualTo("dark-mode")
        assertThat(event.getString("evaluationReason")).isEqualTo("found-match")
    }

    @Test
    fun `truncates a decimal read as a whole number`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getInt("sample-rate", 7)).isEqualTo(0)
    }

    @Test
    fun `falls back when no config carries the key`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("no-such-config", true)).isTrue()
        assertThat(logger.messagesContaining("(config-state-missing)")).hasSize(1)
    }

    @Test
    fun `falls back when the config holds a different type`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("max-items", true)).isTrue()
        assertThat(logger.messagesContaining("(type-mismatch)")).hasSize(1)
    }

    @Test
    fun `falls back when the config has no value for the context`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("beta-banner", true)).isTrue()
        assertThat(logger.messagesContaining("(value-missing)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a boolean`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getBoolean("welcome-message", true)).isTrue()
        assertThat(logger.messagesContaining("(invalid-boolean)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a whole number`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getInt("welcome-message", 7)).isEqualTo(7)
        assertThat(logger.messagesContaining("(invalid-number)")).hasSize(1)
    }

    @Test
    fun `falls back when the value does not spell a decimal number`() = runBlocking {
        val client = client()

        client.initialize()

        assertThat(client.getDouble("welcome-message", 1.5)).isEqualTo(1.5)
        assertThat(logger.messagesContaining("(invalid-number)")).hasSize(1)
    }

    @Test
    fun `is initializing until the first config state arrives`() = runBlocking {
        val client = client()
        assertThat(client.isInitializing).isFalse()

        client.initialize()

        assertThat(client.isInitializing).isFalse()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `stays initializing after a timeout while it keeps trying for its first config state`() = runBlocking {
        server.sendsConfigState = false
        val client = client(timeoutMillis = 300)

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isTrue()
    }

    @Test
    fun `stays initializing until values arrive after the timeout`() = runBlocking {
        server.responseDelayMillis = 500
        val client = client(timeoutMillis = 200)

        client.initialize()

        assertThat(client.isInitializing).isTrue()
        waitFor("the values") { client.isReady }
        assertThat(client.isInitializing).isFalse()
    }

    @Test
    fun `stays initializing until a poll after the timeout brings values`() = runBlocking {
        server.responseDelayMillis = 500
        val client = client(
            mode = ConnectionMode.POLLING,
            timeoutMillis = 200,
            transportFactory = fastPollingTransportFactory,
        )

        client.initialize()
        server.responseDelayMillis = 0

        assertThat(client.isInitializing).isTrue()
        waitFor("the values") { client.isReady }
        assertThat(client.isInitializing).isFalse()
    }

    @Test
    fun `stops initializing when the server rejects the request`() = runBlocking {
        server.status = 401
        val client = client()

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isFalse()
    }

    @Test
    fun `stops initializing when the server rejects the request when polling`() = runBlocking {
        server.status = 401
        val client = client(mode = ConnectionMode.POLLING)

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(client.isInitializing).isFalse()
    }

    @Test
    fun `stops initializing when the transport throws something other than a connection failure`() =
        runBlocking {
            val client = client(transportFactory = unexpectedlyFailingTransportFactory)

            client.initialize()

            assertThat(client.isInitializing).isFalse()
        }

    @Test
    fun `is not initializing during a second initialize once config state has arrived`() = runBlocking {
        val client = client()
        client.initialize()

        val second = launch(start = CoroutineStart.UNDISPATCHED) { client.initialize() }

        assertThat(client.isInitializing).isFalse()
        second.join()
        assertThat(client.isReady).isTrue()
    }

    @Test
    fun `is not initializing during a context update`() = runBlocking {
        val client = client()
        client.initialize()

        val updating = launch(start = CoroutineStart.UNDISPATCHED) { client.updateContext(proContext) }

        assertThat(client.isInitializing).isFalse()
        updating.join()
    }

    @Test
    fun `tells the transport what prompted each attempt`() = runBlocking {
        val client = client(transportFactory = reasonRecordingTransportFactory)

        client.initialize()
        client.updateContext(proContext)
        client.pauseNetwork()
        client.resumeNetwork()

        assertThat(connectReasons).containsExactly(
            ConnectReason.INITIALIZATION,
            ConnectReason.CONTEXT_UPDATE,
            ConnectReason.NETWORK_RESUME,
        ).inOrder()
    }

    @Test
    fun `takes the context it was initialized with`() = runBlocking {
        val client = client()
        assertThat(client.context).isNull()

        client.initialize(proContext)

        assertThat(client.context).isEqualTo(proContext)
    }

    @Test
    fun `connects without a context whose context kind is invalid at initialization`() = runBlocking<Unit> {
        val client = client()
        val contextUpdates = client.recordContextUpdates()

        client.initialize(contextWithAnInvalidContextKind)

        assertThat(sentPayload().getJSONObject("givenContext").toString()).isEqualTo(noContextJson)
        assertThat(client.context).isNull()
        assertThat(client.isReady).isTrue()
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, there")
        waitFor("the context update event") { contextUpdates.isNotEmpty() }
        assertThat(contextUpdates).containsExactly(ClientEvent.ContextUpdated(null))
        assertThat(errorsLogged("'Tenant'")).hasSize(1)
    }

    @Test
    fun `connects without a context whose context kind is invalid at initialization when polling`() =
        runBlocking<Unit> {
            val client = client(mode = ConnectionMode.POLLING)
            val contextUpdates = client.recordContextUpdates()

            client.initialize(contextWithAnInvalidContextKind)

            assertThat(sentPayload().getJSONObject("givenContext").toString()).isEqualTo(noContextJson)
            assertThat(client.context).isNull()
            assertThat(client.isReady).isTrue()
            assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, there")
            waitFor("the context update event") { contextUpdates.isNotEmpty() }
            assertThat(contextUpdates).containsExactly(ClientEvent.ContextUpdated(null))
            assertThat(errorsLogged("'Tenant'")).hasSize(1)
        }

    @Test
    fun `stops using the previous context after an update whose context kind is invalid`() = runBlocking<Unit> {
        val client = client()
        val contextUpdates = client.recordContextUpdates()
        client.initialize(proContext)
        checkNotNull(server.takeRequest())

        client.updateContext(contextWithAnInvalidContextKind)

        assertThat(sentPayload().getJSONObject("givenContext").toString()).isEqualTo(noContextJson)
        assertThat(client.context).isNull()
        assertThat(client.isReady).isTrue()
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, there")
        assertThat(client.evaluateString("welcome-message", "fallback").context).isNull()
        waitFor("both context update events") { contextUpdates.size == 2 }
        assertThat(contextUpdates)
            .containsExactly(ClientEvent.ContextUpdated(proContext), ClientEvent.ContextUpdated(null))
            .inOrder()
        assertThat(errorsLogged("'Tenant'")).hasSize(1)
    }

    @Test
    fun `stops using the previous context after an update whose context kind is invalid when polling`() =
        runBlocking<Unit> {
            val client = client(mode = ConnectionMode.POLLING)
            val contextUpdates = client.recordContextUpdates()
            client.initialize(proContext)
            checkNotNull(server.takeRequest())

            client.updateContext(contextWithAnInvalidContextKind)

            assertThat(sentPayload().getJSONObject("givenContext").toString()).isEqualTo(noContextJson)
            assertThat(client.context).isNull()
            assertThat(client.isReady).isTrue()
            assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, there")
            waitFor("both context update events") { contextUpdates.size == 2 }
            assertThat(contextUpdates)
                .containsExactly(ClientEvent.ContextUpdated(proContext), ClientEvent.ContextUpdated(null))
                .inOrder()
            assertThat(errorsLogged("'Tenant'")).hasSize(1)
        }

    @Test
    fun `resumes without the context it stopped using for an update whose context kind is invalid`() =
        runBlocking<Unit> {
            val client = client()
            client.initialize(proContext)
            client.updateContext(contextWithAnInvalidContextKind)
            client.pauseNetwork()

            client.resumeNetwork()

            val (initialization, update, resume) = sentContexts("/client/sse/v1", count = 3)
            assertThat(JSONObject(initialization).getString("id")).isEqualTo("user-123")
            assertThat(update).isEqualTo(noContextJson)
            assertThat(resume).isEqualTo(noContextJson)
            assertThat(client.context).isNull()
        }

    @Test
    fun `keeps the previous context when connecting without a context whose context kind is invalid fails`() =
        runBlocking<Unit> {
            val client = client()
            client.initialize(proContext)
            server.status = 401

            client.updateContext(contextWithAnInvalidContextKind)

            assertThat(client.context).isEqualTo(proContext)
            assertThat(errorsLogged("'Tenant'")).hasSize(1)
        }

    @Test
    fun `counts and logs one invalid context submission per initialization or update`() = runBlocking<Unit> {
        val client = client()

        client.initialize(contextWithAnInvalidContextKind)
        client.updateContext(ConfigDirectorContext.build { kind("Tenant") })
        client.updateContext(ConfigDirectorContext.build { kind("Device") })
        client.pauseNetwork()
        client.resumeNetwork()

        waitFor("the invalid context submissions to be reported") {
            invalidContextSubmissionCounts() == mapOf("Tenant" to 2, "Device" to 1)
        }
        settle()
        assertThat(invalidContextSubmissionCounts()).containsExactly("Tenant", 2, "Device", 1)
        assertThat(errorsLogged("'Tenant'")).hasSize(2)
        assertThat(errorsLogged("'Device'")).hasSize(1)
    }

    @Test
    fun `reconnects without the context it ignored and counts it only once`() = runBlocking<Unit> {
        val client = client(transportFactory = fastReconnectingTransportFactory)

        client.initialize(contextWithAnInvalidContextKind)

        assertThat(sentContexts("/client/sse/v1", count = 3))
            .containsExactly(noContextJson, noContextJson, noContextJson)
        waitFor("the invalid context submission to be reported") {
            invalidContextSubmissionCounts().isNotEmpty()
        }
        settle()
        assertThat(invalidContextSubmissionCounts()).containsExactly("Tenant", 1)
        assertThat(errorsLogged("'Tenant'")).hasSize(1)
    }

    @Test
    fun `polls without the context it ignored and counts it only once`() = runBlocking<Unit> {
        val client = fastPollingClient()

        client.initialize(contextWithAnInvalidContextKind)

        assertThat(sentContexts("/client/polling/v1", count = 3))
            .containsExactly(noContextJson, noContextJson, noContextJson)
        waitFor("the invalid context submission to be reported") {
            invalidContextSubmissionCounts().isNotEmpty()
        }
        settle()
        assertThat(invalidContextSubmissionCounts()).containsExactly("Tenant", 1)
        assertThat(errorsLogged("'Tenant'")).hasSize(1)
    }

    @Test
    fun `reports an invalid context kind cut to its first 128 characters`() = runBlocking<Unit> {
        val client = client()

        client.initialize(ConfigDirectorContext.build { kind("\uD83D\uDE00".repeat(129)) })

        waitFor("the invalid context submission to be reported") {
            invalidContextSubmissionCounts().isNotEmpty()
        }
        assertThat(invalidContextSubmissionCounts()).containsExactly("\uD83D\uDE00".repeat(128), 1)
    }

    @Test
    fun `never reports the identifier, name or traits of a context it ignored`() = runBlocking<Unit> {
        val client = client()

        client.initialize(
            ConfigDirectorContext.build {
                id("device-42")
                kind("Device")
                name("Lobby screen")
                trait("floor", "mezzanine")
            },
        )

        waitFor("the invalid context submission to be reported") {
            invalidContextSubmissionCounts().isNotEmpty()
        }
        val report = server.telemetryReports.single().toString()
        assertThat(report).doesNotContain("device-42")
        assertThat(report).doesNotContain("Lobby screen")
        assertThat(report).doesNotContain("mezzanine")
    }

    @Test
    fun `re-evaluates against an updated context`() = runBlocking {
        val client = client()
        client.initialize(ConfigDirectorContext.build { id("user-123") })
        assertThat(client.getInt("max-items", 0)).isEqualTo(10)

        client.updateContext(proContext)

        assertThat(client.context).isEqualTo(proContext)
        assertThat(client.isReady).isTrue()
        assertThat(client.getInt("max-items", 0)).isEqualTo(25)
    }

    @Test
    fun `merges a config set that carries only what changed`() = runBlocking {
        val client = fastPollingClient()
        client.initialize()
        assertThat(client.getBoolean("dark-mode", false)).isFalse()

        server.scriptDelta("dark-mode", "boolean", "true")

        waitFor("the delta to arrive") { client.getBoolean("dark-mode", false) }
        assertThat(client.getString("welcome-message", "fallback")).isEqualTo("Hello, there")
    }

    @Test
    fun `fetches again on the polling interval`() = runBlocking {
        fastPollingClient().initialize()

        waitFor("a second fetch") { server.requestCount >= 2 }
    }

    @Test
    fun `is not ready when the server rejects the request`() = runBlocking {
        server.status = 401
        val client = client()

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(logger.messagesContaining("An error occurred during initialization")).hasSize(1)
    }

    @Test
    fun `warns when no config state arrives in time`() = runBlocking {
        server.sendsConfigState = false
        val client = client(timeoutMillis = 300)

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(logger.messagesContaining("Timed out waiting for initialization")).hasSize(1)
    }

    @Test
    fun `becomes ready on its own once a connection that stayed silent past the timeout is reopened`() =
        runBlocking<Unit> {
            server.responseDelayMillis = 2_500
            val client = client(timeoutMillis = 200, transportFactory = shortStallTimeoutTransportFactory)
            val events = CopyOnWriteArrayList<ClientEvent>()
            client.addEventListener { events += it }

            client.initialize()
            server.responseDelayMillis = 0

            assertThat(client.isReady).isFalse()
            waitFor("the ready event") { ClientEvent.Ready(ConnectReason.INITIALIZATION) in events }
            assertThat(sentContexts("/client/sse/v1", count = 2)).hasSize(2)
        }

    @Test
    fun `takes the context it was initialized with once values arrive after the timeout`() = runBlocking<Unit> {
        server.responseDelayMillis = 500
        val client = client(timeoutMillis = 200)
        val events = CopyOnWriteArrayList<ClientEvent>()
        client.addEventListener { events += it }

        client.initialize(proContext)

        assertThat(client.context).isNull()
        waitFor("the context event") { ClientEvent.ContextUpdated(proContext) in events }
        assertThat(client.context).isEqualTo(proContext)
        assertThat(events.map { it::class }).containsExactly(
            ClientEvent.Ready::class,
            ClientEvent.ConfigsUpdated::class,
            ClientEvent.ContextUpdated::class,
        ).inOrder()
    }

    @Test
    fun `takes the context it was initialized with once a poll after the timeout brings values`() =
        runBlocking<Unit> {
            server.responseDelayMillis = 500
            val client = client(
                mode = ConnectionMode.POLLING,
                timeoutMillis = 200,
                transportFactory = fastPollingTransportFactory,
            )
            val contextUpdates = client.recordContextUpdates()

            client.initialize(proContext)
            server.responseDelayMillis = 0

            assertThat(client.context).isNull()
            waitFor("the context event") { ClientEvent.ContextUpdated(proContext) in contextUpdates }
            assertThat(client.context).isEqualTo(proContext)
        }

    @Test
    fun `keeps the previous context until the values of an update that timed out arrive`() = runBlocking<Unit> {
        val client = client(timeoutMillis = 200)
        client.initialize(freeContext)
        server.responseDelayMillis = 500
        val contextUpdates = client.recordContextUpdates()

        client.updateContext(proContext)

        assertThat(client.context).isEqualTo(freeContext)
        waitFor("the context event") { ClientEvent.ContextUpdated(proContext) in contextUpdates }
        assertThat(client.context).isEqualTo(proContext)
    }

    @Test
    fun `never takes the context of an attempt that a later one replaced`() = runBlocking<Unit> {
        server.responseDelayMillis = 500
        val client = client(timeoutMillis = 300)
        val contextUpdates = client.recordContextUpdates()
        val initializing = launch(Dispatchers.Default) { client.initialize(proContext) }
        waitFor("the initialize request") { server.requestCount == 1 }
        server.responseDelayMillis = 0

        client.updateContext(freeContext)
        initializing.join()

        assertThat(contextUpdates).containsExactly(ClientEvent.ContextUpdated(freeContext))
        assertThat(client.context).isEqualTo(freeContext)
    }

    @Test
    fun `keeps polling with the updated context when the initialize response lands after the update`() =
        runBlocking<Unit> {
            server.responseDelayMillis = 500
            val client = fastPollingClient()
            val initializing = launch(Dispatchers.Default) { client.initialize(proContext) }
            waitFor("the initialize request") { server.requestCount == 1 }
            server.responseDelayMillis = 0

            client.updateContext(freeContext)
            initializing.join()
            settle()

            assertThat(latestSentContextId("/client/polling/v1")).isEqualTo("user-456")
            assertThat(client.context).isEqualTo(freeContext)
            assertThat(client.getInt("max-items", 0)).isEqualTo(10)
        }

    @Test
    fun `keeps the previous context when the earlier connection delivers a change while a failing update connects`() =
        runBlocking<Unit> {
            val client = client(transportFactory = changeOnTheEarlierConnectionTransportFactory)
            client.initialize(proContext)
            server.status = 401

            client.updateContext(freeContext)

            assertThat(client.getBoolean("dark-mode", true)).isFalse()
            assertThat(client.context).isEqualTo(proContext)
        }

    @Test
    fun `never takes the context of a timed-out initialize whose connection delivers a change once an update began`() =
        runBlocking<Unit> {
            server.responseDelayMillis = 500
            val client = client(timeoutMillis = 200, transportFactory = changeOnTheEarlierConnectionTransportFactory)
            client.initialize(proContext)
            server.responseDelayMillis = 0
            server.status = 401

            client.updateContext(freeContext)

            assertThat(client.getBoolean("dark-mode", true)).isFalse()
            assertThat(client.context).isNull()
        }

    @Test
    fun `reports the configs it evaluated to the server`() = runBlocking {
        val client = client()
        client.initialize(proContext)
        client.getBoolean("dark-mode", false)
        client.getBoolean("dark-mode", false)

        // A context update reports what the context before it saw.
        client.updateContext(ConfigDirectorContext.build { id("user-456") })

        waitFor("the telemetry report") { server.telemetryReports.isNotEmpty() }
        val payload = server.telemetryReports.first()
        assertThat(payload.getString("clientSdkKey")).isEqualTo("client-sdk-key")
        assertThat(payload.getJSONObject("context").getString("id")).isEqualTo("user-123")

        val events = payload.getJSONObject("aggregatedEvents").getJSONArray("evaluatedConfig")
        val darkMode = (0 until events.length())
            .map { events.getJSONObject(it) }
            .single { it.getJSONObject("event").getString("key") == "dark-mode" }

        assertThat(darkMode.getInt("count")).isEqualTo(2)
        val event = darkMode.getJSONObject("event")
        assertThat(event.getString("contextId")).isEqualTo("user-123")
        assertThat(event.getString("requestedType")).isEqualTo("Boolean")
        assertThat(event.getString("evaluationReason")).isEqualTo("found-match")
        assertThat(event.getJSONObject("evaluatedValue").getString("value")).isEqualTo("true")
        assertThat(event.getJSONObject("defaultValue").getString("value")).isEqualTo("false")
    }

    @Test
    fun `reports what it evaluated when the client closes`() = runBlocking {
        val client = client()
        client.initialize(proContext)
        client.getInt("max-items", 0)

        client.close()

        waitFor("the telemetry report") { server.telemetryReports.isNotEmpty() }
        val events = server.telemetryReports.first()
            .getJSONObject("aggregatedEvents")
            .getJSONArray("evaluatedConfig")
        assertThat(events.getJSONObject(0).getJSONObject("event").getString("key"))
            .isEqualTo("max-items")
    }

    @Test
    fun `stops talking to the server once closed`() = runBlocking {
        val client = fastPollingClient()
        client.initialize()
        waitFor("a second fetch") { server.requestCount >= 2 }

        client.close()

        val fetched = server.requestCount
        Thread.sleep(300)
        assertThat(server.requestCount).isEqualTo(fetched)
        // A transport left running would go on failing against the client it can no longer use.
        assertThat(logger.messagesContaining("Error during polling")).isEmpty()
    }

    @Test
    fun `keeps serving the last config state once closed`() = runBlocking {
        val client = client()
        client.initialize(proContext)

        client.close()

        assertThat(client.isReady).isFalse()
        assertThat(client.getBoolean("dark-mode", false)).isTrue()
    }

    @Test
    fun `closes only once`() {
        val client = client()

        client.close()
        client.close()

        assertThat(logger.messagesContaining("close() called")).hasSize(1)
    }

    @Test
    fun `will not connect once closed`() = runBlocking {
        val client = client()
        client.close()

        client.initialize()

        assertThat(client.isReady).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(logger.messagesContaining("The client is closed")).hasSize(1)
    }
}

private class CountingMap(
    private val entriesByKey: Map<String, Any?>,
) : Map<String, Any?> by entriesByKey {
    var iterations = 0
        private set

    override val entries: Set<Map.Entry<String, Any?>>
        get() {
            iterations += 1
            return entriesByKey.entries
        }
}

private const val STORED_INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
