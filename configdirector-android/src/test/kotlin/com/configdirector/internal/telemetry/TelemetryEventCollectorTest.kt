package com.configdirector.internal.telemetry

import com.configdirector.ConfigDirectorContext
import com.configdirector.EvaluationReason
import com.configdirector.RecordingLogger
import com.configdirector.internal.ConfigType
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class TelemetryEventCollectorTest {

    private class FakeReporter : EventReporter {
        val reports = CopyOnWriteArrayList<EventReport>()
        val outcome = AtomicReference(ReportOutcome.SUCCEEDED)
        val closed = AtomicReference(false)

        override suspend fun report(report: EventReport): ReportOutcome {
            reports += report
            return outcome.get()
        }

        override fun close() {
            closed.set(true)
        }
    }

    private val reporter = FakeReporter()
    private val logger = RecordingLogger()
    private var collector: TelemetryEventCollector? = null

    @After
    fun closeCollector() {
        collector?.close()
    }

    private fun collector(
        flushIntervalMillis: Long = 50,
        initialFlushDelayMillis: Long = 50,
        queueLimit: Int = 1_000,
    ) = TelemetryEventCollector(
        reporter,
        logger,
        TelemetryOptions(flushIntervalMillis, initialFlushDelayMillis, queueLimit),
    ).also { collector = it }

    private fun waitFor(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun settle() {
        Thread.sleep(200)
    }

    @Test
    fun `reports what it collected on the interval`() {
        val collector = collector()

        collector.evaluatedConfig(evaluation("dark-mode"))

        waitFor("the first report") { reporter.reports.isNotEmpty() }
        assertThat(reporter.reports.first().snapshot.events.map { it.key }).containsExactly("dark-mode")
    }

    @Test
    fun `collects an evaluation without reporting it straight away`() {
        val collector = collector(initialFlushDelayMillis = 5_000)

        collector.evaluatedConfig(evaluation("dark-mode"))

        assertThat(collector.pendingEventCount).isEqualTo(1)
        assertThat(reporter.reports).isEmpty()
    }

    @Test
    fun `reports nothing when nothing was collected`() {
        collector()

        settle()

        assertThat(reporter.reports).isEmpty()
    }

    @Test
    fun `reports what the previous context saw when the context changes`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        val first = ConfigDirectorContext.build { id("user-123") }
        val second = ConfigDirectorContext.build { id("user-456") }
        collector.updateContext(first)
        collector.evaluatedConfig(evaluation("dark-mode"))

        collector.updateContext(second)

        waitFor("the report for the first context") { reporter.reports.isNotEmpty() }
        assertThat(reporter.reports.single().context).isEqualTo(first)

        collector.evaluatedConfig(evaluation("welcome-message"))
        collector.flush()
        waitFor("the report for the second context") { reporter.reports.size == 2 }
        assertThat(reporter.reports[1].context).isEqualTo(second)
    }

    @Test
    fun `reports what is left when it closes`() {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.evaluatedConfig(evaluation("dark-mode"))

        collector.close()

        waitFor("the final report") { reporter.reports.isNotEmpty() }
        assertThat(reporter.reports.single().snapshot.events).hasSize(1)
        waitFor("the reporter to close") { reporter.closed.get() }
    }

    @Test
    fun `collects nothing once closed`() {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.close()

        collector.evaluatedConfig(evaluation("dark-mode"))

        assertThat(collector.pendingEventCount).isEqualTo(0)
    }

    @Test
    fun `stops collecting after a failure retrying cannot fix`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        reporter.outcome.set(ReportOutcome.FAILED_FATALLY)
        collector.evaluatedConfig(evaluation("dark-mode"))

        collector.flush()

        collector.evaluatedConfig(evaluation("welcome-message"))
        settle()
        assertThat(collector.pendingEventCount).isEqualTo(0)
        assertThat(reporter.reports).hasSize(1)
        assertThat(logger.messagesContaining("No longer collecting events")).hasSize(1)
    }

    @Test
    fun `keeps collecting after a failure worth retrying`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        reporter.outcome.set(ReportOutcome.FAILED)
        collector.evaluatedConfig(evaluation("dark-mode"))
        collector.flush()

        collector.evaluatedConfig(evaluation("welcome-message"))

        assertThat(collector.pendingEventCount).isEqualTo(1)
    }

    @Test
    fun `drops the oldest events once the queue is full`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000, queueLimit = 2)
        repeat(4) { collector.evaluatedConfig(evaluation("key-$it")) }

        collector.flush()

        waitFor("the report") { reporter.reports.isNotEmpty() }
        val snapshot = reporter.reports.single().snapshot
        assertThat(snapshot.events.map { it.key }).containsExactly("key-2", "key-3").inOrder()
        assertThat(snapshot.droppedCount).isEqualTo(2)
    }

    private fun evaluation(key: String) = EvaluatedConfigEvent(
        contextId = null,
        key = key,
        type = ConfigType.BOOLEAN,
        defaultValue = TelemetryValue(value = "false"),
        requestedType = "Boolean",
        evaluatedValue = TelemetryValue(value = "true"),
        evaluatedValueId = null,
        usedDefault = false,
        evaluationReason = EvaluationReason.FOUND_MATCH,
    )
}
