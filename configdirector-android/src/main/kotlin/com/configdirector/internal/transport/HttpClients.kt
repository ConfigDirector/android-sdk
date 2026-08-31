package com.configdirector.internal.transport

import com.configdirector.ConfigDirectorLogger
import com.configdirector.error
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** The HTTP client every transport shares, built from the caller's connection settings. */
internal fun sdkHttpClient(timeoutMillis: Long, logger: ConfigDirectorLogger): OkHttpClient =
    OkHttpClient.Builder()
        .dispatcher(Dispatcher(requestExecutor(logger)))
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

/**
 * OkHttp reports an `IOException` to the caller and rethrows anything else on the thread that was
 * serving the request, where the default handler ends the process. Android throws a
 * `SecurityException` there when the app has no internet permission, so without a handler of our own
 * the SDK would take its host app down over a failed connection.
 */
private fun requestExecutor(logger: ConfigDirectorLogger): ExecutorService {
    val threadCount = AtomicInteger()

    return ThreadPoolExecutor(
        0,
        Int.MAX_VALUE,
        IDLE_THREAD_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable ->
        Thread(runnable, "ConfigDirector Dispatcher ${threadCount.incrementAndGet()}").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                logger.error(failure) {
                    "Unexpected failure on a request thread. The connection it was serving has " +
                        "failed; the SDK contained the failure rather than letting it end the app."
                }
            }
        }
    }
}

private const val IDLE_THREAD_SECONDS = 60L
