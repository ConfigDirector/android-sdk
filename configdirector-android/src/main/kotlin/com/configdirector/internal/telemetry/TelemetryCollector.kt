package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext
import com.configdirector.ConfigDirectorLogger
import com.configdirector.warn
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the collector is tuned. Not part of the public API: the defaults are what applications get. */
internal class TelemetryOptions(
    val flushIntervalMillis: Long = 30_000,
    val initialFlushDelayMillis: Long = 5_000,
    val queueLimit: Int = 1_000,
)

/**
 * Collects what the SDK reports back to ConfigDirector.
 *
 * [evaluatedConfig] is called from the client's hot path, so implementations must return without
 * doing any appreciable work.
 */
internal interface TelemetryClient {
    fun evaluatedConfig(event: EvaluatedConfigEvent)

    /**
     * Reports the events collected so far against the previous context, and attributes the ones
     * collected from now on to [context].
     */
    fun updateContext(context: ConfigDirectorContext?)

    /** Reports everything collected so far without waiting for the next flush. */
    suspend fun flush()

    /** Reports whatever is left and releases every resource held. */
    fun close()
}

/**
 * The default [TelemetryClient]: it queues evaluations as they happen and hands them to an
 * [EventReporter] on an interval.
 *
 * Collecting an event only appends it to a bounded in-memory queue, which is what keeps evaluating
 * a config cheap. Everything expensive happens in the reporter, on a background dispatcher.
 */
internal class TelemetryEventCollector(
    private val reporter: EventReporter,
    private val logger: ConfigDirectorLogger,
    private val options: TelemetryOptions = TelemetryOptions(),
) : TelemetryClient {

    private val queue = EventQueue(options.queueLimit)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushes = Mutex()
    private val context = AtomicReference<ConfigDirectorContext?>(null)
    private val timer = AtomicReference<Job?>(null)
    private val collecting = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)

    init {
        startTimer(options.initialFlushDelayMillis)
    }

    /** What has been collected but not yet reported. */
    val pendingEventCount: Int get() = queue.pendingCount

    override fun evaluatedConfig(event: EvaluatedConfigEvent) {
        if (!collecting.get()) return

        queue.push(event)
    }

    override fun updateContext(context: ConfigDirectorContext?) {
        if (closed.get()) return

        val pending = takeReport()
        this.context.set(context)
        scope.launch { send(pending) }

        restartTimer()
    }

    override suspend fun flush() {
        if (closed.get()) return

        send(takeReport())
        restartTimer()
    }

    override fun close() {
        if (closed.getAndSet(true)) return

        collecting.set(false)
        timer.getAndSet(null)?.cancel()

        val pending = takeReport()
        scope.launch {
            send(pending)
            reporter.close()
            queue.clear()
            scope.cancel()
        }
    }

    private fun takeReport(): EventReport? =
        if (queue.isEmpty) null else EventReport(queue.takeSnapshot(), context.get())

    /** Sends after whatever is already in flight, so batches reach the server in the order they
     * were collected. */
    private suspend fun send(report: EventReport?) {
        if (report == null) return

        val outcome = flushes.withLock { reporter.report(report) }
        if (outcome == ReportOutcome.FAILED_FATALLY) {
            stopCollecting()
        }
    }

    private fun stopCollecting() {
        collecting.set(false)
        timer.getAndSet(null)?.cancel()
        queue.clear()
        reporter.close()

        logger.warn {
            "[TelemetryEventCollector] Received a fatal error while reporting telemetry. No longer " +
                "collecting events."
        }
    }

    private fun restartTimer() {
        if (collecting.get() && !closed.get()) {
            startTimer(options.flushIntervalMillis)
        }
    }

    private fun startTimer(firstDelayMillis: Long) {
        val started = scope.launch {
            var next = firstDelayMillis
            while (isActive) {
                delay(next)
                send(takeReport())
                next = options.flushIntervalMillis
            }
        }

        timer.getAndSet(started)?.cancel()
    }
}
