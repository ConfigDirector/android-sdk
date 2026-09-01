package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext
import com.configdirector.internal.JsonValues
import com.configdirector.internal.transport.TransportOptions
import com.configdirector.internal.transport.await
import com.configdirector.internal.transport.isFatalHttpStatus
import com.configdirector.warn
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class EventReport(
    val snapshot: EventQueueSnapshot,
    val context: ConfigDirectorContext?,
)

internal enum class ReportOutcome {
    SUCCEEDED,
    FAILED,

    /** The server rejected the report in a way retrying cannot fix, so nothing more should be sent. */
    FAILED_FATALLY,
}

/** Turns collected events into a report the ConfigDirector server accepts, and sends it. */
internal interface EventReporter {
    suspend fun report(report: EventReport): ReportOutcome

    fun close()
}

/**
 * The default [EventReporter].
 *
 * Everything expensive -- hashing large values, aggregating, encoding, and the request itself --
 * happens here rather than where events are collected, so none of it runs on the caller's thread.
 */
internal class HttpEventReporter(
    private val options: TransportOptions,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : EventReporter {

    private val url = options.endpoint(PATH)
    private val stopped = AtomicBoolean(false)

    override suspend fun report(report: EventReport): ReportOutcome {
        if (stopped.get()) return ReportOutcome.FAILED_FATALLY

        val aggregated = EventQueueSnapshot(
            startTime = report.snapshot.startTime,
            endTime = report.snapshot.endTime,
            events = report.snapshot.events.map { it.compacted() },
            droppedCount = report.snapshot.droppedCount,
        ).aggregated()

        if (aggregated.isEmpty() && report.snapshot.droppedCount == 0) return ReportOutcome.SUCCEEDED

        val outcome = send(payload(aggregated, report))
        if (outcome == ReportOutcome.FAILED_FATALLY) {
            stopped.set(true)
        }
        return outcome
    }

    override fun close() {
        stopped.set(true)
    }

    private fun payload(aggregated: List<AggregatedEvent>, report: EventReport): String {
        val events = JSONArray().apply { aggregated.forEach { put(it.toJson()) } }

        return JSONObject()
            .put("clientSdkKey", options.clientSdkKey)
            .put(
                "metaContext",
                JSONObject()
                    .put("sdkName", options.metaContext.sdkName)
                    .put("sdkVersion", options.metaContext.sdkVersion),
            )
            .apply { report.context?.let { put("context", JsonValues.contextJson(it)) } }
            .put("discreteEvents", JSONObject())
            .put("aggregatedEvents", JSONObject().put("evaluatedConfig", events))
            .put(
                "droppedEvents",
                JSONObject().put("evaluatedConfig", report.snapshot.droppedCount),
            )
            .toString()
    }

    private suspend fun send(body: String): ReportOutcome {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .build()

        val response = try {
            withTimeout(timeoutMillis) { options.httpClient.newCall(request).await() }
        } catch (timeout: TimeoutCancellationException) {
            options.logger.warn {
                "[EventReporter] Timed out after ${timeoutMillis}ms sending telemetry data."
            }
            return ReportOutcome.FAILED
        } catch (failure: IOException) {
            options.logger.warn(failure) { "[EventReporter] Error attempting to send telemetry data" }
            return ReportOutcome.FAILED
        }

        val status = response.use { it.code }

        if (status.isFatalHttpStatus()) {
            options.logger.warn {
                "[EventReporter] Received a fatal status response ($status) from the telemetry " +
                    "endpoint. No more telemetry data will be sent."
            }
            return ReportOutcome.FAILED_FATALLY
        }

        return if (status in 200..299) ReportOutcome.SUCCEEDED else ReportOutcome.FAILED
    }

    private companion object {
        private const val PATH = "client/telemetry/v1"
        private const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        private val JSON = "application/json".toMediaType()
    }
}
