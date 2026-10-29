package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConnectReason
import com.configdirector.internal.ConfigSet
import com.configdirector.warn
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException

/**
 * A [Transport] that fetches config state on connect and then re-fetches it on a fixed interval.
 */
internal class PollingTransport(private val options: TransportOptions) : Transport {

    private val url = options.endpoint(PATH)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = Any()
    private var connectionGeneration = 0L
    private var polling: Job? = null
    private val lastUpdateTimestamp = AtomicReference<String?>(null)
    private val hasFatalError = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override suspend fun connect(
        context: ConfigDirectorContext,
        timeoutMillis: Long,
        reason: ConnectReason,
        onConfigSet: (ConfigSet) -> Unit,
    ) {
        if (closed.get()) return

        if (hasFatalError.get()) {
            options.logger.warn {
                "[PollingTransport] There was a prior unrecoverable error. Ignoring attempt to reconnect."
            }
            return
        }

        val generation = endCurrentConnection()

        try {
            fetch(context, timeoutMillis, generation, onConfigSet)
        } finally {
            // A transient failure on the first fetch must not leave the client without a
            // connection: polling starts regardless of how that fetch went.
            if (currentCoroutineContext().isActive) {
                schedulePolling(context, timeoutMillis, generation, onConfigSet)
            }
        }
    }

    override fun disconnect() {
        endCurrentConnection()
    }

    override fun close() {
        closed.set(true)
        disconnect()
        scope.cancel()
    }

    private fun endCurrentConnection(): Long {
        val (generation, pollingToCancel) = synchronized(connectionLock) {
            connectionGeneration += 1
            val pollingToCancel = polling
            polling = null
            connectionGeneration to pollingToCancel
        }
        pollingToCancel?.cancel()
        return generation
    }

    private fun isCurrentConnectionLocked(generation: Long): Boolean = connectionGeneration == generation

    private fun schedulePolling(
        context: ConfigDirectorContext,
        timeoutMillis: Long,
        generation: Long,
        onConfigSet: (ConfigSet) -> Unit,
    ) {
        if (hasFatalError.get() || closed.get()) return

        val job = scope.launch {
            while (isActive) {
                delay(options.pollingIntervalMillis)
                try {
                    fetch(context, timeoutMillis, generation, onConfigSet)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    options.logger.warn(failure) { "[PollingTransport] Error during polling" }
                }
            }
        }
        val pollingToCancel = synchronized(connectionLock) {
            if (isCurrentConnectionLocked(generation)) {
                val previous = polling
                polling = job
                previous
            } else {
                job
            }
        }
        pollingToCancel?.cancel()
    }

    private suspend fun fetch(
        context: ConfigDirectorContext,
        timeoutMillis: Long,
        generation: Long,
        onConfigSet: (ConfigSet) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            // A cached config set would serve stale values after a change in the dashboard.
            .header("Cache-Control", "no-store")
            .post(options.payload(context, lastUpdateTimestamp.get()).toRequestBody(JSON))
            .build()

        val response = try {
            withTimeout(timeoutMillis) { options.httpClient.newCall(request).await() }
        } catch (timeout: TimeoutCancellationException) {
            throw ConnectionFailedException(
                "Connection timed out after ${timeoutMillis}ms.",
                isUnrecoverable = false,
            )
        } catch (failure: IOException) {
            throw networkFailure(failure)
        }

        response.use {
            val body = try {
                it.body?.string().orEmpty()
            } catch (failure: IOException) {
                throw networkFailure(failure)
            }
            throwOnErrorStatus(it.code, body)

            if (it.code != HTTP_OK) return

            dispatch(body, generation, onConfigSet)
        }
    }

    private fun networkFailure(failure: IOException) = ConnectionFailedException(
        "Connection failed with error: $failure.",
        isUnrecoverable = false,
        cause = failure,
    )

    private fun throwOnErrorStatus(status: Int, body: String) {
        if (status in 200..299) return

        if (!status.isFatalHttpStatus()) {
            throw ConnectionFailedException(
                "Connection failed with status: $status",
                isUnrecoverable = false,
                statusCode = status,
            )
        }

        hasFatalError.set(true)
        disconnect()

        val text = body.trim()
        val detail = if (text.isEmpty()) "" else " ($text)"
        throw ConnectionFailedException(
            "Connection failed with status: $status$detail. This is an unrecoverable error, retry " +
                "attempts will be ignored.",
            isUnrecoverable = true,
            statusCode = status,
        )
    }

    private fun dispatch(body: String, generation: Long, onConfigSet: (ConfigSet) -> Unit) {
        val configSet = try {
            parseConfigSet(body)
        } catch (malformed: JSONException) {
            throw ConnectionFailedException(
                "Failed to parse the response from the server: $malformed",
                isUnrecoverable = false,
                cause = malformed,
            )
        }

        synchronized(connectionLock) {
            if (!isCurrentConnectionLocked(generation)) return

            lastUpdateTimestamp.set(configSet.timestamp)
            onConfigSet(configSet)
        }
    }

    companion object {
        private const val PATH = "client/polling/v1"
        private const val HTTP_OK = 200
        private val JSON = "application/json".toMediaType()
    }
}
