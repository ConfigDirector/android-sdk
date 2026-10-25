package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext
import com.configdirector.EvaluationReason
import com.configdirector.LogLevel
import com.configdirector.RecordingLogger
import com.configdirector.internal.ClientSession
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

    private fun reporter(
        timeoutMillis: Long = 5_000,
        clientSession: ClientSession = ClientSession("instance-1", ephemeral = false),
    ) = HttpEventReporter(
        TransportOptions(
            clientSdkKey = "client-sdk-key",
            baseUrl = server.url("/").toString(),
            metaContext = SdkMetaContext("android-client-sdk", "9.9.9", "Sample", "1.0", "Android"),
            clientSession = clientSession,
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
        invalidContextSubmissionSummaries: List<InvalidContextSubmissionSummary> = emptyList(),
        context: ConfigDirectorContext? = this.context,
    ) = EventReport(
        EventQueueSnapshot(1788134400123L, 1788134401123L, events, droppedCount),
        invalidContextSubmissionSummaries,
        context,
    )

    @Test
    fun `posts the key, the instance ID, the metadata, the context and what was collected`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        val outcome = reporter().report(report(listOf(evaluation("dark-mode"), evaluation("dark-mode"))))

        assertThat(outcome).isEqualTo(ReportOutcome.SUCCEEDED)
        val request = request()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/client/telemetry/v1")

        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("clientSdkKey")).isEqualTo("client-sdk-key")
        assertThat(body.getString("instanceId")).isEqualTo("instance-1")
        assertThat(body.has("ephemeral")).isFalse()
        assertThat(body.getJSONObject("metaContext").getString("sdkName"))
            .isEqualTo("android-client-sdk")
        assertThat(body.getJSONObject("metaContext").getString("sdkVersion")).isEqualTo("9.9.9")
        assertThat(body.getJSONObject("metaContext").getString("appName")).isEqualTo("Sample")
        assertThat(body.getJSONObject("metaContext").getString("appVersion")).isEqualTo("1.0")
        assertThat(body.getJSONObject("metaContext").getString("userAgent")).isEqualTo("Android")
        assertThat(body.getJSONObject("context").getString("id")).isEqualTo("user-123")
        assertThat(body.getJSONObject("droppedEvents").getInt("evaluatedConfig")).isEqualTo(0)

        val aggregatedEvaluations = body.getJSONObject("aggregatedEvents").getJSONArray("evaluatedConfig")
        assertThat(aggregatedEvaluations.length()).isEqualTo(1)
        val aggregatedEvaluation = aggregatedEvaluations.getJSONObject(0)
        assertThat(aggregatedEvaluation.getInt("count")).isEqualTo(2)
        assertThat(aggregatedEvaluation.getString("startTime")).isEqualTo("2026-08-31T00:00:00.123Z")
        assertThat(aggregatedEvaluation.getString("endTime")).isEqualTo("2026-08-31T00:00:01.123Z")

        val event = aggregatedEvaluation.getJSONObject("event")
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
    fun `leaves out that an instance ID which is not storage-backed is ephemeral`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        reporter(clientSession = ClientSession("instance-1", ephemeral = true))
            .report(report(listOf(evaluation("dark-mode"))))

        val body = JSONObject(request().body.readUtf8())
        assertThat(body.getString("instanceId")).isEqualTo("instance-1")
        assertThat(body.has("ephemeral")).isFalse()
    }

    @Test
    fun `reports a value too large to send by its id`() = runBlocking<Unit> {
        server.enqueue(MockResponse())
        val valueTooLargeToSend = "a".repeat(TelemetryValue.MAX_VALUE_LENGTH + 1)

        reporter().report(
            report(listOf(evaluation("dark-mode", evaluatedValue = valueTooLargeToSend, valueId = null))),
        )

        val evaluatedValue = JSONObject(request().body.readUtf8())
            .getJSONObject("aggregatedEvents")
            .getJSONArray("evaluatedConfig")
            .getJSONObject(0)
            .getJSONObject("event")
            .getJSONObject("evaluatedValue")
        assertThat(evaluatedValue.has("value")).isFalse()
        assertThat(evaluatedValue.getString("valueId")).isEqualTo(valueIdFor(valueTooLargeToSend))
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
    fun `posts the context kind with the context`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        reporter().report(
            report(listOf(evaluation("dark-mode")), context = ConfigDirectorContext.build { id("42"); kind("device") }),
        )

        val context = JSONObject(request().body.readUtf8()).getJSONObject("context")
        assertThat(context.getString("id")).isEqualTo("42")
        assertThat(context.getString("kind")).isEqualTo("device")
    }

    @Test
    fun `posts the invalid context submissions it was given`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        reporter().report(
            report(
                listOf(evaluation("dark-mode")),
                invalidContextSubmissionSummaries = listOf(
                    InvalidContextSubmissionSummary(1788134400123L, 1788134401123L, count = 3, contextKindSample = "Tenant"),
                    InvalidContextSubmissionSummary(1788134400123L, 1788134401123L, count = 2, contextKindSample = null),
                ),
            ),
        )

        val submissions = JSONObject(request().body.readUtf8())
            .getJSONObject("aggregatedEvents")
            .getJSONArray("invalidContext")
        assertThat(submissions.length()).isEqualTo(2)

        val tenantSubmissions = submissions.getJSONObject(0)
        assertThat(tenantSubmissions.getString("startTime")).isEqualTo("2026-08-31T00:00:00.123Z")
        assertThat(tenantSubmissions.getString("endTime")).isEqualTo("2026-08-31T00:00:01.123Z")
        assertThat(tenantSubmissions.getInt("count")).isEqualTo(3)
        assertThat(tenantSubmissions.getJSONObject("event").getString("reason")).isEqualTo("invalid-context-kind")
        assertThat(tenantSubmissions.getJSONObject("event").getString("kindSample")).isEqualTo("Tenant")

        val submissionsBeyondSampleLimit = submissions.getJSONObject(1)
        assertThat(submissionsBeyondSampleLimit.getInt("count")).isEqualTo(2)
        assertThat(submissionsBeyondSampleLimit.getJSONObject("event").getString("reason"))
            .isEqualTo("invalid-context-kind")
        assertThat(submissionsBeyondSampleLimit.getJSONObject("event").has("kindSample")).isFalse()
    }

    @Test
    fun `posts invalid context submissions when nothing was evaluated`() = runBlocking<Unit> {
        server.enqueue(MockResponse())

        val outcome = reporter().report(
            report(
                emptyList(),
                invalidContextSubmissionSummaries = listOf(
                    InvalidContextSubmissionSummary(1788134400123L, 1788134401123L, count = 1, contextKindSample = "Tenant"),
                ),
            ),
        )

        assertThat(outcome).isEqualTo(ReportOutcome.SUCCEEDED)
        val aggregatedEvents = JSONObject(request().body.readUtf8()).getJSONObject("aggregatedEvents")
        assertThat(aggregatedEvents.getJSONArray("evaluatedConfig").length()).isEqualTo(0)
        assertThat(aggregatedEvents.getJSONArray("invalidContext").getJSONObject(0).getInt("count")).isEqualTo(1)
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
    fun `keeps reporting after being rate limited`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429))
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
        evaluatedValue: String = "true",
        valueId: String? = "v1",
    ) = EvaluatedConfigEvent(
        contextId = "user-123",
        key = key,
        type = ConfigType.BOOLEAN,
        defaultValue = TelemetryValue(value = "false", type = ConfigType.BOOLEAN),
        requestedType = "Boolean",
        evaluatedValue = TelemetryValue(value = evaluatedValue, valueId = valueId, type = ConfigType.BOOLEAN),
        evaluatedValueId = valueId,
        usedDefault = false,
        evaluationReason = EvaluationReason.FOUND_MATCH,
    )
}
