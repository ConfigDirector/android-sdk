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
        var whileReporting: () -> Unit = {}

        override suspend fun report(report: EventReport): ReportOutcome {
            reports += report
            whileReporting()
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

    @Test
    fun `reports invalid context submissions when nothing was evaluated`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.recordInvalidContextSubmission("Tenant")
        collector.recordInvalidContextSubmission("Tenant")

        collector.flush()

        val summary = reporter.reports.single().invalidContextSubmissionSummaries.single()
        assertThat(summary.contextKindSample).isEqualTo("Tenant")
        assertThat(summary.count).isEqualTo(2)
    }

    @Test
    fun `keeps 20 context kind samples per report and counts the rest without one`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        repeat(22) { collector.recordInvalidContextSubmission("kind-$it") }
        collector.recordInvalidContextSubmission("kind-0")
        collector.recordInvalidContextSubmission("kind-21")

        collector.flush()

        val summaries = reporter.reports.single().invalidContextSubmissionSummaries
        assertThat(summaries).hasSize(21)
        assertThat(summaries.single { it.contextKindSample == "kind-0" }.count).isEqualTo(2)
        assertThat(summaries.single { it.contextKindSample == "kind-19" }.count).isEqualTo(1)
        assertThat(summaries.none { it.contextKindSample == "kind-20" }).isTrue()
        assertThat(summaries.none { it.contextKindSample == "kind-21" }).isTrue()
        assertThat(summaries.single { it.contextKindSample == null }.count).isEqualTo(3)
    }

    @Test
    fun `starts its context kind samples afresh after each report`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        repeat(21) { collector.recordInvalidContextSubmission("kind-$it") }
        collector.flush()

        collector.recordInvalidContextSubmission("device-kind")
        collector.flush()

        val summary = reporter.reports[1].invalidContextSubmissionSummaries.single()
        assertThat(summary.contextKindSample).isEqualTo("device-kind")
        assertThat(summary.count).isEqualTo(1)
    }

    @Test
    fun `collects no invalid context submissions after a failure retrying cannot fix`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        reporter.outcome.set(ReportOutcome.FAILED_FATALLY)
        collector.evaluatedConfig(evaluation("dark-mode"))
        collector.flush()

        collector.recordInvalidContextSubmission("Tenant")
        collector.flush()

        assertThat(reporter.reports).hasSize(1)
    }

    @Test
    fun `reports tracked events when nothing was evaluated`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.trackedEvent(admittedEvent("checkout-completed"))

        collector.flush()

        assertThat(reporter.reports.single().trackedEvents.map { it.name }).containsExactly("checkout-completed")
    }

    @Test
    fun `reports each tracked event once`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.trackedEvent(admittedEvent("checkout-completed"))

        collector.flush()
        collector.flush()

        assertThat(reporter.reports).hasSize(1)
    }

    @Test
    fun `reports the tracked events left when it closes`() {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.trackedEvent(admittedEvent("checkout-completed"))

        collector.close()

        waitFor("the final report") { reporter.reports.isNotEmpty() }
        assertThat(reporter.reports.single().trackedEvents.map { it.name }).containsExactly("checkout-completed")
    }

    @Test
    fun `reports the tracked events of the previous context when the context changes`() {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.trackedEvent(admittedEvent("checkout-completed"))

        collector.updateContext(ConfigDirectorContext.build { id("user-b") })

        waitFor("the report") { reporter.reports.isNotEmpty() }
        assertThat(reporter.reports.single().trackedEvents.map { it.name }).containsExactly("checkout-completed")
    }

    @Test
    fun `logs one warning per cause until a report is sent`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        val leftOutValue = admittedEvent("checkout-completed", TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)

        collector.trackedEvent(leftOutValue)
        collector.trackedEvent(leftOutValue)

        assertThat(logger.messagesContaining("not a finite number")).hasSize(1)

        collector.flush()
        collector.trackedEvent(leftOutValue)

        assertThat(logger.messagesContaining("not a finite number")).hasSize(2)
    }

    @Test
    fun `logs a warning for a dropped event at warning level`() {
        val collector = collector(initialFlushDelayMillis = 5_000)

        collector.trackedEvent(
            TrackedEventAdmission.Dropped(listOf(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT)),
        )

        assertThat(logger.messagesContaining("WARN: ")).hasSize(1)
    }

    @Test
    fun `collects no tracked events once closed, and logs nothing for them`() {
        val collector = collector(initialFlushDelayMillis = 5_000)
        collector.close()

        collector.trackedEvent(admittedEvent("checkout-completed", TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER))

        settle()
        assertThat(reporter.reports).isEmpty()
        assertThat(logger.messagesContaining("not a finite number")).isEmpty()
    }

    @Test
    fun `collects no tracked events after a failure retrying cannot fix`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        reporter.outcome.set(ReportOutcome.FAILED_FATALLY)
        collector.trackedEvent(admittedEvent("first"))
        collector.flush()

        collector.trackedEvent(admittedEvent("second"))
        collector.flush()

        assertThat(reporter.reports).hasSize(1)
    }

    @Test
    fun `discards tracked events collected while a report that fails for good is in flight`() = runBlocking<Unit> {
        val collector = collector(initialFlushDelayMillis = 5_000)
        reporter.outcome.set(ReportOutcome.FAILED_FATALLY)
        reporter.whileReporting = { collector.trackedEvent(admittedEvent("while-reporting")) }
        collector.trackedEvent(admittedEvent("first"))

        collector.flush()
        reporter.whileReporting = {}
        collector.flush()

        assertThat(reporter.reports).hasSize(1)
    }

    private fun admittedEvent(name: String, vararg warnings: TrackedEventWarning) = TrackedEventAdmission.Admitted(
        TrackedEventEntry(
            name = name,
            contextKind = null,
            contextIdentifier = null,
            value = null,
            details = null,
            happenedAtMillis = 0,
        ),
        context = null,
        warnings.toList(),
    )

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
        ruleId = null,
        shareId = null,
        variationId = null,
    )
}
