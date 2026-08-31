package com.configdirector.internal.transport

import com.configdirector.RecordingLogger
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class HttpClientsTest {

    private val logger = RecordingLogger()

    @Test
    fun `contains a failure on a request thread instead of letting it end the process`() {
        val reachedTheDefaultHandler = AtomicInteger()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> reachedTheDefaultHandler.incrementAndGet() }
        val threw = CountDownLatch(1)

        try {
            sdkHttpClient(timeoutMillis = 1_000, logger = logger).dispatcher.executorService.execute {
                try {
                    // What Android throws at OkHttp when the app has no INTERNET permission. It is
                    // not an IOException, so OkHttp rethrows it on its own thread.
                    throw SecurityException("Permission denied (missing INTERNET permission?)")
                } finally {
                    threw.countDown()
                }
            }
            assertThat(threw.await(2, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(100)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        // The default handler is what ends the process, so reaching it is the crash.
        assertThat(reachedTheDefaultHandler.get()).isEqualTo(0)
        assertThat(logger.messagesContaining("Unexpected failure on a request thread")).hasSize(1)
        assertThat(logger.errors.filterIsInstance<SecurityException>()).hasSize(1)
    }
}
