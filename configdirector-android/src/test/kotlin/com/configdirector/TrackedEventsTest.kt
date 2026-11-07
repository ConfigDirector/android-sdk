package com.configdirector

import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackedEventsTest {

    private val server = FakeSdkServer()
    private val logger = RecordingLogger()
    private val clients = CopyOnWriteArrayList<ConfigDirectorClient>()
    private val user = ConfigDirectorContext.build { id("user-123") }

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

    private fun client(): ConfigDirectorClient = ConfigDirectorClient(
        RuntimeEnvironment.getApplication(),
        "client-sdk-key",
        ClientOptions.build {
            logger(logger)
            connection { baseUrl(server.baseUrl) }
        },
    ).also { clients += it }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun reportBodyHoldingTrackedEvents(): String {
        waitFor("a report holding tracked events") {
            server.telemetryReportBodies.any { "\"trackedEvents\":[{" in it }
        }
        return server.telemetryReportBodies.first { "\"trackedEvents\":[{" in it }
    }

    private fun reportedTrackedEvents(): List<JSONObject> {
        val entries = JSONObject(reportBodyHoldingTrackedEvents())
            .getJSONObject("discreteEvents")
            .getJSONArray("trackedEvents")
        return (0 until entries.length()).map { entries.getJSONObject(it) }
    }

    @Test
    fun `puts an event on the next report with the context's identifier, the value and the details`() =
        runBlocking<Unit> {
            val client = client()
            client.initialize(user)

            client.track("checkout-completed", TrackOptions(value = 49.9, details = mapOf("plan" to "pro")))
            client.close()

            assertThat(reportBodyHoldingTrackedEvents()).contains(
                """{"name":"checkout-completed","contextKind":"user","contextIdentifier":"user-123",""" +
                    """"value":49.9,"details":{"plan":"pro"},"happenedAt":""",
            )
        }

    @Test
    fun `sends an event tracked without options with its name, context and time alone`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.track("checkout-completed")
        client.close()

        val entry = reportedTrackedEvents().single()
        assertThat(entry.keys().asSequence().toSet())
            .containsExactly("name", "contextKind", "contextIdentifier", "happenedAt")
    }

    @Test
    fun `stamps an event when it is tracked, not when the report goes out`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)
        val before = System.currentTimeMillis()

        client.track("checkout-completed")
        val trackedBy = System.currentTimeMillis()
        Thread.sleep(50)
        client.close()

        val happenedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(reportedTrackedEvents().single().getString("happenedAt"))!!
            .time
        assertThat(happenedAt).isAtLeast(before)
        assertThat(happenedAt).isAtMost(trackedBy)
    }

    @Test
    fun `sends an event tracked before the client has a context without one, logging nothing`() = runBlocking<Unit> {
        val client = client()

        client.track("app-opened")
        client.close()

        val entry = reportedTrackedEvents().single()
        assertThat(entry.getString("name")).isEqualTo("app-opened")
        assertThat(entry.has("contextIdentifier")).isFalse()
        assertThat(entry.has("contextKind")).isFalse()
        assertThat(logger.messagesContaining("tracked event")).isEmpty()
    }

    @Test
    fun `sends an event for a context without an id without one, logging nothing`() = runBlocking<Unit> {
        val client = client()
        client.initialize(ConfigDirectorContext.build { name("Ada") })

        client.track("checkout-completed")
        client.close()

        val entry = reportedTrackedEvents().single()
        assertThat(entry.has("contextIdentifier")).isFalse()
        assertThat(entry.has("contextKind")).isFalse()
        assertThat(logger.messagesContaining("tracked event")).isEmpty()
    }

    @Test
    fun `withholds the id of a context marked anonymous`() = runBlocking<Unit> {
        val client = client()
        client.initialize(ConfigDirectorContext.build { id("user-123"); anonymous(true) })

        client.track("checkout-completed")
        client.close()

        val entry = reportedTrackedEvents().single()
        assertThat(entry.has("contextIdentifier")).isFalse()
        assertThat(entry.has("contextKind")).isFalse()
    }

    @Test
    fun `sends the kind of a context that has one`() = runBlocking<Unit> {
        val client = client()
        client.initialize(ConfigDirectorContext.build { id("device-9"); kind("device") })

        client.track("checkout-completed")
        client.close()

        val entry = reportedTrackedEvents().single()
        assertThat(entry.getString("contextKind")).isEqualTo("device")
        assertThat(entry.getString("contextIdentifier")).isEqualTo("device-9")
    }

    @Test
    fun `sends an event without a context after a context with an invalid kind was ignored`() = runBlocking<Unit> {
        val client = client()
        client.initialize(ConfigDirectorContext.build { id("user-123"); kind("Not Valid") })

        client.track("checkout-completed")
        client.close()

        val entry = reportedTrackedEvents().single()
        assertThat(entry.has("contextIdentifier")).isFalse()
        assertThat(entry.has("contextKind")).isFalse()
    }

    @Test
    fun `tracks for the context in use until a context update takes effect`() = runBlocking<Unit> {
        val userB = ConfigDirectorContext.build { id("user-b") }
        val client = client()
        client.initialize(user)
        server.responseDelayMillis = 300
        val update = launch(Dispatchers.Default) { client.updateContext(userB) }
        waitFor("the update's request") { server.requestCount >= 2 }

        client.track("while-updating")
        update.join()
        waitFor("the report of the event tracked while updating") {
            server.telemetryReportBodies.any { "while-updating" in it }
        }
        client.track("after-updating")
        client.close()

        waitFor("the report of the event tracked after updating") {
            server.telemetryReportBodies.any { "after-updating" in it }
        }
        val contextIdentifiers = server.telemetryReports.flatMap { report ->
            val entries = report.getJSONObject("discreteEvents").getJSONArray("trackedEvents")
            (0 until entries.length()).map { entries.getJSONObject(it) }
        }.associate { it.getString("name") to it.getString("contextIdentifier") }
        assertThat(contextIdentifiers).containsExactly("while-updating", "user-123", "after-updating", "user-b")
    }

    @Test
    fun `drops the 1,001st event of a report with one warning`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        repeat(1_002) { client.track("event-$it") }
        client.close()

        val entries = reportedTrackedEvents()
        assertThat(entries).hasSize(1_000)
        assertThat(entries.last().getString("name")).isEqualTo("event-999")
        assertThat(logger.messagesContaining("already waiting for the next telemetry batch")).hasSize(1)
    }

    @Test
    fun `drops an event whose name is too long with a warning`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.track("n".repeat(151))
        client.track("checkout-completed")
        client.close()

        assertThat(reportedTrackedEvents().single().getString("name")).isEqualTo("checkout-completed")
        assertThat(logger.messagesContaining("WARN: [ConfigDirectorClient] A tracked event was not reported")).hasSize(1)
    }

    @Test
    fun `sends an event without details larger than 1 KB, with a warning`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.track("checkout-completed", TrackOptions(value = 1, details = mapOf("k" to "a".repeat(1_017))))
        client.close()

        val entry = reportedTrackedEvents().single()
        assertThat(entry.has("details")).isFalse()
        assertThat(entry.getInt("value")).isEqualTo(1)
        assertThat(logger.messagesContaining("WARN: [ConfigDirectorClient] A tracked event was reported without its details"))
            .hasSize(1)
    }

    @Test
    fun `sends an event without a value that is not a finite number, with a warning`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.track("checkout-completed", TrackOptions(value = Double.NaN))
        client.close()

        assertThat(reportedTrackedEvents().single().has("value")).isFalse()
        assertThat(logger.messagesContaining("WARN: [ConfigDirectorClient] A tracked event was reported without its value"))
            .hasSize(1)
    }

    @Test
    fun `sends every digit of a BigDecimal that Android's own JSON would round`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.track("checkout-completed", TrackOptions(value = BigDecimal("1.00000000000000000001")))
        client.close()

        assertThat(reportBodyHoldingTrackedEvents()).contains(""""value":1.00000000000000000001,""")
    }

    @Test
    fun `sends slashes in details as the bytes the limit measured`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.track("checkout-completed", TrackOptions(details = mapOf("url" to "https://example.com/é")))
        client.close()

        assertThat(reportBodyHoldingTrackedEvents()).contains(""""details":{"url":"https://example.com/é"},""")
    }

    @Test
    fun `carries a tracked event and the evaluation counts in one report`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.getBoolean("dark-mode", false)
        client.track("checkout-completed")
        client.close()

        val report = JSONObject(reportBodyHoldingTrackedEvents())
        assertThat(report.getJSONObject("aggregatedEvents").getJSONArray("evaluatedConfig").length()).isEqualTo(1)
        assertThat(report.getString("clientSdkKey")).isEqualTo("client-sdk-key")
    }

    @Test
    fun `carries a tracked event and an invalid context kind in one report`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)

        client.track("checkout-completed")
        client.updateContext(ConfigDirectorContext.build { id("user-123"); kind("Not Valid") })

        val report = JSONObject(reportBodyHoldingTrackedEvents())
        assertThat(report.getJSONObject("aggregatedEvents").getJSONArray("invalidContext").length()).isEqualTo(1)
    }

    @Test
    fun `does nothing once closed`() = runBlocking<Unit> {
        val client = client()
        client.initialize(user)
        client.track("before-closing")
        client.close()
        reportBodyHoldingTrackedEvents()

        client.track("after-closing", TrackOptions(value = Double.NaN))
        Thread.sleep(100)

        assertThat(server.telemetryReportBodies.none { "after-closing" in it }).isTrue()
        assertThat(logger.messagesContaining("tracked event")).isEmpty()
    }
}
