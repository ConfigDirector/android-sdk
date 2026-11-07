package com.configdirector.internal.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackedEventQueueTest {

    private val queue = TrackedEventQueue()

    private fun entry(name: String) = TrackedEventEntry(
        name = name,
        contextKind = null,
        contextIdentifier = null,
        value = null,
        details = null,
        happenedAtMillis = 0,
    )

    private fun admitted(name: String, vararg warnings: TrackedEventWarning) =
        TrackedEventAdmission.Admitted(entry(name), context = null, warnings.toList())

    private val nameOutsideTheLimit =
        TrackedEventAdmission.Dropped(listOf(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT))

    @Test
    fun `hands over what it holds in the order it was added, then starts over`() {
        queue.add(admitted("first"))
        queue.add(admitted("second"))

        assertThat(queue.takeBatch().map { it.name }).containsExactly("first", "second").inOrder()
        assertThat(queue.takeBatch()).isEmpty()
    }

    @Test
    fun `keeps an event with its warning`() {
        val warnings = queue.add(admitted("first", TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER))

        assertThat(warnings).containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
        assertThat(queue.takeBatch().map { it.name }).containsExactly("first")
    }

    @Test
    fun `holds nothing for a dropped event`() {
        val warnings = queue.add(nameOutsideTheLimit)

        assertThat(warnings).containsExactly(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT)
        assertThat(queue.takeBatch()).isEmpty()
    }

    @Test
    fun `raises a warning once per batch, whichever event raises it`() {
        val first = queue.add(admitted("first", TrackedEventWarning.DETAILS_NOT_REPORTABLE))
        val second = queue.add(admitted("second", TrackedEventWarning.DETAILS_NOT_REPORTABLE))

        assertThat(first).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
        assertThat(second).isEmpty()
    }

    @Test
    fun `raises each cause of a warning once per batch`() {
        val first = queue.add(admitted("first", TrackedEventWarning.DETAILS_NOT_REPORTABLE))
        val second = queue.add(admitted("second", TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER))

        assertThat(first).containsExactly(TrackedEventWarning.DETAILS_NOT_REPORTABLE)
        assertThat(second).containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }

    @Test
    fun `raises a warning again in the next batch`() {
        queue.add(nameOutsideTheLimit)
        queue.takeBatch()

        assertThat(queue.add(nameOutsideTheLimit)).containsExactly(TrackedEventWarning.NAME_OUTSIDE_THE_CHARACTER_LIMIT)
    }

    @Test
    fun `keeps 1,000 events`() {
        repeat(1_000) { queue.add(admitted("event-$it")) }

        val batch = queue.takeBatch()

        assertThat(batch).hasSize(1_000)
        assertThat(batch.last().name).isEqualTo("event-999")
    }

    @Test
    fun `drops the 1,001st event with one warning`() {
        repeat(1_000) { queue.add(admitted("event-$it")) }

        val warnings = queue.add(admitted("event-1000"))

        assertThat(warnings).containsExactly(TrackedEventWarning.BATCH_ALREADY_FULL)
        assertThat(queue.takeBatch().map { it.name }).doesNotContain("event-1000")
    }

    @Test
    fun `raises no further warning for later events of a full batch`() {
        repeat(1_001) { queue.add(admitted("event-$it")) }

        assertThat(queue.add(admitted("event-1001"))).isEmpty()
    }

    @Test
    fun `raises only that the batch is full for an event it does not keep`() {
        repeat(1_000) { queue.add(admitted("event-$it")) }

        val warnings = queue.add(admitted("event-1000", TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER))

        assertThat(warnings).containsExactly(TrackedEventWarning.BATCH_ALREADY_FULL)
    }

    @Test
    fun `keeps events again once the batch was taken`() {
        repeat(1_001) { queue.add(admitted("event-$it")) }
        queue.takeBatch()

        queue.add(admitted("next"))

        assertThat(queue.takeBatch().map { it.name }).containsExactly("next")
    }

    @Test
    fun `holds nothing and raises warnings afresh once cleared`() {
        queue.add(admitted("first", TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER))

        queue.clear()

        assertThat(queue.takeBatch()).isEmpty()
        assertThat(queue.add(admitted("second", TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)))
            .containsExactly(TrackedEventWarning.VALUE_NOT_A_FINITE_NUMBER)
    }
}
