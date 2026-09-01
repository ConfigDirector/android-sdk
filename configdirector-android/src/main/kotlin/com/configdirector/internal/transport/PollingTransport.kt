package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorContext
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
 *
 * A null polling interval disables the interval, which is how the one-time transport fetches config
 * state on connect only.
 */
internal class PollingTransport(
    private val options: TransportOptions,
    private val pollingIntervalMillis: Long? = options.pollingIntervalMillis,
    private val onConfigSet: (ConfigSet) -> Unit,
) : Transport {

    private val url = options.endpoint(PATH)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val polling = AtomicReference<Job?>(null)
    private val lastUpdateTimestamp = AtomicReference<String?>(null)
    private val hasFatalError = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override suspend fun connect(context: ConfigDirectorContext, timeoutMillis: Long) {
        if (closed.get()) return

        if (hasFatalError.get()) {
            options.logger.warn {
                "[PollingTransport] There was a prior unrecoverable error. Ignoring attempt to reconnect."
            }
            return
        }

        disconnect()

        try {
            fetch(context, timeoutMillis)
        } finally {
            // A transient failure on the first fetch must not leave the client without a
            // connection: polling starts regardless of how that fetch went.
            if (currentCoroutineContext().isActive) {
                schedulePolling(context, timeoutMillis)
            }
        }
    }

    override fun disconnect() {
        polling.getAndSet(null)?.cancel()
    }

    override fun close() {
        closed.set(true)
        disconnect()
        scope.cancel()
    }

    private fun schedulePolling(context: ConfigDirectorContext, timeoutMillis: Long) {
        val interval = pollingIntervalMillis ?: return
        if (interval <= 0 || hasFatalError.get() || closed.get()) return

        val job = scope.launch {
            while (isActive) {
                delay(interval)
                try {
                    fetch(context, timeoutMillis)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    options.logger.warn(failure) { "[PollingTransport] Error during polling" }
                }
            }
        }
        polling.getAndSet(job)?.cancel()
    }

    private suspend fun fetch(context: ConfigDirectorContext, timeoutMillis: Long) {
        val request = Request.Builder()
            .url(url)
            // A cached config set would serve stale values after a change in the dashboard.
            .header("Cache-Control", "no-store")
            .post(options.payload(context, lastUpdateTimestamp.get()).toRequestBody(JSON))
            .build()

        val response = try {
            withTimeout(timeoutMillis) { options.httpClient.newCall(request).await() }
        } catch (timeout: TimeoutCancellationException) {
            throw ConnectionFailedException("Connection timed out after ${timeoutMillis}ms.")
        } catch (failure: IOException) {
            throw ConnectionFailedException("Connection failed with error: $failure.", cause = failure)
        }

        response.use {
            val body = it.body?.string().orEmpty()
            throwOnErrorStatus(it.code, body)

            if (it.code != HTTP_OK) return

            dispatch(body)
        }
    }

    private fun throwOnErrorStatus(status: Int, body: String) {
        if (status in 200..299) return

        if (!status.isFatalHttpStatus()) {
            throw ConnectionFailedException("Connection failed with status: $status", status)
        }

        hasFatalError.set(true)
        disconnect()

        val text = body.trim()
        val detail = if (text.isEmpty()) "" else " ($text)"
        throw ConnectionFailedException(
            "Connection failed with status: $status$detail. This is an unrecoverable error, retry " +
                "attempts will be ignored.",
            status,
        )
    }

    private fun dispatch(body: String) {
        val configSet = try {
            parseConfigSet(body)
        } catch (malformed: JSONException) {
            throw ConnectionFailedException(
                "Failed to parse the response from the server: $malformed",
                cause = malformed,
            )
        }

        lastUpdateTimestamp.set(configSet.timestamp)
        onConfigSet(configSet)
    }

    companion object {
        private const val PATH = "client/polling/v1"
        private const val HTTP_OK = 200
        private val JSON = "application/json".toMediaType()

        /** A transport that fetches config state on connect only, never polling afterwards. */
        fun oneTime(options: TransportOptions, onConfigSet: (ConfigSet) -> Unit) =
            PollingTransport(options, pollingIntervalMillis = null, onConfigSet = onConfigSet)
    }
}
