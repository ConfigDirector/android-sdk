package com.configdirector.internal

import com.configdirector.CachingOptions
import com.configdirector.CachingProvider
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorLogger
import com.configdirector.debug
import com.configdirector.warn
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal sealed class CachingPlan {
    class KeepValues(val store: CachedValuesStore) : CachingPlan()

    object KeepNothing : CachingPlan()
}

internal fun planCaching(
    caching: CachingOptions,
    clientSdkKey: String,
    instanceId: String,
    platformProvider: () -> CachingProvider?,
    logger: ConfigDirectorLogger,
): CachingPlan {
    if (!caching.enabled) {
        logger.debug { "No values are kept on the device: caching is turned off" }
        return CachingPlan.KeepNothing
    }

    val provider = caching.provider ?: platformProvider() ?: run {
        logger.debug { "No values are kept on the device: this platform has no store for them" }
        return CachingPlan.KeepNothing
    }
    return CachingPlan.KeepValues(CachedValuesStore(provider, clientSdkKey, instanceId, logger))
}

internal sealed class CachedValuesRead {
    class Stored(val cachedValues: CachedValues) : CachedValuesRead()

    object NothingStored : CachedValuesRead()

    object NotAnEntry : CachedValuesRead()

    object StorageFailed : CachedValuesRead()
}

internal class CachedValuesStore(
    private val provider: CachingProvider,
    private val clientSdkKey: String,
    private val instanceId: String,
    logger: ConfigDirectorLogger,
) {
    private val warnings = CachingWarnings(logger)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingWrite = PendingWrite()
    private val pendingRead: Deferred<CachedValuesRead> = scope.async { readEntry() }

    suspend fun read(): CachedValuesRead = pendingRead.await()

    fun contextTag(context: ConfigDirectorContext?): ContextTag = contextTagFor(context, instanceId)

    fun replace(cachedValues: CachedValues) {
        if (pendingWrite.queue(cachedValues)) {
            scope.launch { writeQueued() }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun writeQueued() {
        while (true) {
            val next = pendingWrite.take() ?: return
            writeEntry(next)
        }
    }

    private suspend fun writeEntry(cachedValues: CachedValues) {
        val serialized = serializeCachedValuesEntry(clientSdkKey, cachedValues)
        val size = serialized.toByteArray(Charsets.UTF_8).size
        val limit = provider.sizeLimit()
        if (size > limit) {
            warnings.warnOnce(CachingWarnings.Kind.OVER_THE_SIZE_LIMIT) {
                "The values received ($size bytes) exceed the caching provider's size limit of $limit bytes, " +
                    "so no values are kept on the device."
            }
            removeEntry()
            return
        }

        try {
            provider.write(serialized)
        } catch (failure: Exception) {
            warnings.warnOnce(CachingWarnings.Kind.WRITE_FAILED, failure) {
                "The ConfigDirector SDK could not keep the values on the device."
            }
        }
    }

    private suspend fun readEntry(): CachedValuesRead {
        val stored = try {
            provider.read()
        } catch (failure: Exception) {
            warnings.warnOnce(CachingWarnings.Kind.READ_FAILED, failure) {
                "The ConfigDirector SDK could not read the values kept on the device, so this launch starts " +
                    "without them."
            }
            return CachedValuesRead.StorageFailed
        }
        if (stored.isNullOrEmpty()) return CachedValuesRead.NothingStored

        val cachedValues = cachedValuesIn(stored, clientSdkKey) ?: run {
            warnings.warnOnce(CachingWarnings.Kind.NOT_AN_ENTRY) {
                "The values kept on the device could not be read and were removed, so this launch starts " +
                    "without them."
            }
            removeEntry()
            return CachedValuesRead.NotAnEntry
        }
        return CachedValuesRead.Stored(cachedValues)
    }

    private suspend fun removeEntry() {
        try {
            provider.remove()
        } catch (failure: Exception) {
            warnings.warnOnce(CachingWarnings.Kind.REMOVE_FAILED, failure) {
                "The ConfigDirector SDK could not remove the values kept on the device."
            }
        }
    }
}

private class PendingWrite {
    private val lock = Any()
    private var queued: CachedValues? = null
    private var writing = false

    fun queue(cachedValues: CachedValues): Boolean = synchronized(lock) {
        queued = cachedValues
        if (writing) return false
        writing = true
        true
    }

    fun take(): CachedValues? = synchronized(lock) {
        val next = queued
        queued = null
        if (next == null) writing = false
        next
    }
}

private class CachingWarnings(private val logger: ConfigDirectorLogger) {
    enum class Kind { READ_FAILED, NOT_AN_ENTRY, OVER_THE_SIZE_LIMIT, WRITE_FAILED, REMOVE_FAILED }

    private val warned: MutableSet<Kind> = Collections.synchronizedSet(mutableSetOf())

    fun warnOnce(kind: Kind, failure: Throwable? = null, message: () -> String) {
        if (!warned.add(kind)) return

        logger.warn(failure, message)
    }
}
