package com.configdirector.internal.transport

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Awaits the call, cancelling it if the coroutine waiting on it is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }

    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.closeQuietly() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }
    })
}

private fun Response.closeQuietly() {
    try {
        close()
    } catch (ignored: RuntimeException) {
        throw ignored
    } catch (ignored: Exception) {
        // Nothing to do: the caller is gone and this response is being dropped.
    }
}
