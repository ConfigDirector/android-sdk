package com.configdirector.internal

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Test

class ConnectAttemptTest {

    @Test
    fun `connects once however many times it is marked connected`() {
        val connections = AtomicInteger()
        val attempt = ConnectAttempt { connections.incrementAndGet() }

        attempt.markConnected()
        attempt.markConnected()

        assertThat(connections.get()).isEqualTo(1)
    }

    @Test
    fun `a caller that finds another one connecting returns once that connection is done`() {
        val connecting = CountDownLatch(1)
        val finishConnecting = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val attempt = ConnectAttempt {
            connecting.countDown()
            finishConnecting.await()
            connected.set(true)
        }
        val first = thread { attempt.markConnected() }
        assertThat(connecting.await(5, TimeUnit.SECONDS)).isTrue()
        val connectedWhenTheSecondCallerReturned = AtomicBoolean(false)
        val second = thread {
            attempt.markConnected()
            connectedWhenTheSecondCallerReturned.set(connected.get())
        }
        second.join(200)

        finishConnecting.countDown()
        first.join(5_000)
        second.join(5_000)

        assertThat(connectedWhenTheSecondCallerReturned.get()).isTrue()
    }
}
