package com.configdirector.testing

import com.configdirector.ConfigDirectorContext
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackedEventTest {

    private val context = ConfigDirectorContext.build { id("user-123") }

    @Test
    fun `equals an event with the same fields`() {
        val event = TrackedEvent("checkout-completed", context, 49.9, mapOf("plan" to "pro"))

        assertThat(event).isEqualTo(TrackedEvent("checkout-completed", context, 49.9, mapOf("plan" to "pro")))
        assertThat(event.hashCode())
            .isEqualTo(TrackedEvent("checkout-completed", context, 49.9, mapOf("plan" to "pro")).hashCode())
    }

    @Test
    fun `differs from an event with another name`() {
        assertThat(TrackedEvent("first")).isNotEqualTo(TrackedEvent("second"))
    }

    @Test
    fun `differs from an event with another context`() {
        assertThat(TrackedEvent("first", context)).isNotEqualTo(TrackedEvent("first"))
    }

    @Test
    fun `differs from an event with another value`() {
        assertThat(TrackedEvent("first", value = 1)).isNotEqualTo(TrackedEvent("first", value = 2))
    }

    @Test
    fun `differs from an event with other details`() {
        assertThat(TrackedEvent("first", details = mapOf("plan" to "pro")))
            .isNotEqualTo(TrackedEvent("first", details = mapOf("plan" to "free")))
    }
}
