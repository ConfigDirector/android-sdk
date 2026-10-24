package com.configdirector.internal

import com.configdirector.CachingProvider
import com.configdirector.RecordingLogger
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CachedValuesStoreTest {

    private val logger = RecordingLogger()

    private class GatedCachingProvider : CachingProvider {
        val writes = CopyOnWriteArrayList<String>()
        val firstWriteGate = CompletableDeferred<Unit>()

        override suspend fun read(): String? = null

        override suspend fun write(value: String) {
            writes += value
            if (writes.size == 1) firstWriteGate.await()
        }

        override suspend fun remove() = Unit

        override fun sizeLimit(): Int = 256 * 1024
    }

    private fun cachedValues(darkMode: String) = CachedValues(
        contextTag = ContextTag.Identified(contextKind = "user", contextId = "user-123"),
        environmentId = "env-1",
        projectId = "project-1",
        configs = mapOf("dark-mode" to ConfigState("dark-mode", ConfigType.BOOLEAN, darkMode)),
        receivedAt = 1_761_000_000_000,
    )

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    @Test
    fun `writes only the latest values queued while a write is in flight`() = runBlocking<Unit> {
        val provider = GatedCachingProvider()
        val store = CachedValuesStore(provider, CLIENT_SDK_KEY, INSTANCE_ID, logger)
        store.replace(cachedValues("first"))
        waitFor("the first write to start") { provider.writes.size == 1 }

        store.replace(cachedValues("second"))
        store.replace(cachedValues("third"))
        provider.firstWriteGate.complete(Unit)

        waitFor("the queued write") { provider.writes.size == 2 }
        Thread.sleep(200)
        assertThat(provider.writes).hasSize(2)
        val written = checkNotNull(cachedValuesIn(provider.writes[1], CLIENT_SDK_KEY))
        assertThat(written.configs.getValue("dark-mode").value).isEqualTo("third")
        store.close()
    }
}

private const val CLIENT_SDK_KEY = "client-sdk-key"
private const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
