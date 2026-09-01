package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext
import com.configdirector.EvaluationReason
import com.configdirector.LogLevel
import com.configdirector.RecordingLogger
import com.configdirector.internal.ConfigType
import com.configdirector.internal.transport.SdkMetaContext
import com.configdirector.internal.transport.TransportOptions
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpEventReporterTest {

    private val server = MockWebServer()
    private val logger = RecordingLogger(LogLevel.DEBUG)

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun reporter(timeoutMillis: Long = 5_000) = HttpEventReporter(
        TransportOptions(
            clientSdkKey = "client-sdk-key",
            baseUrl = server.url("/").toString(),
            metaContext = SdkMetaContext("android-client-sdk", "0.1.0", "Sample", "1.0", "Android"),
            instanceId = "instance-1",
            logger = logger,
            pollingIntervalMillis = 60_000,
            httpClient = OkHttpClient(),
        ),
        timeoutMillis = timeoutMillis,
    )

    private fun request(): RecordedRequest =
        checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "The server received no request." }

    private val context = ConfigDirectorContext.build { id("user-123"); trait("plan", "pro") }

    private fun report(
        events: List<EvaluatedConfigEvent>,
        droppedCount: Int = 0,
        context: ConfigDirectorContext? = this.context,
    ) = EventReport(EventQueueSnapshot(1788134400123L, 1788134401123L, events, droppedCount), context)

    @Test
    fun `posts the key, the metadata, the context and what was collected`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        val outcome = reporter().report(report(listOf(evaluation("dark-mode"), evaluation("dark-mode"))))

        assertThat(outcome).isEqualTo(ReportOutcome.SUCCEEDED)
        val request = request()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/client/telemetry/v1")

        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("clientSdkKey")).isEqualTo("client-sdk-key")
        assertThat(body.getJSONObject("metaContext").getString("sdkName"))
            .isEqualTo("android-client-sdk")
        assertThat(body.getJSONObject("metaContext").has("appName")).isFalse()
        assertThat(body.getJSONObject("context").getString("id")).isEqualTo("user-123")
        assertThat(body.getJSONObject("droppedEvents").getInt("evaluatedConfig")).isEqualTo(0)

        val aggregated = body.getJSONObject("aggregatedEvents").getJSONArray("evaluatedConfig")
        assertThat(aggregated.length()).isEqualTo(1)
        val entry = aggregated.getJSONObject(0)
        assertThat(entry.getInt("count")).isEqualTo(2)
        assertThat(entry.getString("startTime")).isEqualTo("2026-08-31T00:00:00.123Z")
        assertThat(entry.getString("endTime")).isEqualTo("2026-08-31T00:00:01.123Z")

        val event = entry.getJSONObject("event")
        assertThat(event.getString("key")).isEqualTo("dark-mode")
        assertThat(event.getString("contextId")).isEqualTo("user-123")
        assertThat(event.getString("type")).isEqualTo("boolean")
        assertThat(event.getString("requestedType")).isEqualTo("Boolean")
        assertThat(event.getBoolean("usedDefault")).isFalse()
        assertThat(event.getString("evaluationReason")).isEqualTo("found-match")
        assertThat(event.getString("evaluatedValueId")).isEqualTo("v1")
        assertThat(event.getJSONObject("evaluatedValue").getString("value")).isEqualTo("true")
        assertThat(event.getJSONObject("defaultValue").getString("value")).isEqualTo("false")
    }

    @Test
    fun `reports a value too large to send by its id`() = runBlocking<Unit> {
        server.enqueue(MockResponse())
        val long = "a".repeat(TelemetryValue.MAX_VALUE_LENGTH + 1)

        reporter().report(report(listOf(evaluation("dark-mode", evaluated = long, valueId = null))))

        val event = JSONObject(request().body.readUtf8())
            .getJSONObject("aggregatedEvents")
            .getJSONArray("evaluatedConfig")
            .getJSONObject(0)
            .getJSONObject("event")
            .getJSONObject("evaluatedValue")
        assertThat(event.has("value")).isFalse()
        assertThat(event.getString("valueId")).isEqualTo(ValueIds.generate(long))
    }

    @Test
    fun `says how many events were dropped`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        reporter().report(report(listOf(evaluation("dark-mode")), droppedCount = 7))

        val body = JSONObject(request().body.readUtf8())
        assertThat(body.getJSONObject("droppedEvents").getInt("evaluatedConfig")).isEqualTo(7)
    }

    @Test
    fun `sends nothing when nothing was collected`() = runBlocking<Unit> {
        val outcome = reporter().report(report(emptyList()))

        assertThat(outcome).isEqualTo(ReportOutcome.SUCCEEDED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `leaves the context off when there is none`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        reporter().report(report(listOf(evaluation("dark-mode")), context = null))

        assertThat(JSONObject(request().body.readUtf8()).has("context")).isFalse()
    }

    @Test
    fun `stops reporting after a status retrying cannot fix`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        val reporter = reporter()

        val first = reporter.report(report(listOf(evaluation("dark-mode"))))
        val second = reporter.report(report(listOf(evaluation("dark-mode"))))

        assertThat(first).isEqualTo(ReportOutcome.FAILED_FATALLY)
        assertThat(second).isEqualTo(ReportOutcome.FAILED_FATALLY)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(logger.messagesContaining("No more telemetry data will be sent")).hasSize(1)
    }

    @Test
    fun `keeps reporting after a status worth retrying`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse())
        val reporter = reporter()

        val first = reporter.report(report(listOf(evaluation("dark-mode"))))
        val second = reporter.report(report(listOf(evaluation("dark-mode"))))

        assertThat(first).isEqualTo(ReportOutcome.FAILED)
        assertThat(second).isEqualTo(ReportOutcome.SUCCEEDED)
    }

    @Test
    fun `gives up on a report the server does not answer in time`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS))

        val outcome = reporter(timeoutMillis = 200).report(report(listOf(evaluation("dark-mode"))))

        assertThat(outcome).isEqualTo(ReportOutcome.FAILED)
        assertThat(logger.messagesContaining("Timed out after 200ms")).hasSize(1)
    }

    private fun evaluation(
        key: String,
        evaluated: String = "true",
        valueId: String? = "v1",
    ) = EvaluatedConfigEvent(
        contextId = "user-123",
        key = key,
        type = ConfigType.BOOLEAN,
        defaultValue = TelemetryValue(value = "false", type = ConfigType.BOOLEAN),
        requestedType = "Boolean",
        evaluatedValue = TelemetryValue(value = evaluated, valueId = valueId, type = ConfigType.BOOLEAN),
        evaluatedValueId = valueId,
        usedDefault = false,
        evaluationReason = EvaluationReason.FOUND_MATCH,
    )
}
