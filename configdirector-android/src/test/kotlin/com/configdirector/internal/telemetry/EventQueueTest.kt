package com.configdirector.internal.telemetry

import com.configdirector.EvaluationReason
import com.configdirector.internal.ConfigType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EventQueueTest {

    private val queue = EventQueue(limit = 3)

    @Test
    fun `holds what it collected until a snapshot is taken`() {
        queue.push(evaluation("one"))
        queue.push(evaluation("two"))

        val snapshot = queue.takeSnapshot()

        assertThat(snapshot.events.map { it.key }).containsExactly("one", "two").inOrder()
        assertThat(snapshot.droppedCount).isEqualTo(0)
    }

    @Test
    fun `starts over once a snapshot is taken`() {
        queue.push(evaluation("one"))
        queue.takeSnapshot()

        assertThat(queue.isEmpty).isTrue()
        assertThat(queue.takeSnapshot().events).isEmpty()
    }

    @Test
    fun `drops the oldest events when it fills up, and says how many`() {
        repeat(5) { queue.push(evaluation("key-$it")) }

        val snapshot = queue.takeSnapshot()

        assertThat(snapshot.events.map { it.key }).containsExactly("key-2", "key-3", "key-4").inOrder()
        assertThat(snapshot.droppedCount).isEqualTo(2)
    }

    @Test
    fun `is not empty while it holds only the count of what it dropped`() {
        repeat(4) { queue.push(evaluation("key-$it")) }
        val dropped = queue.takeSnapshot().droppedCount

        assertThat(dropped).isEqualTo(1)
        assertThat(queue.isEmpty).isTrue()
    }

    @Test
    fun `covers the window from the first event to the snapshot`() {
        val before = System.currentTimeMillis()
        queue.push(evaluation("one"))

        val snapshot = queue.takeSnapshot()

        assertThat(snapshot.startTime).isAtLeast(before)
        assertThat(snapshot.endTime).isAtLeast(snapshot.startTime)
    }

    @Test
    fun `keeps nothing once cleared`() {
        queue.push(evaluation("one"))

        queue.clear()

        assertThat(queue.isEmpty).isTrue()
        assertThat(queue.takeSnapshot().droppedCount).isEqualTo(0)
    }

    @Test
    fun `collects from several threads at once without losing count`() {
        val wide = EventQueue(limit = 1_000)
        val threads = (0 until 8).map { thread ->
            Thread { repeat(100) { wide.push(evaluation("key-$thread")) } }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val snapshot = wide.takeSnapshot()
        assertThat(snapshot.events).hasSize(800)
        assertThat(snapshot.droppedCount).isEqualTo(0)
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
