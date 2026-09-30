package com.configdirector.internal.testing

import com.configdirector.ClientOptions
import com.configdirector.ConfigDirectorClient
import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorLogger
import com.configdirector.ConfigDirectorTestingApi
import com.configdirector.ConfigDirectorWrapperApi
import com.configdirector.ConnectReason
import com.configdirector.Metadata
import com.configdirector.SdkIdentity
import com.configdirector.internal.ConfigSet
import com.configdirector.internal.ConfigSetKind
import com.configdirector.internal.ConfigState
import com.configdirector.internal.Constants
import com.configdirector.internal.lifecycle.AppLifecycleObserver
import com.configdirector.internal.lifecycle.AppLifecyclePhase
import com.configdirector.internal.telemetry.EvaluatedConfigEvent
import com.configdirector.internal.telemetry.TelemetryClient
import com.configdirector.internal.transport.ConnectionFailedException
import com.configdirector.internal.transport.Transport
import com.configdirector.internal.transport.toSdkMetaContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val PLACEHOLDER_SDK_KEY = "test-client"
private const val FATAL_STATUS = 401

/**
 * The SDK's real [ConfigDirectorClient] over an in-memory connection that a test controls: it
 * serves the values it is given, delivers every change through the client's own update handling,
 * and holds or fails connection attempts on request. Nothing reaches the network, no telemetry is
 * collected, and no Android `Context` is needed.
 *
 * This is the entry point of the `configdirector-android-testing` artifact, which wraps it in the
 * test client API; tests use that artifact rather than this class.
 */
@ConfigDirectorTestingApi
public class InMemoryConnection(
    values: Map<String, @JvmSuppressWildcards Any>,
    timeoutMillis: Long,
    logger: ConfigDirectorLogger,
) {

    private val lock = Any()
    private var configs: MutableMap<String, ConfigState> = LinkedHashMap(encodeTestValues(values))
    private val initialization = AttemptControls()
    private val contextUpdate = AttemptControls()
    private val recordedContexts = mutableListOf<ConfigDirectorContext>()
    private val pendingDeliveries = ConcurrentLinkedQueue<ConfigSet>()
    private val delivering = AtomicBoolean(false)
    private var connected = false
    private lateinit var onConfigSet: (ConfigSet) -> Unit

    /** The client under test. It starts uninitialized, like a production client. */
    @OptIn(ConfigDirectorWrapperApi::class)
    public val client: ConfigDirectorClient = ConfigDirectorClient(
        clientSdkKey = PLACEHOLDER_SDK_KEY,
        options = ClientOptions.build {
            logger(logger)
            connection { timeoutMillis(timeoutMillis) }
        },
        metaContext = SdkIdentity.ANDROID_CLIENT_SDK.toSdkMetaContext(Metadata.empty()),
        lifecycle = NoLifecycleObserver,
        telemetryFactory = { DiscardingTelemetryClient },
        transportFactory = { _, _, handler ->
            onConfigSet = handler
            InMemoryTransport()
        },
    )

    /**
     * The context of every `initialize` and `updateContext` call, in call order. `initialize`
     * without a context records an empty context. `resumeNetwork` is not recorded.
     */
    public val contextUpdates: List<ConfigDirectorContext>
        get() = synchronized(lock) { recordedContexts.toList() }

    @get:JvmSynthetic
    internal val isHoldingAnAttempt: Boolean
        get() = synchronized(lock) { initialization.held != null || contextUpdate.held != null }

    /**
     * Stores [value] under [key] and, once the client is connected, delivers an update carrying
     * only [key]. The config type follows from the value's type: a `Boolean`; an `Int` or `Long`
     * (an integer config); a `Float` or `Double` (a float config); a `String`; or a `Map` with
     * `String` keys or a `List` (a JSON config).
     *
     * @throws com.configdirector.ConfigDirectorValidationException if [key] is blank, the value is
     *   of another type or not finite, or a JSON document holds a value of another type or a
     *   non-`String` key
     */
    public fun setValue(key: String, value: Any) {
        val configState = encodeTestValue(key, value)
        synchronized(lock) {
            configs[key] = configState
            deliverIfConnectedLocked(ConfigSet(mapOf(key to configState), ConfigSetKind.DELTA))
        }
        drain()
    }

    /**
     * Removes the value under [key] and, once the client is connected, delivers a full update
     * without it, so [key] reads as its default value.
     */
    public fun removeValue(key: String) {
        synchronized(lock) {
            configs.remove(key)
            deliverIfConnectedLocked(fullUpdateLocked())
        }
        drain()
    }

    /**
     * Replaces every stored value with [values], disarms any armed hold or failure, and, once the
     * client is connected, delivers a full update. See [setValue] for the accepted values.
     */
    public fun replaceValues(values: Map<String, @JvmSuppressWildcards Any>) {
        val encoded = encodeTestValues(values)
        synchronized(lock) {
            configs = LinkedHashMap(encoded)
            initialization.armed = Armed.NOTHING
            contextUpdate.armed = Armed.NOTHING
            deliverIfConnectedLocked(fullUpdateLocked())
        }
        drain()
    }

    /**
     * Makes the next `initialize` wait, not ready, until [completeInitialization] or
     * [failInitialization], or until the client's timeout elapses.
     */
    public fun holdInitialization() {
        hold(initialization)
    }

    /**
     * Delivers the stored values to a held `initialize` on the calling thread, so the client is
     * ready when this returns, and then lets `initialize` complete. Called while a hold is armed
     * but no `initialize` has picked it up, it disarms the hold.
     */
    public fun completeInitialization() {
        complete(initialization)
    }

    /**
     * Fails a held `initialize` the way an invalid SDK key does: it completes promptly, the client
     * is not ready, and the client logs the error. Called while no `initialize` is held, it arms
     * the next one to fail.
     */
    public fun failInitialization() {
        fail(initialization)
    }

    /** As [holdInitialization], for the next `updateContext`. */
    public fun holdContextUpdate() {
        hold(contextUpdate)
    }

    /** As [completeInitialization], for a held `updateContext`. */
    public fun completeContextUpdate() {
        complete(contextUpdate)
    }

    /** As [failInitialization], for a held `updateContext`. */
    public fun failContextUpdate() {
        fail(contextUpdate)
    }

    private fun hold(controls: AttemptControls) {
        synchronized(lock) { controls.armed = Armed.HOLD }
    }

    private fun complete(controls: AttemptControls) {
        val held = synchronized(lock) {
            val held = controls.held
            if (held != null) {
                controls.held = null
                connected = true
                pendingDeliveries.add(fullUpdateLocked())
            } else if (controls.armed == Armed.HOLD) {
                controls.armed = Armed.NOTHING
            }
            held
        }
        if (held != null) {
            drain()
            held.complete(AttemptOutcome.COMPLETED)
        }
    }

    private fun fail(controls: AttemptControls) {
        val held = synchronized(lock) {
            val held = controls.held
            if (held != null) {
                controls.held = null
            } else {
                controls.armed = Armed.FAILURE
            }
            held
        }
        held?.complete(AttemptOutcome.FAILED)
    }

    private suspend fun connect(
        context: ConfigDirectorContext,
        timeoutMillis: Long,
        reason: ConnectReason,
    ) {
        val controls = when (reason) {
            ConnectReason.INITIALIZATION -> initialization
            ConnectReason.CONTEXT_UPDATE -> contextUpdate
            ConnectReason.NETWORK_RESUME -> null
        }

        val ended: List<CompletableDeferred<AttemptOutcome>>
        var armed = Armed.NOTHING
        var held: CompletableDeferred<AttemptOutcome>? = null
        synchronized(lock) {
            ended = takeHeldLocked()
            connected = false
            if (controls != null) {
                recordedContexts += context
                armed = controls.armed
                controls.armed = Armed.NOTHING
                if (armed == Armed.HOLD) {
                    held = CompletableDeferred<AttemptOutcome>().also { controls.held = it }
                }
            }
        }
        ended.forEach { it.complete(AttemptOutcome.ENDED) }

        when (armed) {
            Armed.NOTHING -> deliverFirstUpdate()
            Armed.FAILURE -> throw fatalFailure(reason)
            Armed.HOLD -> awaitHeld(checkNotNull(controls), checkNotNull(held), timeoutMillis, reason)
        }
    }

    private suspend fun awaitHeld(
        controls: AttemptControls,
        held: CompletableDeferred<AttemptOutcome>,
        timeoutMillis: Long,
        reason: ConnectReason,
    ) {
        val outcome = try {
            withTimeout(timeoutMillis) { held.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw ConnectionFailedException("Connection timed out after ${timeoutMillis}ms.")
        } finally {
            synchronized(lock) {
                if (controls.held === held) controls.held = null
            }
        }

        when (outcome) {
            AttemptOutcome.COMPLETED -> Unit
            AttemptOutcome.ENDED -> throw ConnectionFailedException(
                "The connection was closed before it was established.",
            )
            AttemptOutcome.FAILED -> throw fatalFailure(reason)
        }
    }

    private fun fatalFailure(reason: ConnectReason) = ConnectionFailedException(
        "Connection failed with status: $FATAL_STATUS. Error: the test client failed this " +
            "${reason.description}. This is an unrecoverable error, will not attempt to reconnect.",
        FATAL_STATUS,
    )

    private fun deliverFirstUpdate() {
        synchronized(lock) {
            connected = true
            pendingDeliveries.add(fullUpdateLocked())
        }
        drain()
    }

    private fun endAttempts() {
        val ended = synchronized(lock) {
            connected = false
            takeHeldLocked()
        }
        ended.forEach { it.complete(AttemptOutcome.ENDED) }
    }

    private fun takeHeldLocked(): List<CompletableDeferred<AttemptOutcome>> {
        val held = listOfNotNull(initialization.held, contextUpdate.held)
        initialization.held = null
        contextUpdate.held = null
        return held
    }

    private fun deliverIfConnectedLocked(configSet: ConfigSet) {
        if (connected) {
            pendingDeliveries.add(configSet)
        }
    }

    private fun fullUpdateLocked(): ConfigSet = ConfigSet(LinkedHashMap(configs), ConfigSetKind.FULL)

    private fun drain() {
        while (pendingDeliveries.isNotEmpty() && delivering.compareAndSet(false, true)) {
            try {
                while (true) {
                    val next = pendingDeliveries.poll() ?: break
                    onConfigSet(next)
                }
            } finally {
                delivering.set(false)
            }
        }
    }

    private inner class InMemoryTransport : Transport {
        override suspend fun connect(
            context: ConfigDirectorContext,
            timeoutMillis: Long,
            reason: ConnectReason,
        ) = this@InMemoryConnection.connect(context, timeoutMillis, reason)

        override fun disconnect() = endAttempts()

        override fun close() = endAttempts()
    }

    public companion object {
        /** The version of the SDK this class ships in, for the testing artifact to check itself against. */
        @JvmStatic
        public val sdkVersion: String
            get() = Constants.SDK_VERSION
    }
}

private enum class Armed { NOTHING, HOLD, FAILURE }

private enum class AttemptOutcome { COMPLETED, ENDED, FAILED }

private class AttemptControls {
    var armed = Armed.NOTHING
    var held: CompletableDeferred<AttemptOutcome>? = null
}

private object NoLifecycleObserver : AppLifecycleObserver {
    override fun start(onChange: (AppLifecyclePhase) -> Unit) = Unit

    override fun stop() = Unit
}

private object DiscardingTelemetryClient : TelemetryClient {
    override fun evaluatedConfig(event: EvaluatedConfigEvent) = Unit

    override fun updateContext(context: ConfigDirectorContext?) = Unit

    override suspend fun flush() = Unit

    override fun close() = Unit
}
